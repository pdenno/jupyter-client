(ns pdenno.jupyter-client.magic-server
  "A rather vanilla zmq server useful for responding to Ipython magic"
  (:require
   [clojure.tools.logging      :as log]
   [clojure.walk               :as walk :only keywordize-keys]
   [pdenno.jupyter-client.util :as util]
   [zeromq.zmq                 :as zmq]))

(defprotocol Blocking-Server
  (start [this])
  (stop  [this]))

(defrecord Magic-Server [port server-fn stop-key fut]
  Blocking-Server
  (start [_]
    (log/info "Starting magic server")
    (reset! fut (future (server-fn))))
  (stop  [_]
    (when port
      (with-open [socket (-> (zmq/socket (zmq/context 1) :req)
                             (zmq/connect (str "tcp://*:" port)))]
        (->> stop-key util/json-str (zmq/send-str socket))
        (Thread/sleep 1000) ; Necessary! Give it a chance to quit on its own. 
        (when (future? @fut)
          (future-cancel @fut)
        (reset! fut nil))))))

;;; https://blog.scottlogic.com/2015/03/20/ZeroMQ-Quick-Intro.html
;;; Contexts help manage any sockets that are created as well as the number of threads ZeroMQ uses behind the scenes.
;;; Create one when you initialize a process and destroy it as the process is terminated. Contexts can be shared between
;;; threads and, in fact, are the only ZeroMQ objects that can safely do this.
(defn magic-server-loop
  "Return a function that listens on port and runs response-fn in a loop."
  [port response-fn skey]
  (fn []
    (let [endpoint (str "tcp://*:" port)
          keep-running? (atom true)]
      (with-open [socket (-> (zmq/socket (zmq/context 1) :rep)
                             (zmq/bind endpoint))]
        (zmq/set-linger socket 0)
        (try
          (while @keep-running? 
            (let [request (-> (zmq/receive-str socket)
                              util/parse-json-str
                              walk/keywordize-keys)]
              (if (= request skey)
                (swap! keep-running? not)
                (try (log/info "Received request: " request)
                     (let [resp (try (response-fn request)
                                     (catch Exception e
                                       (log/info "Error in response-fn on request " request ": " e)
                                       "Error in response-fn"))]
                       (log/info "magic response-fn result: " resp)
                       (->> resp util/json-str (zmq/send-str socket)))
                     (catch Exception e
                       (log/info "Magic response not serializable")
                       (->> "Magic response not serializable" util/json-str (zmq/send-str socket)))))))
          (finally
            (log/info "Stopping magic server")
            (zmq/unbind socket endpoint)
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
