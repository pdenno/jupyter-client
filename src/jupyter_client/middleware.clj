(ns jupyter-client.middleware
  (:require
   [clojure.pprint		 :as pp	:refer [pprint cl-format]]
   [clojure.spec.alpha		 :as s]
   [taoensso.timbre		 :as log]
   [jupyter-client.spec	         :as sp]
   [jupyter-client.util	         :as u]
   [jupyter-client.transport	 :as tp :refer [handler-when 
                                                ;response-mapping-transport
                                                parent-msgtype-pred]]))

;;; ---------------------------------------------------------------------------------
;;; Base
;;; ---------------------------------------------------------------------------------
#_(defn jupyter-message
  [{:keys [parent-message signer] :as ctx} resp-socket resp-msgtype response]
  (let [session-id	(u/message-session parent-message)
        header 		{:date (u/now)
                         :version u/PROTOCOL-VERSION
                         :msg_id (u/uuid)
                         :username "user"
                         :session session-id
                         :msg_type resp-msgtype}
        parent-header	(u/message-header parent-message)
        metadata	{}
        ]
    {:envelope (if (= resp-socket :req) (u/message-envelope parent-message) [(u/>bytes resp-msgtype)])
     :delimiter "<IDS|MSG>"
     :signature (signer header parent-header metadata response)
     :header header
     :parent-header parent-header
     :metadata metadata
     :content response}))

(defn encode-jupyter-message
  [jupyter-message]
  (let [segment-order	(juxt :envelope :delimiter :signature :header :parent-header :metadata :content)
        segments	(segment-order jupyter-message)
        envelope	(first segments)
        payload		(rest segments)
        result  (vec (concat envelope
                             (for [p payload]
                               (u/>bytes p))))]
    result))

;;; ----------------------------------------------------------------------------------------------------
;;; Logging
;;; ----------------------------------------------------------------------------------------------------
(def ^:private logging? (atom false))

(defn- set-logging-traffic!
  [v]
  (reset! logging? (or (and v true) false)))

(defn enable-log-traffic!
  []
  (set-logging-traffic! true))

(defn disable-log-traffic!
  []
  (set-logging-traffic! false))


(defn init!
  []
  (set-logging-traffic! (u/log-traffic?)))

