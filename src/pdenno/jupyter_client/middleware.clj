(ns pdenno.jupyter-client.middleware
  (:require
   [clojure.spec.alpha		         :as s]
   [pdenno.jupyter-client.spec	         :as sp]
   [pdenno.jupyter-client.util	         :as util]
   [pdenno.jupyter-client.transport	 :as tp :refer [handler-when 
                                                        parent-msgtype-pred]]))

;;; ---------------------------------------------------------------------------------
;;; Base
;;; ---------------------------------------------------------------------------------
#_(defn jupyter-message
  [{:keys [parent-message signer] :as ctx} resp-socket resp-msgtype response]
  (let [session-id	(util/message-session parent-message)
        header 		{:date (util/now)
                         :version util/PROTOCOL-VERSION
                         :msg_id (util/uuid)
                         :username "user"
                         :session session-id
                         :msg_type resp-msgtype}
        parent-header	(util/message-header parent-message)
        metadata	{}
        ]
    {:envelope (if (= resp-socket :req) (util/message-envelope parent-message) [(util/>bytes resp-msgtype)])
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
                               (util/>bytes p))))]
    result))
