(ns jupyter-client.core
  (:require
   [clojure.pprint		   :refer [pprint cl-format]]
   [clojure.walk		   :as walk]
   [cheshire.core		   :as cheshire]
   [pandect.algo.sha256		   :refer [sha256-hmac]]
   [taoensso.timbre		   :as log]
   [zeromq.zmq			   :as zmq]
   
   [jupyter-client.util	           :as u]
   [jupyter-client.transport	   :as T]
   [jupyter-client.middleware	   :as MB]
   [jupyter-client.zmq-client      :as zmqc]))

;;; "4fb56c501d3340d398bb8d3742cd9e50" The :envelope byte array is the same as the session.
;;; Thus I need to replace it. 
(def EXE-MSG {;:envelope 
;;;             [(byte-array [52, 102, 98, 53, 54, 99, 53, 48, 49, 100, 51, 51, 52, 48, 100, 51,
;;;                            57, 56, 98, 98, 56, 100, 51, 55, 52, 50, 99, 100, 57, 101, 53, 48])],
              :delimiter "<IDS|MSG>",
              :signature ; connect.json says "signature_scheme": "hmac-sha256",
              "5a98d552a77da7f1595030a8771f4ef861a5ca406d13b1001d4acfa7a58ace88",  
              :header
              {:msg_id "e93eb6ef140940518bb379ae9c962a7d",
               :username "username",
;;;            :session "4fb56c501d3340d398bb8d3742cd9e50", ; to be replaced with connect.json/key
               :msg_type "execute_request",
               :version "5.2"},
              :parent-header {},
              :content
              {:code "(+ 1 2 3)",
               :silent false,
               :store_history true,
               :user_expressions {},
               :allow_stdin true,
               :stop_on_error true}})

(declare wait-response)

;;; (heartbeat? (-> "./resources/connect.json" slurp u/parse-json-str walk/keywordize-keys))
(defn heartbeat?
  "Clients send ping messages on a REQ socket, which are echoed right back from the Kernel’s REP socket.
   These are simple bytestrings, not full JSON messages...
   Heartbeat: This socket allows for simple bytestring messages to be sent between the frontend and
   the kernel to ensure that they are still connected."
  [config]
  (let [ctx    (zmq/context 1)
        hb-ep  (str "tcp://127.0.0.1:" (:hb_port config))]
    (with-open [HB (-> (zmq/socket ctx :req)
                       (zmq/connect hb-ep))]
      (try
        (while (not (.. Thread currentThread isInterrupted))
          (zmq/send-str HB "ping") ; This is send with (.getBytes <string>).
          (Thread/sleep 1000)
          (let [resp (wait-response HB 5000)]
            (if (= :timeout resp) (println "%Timeout") (println resp))))
        (finally
          (println "Disconnecting")
          (zmq/disconnect HB hb-ep)
          (zmq/close HB)
          (zmq/destroy ctx))))))

(defn wait-response
  "zmq/receive-str within timeout or return :timeout."
  [socket timeout]
  (let [p (promise)]
    (future (deliver p (zmq/receive-str socket)))
    (deref p timeout :timeout)))

;;; Doc on zmq/socket
;;;   The newly created socket is initially unbound, and not associated with any
;;;   endpoints. In order to establish a message flow a socket must first be
;;;   connected to at least one endpoint with connect, or at least one endpoint
;;;   must be created for accepting incoming connections with bind.

;;; It seems to me that the meaning of bind and serve isn't accurately communicated in the documentation. 
;;; What really matters is the messaging pattern used. (e.g. :rep, :req, :pair).
;;; You can do both send and recv with server/client doing respectively bind/connect with :rep/:req.

(declare msg-tryme)

;;; stdin messages are unique in that the request comes from the kernel, and the reply from the frontend.
;;; The frontend is not required to support this, but if it does not, it must set 'allow_stdin' : False

(defn trytry []
  (msg-tryme :code "file = open('/Users/pdenno/Documents/git/jupyter-client/testfile.txt','w')")
  (msg-tryme :code "file.write(foo)")
  (msg-tryme :code "file.close()"))

(defn msg-tryme [& {:keys [code config-file] :or {config-file "./resources/connect.json",
                                                  code "print('Greetings from Clojure!')"}}]
  (let [config           (-> config-file
                             slurp
                             u/parse-json-str
                             walk/keywordize-keys
                             (update :key #(clojure.string/replace % #"-" "")))
        [signer checker]  (u/make-signer-checker (:key config))
        msg               (as-> EXE-MSG ?msg
                            (assoc ?msg :envelope [(-> config :key .getBytes)])
                            (assoc-in ?msg [:header :session] (:key config))
                            (assoc-in ?msg [:content :code] code)
                            (assoc-in ?msg [:content :allow_stdin] false)
                            (assoc ?msg :signature (signer (:header ?msg) (:parent-header ?msg) {} {})) ; Not working.
                            (MB/encode-jupyter-message ?msg))
        ctx               (zmq/context 1)
        proto-ctx	  {:signer signer, :checker checker}
        sh-ep             (str "tcp://127.0.0.1:" (-> config :shell_port))
        io-ep             (str "tcp://127.0.0.1:" (-> config :iopub_port))
        preq              (promise)
        psub              (promise)
        result            (atom nil)]
    (log/set-level! :warn)
    (with-open [shell (-> (zmq/socket ctx :req) (zmq/connect sh-ep)) 
                sub   (-> (zmq/socket ctx :sub) (zmq/connect io-ep))] 
      (try
        (let [transport (zmqc/make-zmq-transport proto-ctx shell)]
          (T/send-req transport "execute_request" {:encoded-jupyter-message msg})
          (cl-format *out* "~%Send completes.")
          (future (deliver preq (-> (T/receive-req transport)
                                    (dissoc :jupyter-client.util/zmq-raw-message))))
          (future (deliver psub (-> (T/receive-iopub transport)
                                    (dissoc :jupyter-client.util/zmq-raw-message))))
          (reset! result {:res (deref preq 5000 :timeout)
                          :sub (deref psub 5000 :timeout)}))
        (finally ; This doesn't seem to run when I interrupt with read on iopub
          (cl-format *out* "~%Cleanup")
          (zmq/disconnect shell sh-ep)
          (zmq/set-linger shell 0)
          (zmq/close shell)
          @result)))))

(def hey
  (let [config (-> "./resources/connect.json"
                   slurp
                   u/parse-json-str
                   walk/keywordize-keys
                   (update :key #(clojure.string/replace % #"-" "")))]
    (-> EXE-MSG
        (assoc :envelope [(-> config :key .getBytes)])
        (assoc-in [:header :session] (:key config))
        (assoc-in [:content :code] "foo"))))