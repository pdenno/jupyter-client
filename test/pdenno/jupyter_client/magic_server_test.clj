(ns pdenno.jupyter-client.magic-server-test
  "Test the magic server."
  (:require
   [clojure.java.io                    :as io]
   [clojure.test                       :refer :all]
   [clojure.string                     :as string]
   [clojure.tools.logging              :as log]
   [clojure.walk		       :as walk]
   [pdenno.jupyter-client.magic-server :as msrv]
   [pdenno.jupyter-client.util         :as util]
   [zeromq.zmq	 	               :as zmq]))

(def test-port (util/get-free-port))
(def record-of-run (atom []))
(def clock (atom 0))

(defn tick! [& event]
  (swap! record-of-run conj (apply str "At " @clock " " event))
  (Thread/sleep 200)
  (swap! clock inc))

;;; ======  Stand-alone test =========================================
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
          ;;(zmq/destroy ctx) ; I'm having problems with this. 
          (zmq/close sock))))))

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
    (reset! record-of-run [])
    (reset! clock 0)
    (reset! server (msrv/make-magic-server test-port test-response-fn))
    (msrv/start @server)
    (tick! "start-server")
    (client-asks {:data 1})
    (client-asks {:data 2})
    (client-asks {:data 3})
    (tick! "stop-server")
    (msrv/stop @server)
    (tick! "start-server")
    (reset! server (msrv/make-magic-server test-port test-response-fn))
    (msrv/start @server)
    (client-asks {:data 100})
    (client-asks {:data 200})
    (client-asks {:data 300})
    (msrv/stop @server)))

(deftest client-server-interactions
  (is (= (do (test-script) @record-of-run)
         ["At 0 start-server"
          "At 1 client-asks: {:data 1}"
          "At 2 server receives: {:data 1}"
          "At 3 server responds: {:data 11}"
          "At 4 client receives: {:data 11}"
          "At 5 client-asks: {:data 2}"
          "At 6 server receives: {:data 2}"
          "At 7 server responds: {:data 12}"
          "At 8 client receives: {:data 12}"
          "At 9 client-asks: {:data 3}"
          "At 10 server receives: {:data 3}"
          "At 11 server responds: {:data 13}"
          "At 12 client receives: {:data 13}"
          "At 13 stop-server"
          "At 14 start-server"
          "At 15 client-asks: {:data 100}"
          "At 16 server receives: {:data 100}"
          "At 17 server responds: {:data 110}"
          "At 18 client receives: {:data 110}"
          "At 19 client-asks: {:data 200}"
          "At 20 server receives: {:data 200}"
          "At 21 server responds: {:data 210}"
          "At 22 client receives: {:data 210}"
          "At 23 client-asks: {:data 300}"
          "At 24 server receives: {:data 300}"
          "At 25 server responds: {:data 310}"
          "At 26 client receives: {:data 310}"])))

;;; ======  Test with running Jupyter Notebook  ==========================
;;; This isn't easily automated. It requires executing the jupyter cell that connects. 
;;; That cell is either one with the magic, or the %load_ext, if the __init__.py connects.
;;; See https://github.com/pdenno/mznb for an example magic that does both. 

(def conn-file
  "You need to tell it what port to listen to.  I keep this in a file ~/.local/share/nb-agent/runtime.json.
   This file is a JSON map that contains a key 'magic-server-port' which specifies the port number (int)
   on which the notebook will communicate."
  (str (System/getProperty "user.home") "/.local/share/nb-agent/runtime.json"))

;;; (magic-server-communicates :conn-file conn-file)
(defn magic-server-communicates?
  "This is a test, but as described above, it can't be easily automated (given my current skills!)."
  [& {:keys [conn-file port]}]
  (println "Now run the cell containing %load_ext <your ipython extension magic>.")
  (let [port (cond port port
                   conn-file (->  conn-file
                                  slurp
                                  util/parse-json-str
                                  walk/keywordize-keys
                                  :magic-server-port)
                   :else (throw (ex-info "Specify a :conn-file or :port." {})))
        server (msrv/make-magic-server port (fn [msg-or-content] (log/info msg-or-content)))]
    (msrv/start server)
    (try
      (while (not (.. Thread currentThread isInterrupted))
        (println "Waiting for Jupyter action on port" port)
        (Thread/sleep 5000))
      (finally
        (msrv/stop server)))))
           

