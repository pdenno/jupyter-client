(defproject pdenno/jupyter-client "0.2.0-SNAPSHOT"
  :description "A clojure client to jupyter kernels"
  :url "https://github.com/pdenno/jupyter-client"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[cheshire                      "5.9.0"]
                 [clojure.java-time             "0.3.2"]
                 [com.cognitect/transit-clj     "0.8.313"]
                 [org.clojure/clojure           "1.10.1"]
                 [org.clojure/tools.logging     "0.5.0"]
                 [org.zeromq/cljzmq             "0.1.4" :exclusions [org.zeromq/jzmq]]
                 [org.zeromq/jeromq             "0.5.1"]
                 [pandect                       "0.6.1"]
                 #_[http-kit                      "2.3.0"]
                 [ring/ring-jetty-adapter       "1.7.1"]
                 [metosin/reitit                "0.3.9"]
                 [metosin/reitit-swagger        "0.3.9"]
                 [metosin/reitit-swagger-ui     "0.3.9"]]
                 

  :repl-options {:init-ns pdenno.jupyter-client.core}
  :profiles     {:dev           {:dependencies [[midje "1.9.9" :exclusions [org.clojure/clojure]]]
                                 :plugins [[lein-midje "3.2.1"]
                                           [com.roomkey/lein-v "7.0.0"]]}})
                 
