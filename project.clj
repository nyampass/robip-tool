(defproject robip-up "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [org.clojure/core.async "0.2.374"]
                 [clj-serial "2.0.4-SNAPSHOT"]
                 [bytebuffer "0.2.0"]
                 [org.clojure/tools.cli "0.3.3"]]
  :main robip-up.core
  :repl-options {:init-ns robip-up.esprom})









