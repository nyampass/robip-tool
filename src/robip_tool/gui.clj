(ns robip-tool.gui
  (:require [robip-tool.esprom :as esprom]
            [robip-tool.api :as api]
            [robip-tool.config :as config]
            [clojure.java.shell :refer [sh]]
            [clojure.java.io :as io]
            [me.raynes.fs :as fs])
  (:import [javax.swing JFrame JPanel JTextField JButton JLabel JList JScrollPane
            GroupLayout GroupLayout$Alignment JTextArea JProgressBar JDialog
            JOptionPane BorderFactory
            SwingWorker SwingUtilities]
           [java.awt BorderLayout]
           [java.awt.event ActionListener]
           [java.beans PropertyChangeListener])
  (:gen-class))

(defn frame []
  (let [frame (JFrame. "Robip tool")]
    (doto frame
      (.setSize 400, 180)
      (.setLocationRelativeTo nil))
    frame))

(defn show [frame]
  (.setVisible frame true))

(defn panel [parts]
  (let [panel (JPanel.)]
    (doseq [part parts]
      (.add panel part))
    panel))

(defn label [text]
  (JLabel. text))

(defn layout-horizontal-group [layout groups]
  (let [sequential-group (.createSequentialGroup layout)]
    (doseq [group groups]
      (let [row-group (.createParallelGroup layout)]
        (doseq [component group]
          (.addComponent row-group component))
        (.addGroup sequential-group row-group)))
    (.setHorizontalGroup layout sequential-group)))

(defn layout-vertical-group [layout groups]
  (let [sequential-group (.createSequentialGroup layout)]
    (doseq [group groups]
      (let [row-group (.createParallelGroup layout GroupLayout$Alignment/BASELINE)]
        (doseq [component group]
          (.addComponent row-group component))
        (.addGroup sequential-group row-group)))
    (.setVerticalGroup layout sequential-group)))

(defn group-layout [frame hgroups vgroups]
  (let [container (.getContentPane frame)
        layout (GroupLayout. container)]
    (.setLayout container layout)
    (doto layout
      (.setAutoCreateGaps true)
      (.setAutoCreateContainerGaps true))
    (layout-horizontal-group layout hgroups)
    (layout-vertical-group layout vgroups)
    layout))

(defn message [frame text]
  (prn :messsage text)
  (JOptionPane/showMessageDialog frame text))

(defn swing-invoke [run]
  (javax.swing.SwingUtilities/invokeLater
   (try
     (reify Runnable
       (run [_] (run)))
     (catch Throwable e
       (prn :error e)))))


(defn direct-write! [port file]
  (let [esp (esprom/esprom port)]
    (Thread/sleep 0.2)
    (esprom/connect esp)
    (esprom/write-flash esp [[0 (.getAbsolutePath file)]])
    (esprom/close esp)))

(defn serve-resource [path]
  (.getResourceAsStream (clojure.lang.RT/baseLoader) path))

(defn esptool []
  (let [esptool (fs/file (fs/tmpdir) "esptool")]
    (io/copy (serve-resource "esptool")
             esptool)
    (fs/chmod "+x" (.getPath esptool))
    esptool))

(defn write-by-process! [port file]
  (let [params [(.getPath (esptool))
                "-cd" "nodemcu" "-cb" "115200" "-cp" (str "/dev/" port)
                "-ca" "0x00000" "-cf" (.getAbsolutePath file)]]
    (prn params)
    (= (:exit (apply sh params)))))

(defn write! [id port frame publish]
  (prn :write! :id id :port port)
  (try
    (publish 0 "サーバからバイナリファイルの取得中...")
    (let [file (api/fetch-binary id)]
      (publish 10 "書き込み中...")
      ; (direct-write! port file)
      (if (write-by-process! port file)
        (message frame "書き込みに成功しました!")))
    (catch Throwable e
      (prn :write! :error e)
      (message frame "書き込みに失敗しました"))))

(defn show-progress-dialog [run]
  (let [area (JTextArea. "")
        progress-bar (JProgressBar. 0 100)
        panel (JPanel. (BorderLayout. 5 5))
        dialog (JDialog.)]
    (.setEditable area false)
    (.setIndeterminate progress-bar true) ;; TODO show progress
    (.setValue progress-bar 0)
    (.add panel area BorderLayout/PAGE_START)
    (.setBorder panel (BorderFactory/createEmptyBorder 12 12 12 12))
    (.add panel progress-bar BorderLayout/CENTER)
    (.add (.getContentPane dialog) panel)
    (.setBackground area (.getBackground panel))
    (doto dialog
      (.setResizable true)
      (.pack)
      (.setSize 500 (.getHeight dialog))
      (.setLocationRelativeTo nil))
    (let [publish-fn (fn [progress-val text]
                       (swing-invoke
                        #(do
                           (prn :progress progress-val text)
                           (.setValue progress-bar progress-val)
                           (.setText area text))))
          worker (proxy [SwingWorker] []
                   (done []
                     (.dispose dialog))
                   (doInBackground []
                     (run publish-fn)))]
      (.execute worker))
    (.setVisible dialog true)))

(defn click-write-button [frame id port]
  (if-not (seq id)
    (message frame "Robip IDを入力してください")
    (if-not (seq port)
      (message frame "ポートを選択してください")
      (do
        (config/write! {:robip-id id})
        (show-progress-dialog (partial write! id port frame))))))

(defn button-with-action [text action]
  (let [button (JButton. text)]
    (.addActionListener button
                        (reify ActionListener
                          (actionPerformed [_ e]
                            (do (action)))))
    button))

(defn parts [frame]
  (let [id-field (JTextField. (or (get (config/read-config) "robip-id") "") 20)
        ports #(into-array String (esprom/ports))
        port-field (JList. (ports))
        rescan-button (button-with-action
                       "⟳"
                       #(.setListData port-field (ports)))
        write-button (button-with-action
                      "書き込む"
                      #(click-write-button frame
                                           (.getText id-field)
                                           (.getSelectedValue port-field)))]
    {:id-label (label "Robip ID:")
     :id-field id-field
     :port-label (label "ポート:")
     :port-field (JScrollPane. port-field)
     :rescan-button rescan-button
     :write-button write-button}))

(defn run []
  (swing-invoke
   #(let [frame (frame)
          {:keys [id-label id-field
                  port-label port-field
                rescan-button write-button]} (parts frame)]
      (group-layout frame
                    [[id-label port-label] [id-field port-field] [write-button rescan-button]]
                    [[id-label id-field write-button] [port-label port-field rescan-button]])
      (show frame))))
