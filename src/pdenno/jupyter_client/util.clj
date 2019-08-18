(ns pdenno.jupyter-client.util
  (:require
   [cheshire.core	       :as cheshire]
   [clojure.pprint	       :as pp :refer [cl-format]]
   [clojure.spec.alpha	       :as s]
   [java-time		       :as jtm]
   [pandect.algo.sha256	       :refer [sha256-hmac]]
   [pdenno.jupyter-client.spec :as sp])
  (:import [java.time.format DateTimeFormatter]))

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
  "Define two functions using the signing key and return them in a vector [signer-fn checker-fn]"
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

;;; apeckham/.clj
(defn get-free-port []
        (let [socket (java.net.ServerSocket. 0)]
          (.close socket)
          (.getLocalPort socket)))

(def uuid-pat
  (re-pattern
   (apply cl-format nil "^~A{8}\\-~A{4}\\-~A{4}\\-~A{4}\\-~A{12}$"
          (repeat 5 "[0123456789abcdefg]"))))

(defn uuid-ish?
  "Return true if the string looks like a Java Random UUID"
  [str]
  (re-matches uuid-pat str))
