(ns robip-tool.core
  (:require [robip-tool.esprom :as esprom]
            [robip-tool.gui :as gui]
            [clojure.tools.cli :refer [parse-opts]]
            [clojure.string :refer [join]])
  (:gen-class))

(def cli-options
  [["-g" "--gui" "gui mode"]
   ["-p" "--port PORT" "Serial port device"]
   ["-d" "--default-port" "Use default serial port"]
   ["-h" "--help"]])

(defn exit [status & [msg]]
  (if msg
    (println msg))
  (System/exit status))

(defn error-msg [errors]
  (str "The following errors occurred while parsing your command:\n\n"
       (join \newline errors)))

(defn usage [options-summary]
  (->> ["Write a binary blob to HaLake board"
        ""
        "Usage: java -m robip-tool.core [options] \"Address and binary file to write where separeted by space\""
        ""
        "Options:"
        options-summary]
       (join \newline)))

(defn write! [port addr-files]
  (try
    (let [esp (esprom/esprom port)]
      (Thread/sleep 0.2)
      (esprom/connect esp)
      (esprom/write-flash esp addr-files)
      (exit 0))
    (catch Exception e
      (binding [*out* *err*]
        (println (.getMessage e))
        (.printStackTrace e))
      (exit 1))))

(defn -main [& args]
  (let [{:keys [options arguments errors summary] :as arg} (parse-opts args cli-options)]
    (prn arg args)
    (cond
      (:help options) (exit 0 (usage summary))
      errors (exit 1 (error-msg errors))
      (or (:gui options)
          (= (count args) 0)) (gui/run)
      (or (not= (mod (count arguments) 2) 0)
          (= (count arguments) 0)) (exit 1 (usage summary))
      :else (write! (or (:port options)
                        (when (:default-port options)
                          (esprom/usbserial-port)))
                    (map (fn [[addr filename]]
                           [(Integer. addr) filename]) (partition 2 arguments))))))
