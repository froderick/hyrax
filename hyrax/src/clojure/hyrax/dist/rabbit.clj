(ns hyrax.dist.rabbit
  (:require [hyrax.dist.api :as api]
            [clojure.set]
            [clojure.tools.logging :as log]
            [langohr.core      :as rmq]
            [langohr.channel   :as lch]
            [langohr.queue     :as lq]
            [langohr.exchange  :as le]
            [langohr.consumers :as lc]
            [langohr.basic     :as lb])
  (:import [java.util.concurrent BlockingQueue LinkedBlockingQueue TimeUnit Executors 
                                 ScheduledExecutorService Future]
            [com.rabbitmq.client Connection]))

(defn require-ch 
  [conn]
  (let [ch (lch/open conn)]
    (when-not ch
      (throw (Exception. "cannot open channel")))
    ch))

(defmacro with-conn
"Evaluates body while providing a connection to the requested rabbit broker.
  The binding provides the broker information for the connection and the name to which
  that is bound for evaluation of the body. 
  (with-conn [conn params-map] ...)"

  [binding & body]
  `(let [config# ~(second binding)
         ~(first binding) (rmq/connect config#)]
     (try
       ~@body
       (finally 
         (try
           (rmq/close ~(first binding))
           (catch Exception e#
             (.printStackTrace e#)))))))

(defmacro with-chan
"Evaluates body while providing a channel to the requested rabbit connection. 
  The binding provides the connection and the name to which the channel
  is bound for evaluation of the body. 
  (with-chan [ch conn] ...)"

  [binding & body]
  `(let [conn# ~(second binding)
         ~(first binding) (require-ch conn#)]
     (try
       ~@body
       (finally 
         (try
           (rmq/close ~(first binding))
           (catch Exception e#))))))

;;
;; bucket queue init
;;

(defn- with-rabbit-lock!
  [conn queue-name instance-id f]
  (with-chan [ch conn]
    (let [acquired (try
                     (lq/declare ch queue-name {:durable false
                                                :exclusive true
                                                :auto-delete false})
                     true
                     (catch Exception e
                       false))]
      (when acquired
        (log/debugf "[%s] acquired owner: %s" instance-id queue-name)
        (try 
          (f)
          (finally 
            (lq/delete ch queue-name)
            (log/debugf "[%s] released owner: %s" instance-id queue-name))))

      acquired)))

(defn- queue-exists?
  [conn queue-name]
  (with-chan [ch conn]
    (try 
      (lq/declare-passive ch queue-name)
      true
      (catch Exception e
        false))))

(defn init-buckets! 
  [conn owner-queue bucket-queue buckets instance-id]

  (with-rabbit-lock! conn owner-queue instance-id
    (fn []
      (when-not (queue-exists? conn bucket-queue)
        (log/infof "[%s] queue does not exist, creating and seeding it: %s => %s" 
                  instance-id bucket-queue buckets)
        (with-chan [ch conn]
          (lq/declare ch bucket-queue {:durable false 
                                       :exclusive false, 
                                       :auto-delete false})
          (doseq [^String bucket buckets]
            (lb/publish ch "" bucket-queue (.getBytes bucket)))))))
  nil)

;;
;; bucket consumer
;;

(defrecord BucketConsumer [instance-id ch consumer-tag incoming active status empty-signal])

(defn- bucket-consumer-shutdown-handler!
  "Cleans up resources on consumer shutdown based on observing changes to
   the consumer state."
  [old-state {:keys [instance-id status active ^BlockingQueue empty-signal ch consumer-tag]}]

  (cond
    ;; Let the shutdown thread know (via blockingqueue) when all the active
    ;; buckets have been released so that shutdown can complete.
    (= status :stopping)
    (when (empty? active)
      (log/debugf "[%s] bucket-consumer shutdown requested, sending empty signal" instance-id)
      (.offer empty-signal :empty))

    ;; When the state goes from stopping to stopped, clean up all the resources.
    (and (= status :stopped) (not= status (:status old-state)))
    (do
      (log/debugf "[%s] bucket-consumer shutting down" instance-id)

      ;; if any of these lines fail, the channel is left in an effectively
      ;; closed state, so we don't need to make sure we close it here
      (try 
        (lb/cancel ch consumer-tag)
        (lb/recover ch true)
        (lch/close ch)
        (catch Exception e
          (log/errorf e "[%s] bucket-consumer shutdown failed" instance-id))))))

(defn- incoming-swap 
  [{:keys [incoming] :as state} bucket]

  (assoc state :incoming (conj incoming bucket)))

(defn start-bucket-consumer! 
  "Creates and starts a bucket consumer, returns an atom that contains
   the state of the consumer."

  ([conn queue-name qos instance-id]
   (start-bucket-consumer! conn queue-name qos instance-id (atom {})))

  ([conn queue-name qos instance-id state-atom]

   (log/debugf "[%s] bucket consumer starting: %s" instance-id {:qos qos})
   
   (let [ch (doto (require-ch conn) (lb/qos qos))
         handler (fn [ch {:keys [delivery-tag] :as meta} ^bytes payload]
                   (let [bucket [(String. payload) delivery-tag]]
                     (swap! state-atom #(incoming-swap % bucket))))]

     (add-watch state-atom :watcher #(bucket-consumer-shutdown-handler! %3 %4))

     (reset! state-atom (map->BucketConsumer {:instance-id instance-id
                                              :ch ch

                                              ;; if this line fails the chan will automatically
                                              ;; be left in a closed state, we don't need to clean it up
                                              :consumer-tag (lc/subscribe ch queue-name handler)

                                              :incoming []
                                              :active []
                                              :status :running
                                              :empty-signal (LinkedBlockingQueue. 1)}))
     state-atom)))

(defn- stop-swap 
  [{:keys [active] :as state} force-stop]

  (if (or (empty? active) force-stop)
    (assoc state 
           :status :stopped
           :incoming []
           :released [])
    (assoc state :status :stopping)))

(defn stop-bucket-consumer! 
  "Blocks, waiting for the client to release any active buckets. Once all
   buckets have been released, the shutdown process completes and the function
   returns. Always returns nil."
  [state-atom & {:keys [force-stop]}]

    (loop []
      (let [{:keys [status ^BlockingQueue empty-signal]} (swap! state-atom stop-swap force-stop)]
        (when (not= status :stopped)
          (log/debugf "[%s] waiting for empty signal" (:instance-id @state-atom))
          (.take empty-signal) ;; wait for a message indicating we should try again
          (recur))))
  nil)

(defn- buckets-swap 
  [{:keys [incoming active status] :as state}]

  (if (not= status :running)
    state
    (assoc state 
           :incoming []
           :active (vec (concat incoming active)))))

(defn buckets!
  "Returns the set of bucket names currently available to the client."
  [state-atom]

  (->> (swap! state-atom buckets-swap)
       :active
       (map first)
       (into #{})))

(defn- release-swap 
  [{:keys [active] :as state} buckets]

  (let [bucket-set (set buckets)
        released?  (fn [[bucket _]] (contains? bucket-set bucket))]
    (assoc state 
           :active (vec (filter #(not (released? %)) active))
           :released (vec (filter released? active)))))

(defn release! 
  "Releases buckets held by the client back into the pool, allowing
   a new set of buckets to be allocated to the client. Returns nil."
  [state-atom buckets]

  (let [{:keys [ch released]} (swap! state-atom #(release-swap % buckets))]
      (doseq [[_ delivery-tag] released]
        (lb/reject ch delivery-tag true))
  nil))

;;
;; broadcast sender and consumer
;;

(defn send-broadcast! [conn exchange-name peer-id ^String message]
  (with-chan [ch conn]
    (lb/publish ch exchange-name "" (.getBytes message) 
                {:headers {"peer-id" peer-id}})))

(defrecord BroadcastConsumer [ch consumer-tag])

(defn start-broadcast-consumer! 
  "Creates and starts a broadcast consumer, returns a record that contains
   the state of the consumer. The handler function signature looks like 
   the following: (fn [peer-id message])."
  [conn exchange-name handler-fn]

  (let [ch (require-ch conn)
        queue-name (-> ch lq/declare :queue)
        handler (fn [ch {:keys [delivery-tag headers]} ^bytes payload]
                  (let [; rabbit driver weirdness
                        ^com.rabbitmq.client.LongString sender-wrapper (get headers "peer-id") 
                        sender-id (-> sender-wrapper
                                      (.getBytes)
                                      (String.))
                        broadcast (String. payload)]
                    (try 
                      (handler-fn sender-id broadcast)
                      (catch Exception e
                        (.printStackTrace e))
                      (finally 
                        (lb/ack ch delivery-tag)))))]

    (lb/qos ch 10)
    (le/declare ch exchange-name "fanout")
    (lq/bind ch queue-name exchange-name)
  
    (map->BroadcastConsumer {:ch ch
                             :consumer-tag (lc/subscribe ch queue-name handler)})))

(defn stop-broadcast-consumer! 
  "Shuts down the broadcast consumer. Returns nil."
  [{:keys [ch consumer-tag]}]

  (lb/cancel ch consumer-tag)
  (lch/close ch)
  nil)

;;
;; cluster-aware bucket distributor implementation
;;

;; handle broadcast events

(defn- announce-swap
  [peer-id {:keys [peers] :as state}]

  (let [now (System/currentTimeMillis)
        updated-peers (assoc peers peer-id now)]
  (assoc state :peers updated-peers)))

(defn- retract-swap
  [peer-id {:keys [peers] :as state}]
    (assoc state :peers (dissoc peers peer-id)))

(defn- handle-broadcast! 
  [{:keys [peer-id state-atom broadcast!] :as distributor} sender-id ^String msg]

  (when-not (= peer-id sender-id)
    (log/debugf "[%s] received [%s]" peer-id msg))

  (cond
    (.startsWith msg "announce:")
    (let [peer-id (-> msg (clojure.string/split #":") second)]
      (swap! state-atom #(announce-swap peer-id %)))

    (.startsWith msg "retract:")
    (let [peer-id (-> msg (clojure.string/split #":") second)]
      (swap! state-atom #(retract-swap peer-id %)))

    (.startsWith msg "poll")
    (broadcast! (str "announce:" peer-id))))

;; handle periodic self-announce and peer expiration

(defn- expire-swap [expiration-period ^TimeUnit expiration-units peer-id {:keys [peers] :as state}]
  (let [now (System/currentTimeMillis)
        oldest-permitted (- now (.toMillis expiration-units expiration-period))
        expired (->> peers
                     (filter #(< (second %) oldest-permitted))
                     (into #{}))]

    (doseq [[id _] expired]
      (log/debugf "[%s] peer-expired: %s" peer-id id))

    (assoc state :peers (->> (clojure.set/difference peers expired)
                             (into {})))))

(defn- update-peers! 
  [{:keys [broadcast! peer-id state-atom] {:keys [expiration-period expiration-units]} :options}]
  (try
    (broadcast! (str "announce:" peer-id))
    (swap! state-atom #(expire-swap expiration-period expiration-units peer-id %))
    (catch Exception e
      (.printStackTrace e))))

;; handle recalculating the partition size and changing the qos on the
;; bucket consumer to match

(defn- partitions-swap 
  [default-buckets {:keys [peers partition-size] :as state}]

  (let [known-consumers (count peers)
        size (if (zero? known-consumers)
                1
                (int (/ (count default-buckets) known-consumers)))]
    (if (not= size partition-size)
      (assoc state :partition-size size)
      state)))

(defn- update-partitions!
  [{:keys [default-buckets state-atom]}]

  (try
    (swap! state-atom #(partitions-swap default-buckets %))
    (catch Exception e
      (.printStackTrace e))))

(defn- interruptible-sleep [millis peer-id]
  (try
    (Thread/sleep 1000)
    (catch InterruptedException e
      (log/infof e "[%s] interrupted while about to retry bucket-consumer startup" peer-id))))

(defn- log-peer-changes 
  [last-peers peers peer-id]
  (let [make-set #(->> % (map first) (into #{}))
        a (make-set last-peers)
        b (make-set peers)]
    (doseq [id (clojure.set/difference b a)]
      (log/debugf "[%s] peer added: %s%s" peer-id id (if (= id peer-id) " (self)" "")))
    (doseq [id (clojure.set/difference a b)]
      (log/debugf "[%s] peer removed: %s%s" peer-id id (if (= id peer-id) " (self)" "")))))

(defn- partition-size-listener!
  "Restarts the distributor's bucket consumer when the partition size changes 
   so that it and the qos value match."
  [conn queue-name peer-id
   {last-size :partition-size last-peers :peers} 
   {new-size :partition-size consumer :bucket-consumer :keys [peers shutdown]}]

  (when (log/enabled? :debug)
    (log-peer-changes last-peers peers peer-id))

  (let [start-fn #(try
                    (start-bucket-consumer! conn queue-name new-size peer-id consumer)
                    (catch Exception e
                      (log/errorf e "[%s] bucket-consumer start failed" peer-id)
                      nil))]

    (if (not= last-size new-size)

      (do
        (log/infof "[%s] detected %s consumer(s), using bucket partition size of %s (was %s)" 
                   peer-id (count peers) new-size last-size)
        (stop-bucket-consumer! consumer) ;; doesn't throw exceptions on errors
        (start-fn))
      
      (let [{:keys [status]} @consumer]
        (when (= status :stopped)
          (log/infof "[%s] retrying bucket-consumer startup" peer-id)
          (start-fn))))))

(defn peer-id-old []
  (let [hostname (-> (java.net.InetAddress/getLocalHost) .getHostName)
        uuid (java.util.UUID/randomUUID)]
    (str hostname "/" uuid)))

(let [names (->> (clojure.java.io/resource "names.txt")
                 slurp 
                 clojure.string/split-lines)]
  (defn peer-id []
    (let [hostname (-> (java.net.InetAddress/getLocalHost) .getHostName)
          id (-> names
                 shuffle
                 first)]
      (str hostname "/" id))))

;; bucket distributor main entry point

(defrecord RabbitDistributor [options default-buckets peer-id state-atom 
                              broadcast! broadcast-consumer peers-future partition-future])

(defn start-bucket-distributor! 
  [conn bucket-name default-buckets ^ScheduledExecutorService scheduler options]

  (let [defaults {:peers-period      1                     :peers-units      TimeUnit/MINUTES
                  :expiration-period 2                     :expiration-units TimeUnit/MINUTES
                  :partition-delay   5 :partition-period 5 :partition-units  TimeUnit/SECONDS}
        options (merge defaults options)

        owner-queue (str bucket-name ".bucket.owner")
        bucket-queue (str bucket-name ".bucket")
        broadcast-exchange (str bucket-name ".bucket.broadcast")]

    
    (let [peer-id (peer-id)

          _ (log/infof "[%s] starting distributor" peer-id)

          _ (init-buckets! conn owner-queue bucket-queue default-buckets peer-id)

          state-atom (-> (atom {:peers {} ; atomic peer/bucket partition size state
                                :partition-size 1
                                :bucket-consumer (start-bucket-consumer! conn bucket-queue 1 peer-id)
                                :shutdown false})
                         (add-watch :watch 
                                    #(partition-size-listener! conn bucket-queue peer-id %3 %4)))

          broadcast! #(send-broadcast! conn broadcast-exchange peer-id %)

          ; core stuff that gets passed around
          distributor (map->RabbitDistributor {:options options
                                               :default-buckets default-buckets
                                               :peer-id peer-id 
                                               :state-atom state-atom
                                               :broadcast! broadcast!})

          broadcast-consumer (start-broadcast-consumer! conn broadcast-exchange
                                                        #(handle-broadcast! distributor %1 %2))
      {:keys [peers-period peers-units 
              partition-delay partition-period partition-units]} options]

      (broadcast! "poll")

      ; this stuff we only need again on shutdown
      (assoc distributor 
             :broadcast-consumer broadcast-consumer
             :peers-future (.scheduleAtFixedRate scheduler #(update-peers! distributor)
                             0 peers-period peers-units)
             :partition-future (.scheduleAtFixedRate scheduler #(update-partitions! distributor)
                                 partition-delay partition-period partition-units)))))

(defn stop-bucket-distributor! 
  [{:keys [broadcast! broadcast-consumer ^Future peers-future ^Future partition-future peer-id state-atom]}]

  (log/infof "[%s] stopping distributor" peer-id)

  (.cancel peers-future true)
  (.cancel partition-future true)
  (stop-broadcast-consumer! broadcast-consumer)
  (stop-bucket-consumer! (-> @state-atom :bucket-consumer))
  (broadcast! (str "retract:" peer-id)))

(extend-protocol api/Distributor
  RabbitDistributor
  (acquire-buckets* [this]
    (buckets! (-> this :state-atom deref :bucket-consumer)))
  (release-buckets* [this buckets]
    (release! (-> this :state-atom deref :bucket-consumer) buckets)))


  
