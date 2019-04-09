(ns pdenno.jupyter-client.transport
  (:refer-clojure :exclude [send])
  (:require
   [clojure.pprint		:as pp	:refer [pprint cl-format]]
   [pdenno.jupyter-client.util 	:as u]))

(defprotocol Transport
  (send* [_ socket msgtype message]
    "Send `message` of type `msgtype` on `socket`.  Not intended to be
    used directly, use `send-req`, `send-stdin`, and `send-iopub`
    instead.")
  (receive* [_ socket]
    "Read full Jupyter message from `socket`.  Not intended to be used directly,
    use `receive-stdin` or `receive-req` instead."))

(defn send-req
  [transport msgtype message]
  (send* transport :req msgtype message))

(defn send-stdin
  [transport msgtype message]
  (send* transport :stdin msgtype message))

(defn receive-iopub
  [transport]
  (receive* transport :sub))

(defn receive-stdin
  [transport]
  (receive* transport :stdin))

(defn receive-req
  [transport]
  (receive* transport :req))

(defn bind-parent-message
  [transport parent-message]
  (assoc transport :parent-message parent-message))

(defn bind-transport
  [message transport]
  (assoc message :transport transport))

(defn handler-when
  [pred handler]
  (fn [handler']
   (fn [message]
     ((if (pred message) handler handler') message))))

(defn parent-msgtype-pred
  [msgtype]
  (fn [{:keys [parent-message]}]
    (= (u/message-msg-type parent-message) msgtype)))

