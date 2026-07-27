(ns stm-lab.server
  (:require [clojure.string :as string]
            [stm-lab.json :as json]
            [stm-lab.model :as model])
  (:import [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]
           [java.net InetSocketAddress URLDecoder]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files Path Paths]
           [java.util.concurrent Executors])
  (:gen-class))

(def assets
  {"/" ["index.html" "text/html; charset=utf-8"]
   "/index.html" ["index.html" "text/html; charset=utf-8"]
   "/styles.css" ["styles.css" "text/css; charset=utf-8"]
   "/app.js" ["app.js" "text/javascript; charset=utf-8"]})

(defn- environment [name fallback]
  (let [value (System/getenv name)]
    (if (string/blank? value) fallback value)))

(defn- decode [value]
  (URLDecoder/decode value StandardCharsets/UTF_8))

(defn- parse-query [raw-query]
  (if (string/blank? raw-query)
    {}
    (reduce
     (fn [result pair]
       (let [separator (.indexOf ^String pair "=")]
         (if (pos? separator)
           (assoc result
                  (decode (subs pair 0 separator))
                  (decode (subs pair (inc separator))))
           result)))
     {}
     (string/split raw-query #"&"))))

(defn- respond!
  [^HttpExchange exchange status content-type body & [extra-headers]]
  (let [bytes (if (string? body)
                (.getBytes ^String body StandardCharsets/UTF_8)
                body)
        headers (.getResponseHeaders exchange)]
    (.set headers "Content-Type" content-type)
    (.set headers "Cache-Control" "no-store")
    (.set headers "X-Content-Type-Options" "nosniff")
    (.set headers "Referrer-Policy" "strict-origin-when-cross-origin")
    (.set headers
          "Content-Security-Policy"
          (str "default-src 'self'; script-src 'self'; "
               "style-src 'self' 'unsafe-inline'; img-src 'self' data:; "
               "connect-src 'self'; frame-ancestors 'none'"))
    (doseq [[header value] extra-headers]
      (.set headers header value))
    (.sendResponseHeaders exchange status (alength ^bytes bytes))
    (with-open [output (.getResponseBody exchange)]
      (.write output bytes))))

(defn- handle-request [^HttpExchange exchange ^Path public-directory]
  (try
    (if (not= "GET" (.getRequestMethod exchange))
      (respond! exchange
                405
                "application/json; charset=utf-8"
                (json/write-json {:error "method not allowed"})
                {"Allow" "GET"})
      (let [uri (.getRequestURI exchange)
            path (.getPath uri)]
        (cond
          (= path "/healthz")
          (respond! exchange 200 "text/plain; charset=utf-8" "ok")

          (= path "/api/simulate")
          (respond!
           exchange
           200
           "application/json; charset=utf-8"
           (json/write-json
            (model/simulate
             (model/config-from-query (parse-query (.getRawQuery uri))))))

          (contains? assets path)
          (let [[filename content-type] (get assets path)
                file (.resolve public-directory ^String filename)]
            (if (and (Files/isRegularFile file (make-array java.nio.file.LinkOption 0))
                     (<= (Files/size file) (* 2 1024 1024)))
              (respond! exchange 200 content-type (Files/readAllBytes file))
              (respond! exchange
                        500
                        "application/json; charset=utf-8"
                        (json/write-json {:error "asset unavailable"}))))

          :else
          (respond! exchange
                    404
                    "application/json; charset=utf-8"
                    (json/write-json {:error "not found"})))))
    (catch Exception error
      (binding [*out* *err*]
        (println "request failed:" (.getMessage error)))
      (try
        (respond! exchange
                  500
                  "application/json; charset=utf-8"
                  (json/write-json {:error "internal server error"}))
        (catch Exception _)))
    (finally
      (.close exchange))))

(defn- parse-port [value]
  (try
    (let [port (Long/parseLong value)]
      (when (<= 0 port 65535) (int port)))
    (catch Exception _ nil)))

(defn -main [& arguments]
  (when (seq arguments)
    (binding [*out* *err*]
      (println "usage: clojure -M:run"))
    (System/exit 2))
  (let [host (environment "HOST" "127.0.0.1")
        port (parse-port (environment "PORT" "8080"))]
    (when-not (#{"127.0.0.1" "localhost" "0.0.0.0"} host)
      (binding [*out* *err*]
        (println "HOST must be 127.0.0.1, localhost, or 0.0.0.0"))
      (System/exit 2))
    (when (nil? port)
      (binding [*out* *err*]
        (println "PORT must be between 0 and 65535"))
      (System/exit 2))
    (let [bind-host (if (= host "localhost") "127.0.0.1" host)
          public-directory
          (Paths/get (environment "PUBLIC_DIR" "public") (make-array String 0))
          server (HttpServer/create (InetSocketAddress. bind-host port) 0)
          executor (Executors/newFixedThreadPool 4)]
      (.setExecutor server executor)
      (.createContext
       server
       "/"
       (reify HttpHandler
         (handle [_ exchange]
           (handle-request exchange public-directory))))
      (.addShutdownHook
       (Runtime/getRuntime)
       (Thread.
        (fn []
          (.stop server 0)
          (.shutdownNow executor))))
      (.start server)
      (println
       (json/write-json
        {:event "server.started"
         :runtime "clojure-1.12"
         :url (str "http://" bind-host ":" (.getPort (.getAddress server)))}))
      (flush))))
