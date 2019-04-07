(ns futzing-clojupyter.core
  (:require
   [clojure.pprint			  :refer [pprint cl-format]]
   [clojure.walk			  :as walk]
   [zeromq.zmq				  :as zmq]
   [clojupyter.kernel.util		  :as u]
   [futzing-clojupyter.middleware	  :as M]
   [futzing-clojupyter.middleware.base    :as MB]
   [futzing-clojupyter.transport-test     :as TT]
   [futzing-clojupyter.fzmq               :as fzmq]
   [futzing-clojupyter.transport	  :as T]))


;;; "4fb56c501d3340d398bb8d3742cd9e50" The byte array is the same as the session.
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
  "Return within timeout or return :timeout."
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

(def diag (atom nil))
(def diag1 (atom nil))

 (clojure.string/replace "3a-bd6-92-d576055f8702899d2e2a8b41" #"-" "")

(defn msg-tryme [& {:keys [code config-file] :or {config-file "./resources/connect.json", code "foo"}}]
  (let [config              (-> config-file
                                slurp
                                u/parse-json-str
                                walk/keywordize-keys
                                (update :key #(clojure.string/replace % #"-" "")))
        msg                 (-> EXE-MSG
                                (assoc :envelope [(-> config :key .getBytes)])
                                (assoc-in [:header :session] (:key config))
                                (assoc-in [:content :code] code)
                                MB/encode-jupyter-message)
        ctx                 (zmq/context 1)
        [signer checker]    (u/make-signer-checker (:key config))
        proto-ctx	    {:signer signer, :checker checker}
        [sh-ep in-ep io-ep] (mapv #(str "tcp://127.0.0.1:" (% config))  [:shell_port :stdin_port :iopub_port])]
      (with-open [SH (-> (zmq/socket ctx :router) (zmq/connect sh-ep)) ; try :req
                  IN (-> (zmq/socket ctx :router) (zmq/connect in-ep))
                  IO (-> (zmq/socket ctx :pub)    (zmq/connect io-ep))]
        (cl-format *out* "~%SH = ~A ~%IN = ~A ~%IO = ~A" SH IN IO)
        (try
          (let [transport (reset! diag (fzmq/make-zmq-transport proto-ctx SH IN IO))]
            (T/send-req transport "execute_request" msg)
            (cl-format *out* "~%Send completes.")
            (cl-format *out* "~% Received ~A" (T/receive-req transport)))
          (finally
            (cl-format *out* "~% cleanup")
            (zmq/disconnect SH sh-ep)
            (zmq/disconnect IN in-ep)
            (zmq/disconnect IO io-ep)
            (doall (map zmq/close [SH IN IO]))
            #_(zmq/destroy ctx))))))

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
             
             
                 
