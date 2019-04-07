(ns jupyter-client.middleware
  (:require
   [jupyter-client.middleware.base	  :as base]
   [jupyter-client.middleware.log-traffic :as log-traffic]))

;;; ----------------------------------------------------------------------------------------------------
;;; MIDDLEWARE
;;; ----------------------------------------------------------------------------------------------------

(def wrap-busy-idle			base/wrap-busy-idle)
(def wrap-kernel-info-request		base/wrap-kernel-info-request)
(def wrap-shutdown-request		base/wrap-shutdown-request)
(def wrapin-bind-msgtype		base/wrapin-bind-msgtype)
(def wrapin-verify-request-bindings	base/wrapin-verify-request-bindings)
(def wrapout-construct-jupyter-message	base/wrapout-construct-jupyter-message)
(def wrapout-encode-jupyter-message	base/wrapout-encode-jupyter-message)

(def wrap-print-messages		log-traffic/wrap-print-messages)

;;; ----------------------------------------------------------------------------------------------------
;;; HANDLERS
;;; ----------------------------------------------------------------------------------------------------

(def not-implemented-handler		base/not-implemented-handler)

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
        wrap-busy-idle
        #_wrap-base-handlers))

(def default-handler
  (default-wrapper not-implemented-handler))
