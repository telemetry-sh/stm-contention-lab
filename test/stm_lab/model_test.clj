(ns stm-lab.model-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [stm-lab.json :as json]
            [stm-lab.model :as model])
  (:gen-class))

(defn strategy [response policy]
  (first (filter #(= policy (:policy %)) (:strategies response))))

(deftest default-strategies
  (let [response (model/simulate)
        alter (strategy response "alter_hot_ref")
        commute (strategy response "commute_counter")
        sharded (strategy response "sharded_refs")
        batch (strategy response "local_batch_flush")]
    (is (= 4 (count (:strategies response))))
    (is (> (get-in alter [:metrics :retries])
           (get-in sharded [:metrics :retries])
           (get-in batch [:metrics :retries])))
    (is (< (get-in commute [:metrics :retries])
           (get-in sharded [:metrics :retries])))
    (is (> (get-in alter [:metrics :duplicateSideEffects]) 0))
    (is (zero? (get-in commute [:metrics :duplicateSideEffects])))
    (is (zero? (get-in sharded [:metrics :duplicateSideEffects])))
    (is (zero? (get-in batch [:metrics :duplicateSideEffects])))
    (is (< (get-in alter [:metrics :commitEfficiencyPercent]) 50.0))
    (is (> (get-in batch [:metrics :commitEfficiencyPercent]) 90.0))
    (is (= 49 (count (:timeline alter))))
    (is (some #(= "retry" (:outcome %)) (:events alter)))
    (is (some #(and (:sideEffect %)
                    (= "inside-dosync" (:sideEffectPhase %))
                    (= "retry" (:outcome %)))
              (:events alter)))))

(deftest configuration-boundaries
  (let [config (model/config-from-query
                {"workers" "999"
                 "operations_per_worker" "1"
                 "hot_refs" "0"
                 "critical_work_us" "99999"
                 "side_effect_percent" "-4"
                 "shards" "nope"
                 "batch_size" "1"
                 "observation_seconds" "999"
                 "seed" "0"})]
    (is (= 64 (:workers config)))
    (is (= 20 (:operations-per-worker config)))
    (is (= 1 (:hot-refs config)))
    (is (= 2000 (:critical-work-us config)))
    (is (= 0 (:side-effect-percent config)))
    (is (= 16 (:shards config)))
    (is (= 2 (:batch-size config)))
    (is (= 30 (:observation-seconds config)))
    (is (= 1 (:seed config)))))

(deftest deterministic-json
  (is (= (json/write-json (model/simulate))
         (json/write-json (model/simulate)))))

(defn -main [& _]
  (let [result (run-tests 'stm-lab.model-test)
        failures (+ (:fail result) (:error result))]
    (when (pos? failures)
      (System/exit 1))))
