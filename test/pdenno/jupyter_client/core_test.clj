(ns pdenno.jupyter-client.core-test
  "Test the Jupyter notebook core functions. You need a running notebook for this to be meaningful.
   And it DOES touch them (introduce values, etc.)."
  (:require
   [clojure.pprint	               :refer [cl-format]]
   [clojure.test                       :refer :all]
   [clojure.string                     :as string]
   [pdenno.jupyter-client.core         :as core]
   [pdenno.jupyter-client.util         :as util]))

;;; POD ToDo: Is there a way to run Jupyter Notebooks headless for testing like this?
(deftest at-least-one-heartbeat
  (is (some identity (map #(core/heartbeat? :once? true :config-file %)
                          (core/jupyter-runtime-files)))))

;;; If you don't believe it, go check the notebook!
(defn say-hello [cfile]
  (core/req-msg :config-file cfile
                :code (cl-format nil "hello_from_clojure = '''~A'''" (util/now)))
  (core/req-msg :config-file cfile
                :code "print(hello_from_clojure)"))

(deftest can-inject-hello-var
  (let [running (filter #(core/heartbeat? :once? true :config-file %)
                          (core/jupyter-runtime-files))]
    (is (every? say-hello running))))

