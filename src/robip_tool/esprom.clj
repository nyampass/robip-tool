(ns robip-tool.esprom
  (require [bytebuffer.buff :as bb]
           [serial.core :as serial]
           [clojure.java.io :as io]
           [serial.util :as util]
           [clojure.core.async :as async])
  (import java.util.Arrays))

(def list-ports util/list-ports)

(def ESP-FLASH-BEGIN 0x02)
(def ESP-FLASH-DATA 0x03)
(def ESP-FLASH-END 0x04)
(def ESP-FLASH-BLOCK 0x400)
(def ESP-SYNC 0x08)

(def ESP-CHECKSUM-MAGIC 0xef)

(def DEFAULT-BAUD 115200)

(defmacro with-timeout [millis & body]
  `(let [future# (future ~@body)]
     (try
       (.get future# ~millis java.util.concurrent.TimeUnit/MILLISECONDS)
        (catch java.util.concurrent.TimeoutException x#
          (do
            (future-cancel future#)
            nil)))))

(defn read-bytes [{:keys [read-timeout] :as esp} length]
  (with-timeout (or read-timeout 5000)
    #(println :read-bytes (or read-timeout 5000))
    (let [in-stream (-> esp :port :in-stream)
          bs (byte-array length)]
      (.read in-stream bs)
      (vec bs))))

(defn esprom [port]
  (let [port (serial/open port :baud-rate DEFAULT-BAUD)]
    (serial/listen! port identity true)
    {:port port
     :chan (async/chan)}))

(defn- raw-port [esprom]
  (-> esprom :port :raw-port))

(defn- sleep [second]
  (Thread/sleep (* second 1000.0)))

(defn byte->unsigned [b]
  (bit-and (int b) 0xff))

(defn bytes->unsigned [bs]
  (reduce #(+ (bit-shift-left %1 8)
              (byte->unsigned %2)) 0 bs))

(defn pack [format & data]
  (let [buff (bb/byte-buffer 1024)
        _ (.order buff java.nio.ByteOrder/LITTLE_ENDIAN)
        _ (apply bb/pack buff format data)
        length (.position buff)]
    (.rewind buff)
    (let [bytes (byte-array length)]
      (.get buff bytes 0 length)
      (vec bytes))))

(defn unpack [format bytes]
  (let [buff (bb/byte-buffer (count bytes))]
    (.order buff java.nio.ByteOrder/LITTLE_ENDIAN)
    (doseq [b (seq bytes)]
      (bb/put-byte buff b))
    (.flip buff)
    (bb/unpack buff format)))

(defn receive-response [esp]
  (let [[data] (read-bytes esp 1)]
    (if (or (not data)
            (not= (byte->unsigned data) 0xc0))
      (throw (Exception. "Invalid head of packet"))
      (let [_ (sleep 0.05)
            header (read-bytes esp 8)
            [resp op-ret len-ret value] (unpack "BBSI" header)]
        (if (not= resp 0x01)
          (throw (Exception. (str "Invllid response ", resp))))
        (let [body (read-bytes esp len-ret)
              r (read-bytes esp 1)]
          (if (not= (byte->unsigned (first r)) 0xc0)
            (throw (Exception. "Invalid end of package")))
          #(println "response" resp, op-ret, len-ret, value, body)
          [op-ret value body])))))

(defn print-data [label data]
  (doseq [x data]
    (println (apply str (map #(format "%x " %) (seq (serial/to-bytes x))))))
  (println))

(defn- write [{:keys [port] :as esp} packet]
  (let [buffer (apply concat
                      [[0xc0]
                       (map (fn [x]
                              (condp = (byte->unsigned x)
                                0xdb [0xdb 0xdd]
                                0xc0 [0xdb 0xdc]
                                [x]))
                                packet)
                       [0xc0]])]
    #(print-data :write buffer)
    (apply serial/write (cons port buffer))))

(defn command [esp & [op data chk]]
  #(println "command" op (count data) chk)
  (if op
    (let [packet (concat (pack "bbsi" 0, op, (count data), (or chk 0))
                         data)]
      (write esp packet)))
  (loop [retries 100]
    (let [[op-ret value body] (receive-response esp)]
      (if (or (not op) (= op-ret op))
        (do
          [value body])
        (if (<= retries 0)
          (throw (Exception. "Response doesn't match request"))
          (recur (dec retries)))))))

(defn command-result [& command-args]
  (bytes->unsigned (second (apply command command-args))))

(defn sync-command [esp]
  (command esp ESP-SYNC
           (concat [0x07, 0x07, 0x12, 0x20] (repeat 32 0x55)))
  (dotimes [i 7]
    (command esp)))

(defn initialize [esp]
  (let [raw-port (raw-port esp)]
    (.setDTR raw-port false)
    (.setRTS raw-port true)
    (sleep 0.05)
    (.setDTR raw-port true)
    (.setRTS raw-port false)
    (sleep 0.05)
    (.setDTR raw-port false)
    (.enableReceiveTimeout raw-port 300)))
(defn- reset [in-stream]
  (while (> (.available in-stream) 0)
    (.read in-stream)))

(defn connect* [esp]
  (initialize esp)
  (loop [n 3]
    (if-let [result (try
                      (reset (-> esp :port :in-stream))
                      (sync-command esp)
                      true
                      (catch Exception e
                        (sleep 0.05)
                        #(prn :connect n e)
                        (when (zero? n)
                          (throw (Exception. "Failed to connect")))))]
      result
      (recur (dec n)))))

(defn connect [esp]
  (println "Connecting...")
  (let [esp (assoc esp :read-timeout 300)]
    (reduce (fn [_ _]
              (if (connect* esp)
                (reduced true))) nil (range 4))))

(defn close [esp]
  (serial/close! (:port esp)))

(defn div-roundup [a b]
  (int (/ (+ a b -1) b)))

(defn- file->bytes [file]
  (let [bs (byte-array (.length file))
        in-stream (java.io.FileInputStream. file)]
    (.read in-stream bs)
    (.close in-stream)
    (vec bs)))

(defn- calc-flash-begin-command [size offset]
  (let [num-blocks (int (/ (+ size ESP-FLASH-BLOCK -1) ESP-FLASH-BLOCK))
        sectors-per-block 16
        sector-size 4096
        num-sectors (int (/ (+ size sector-size -1) sector-size))
        start-sector (int (/ offset sector-size))
        head-sectors (- sectors-per-block (mod start-sector sectors-per-block))
        head-sectors (if (< num-sectors head-sectors)
                       num-sectors head-sectors)
        eraise-size (if (< num-sectors (* 2 head-sectors))
                      (* (int (/ (+ num-sectors 1) 2)) sector-size)
                      (* (- num-sectors head-sectors) sector-size))]
    [eraise-size num-blocks]))

(defn- flash-begin-command [esp size offset]
  (let [[eraise-size num-blocks] (calc-flash-begin-command size offset)
        result (command-result (assoc esp :read-timeout 10000)
                               ESP-FLASH-BEGIN
                               (pack "iiii" eraise-size num-blocks ESP-FLASH-BLOCK offset))]
    (if (not= result 0x00)
      (throw (Exception. (str "Failed to enter Flash download mode. result = " result))))))

(defn- checksum [data]
  (reduce #(bit-xor %1 (bit-and 0xff (byte->unsigned %2)))
          ESP-CHECKSUM-MAGIC data))

(defn- flash-block-command [esp data index]
  (let [ret (command-result esp ESP-FLASH-DATA
                            (concat (pack "iiii" (count data) index 0 0) data)
                            (checksum data))]
    (if (not= ret 0)
      (throw (Exception. (str "Failed to write to target flash after seq " index))))))

(defn- flash-finish-command [esp reboot?]
  (let [packet (pack "i" (if reboot? 0 1))]
    (if (not= (command-result esp ESP-FLASH-END packet) 0)
      (throw (Exception. "Failed to leave Flash mode")))))

(defn write-flash [esp addr-files]
  (let [flash-mode 0 ;; qio
        flash-size-freq 0x00 ;; 4m + 40m
        flash-info (pack "bb" flash-mode flash-size-freq)]
    (doseq [[addr filename] addr-files]
      (let [file (io/file filename)
            blocks (div-roundup (.length file) ESP-FLASH-BLOCK)]
        (println "Erasing flash...")
        (flash-begin-command esp (* blocks ESP-FLASH-BLOCK) addr)
        (loop [image (file->bytes file)
               index 0]
          (let [block (take ESP-FLASH-BLOCK image)
                block (if (and (= addr 0)
                               (= index 0)
                               (= (byte->unsigned (first block)) 0xe9))
                        (vec (concat (take 2 block)
                                     flash-info
                                     (drop 4 block)))
                        block)
                block (concat block (repeat (- ESP-FLASH-BLOCK (count block)) 0xff))]
            (print "#")
            (flash-block-command esp block index)
            (let [image (vec (drop ESP-FLASH-BLOCK image))]
              (if-not (empty? image)
                (recur image (inc index))))))
        (println "\nLeaving...")
        (flash-begin-command esp 0 0)
        (flash-finish-command esp false)
        (println "Done!")))))

(defn ports []
  (map #(.getName %)
        (serial/port-identifiers)))

(defn usbserial-port []
  (some #(let [port (.getName %)]
           (and (.contains port "tty.usbserial-")
                port))
        (serial/port-identifiers)))
