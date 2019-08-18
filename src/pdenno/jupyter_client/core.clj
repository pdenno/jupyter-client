(ns pdenno.jupyter-client.core
  (:require
   [cheshire.core		     :as cheshire]
   [clojure.java.io                  :as io]
   [clojure.pprint		     :refer [pprint cl-format]]
   [clojure.spec.alpha		     :as s]
   [clojure.tools.logging            :as log]
   [clojure.walk		     :as walk]
   [pdenno.jupyter-client.middleware :as middle]
   [pdenno.jupyter-client.transport  :as T]
   [pdenno.jupyter-client.util	     :as util]
   [pdenno.jupyter-client.zmq-client :as zmqc]
   [pandect.algo.sha256		     :refer [sha256-hmac]]
   [zeromq.zmq			     :as zmq]))


(declare wait-response heartbeat?)

(defn jupyter-runtime-files
  "Return a vector of runtime files names. If :has-heartbeat? is true (default)
   return only those that have a heartbeat."
  [& {:keys [has-heartbeat?] :or {has-heartbeat? true}}]
  (let [pltfm (System/getProperty "os.name")
        home  (System/getProperty "user.home")
        dirname (cond (= "Linux"    pltfm) (str home "/.local/share/jupyter/runtime/")
                      (= "Mac OS X" pltfm) (str home "/Library/Jupyter/runtime/"))]
    (as-> (file-seq (io/file dirname)) ?files
      (map str ?files)
      (filter #(when-let [[_ uuid?] (re-matches #".+kernel-([0123456789abcdef\-]+)\.json" %)] 
                 (when (util/uuid-ish? uuid?) %))
              ?files)
      (cond->> ?files 
        has-heartbeat? (filter #(when (heartbeat? :once? true :config-file %) %))))))

(defn- config-from-file
  "Return a config map for the argument json connection file."
  [config-file]
  (-> config-file
      slurp
      util/parse-json-str
      walk/keywordize-keys))

;;; Linux (map #(heartbeat? :once? true :config-file %) (jupyter-runtime-files))
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
          (zmq/disconnect HB hb-ep)
          (zmq/close HB))))))

(defn wait-response
  "zmq/receive-str within timeout or return :timeout."
  [socket timeout]
  (let [p (promise)]
    (future (deliver p (zmq/receive-str socket)))
    (deref p timeout :timeout)))

(def EXE-MSG {:delimiter "<IDS|MSG>",
              :header {:version util/PROTOCOL-VERSION
                       :username "username",
                       :msg_type "execute_request"},
              :parent-header {},
              :content
              {:silent false,
               :store_history true,
               :user_expressions {},
               :allow_stdin true,
               :stop_on_error true}})

(defn- make-msg
  "Complete all the easy parts of a message."
  [config code signer]
  (as-> EXE-MSG ?msg
    ;; ; They have :envelope = (if (= resp-socket :req) (jup/message-envelope parent-message) [(u/>bytes resp-msgtype)])
    (assoc ?msg :envelope [(-> config :key .getBytes)]) ; Huh?
    ;;(assoc-in ?msg [:header :session] (jup/message-session parent-message)) ; new ... we don't have a parent message.
    (assoc-in ?msg [:header :date] (util/now)) ; new
    (assoc-in ?msg [:header :msg_id] (util/uuid))
    (assoc-in ?msg [:content :code] code)
    (assoc-in ?msg [:content :allow_stdin] false)
    (assoc ?msg :signature "" #_(signer (:header ?msg) ; ; sig not working. Use '' for key. 
                                   (:parent-header ?msg)
                                   {} ; metadata
                                   (:content ?msg)))))

;;; https://jupyter-client.readthedocs.io/en/stable/messaging.html
;;;  Messages on the shell (ROUTER/DEALER) channel:
;;; The client sends an <action>_request message (such as execute_request) on its shell (DEALER) socket.
;;; The kernel receives that request and immediately publishes a status: busy message on IOPub.
;;; The kernel then processes the request and sends the appropriate <action>_reply message, such as execute_reply.
;;; After processing the request and publishing associated IOPub messages, if any, the kernel publishes a status: idle message.
;;; This idle status message indicates that IOPub messages associated with a given request have all been received.
;;; :content {:execution_state "busy"}
;;; :content {:code "print('doesTask' in (globals)())", :execution_count 284}
;;; :content {:name "stdout", :text "False\n"}
;;; :content {:execution_state "idle"}
(defn idle-msg?
  "Return true when the message content is :execution_state 'idle'."
  [m parent-id]
  (when m
    (and (= parent-id (-> m :parent-header :msg_id))
         (= "idle" (-> m :content :execution_state)))))

(defn content-msg?
  "Return :text  when the message is {:name 'stdout', :text 'some text\n'}"
  [m parent-id]
  (when m
    (when (and (= parent-id (-> m :parent-header :msg_id))
               (= "stdout"  (-> m :content :name)))
      (-> m :content :text))))

(def diag (atom nil))

(defn req-msg
  "Send an execute_request to the kernel. Return status and stdout side-effects."
  [& {:keys [code config config-file timeout-ms]
      :or {timeout-ms 1000}}]  
  (let [config            (or config (config-from-file config-file))
        [signer checker]  (util/make-signer-checker (:key config))
        msg               (make-msg config code signer)
        parent-id         (-> msg :header :msg_id)
        emsg              (middle/encode-jupyter-message msg)
        ctx               (zmq/context 1)
        [sh-ep io-ep]     (mapv #(str "tcp://127.0.0.1:" (-> config %)) [:shell_port :iopub_port])
        preq              (promise)
        start             (System/currentTimeMillis)
        result            (atom nil)]
    (reset! diag msg)
    (with-open [shell (-> (zmq/socket ctx :req) (zmq/connect sh-ep)) 
                sub   (-> (zmq/socket ctx :sub) (zmq/connect io-ep))]
      (try
        (zmq/subscribe sub "")
        (let [transport (zmqc/make-zmq-transport signer checker shell sub)]
          (T/send-req transport "execute_request" {:encoded-jupyter-message emsg})
          (future (deliver preq (-> (T/receive-req transport) :content :status keyword)))
          ;; Only return the content message once you've seen the idle message.
          (let [stdout (loop [timeout? false idle? false msg nil] 
                         (let [m (T/receive-iopub transport)] ; recv on IOPUB does not block.
                           (cond idle? msg
                                 timeout? nil
                                 :else (recur
                                        (> (- (System/currentTimeMillis) start) timeout-ms)
                                        (idle-msg? m parent-id)
                                        (or msg (content-msg? m parent-id))))))]
            (reset! result {:status (deref preq timeout-ms :timeout)
                            :stdout stdout})))
        (log/info "result = " @result)
        (catch Exception e (throw (ex-info "Error in req-msg:" {:error e})))
        (finally
          (log/trace "Disconnecting")
          (doall
           (map #(do (zmq/disconnect %1 %2)
                     (zmq/close %1))
                [shell sub] [sh-ep io-ep]))
          @result)))))
