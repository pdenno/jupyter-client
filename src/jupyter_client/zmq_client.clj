(ns jupyter-client.zmq-client
  (:require
   [clojure.pprint		:as pp	:refer [pprint]]
   [taoensso.timbre		:as log]
   [zeromq.zmq			:as zmq]

   [jupyter-client.util 	:as u]
   [jupyter-client.transport	:as T]))

(defn- receive-jupyter-message
  ([zmq-socket flag]
   (letfn [(rcv-all
             [socket flag]
             (loop [acc (transient [])]
               (when-let [part (zmq/receive socket flag)]
                 (let [new-acc (conj! acc part)]
                   (if (zmq/receive-more? socket)
                     (recur new-acc)
                     (persistent! new-acc))))))
           (parts-to-message
             [parts]
             (let [delim "<IDS|MSG>"
                   delim-byte (.getBytes delim)
                   delim-idx (first
                              (map first (filter #(apply = (map seq [(second %) delim-byte]))
                                                 (map-indexed vector parts))))
                   envelope (take delim-idx parts)
                   blobs (map #(new String % "UTF-8")
                              (drop (inc delim-idx) parts))
                   blob-names [:signature :header :parent-header :metadata :content]
                   n-blobs (count blob-names)
                   message (merge
                            {:envelope envelope :delimiter delim}
                            (zipmap blob-names (take n-blobs blobs))
                            {:buffers (drop n-blobs blobs)})]
               message))]
     (when-let [parts (rcv-all zmq-socket flag)]
       (let [message (parts-to-message parts)]
         (log/debug "Received Jupyter message" (with-out-str (pp/pprint message)))
         message)))))

(defn- send-segments
  [socket segments]
  (let [n	(-> segments count dec)
        send-it (fn [seg idx]
                  (let [more? (if (< idx n) zmq/send-more 0)]
                    (zmq/send socket seg more?)))]
    (doall (map send-it segments (range)))))

(defrecord zmq-transport [S req-socket parent-message]
  T/Transport
  (T/send* [_ socket resp-msgtype {:keys [encoded-jupyter-message]}] ; POD resp_msgtype not used.
      (when (not= socket :req) 
        (throw (ex-info (str "send*: Unknown socket " socket ".") {:socket socket})))
    (send-segments req-socket encoded-jupyter-message))
  (T/receive* [_ socket]
    (when (not= socket :req) 
      (throw (ex-info (str "send*: Unknown socket " socket ".") {:socket socket})))
    (-> (receive-jupyter-message req-socket 0)
        u/build-message)))

(alter-meta! #'->zmq-transport #(assoc % :private true))

(defn make-zmq-transport
  [S req-socket]
  (->zmq-transport S req-socket nil))
