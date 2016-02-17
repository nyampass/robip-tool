(ns robip-tool.config
  (:require [me.raynes.fs :as fs]
            [clojure.data.json :as json]))

(def config-path (fs/expand-home "~/.robip"))

(defn read-config []
  (if (fs/exists? config-path)
    (try
      (json/read-str (slurp config-path))
      (catch Throwable _
        {}))
    {}))

(defn write! [config]
  (with-open [w (clojure.java.io/writer config-path)]
    (json/write config w)))
