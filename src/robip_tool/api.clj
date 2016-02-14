(ns robip-tool.api
  (:require [clj-http.client :as client]
            [clojure.java.io :refer [copy]])
  (:import [java.io File]))

(defn fetch-binary [id]
  (let [url (str "http://robip.halake.com/api/" id "/latest")
        tmp (File/createTempFile "robip-" ".bin")]
    (copy (:body (client/get url {:as :stream})) tmp)
    tmp))
