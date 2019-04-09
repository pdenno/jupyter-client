(ns jupyter-client.util
  (:require
   [cheshire.core	   :as cheshire]
   [clojure.pprint	   :as pp]
   [pandect.algo.sha256	   :refer [sha256-hmac]]
   [clojure.spec.alpha	   :as s]
   [java-time		   :as jtm]
   [jupyter-client.spec    :as sp]
   [omniconf.core	   :as cfg])
  (:import [java.time.format DateTimeFormatter]))

(defn log-traffic?
  []
  (cfg/get :traffic-logging?))

;;; POD Not sure this is being used!
(cfg/define
  {:log-level			{:description	"Default log level as defined by com.taoensso/timbre."
                                 :type		:keyword
                                 :one-of	[:trace :debug :info :warn :error :fatal :report]
                                 :default	:error}
   :print-stacktraces?		{:description	(str "Print stacktrace on error. "
                                                     "Temporary workaround for issue with uncaught exceptions in nrepl.")
                                 :type		:boolean
                                 :default	true}
   :traffic-logging?		{:description	"Log all incoming and outgoing ZMQ message to stdout."
                                 :type		:boolean
                                 :default	false}})

;;; ---------------------------------------------------------------------------------
;;; From clojupyter.kernel.util
;;; ---------------------------------------------------------------------------------
(defn ctx?
  [v]
  (s/valid? ::sp/ctx v))

(defn uuid
  []
  (str (java.util.UUID/randomUUID)))

(def json-str cheshire/generate-string)
(def parse-json-str cheshire/parse-string)

(defn >bytes
  [v]
  (cond
    (= (type v) (Class/forName "[B"))	v
    (string? v)	(.getBytes v)
    true	(.getBytes (json-str v))))

(defn now []
 (->> (.withNano (java.time.ZonedDateTime/now) 0)
      (jtm/format DateTimeFormatter/ISO_OFFSET_DATE_TIME)))

(defn make-signer-checker
  [key]
  (let [mkchecker (fn [signer]
                    (fn [{:keys [signature header parent-header metadata content]}]
                      (let [our-signature (signer header parent-header metadata content)]
                        (= our-signature signature))))
        signer	(if (empty? key)
                  (constantly "")
                  (fn [header parent metadata content]
                    (let [res (apply str (map json-str [header parent metadata content]))]
                      (sha256-hmac res key))))]
    [signer (mkchecker signer)]))

(defn pp-str
  [v]
  (with-out-str (pp/pprint v)))

(defn set-var-indent!
  [indent-style var]
  (alter-meta! var #(assoc % :style/indent indent-style)))

;;; ---------------------------------------------------------------------------------
;;; From clojupyter.kernel.jupyter
;;; ---------------------------------------------------------------------------------
(def PROTOCOL-VERSION "5.3")

(defn message-content		[message]	(get-in message [:content]))
(defn message-header	        [message]	(get-in message [:header]))
(defn message-session	        [message]	(get-in message [:header :session]))
(defn message-delimiter		[message]	(get-in message [:delimiter]))
(defn message-envelope	        [message]	(get-in message [:envelope]))
(defn message-parent-header	[message]	(get-in message [:parent-header]))
(defn message-signature		[message]	(get-in message [:signature]))
(defn message-msg-type	        [message]	(get-in message [:header :msg_type]))

(defn build-message
  [message]
  (when message
    {:envelope (message-envelope message)
     :delimiter (message-delimiter message)
     :signature (message-signature message)
     :header (parse-json-str (message-header message) keyword)
     :parent-header (parse-json-str (message-parent-header message) keyword)
     :content (parse-json-str (message-content message) keyword)
     ::zmq-raw-message message}))

