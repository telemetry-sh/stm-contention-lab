(ns stm-lab.json
  (:require [clojure.string :as string]))

(defn- escape-string [value]
  (let [builder (StringBuilder.)]
    (doseq [character value]
      (.append
       builder
       (case character
         \" "\\\""
         \\ "\\\\"
         \backspace "\\b"
         \formfeed "\\f"
         \newline "\\n"
         \return "\\r"
         \tab "\\t"
         (if (< (int character) 32)
           (format "\\u%04x" (int character))
           (str character)))))
    (str builder)))

(defn- key-name [value]
  (cond
    (keyword? value) (name value)
    (string? value) value
    :else (str value)))

(declare write-json)

(defn- write-map [value]
  (str
   "{"
   (string/join
    ","
    (map
     (fn [[key item]]
       (str "\"" (escape-string (key-name key)) "\":" (write-json item)))
     (sort-by (comp key-name key) value)))
   "}"))

(defn- write-sequence [value]
  (str "[" (string/join "," (map write-json value)) "]"))

(defn write-json [value]
  (cond
    (nil? value) "null"
    (string? value) (str "\"" (escape-string value) "\"")
    (keyword? value) (write-json (name value))
    (boolean? value) (if value "true" "false")
    (number? value) (str value)
    (map? value) (write-map value)
    (sequential? value) (write-sequence value)
    :else (throw (ex-info "Unsupported JSON value" {:value value :type (type value)}))))
