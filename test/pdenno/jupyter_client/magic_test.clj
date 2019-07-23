(ns pdenno.jupyter-client.magic_test
  "Test the magic server."
  (:require
   [clojure.test ]
   [clojure.string              :as string]
   [zeromq.zmq	 	        :as zmq]
   [pdenno.jupyter-client.magic :as magic]))

(def test-port 3876)
(def record-of-run (atom []))
(def clock (atom 0))

(defn tick! [& event]
  (swap! record-of-run conj (apply str "at " @clock event))
  (Thread/sleep 1000)
  (swap! clock inc))

(defn client-asks [data]
  (tick! "client-asks: " data)
  (let [ctx  (zmq/context 1)
        ep   (str "tcp://127.0.0.1:" test-port)]
    (with-open [sock (-> (zmq/socket ctx :req)
                         (zmq/connect ep))]
      (zmq/set-linger sock 0)
      (try
        (do (zmq/send-str sock (str data))
            (let [response (zmq/receive-str sock)]
              (tick! "client receives: " response)))
        (finally
          (zmq/disconnect sock ep)
          (zmq/destroy ctx)
          (zmq/close ep))))))

(defn test-response-fn
  "Example response to the python magic. Read data and increment."
  [query]
  (tick! "server receives: " query)
  (let [response (update (read-string query) :data #(+ % 10))]
    (tick! "server responds: " response)
    (str response)))

(defn test-script []
  (let [server (atom nil)
        data   (atom {:data 0})]
    (reset! server (magic/make-magic-server test-port test-response-fn))
    (magic/start @server)
    (tick! "start-server")
    (client-asks {:data 1})
    (client-asks {:data 2})
    (client-asks {:data 3})
    (magic/stop @server)
    (tick! "stop-server")
    (reset! server (magic/make-magic-server test-port test-response-fn))
    (magic/start @server)
    (client-asks {:data 100})
    (client-asks {:data 200})
    (client-asks {:data 300})
    (magic/stop @server)))

  
           

