(ns stm-lab.cli
  (:require [stm-lab.json :as json]
            [stm-lab.model :as model])
  (:gen-class))

(defn -main [& arguments]
  (if (or (empty? arguments) (= ["--json"] (vec arguments)))
    (println (json/write-json (model/simulate)))
    (do
      (binding [*out* *err*]
        (println "usage: clojure -M:json"))
      (System/exit 2))))
