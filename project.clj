(defproject pdenno/jupyter-client "0.2.0-SNAPSHOT"
  :description "A clojure client to jupyter kernels"
  :url "https://github.com/pdenno/jupyter-client"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure           "1.10.0"]
                 [cheshire                      "5.8.1"]
                 [clojure.java-time             "0.3.2"]
                 [org.clojure/tools.logging     "0.4.1"]
                 [com.grammarly/omniconf        "0.3.2"]
                 [com.taoensso/timbre           "4.10.0"]
                 [org.zeromq/cljzmq             "0.1.4" :exclusions [org.zeromq/jzmq]]
                 [org.zeromq/jeromq             "0.5.0"]
                 [pandect                       "0.6.1"]]

  :repl-options {:init-ns pdenno.jupyter-client.core}
  :profiles     {:dev           {:dependencies [[midje "1.9.6" :exclusions [org.clojure/clojure]]]
                                 :plugins [[lein-midje "3.2.1"]
                                           [com.roomkey/lein-v "7.0.0"]]}})
                 
