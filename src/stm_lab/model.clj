(ns stm-lab.model)

(def defaults
  {:workers 16
   :operations-per-worker 120
   :hot-refs 1
   :critical-work-us 180
   :side-effect-percent 25
   :shards 16
   :batch-size 20
   :observation-seconds 12
   :seed 41873})

(def query-keys
  {"workers" :workers
   "operations_per_worker" :operations-per-worker
   "hot_refs" :hot-refs
   "critical_work_us" :critical-work-us
   "side_effect_percent" :side-effect-percent
   "shards" :shards
   "batch_size" :batch-size
   "observation_seconds" :observation-seconds
   "seed" :seed})

(def ranges
  {:workers [2 64]
   :operations-per-worker [20 1000]
   :hot-refs [1 64]
   :critical-work-us [20 2000]
   :side-effect-percent [0 100]
   :shards [2 128]
   :batch-size [2 100]
   :observation-seconds [5 30]
   :seed [1 2147483647]})

(def policies
  [{:policy "alter_hot_ref"
    :name "Alter the hot Ref"
    :kicker "strict write · automatic retry"
    :description "Every logical increment alters the same small Ref set, so overlapping write points invalidate speculative work."
    :tradeoff "Strong transactional semantics, but retries repeat the entire dosync body and amplify any accidental side effect."
    :color "#ff5c7a"
    :recommended false
    :mode :alter
    :semantics "general read-modify-write"}
   {:policy "commute_counter"
    :name "Commute the counter"
    :kicker "commutative update · reorder safely"
    :description "A truly commutative counter update can be applied against the latest value at commit instead of conflicting like alter."
    :tradeoff "Excellent for independent counters; invalid when the function depends on transaction-time ordering or another Ref."
    :color "#ffcc4d"
    :recommended false
    :mode :commute
    :semantics "commutative operation only"}
   {:policy "sharded_refs"
    :name "Shard the Refs"
    :kicker "spread write points · merge on read"
    :description "Workers write across multiple Refs, reducing the probability that two transactions invalidate the same write point."
    :tradeoff "Lower contention moves complexity into shard choice, aggregation, and multi-Ref invariants."
    :color "#42d6c8"
    :recommended false
    :mode :sharded
    :semantics "partitioned transactional state"}
   {:policy "local_batch_flush"
    :name "Batch outside dosync"
    :kicker "accumulate locally · flush once"
    :description "Each worker accumulates immutable local state and enters STM only to flush a bounded batch to a shard."
    :tradeoff "The smallest speculative surface here, with bounded visibility delay and more recovery bookkeeping."
    :color "#9d7bff"
    :recommended true
    :mode :batch
    :semantics "bounded eventual visibility"}])

(defn- parse-long-safe [value fallback]
  (try
    (Long/parseLong (str value))
    (catch Exception _ fallback)))

(defn normalize-config [raw]
  (reduce-kv
   (fn [config key [minimum maximum]]
     (let [candidate (parse-long-safe (get raw key (get defaults key)) (get defaults key))]
       (assoc config key (long (max minimum (min maximum candidate))))))
   {}
   ranges))

(defn config-from-query [query]
  (normalize-config
   (reduce-kv
    (fn [result raw-key value]
      (if-let [key (get query-keys raw-key)]
        (assoc result key value)
        result))
    defaults
    query)))

(defn- public-config [config]
  {:workers (:workers config)
   :operationsPerWorker (:operations-per-worker config)
   :hotRefs (:hot-refs config)
   :criticalWorkUs (:critical-work-us config)
   :sideEffectPercent (:side-effect-percent config)
   :shards (:shards config)
   :batchSize (:batch-size config)
   :observationSeconds (:observation-seconds config)
   :seed (:seed config)})

(defn- round-to [value places]
  (let [factor (Math/pow 10.0 places)]
    (/ (Math/round (* (double value) factor)) factor)))

(defn- ceil-long [value]
  (long (Math/ceil (double value))))

(defn- base-conflict [config]
  (let [workers (:workers config)
        refs (:hot-refs config)
        overlap (/ (double (dec workers))
                   (+ (double (dec workers)) (* 2.0 refs)))
        work-factor (+ 0.45
                       (* 0.5
                          (/ (double (:critical-work-us config))
                             (+ (:critical-work-us config) 120.0))))]
    (min 0.90 (* overlap work-factor))))

(defn- policy-shape [config definition]
  (let [logical-ops (* (:workers config) (:operations-per-worker config))
        conflict (base-conflict config)
        mode (:mode definition)]
    (case mode
      :alter {:commits logical-ops
              :retry-probability conflict
              :transaction-work-us (:critical-work-us config)
              :refs (:hot-refs config)
              :side-effects-inside true}
      :commute {:commits logical-ops
                :retry-probability (min 0.08 (+ 0.012 (* 0.0012 (:workers config))))
                :transaction-work-us (:critical-work-us config)
                :refs (:hot-refs config)
                :side-effects-inside false}
      :sharded {:commits logical-ops
                :retry-probability (min 0.55
                                        (/ conflict
                                           (Math/sqrt (double (:shards config)))))
                :transaction-work-us (:critical-work-us config)
                :refs (:shards config)
                :side-effects-inside false}
      :batch {:commits (ceil-long (/ (double logical-ops) (:batch-size config)))
              :retry-probability (min 0.20
                                        (/ conflict
                                           (* 2.0
                                              (Math/sqrt
                                               (double (:shards config))))))
              :transaction-work-us
              (long
               (Math/round
                (* (:critical-work-us config)
                   (+ 1.0 (/ (Math/log (double (:batch-size config)))
                             (Math/log 8.0))))))
              :refs (:shards config)
              :side-effects-inside false})))

(defn- lcg [state]
  (bit-and 0xffffffff
           (+ (* (long state) 1664525) 1013904223)))

(defn- timeline [config metrics]
  (let [ticks (* 4 (:observation-seconds config))
        average-attempts (/ (double (:attempts metrics)) (:observation-seconds config))
        average-commits (/ (double (:transactionsCommitted metrics))
                           (:observation-seconds config))
        average-retries (/ (double (:retries metrics)) (:observation-seconds config))]
    (mapv
     (fn [tick]
       (let [phase (/ (* 2.0 Math/PI tick) (max 1 ticks))
             pressure (+ 0.82 (* 0.18 (Math/sin phase)))
             retry-pressure (+ 0.72 (* 0.28 (Math/sin (+ phase 0.9))))]
         {:timeMs (* tick 250)
          :attemptsPerSecond (long (Math/round (* average-attempts pressure)))
          :commitsPerSecond (long (Math/round (* average-commits pressure)))
          :retriesPerSecond (long (Math/round (* average-retries retry-pressure)))
          :activeTransactions
          (long
           (Math/ceil
            (* (:workers config)
               (min 1.0
                    (/ (* average-attempts (:critical-work-us config))
                       1000000.0)))))
          :p99TransactionUs
          (long
           (Math/round
            (* (:p99TransactionUs metrics)
               (+ 0.92 (* 0.08 (Math/sin (+ phase 0.35)))))))
          :commitEfficiencyPercent (:commitEfficiencyPercent metrics)}))
     (range (inc ticks)))))

(defn- trace-events [config definition shape metrics]
  (let [mode (:mode definition)
        max-attempts (min 6 (max 1 (:maxAttemptsPerTransaction metrics)))
        side-effect-threshold (:side-effect-percent config)]
    (loop [transaction 0
           state (:seed config)
           events []]
      (if (= transaction 6)
        events
        (let [state' (lcg state)
              attempts
              (case mode
                :alter (max 2 (+ 2 (mod (+ transaction state') (max 1 (dec max-attempts)))))
                :commute (if (= transaction 4) 2 1)
                :sharded (if (zero? (mod (+ transaction state') 4)) 2 1)
                :batch (if (= transaction 5) 2 1))
              has-effect (< (mod (+ state' (* transaction 17)) 100)
                            side-effect-threshold)
              ref-index
              (case mode
                (:alter :commute) (mod transaction (:hot-refs config))
                (mod (+ transaction state') (:refs shape)))
              transaction-id (format "tx-%04d" (+ 730 transaction))
              transaction-events
              (mapv
               (fn [attempt]
                 (let [committed? (= attempt attempts)
                       effect? (and has-effect
                                    (or (:side-effects-inside shape) committed?))]
                   {:transactionId transaction-id
                    :worker (inc (mod transaction (:workers config)))
                    :logicalOperation (+ 12000 transaction)
                    :attempt attempt
                    :outcome (if committed? "commit" "retry")
                    :refKey (str "counter-" ref-index)
                    :durationUs (+ (:transaction-work-us shape)
                                   (* attempt 17)
                                   (mod state' 31))
                    :sideEffect effect?
                    :sideEffectPhase
                    (cond
                      (not effect?) "none"
                      (:side-effects-inside shape) "inside-dosync"
                      :else "after-commit")
                    :commitId (when committed? (format "c-%06d" (+ 880000 transaction)))}))
               (range 1 (inc attempts)))]
          (recur (inc transaction) state' (into events transaction-events)))))))

(defn- strategy [config definition]
  (let [logical-ops (* (:workers config) (:operations-per-worker config))
        shape (policy-shape config definition)
        commits (:commits shape)
        probability (:retry-probability shape)
        attempts (ceil-long (/ commits (- 1.0 probability)))
        retries (- attempts commits)
        committed-effects (long (Math/round
                                 (* logical-ops
                                    (/ (:side-effect-percent config) 100.0))))
        effect-executions (if (:side-effects-inside shape)
                            (long (Math/round
                                   (* attempts
                                      (/ (:side-effect-percent config) 100.0))))
                            committed-effects)
        transaction-work (:transaction-work-us shape)
        speculative-cpu-ms (/ (* attempts transaction-work) 1000.0)
        wasted-ms (/ (* retries transaction-work) 1000.0)
        retry-ratio (/ (double retries) (max 1 commits))
        p99-us
        (long
         (Math/round
          (* transaction-work
             (+ 1.0 (* 1.7 retry-ratio)))))
        parallel-wall-ms (/ (* attempts transaction-work)
                            (* (:workers config) 0.72 1000.0))
        serialization-floor-ms
        (if (= :alter (:mode definition))
          (/ (* commits transaction-work) 1000.0)
          0.0)
        wall-ms (max 1.0 parallel-wall-ms serialization-floor-ms)
        metrics
        {:logicalOperations logical-ops
         :transactionsCommitted commits
         :attempts attempts
         :retries retries
         :maxAttemptsPerTransaction
         (long (Math/ceil (* 2.2 (/ attempts (max 1.0 commits)))))
         :commitEfficiencyPercent (round-to (* 100.0 (/ commits (double attempts))) 1)
         :p99TransactionUs p99-us
         :speculativeCpuMs (round-to speculative-cpu-ms 1)
         :wastedWorkMs (round-to wasted-ms 1)
         :logicalThroughputPerSecond (round-to (/ logical-ops (/ wall-ms 1000.0)) 1)
         :refsTouched (:refs shape)
         :sideEffectExecutions effect-executions
         :committedSideEffects committed-effects
         :duplicateSideEffects (- effect-executions committed-effects)}]
    (merge
     (select-keys definition
                  [:policy :name :kicker :description :tradeoff :color :recommended :semantics])
     {:metrics metrics
      :timeline (timeline config metrics)
      :events (trace-events config definition shape metrics)})))

(defn simulate
  ([] (simulate defaults))
  ([raw-config]
   (let [config (normalize-config raw-config)]
     {:config (public-config config)
      :strategies (mapv #(strategy config %) policies)})))
