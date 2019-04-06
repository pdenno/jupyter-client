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


(def EXE-MSG {:envelope
              [(byte-array [52, 102, 98, 53, 54, 99, 53, 48, 49, 100, 51, 51, 52, 48, 100, 51,
                            57, 56, 98, 98, 56, 100, 51, 55, 52, 50, 99, 100, 57, 101, 53, 48])],
              :delimiter "<IDS|MSG>",
              :signature ; connect.json says "signature_scheme": "hmac-sha256",
              "5a98d552a77da7f1595030a8771f4ef861a5ca406d13b1001d4acfa7a58ace88",  
              :header
              {:msg_id "e93eb6ef140940518bb379ae9c962a7d",
               :username "username",
                      ; "a64cb91089624d76851a3a08bac0c6a4" connect.json "key": "a64cb910-8962-4d76-851a-3a08bac0c6a4"
               :session "4fb56c501d3340d398bb8d3742cd9e50",
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


(def config
  (-> "/Users/pdenno/Library/Jupyter/runtime/kernel-e0d187d0-2070-4733-851c-a8dd36fbaab5.json"
      slurp
      u/parse-json-str
      walk/keywordize-keys))

;;; 48 is address already in use. 
#_(defn- mksocket
  [context addrs type nm]
  (doto (zmq/socket context type)
    (zmq/bind (get addrs nm))))

#_(defn- make-sockets
  [config]
  (let [context (zmq/context 1)
        addrs   (->> [:control_port :shell_port :stdin_port :iopub_port :hb_port]
                     (map #(vector % (address config %)))
                     (into {}))]
    (map (partial apply mksocket context addrs)
         [[:router :control_port] [:router :shell_port] [:router :stdin_port]
          [:pub :iopub_port] [:rep :hb_port]])))

;;; Doc on zmq/socket
;;;   The newly created socket is initially unbound, and not associated with any
;;;   endpoints. In order to establish a message flow a socket must first be
;;;   connected to at least one endpoint with connect, or at least one endpoint
;;;   must be created for accepting incoming connections with bind.

;;; It seems to me that the meaning of bind and serve isn't accurately communicated in the documentation. 
;;; What really matters is the messaging pattern used. (e.g. :rep, :req, :pair).
;;; You can do both send and recv with server/client doing respectively bind/connect with :rep/:req.

(defn msg-tryme [& {:keys [config-file] :or {config-file "./resource/connect.json"}}]
  (let [config
        msg (-> EXE-MSG
                (assoc-in [:content :code] "3 + 3")
                MB/encode-jupyter-message)
        ctx                     (zmq/context 1)
        [signer checker]	(u/make-signer-checker (:key config))
        proto-ctx		{:signer signer, :checker checker}]
    (with-open [SH (-> (zmq/socket ctx :router)
                       (zmq/connect (str "tcp://127.0.0.1:" (:shell_port config))))
                IN (-> (zmq/socket ctx :router)
                       (zmq/connect (str "tcp://127.0.0.1:" (:stdin_port config))))
                IO (-> (zmq/socket ctx :pub)
                       (zmq/connect (str "tcp://127.0.0.1:" (:iopub_port config))))]
      (cl-format *out* "~%SH = ~A ~%IN = ~A ~%IO = ~A" SH IN IO)
      (let [transport (fzmq/make-zmq-transport proto-ctx SH IN IO)]
        (T/send-req transport "execute_request" msg)
        (cl-format *out* "~%Send completes.")
        (T/receive-req transport)))))

        

        
        
  
