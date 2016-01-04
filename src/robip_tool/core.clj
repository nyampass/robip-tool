(ns robip-tool.core
  (:require [robip-tool.esprom :as esprom]
           [clojure.tools.cli :refer [parse-opts]]
           [clojure.string :refer [join]])
  (:gen-class))

(def cli-options
  [["-p" "--port PORT" "Serial port device"
    :default "/dev/ttyUSB0"]
   ["-h" "--help"]])

(defn exit [status msg]
  (println msg)
  (System/exit status))

(defn error-msg [errors]
  (str "The following errors occurred while parsing your command:\n\n"
       (join \newline errors)))

(defn usage [options-summary]
  (->> ["Write a binary blob to flash esp8266"
        ""
        "Usage: java -m robip-tool.core [options] \"Address and binary file to write where separeted by space\""
        ""
        "Options:"
        options-summary]
       (join \newline)))

(defn -main [& args]
  (let [{:keys [options arguments errors summary]} (parse-opts args cli-options)]
    (cond
      (:help options) (exit 0 (usage summary))
      (or (not= (mod (count arguments) 2) 0)
          (= (count arguments) 0)) (exit 1 (usage summary))
      errors (exit 1 (error-msg errors)))
    (let [esp (esprom/esprom (:port options))]
      (Thread/sleep 0.2)
      (esprom/connect esp)
      (esprom/write-flash esp
                          (map (fn [[addr filename]]
                                 [(Integer. addr) filename]) (partition 2 arguments))))))
