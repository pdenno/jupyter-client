(ns pdenno.jupyter-client.core
  (:require
   [clojure.pprint		   :refer [pprint cl-format]]
   [clojure.walk		   :as walk]
   [clojure.spec.alpha		   :as s]
   [cheshire.core		   :as cheshire]
   [pandect.algo.sha256		   :refer [sha256-hmac]]
   [taoensso.timbre		   :as log]
   [zeromq.zmq			   :as zmq]
   
   [pdenno.jupyter-client.util	          :as u]
   [pdenno.jupyter-client.transport	  :as T]
   [pdenno.jupyter-client.middleware	  :as MB]
   [pdenno.jupyter-client.zmq-client      :as zmqc]))

(def EXE-MSG {:delimiter "<IDS|MSG>",
              :header
              {:username "username",
               :msg_type "execute_request",
               :version "5.2"},
              :parent-header {},
              :content
              {:silent false,
               :store_history true,
               :user_expressions {},
               :allow_stdin true,
               :stop_on_error true}})

(declare wait-response)

(defn config-from-file
  "Return a config map for the argument json connection file."
  [config-file]
  (-> config-file
      slurp
      u/parse-json-str
      walk/keywordize-keys
      #_(update :key #(clojure.string/replace % #"-" "")))) ; Questionable.

;;; Linux (heartbeat? :once? true :config-file "/home/pdenno/.local/share/jupyter/runtime/kernel-89e0f64d-5505-4bd6-bcc3-eb998d7bfe12.json")
;;; Mac:  (heartbeat? :once? true :config-file "/Users/pdenno/Library/Jupyter/runtime/kernel-437b1cfd-137e-48d8-b461-7f8c18a28f9b.json")
(defn heartbeat?
  "Clients send ping messages on a REQ socket, which are echoed right back from the Kernel’s REP socket.
   These are simple bytestrings, not full JSON messages...
   Heartbeat: This socket allows for simple bytestring messages to be sent between the frontend and
   the kernel to ensure that they are still connected."
  [& {:keys [once? config-file verbose?]}]
  (let [config (config-from-file config-file)
        ctx    (zmq/context 1)
        hb-ep  (str "tcp://127.0.0.1:" (:hb_port config))]
    (with-open [HB (-> (zmq/socket ctx :req)
                       (zmq/connect hb-ep))]
      (zmq/set-linger HB 0)
      (try
        (if once?
          (do (zmq/send-str HB "ping") ; This is send with (.getBytes <string>).
              (let [resp (wait-response HB 500)]
                (if (= :timeout resp) nil true)))
          (while (not (.. Thread currentThread isInterrupted))
            (zmq/send-str HB "ping") ; This is send with (.getBytes <string>).
            (Thread/sleep 1000)
            (let [resp (wait-response HB 5000)]
              (if (= :timeout resp) (println "%Timeout") (println resp)))))
        (finally
          (when verbose? (println "Disconnecting"))
          ;(zmq/receive HB zmq/dont-wait) ; 2019-0724 nope.
          (zmq/disconnect HB hb-ep)
          ;(zmq/destroy ctx)              ; 2019-0724 nope.
          (zmq/close HB))))))

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

;;; stdin messages are unique in that the request comes from the kernel, and the reply from the frontend.
;;; The frontend is not required to support this, but if it does not, it must set 'allow_stdin' : False

(defn make-msg
  "Complete all the easy parts of a message."
  [config code signer]
  (as-> EXE-MSG ?msg
    (assoc ?msg :envelope [(-> config :key .getBytes)])
    (assoc-in ?msg [:header :session] (:key config))
    (assoc-in ?msg [:header :msg_id] (u/uuid))
    (assoc-in ?msg [:header :version] u/PROTOCOL-VERSION)
    (assoc-in ?msg [:content :code] code)
    (assoc-in ?msg [:content :allow_stdin] false)
    (assoc ?msg :signature (signer (:header ?msg) ; ; sig not working. Use '' for key. 
                                   (:parent-header ?msg)
                                   {} ; metadata
                                   (:content ?msg)))))

(defn req-msg
  "Send an execute_request to the kernel. Return status and stdout side-effects."
  [& {:keys [code config-file timeout-ms verbose?]
      :or {timeout-ms 1000
           config-file "./resources/connect.json",
           code "print('Greetings from Clojure!')"}}]
  (let [config            (config-from-file config-file)
        [signer checker]  (u/make-signer-checker (:key config)) ; Signing does not work. I use key=''
        msg               (-> (make-msg config code signer)
                              MB/encode-jupyter-message)
        ctx               (zmq/context 1)
        proto-ctx	  {:signer signer, :checker checker}
        [sh-ep io-ep]     (mapv #(str "tcp://127.0.0.1:" (-> config %)) [:shell_port :iopub_port])
        [preq psub]       [(promise) (promise)]
        result            (atom nil)]
    (log/set-level! :warn)
    (with-open [shell (-> (zmq/socket ctx :req) (zmq/connect sh-ep)) 
                sub   (-> (zmq/socket ctx :sub) (zmq/connect io-ep))]
      (try
        (zmq/subscribe sub "")
        (let [transport (zmqc/make-zmq-transport proto-ctx shell sub)]
          (T/send-req transport "execute_request" {:encoded-jupyter-message msg})
          (future (deliver preq (-> (T/receive-req transport) :content :status keyword)))
          (future (deliver psub (->> [(T/receive-iopub transport)  ; POD I *assume* the req generates three pub responses:
                                      (T/receive-iopub transport)  ; status, execute_input, stream. If less, it will 
                                      (T/receive-iopub transport)] ; probably time out. 
                                     (map #(dissoc % :jupyter-client.util/zmq-raw-message))
                                     (filter #(= "stream" (-> % :header :msg_type)))
                                     first :content :text)))
          (reset! result {:status (deref preq timeout-ms :timeout)
                          :stdout (deref psub timeout-ms :no-output)}))
        (finally
          (when verbose? (println "Disconnecting"))
          (doall
           (map #(do #_(zmq/receive %1 zmq/dont-wait) ; 2019-07-23
                     (zmq/disconnect %1 %2)
                     (zmq/close %1))
                [shell sub] [sh-ep io-ep]))
          #_(zmq/destroy ctx)  ; 2019-07-23
          @result)))))
