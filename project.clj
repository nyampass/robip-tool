(defproject robip-tool "0.9"
  :description "Robip tool for HaLake Board(inside ESP8266)"
  :url "http://robip.halake.com/"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [org.clojure/core.async "0.2.374"]
                 [clj-serial "2.0.4-SNAPSHOT"]
                 [bytebuffer "0.2.0"]
                 [org.clojure/tools.cli "0.3.3"]
                 [org.clojure/data.json "0.2.6"]
                 [me.raynes/fs "1.4.6"]
                 [clj-http "2.0.1"]]
  :aot :all
  :main robip-tool.core
  :repl-options {:init-ns robip-tool.core}
  :uberjar-name "robip-tool.jar"
  :launch4j-config-file "resources/launch4j-config.xml")









