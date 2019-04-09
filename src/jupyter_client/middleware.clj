(ns jupyter-client.middleware
  (:require
   [clojure.pprint		 :as pp	:refer [pprint cl-format]]
   [clojure.spec.alpha		 :as s]
   [taoensso.timbre		 :as log]
   [jupyter-client.spec	         :as sp]
   [jupyter-client.util	         :as u]
   [jupyter-client.transport	 :as tp :refer [handler-when transport-layer
                                                response-mapping-transport
                                                parent-msgtype-pred]]))

;;; ---------------------------------------------------------------------------------
;;; Base
;;; ---------------------------------------------------------------------------------
(defn jupyter-message
  [{:keys [parent-message signer] :as ctx} resp-socket resp-msgtype response]
  (let [session-id	(u/message-session parent-message)
        header 		{:date (u/now)
                         :version u/PROTOCOL-VERSION
                         :msg_id (u/uuid)
                         :username "kernel"
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

(def wrap-print-messages
  (transport-layer
   {:send-fn (fn [{:keys [transport parent-message msgtype] :as ctx} socket resp-msgtype resp-message]
               (let [uuid (subs (u/uuid) 0 6)]
                 (when @logging? 
                   (log/info (str "wrap-print-messages parent-message (" uuid "):") socket msgtype
                             "\n" (with-out-str (pprint (dissoc parent-message ::u/zmq-raw-message))))
                   (log/info (str "wrap-print-messages response-message (" uuid "):") socket resp-msgtype
                             "\n" (with-out-str (pprint resp-message))))
                 (tp/send* transport socket msgtype resp-message)))}))

(defn init!
  []
  (set-logging-traffic! (u/log-traffic?)))

;;; ----------------------------------------------------------------------------------------------------
;;; MIDDLEWARE FUNCTIONS
;;; ----------------------------------------------------------------------------------------------------
(def wrapin-verify-request-bindings
  (handler-when (complement u/ctx?)
    (fn [ctx]
      (let [s (s/explain-str ::sp/ctx ctx)]
        (throw (ex-info (str "Bad ctx: " s)
                 {:ctx ctx, :explain-str s}))))))

(def wrapin-bind-msgtype
  (fn [handler]
   (fn [ctx]
     (handler
      (assoc ctx :msgtype (get-in ctx [:parent-message :header :msg_type]))))))

(def wrapout-construct-jupyter-message
  (transport-layer
   {:send-fn (fn [{:keys [transport] :as ctx} socket resp-msgtype response]
               (tp/send* transport socket resp-msgtype
                         (assoc response :jupyter-message (jupyter-message ctx socket resp-msgtype response))))}))

(def wrapout-encode-jupyter-message
  (response-mapping-transport
   (fn [ctx {:keys [jupyter-message] :as response}]
     (assoc response :encoded-jupyter-message (encode-jupyter-message jupyter-message)))))

;;; ----------------------------------------------------------------------------------------------------
;;; HANDLER
;;; ----------------------------------------------------------------------------------------------------
(def not-implemented-handler
  (fn [{:keys [msgtype parent-message]}]
    (do (log/error (str "Message type " msgtype " not implemented: Ignored."))
        (log/debug "Message dump:\n" (u/pp-str parent-message)))))


;;; ----------------------------------------------------------------------------------------------------
;;; MIDDLEWARE
;;; ----------------------------------------------------------------------------------------------------
(def wrap-kernel-info-request		wrap-kernel-info-request)
(def wrap-shutdown-request		wrap-shutdown-request)
(def wrapin-bind-msgtype		wrapin-bind-msgtype)
(def wrapin-verify-request-bindings	wrapin-verify-request-bindings)
(def wrapout-construct-jupyter-message	wrapout-construct-jupyter-message)
(def wrapout-encode-jupyter-message	wrapout-encode-jupyter-message)

(def wrap-print-messages		wrap-print-messages)

;;; ----------------------------------------------------------------------------------------------------
;;; HANDLERS
;;; ----------------------------------------------------------------------------------------------------
(def not-implemented-handler		not-implemented-handler)

;;; ----------------------------------------------------------------------------------------------------
;;; MIDDLEWARE
;;; ----------------------------------------------------------------------------------------------------
(def wrap-jupyter-messaging
  (comp wrapout-encode-jupyter-message
        wrapout-construct-jupyter-message))

(def default-wrapper
  (comp wrapin-verify-request-bindings
        wrapin-bind-msgtype
        wrap-print-messages
        wrap-jupyter-messaging
        #_wrap-busy-idle
        #_wrap-base-handlers))

(def default-handler
  (default-wrapper not-implemented-handler))
