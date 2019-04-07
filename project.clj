(defproject jupyter-client "0.1.0-SNAPSHOT"
  :description "A clojure client to jupyter kernels"
  :url "https://github.com/pdenno/jupyter-client"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure  "1.10.0"]
                 [clojupyter           "0.2.2-SNAPSHOT"]]
  :repl-options {:init-ns jupyter-client.core}
  :profiles     {:dev           {:dependencies [[midje "1.9.6" :exclusions [org.clojure/clojure]]]
                                 :plugins [[lein-midje "3.2.1"]
                                           [com.roomkey/lein-v "7.0.0"]]}})
                 
