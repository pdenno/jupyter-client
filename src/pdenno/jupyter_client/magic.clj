(ns pdenno.jupyter-client.magic
  "A rather vanilla zmq server useful for responding to Ipython magic"
  (:require
   [clojure.tools.logging  :as log]
   [zeromq.zmq             :as zmq]))

(defprotocol Blocking-Server
  (start [this])
  (stop  [this]))

(defrecord Magic-Server [port server-fn stop-key fut]
  Blocking-Server
  (start [_] (reset! fut (future (server-fn))))
  (stop  [_] (with-open [socket (-> (zmq/socket (zmq/context 1) :req)
                                    (zmq/connect (str "tcp://*:" port)))]
               (zmq/send-str socket stop-key)
               (log/info (str "Sending stop key " stop-key))
               (when (future? @fut)
                 (future-cancel @fut)
                 (reset! fut nil)))))

(defn magic-server-loop
  "Return a function that listens on port and runs response-fn in a loop."
  [port response-fn skey]
  (fn []
    (let [ctx (zmq/context 1)
          endpoint (str "tcp://*:" port)
          keep-running? (atom true)]
      (with-open [socket (-> (zmq/socket ctx :rep)
                             (zmq/bind endpoint))]
        (zmq/set-linger socket 0)
        (try
          (zmq/receive socket zmq/dont-wait) 
          (while @keep-running? 
            (let [request (zmq/receive-str socket)]
              (if (= request skey)
                (swap! keep-running? not)
                (do (log/info (str "Received request" request))
                    (let [resp (response-fn request)]
                      (if (string? resp)
                        (zmq/send-str socket resp)
                        (zmq/send-str (str "Server response is invalid:" resp))))))))
          (finally
            (log/info "Stopping myself")
            (-> socket
                (zmq/receive zmq/dont-wait)
                (zmq/disconnect endpoint)
                (zmq/unbind     endpoint))
            (zmq/destroy ctx)
            (zmq/close socket)))))))

(defn make-magic-server
  "Return a Magic-Server record; it implements Blocking-Server."
  [port response-fn]
  (let [skey (str (java.util.UUID/randomUUID))]
    (map->Magic-Server
     {:port port
      :server-fn (magic-server-loop port response-fn skey)
      :stop-key skey
      :fut (atom nil)})))

