(defproject bucket-distributor-clj "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [org.clojure/tools.logging "0.3.1"]
                 [com.novemberain/langohr "3.3.0" :exclusions [cheshire clj-http]]
                 [org.slf4j/slf4j-api "1.7.7"]
                 [org.clojure/core.async "0.1.346.0-17112a-alpha"]]

  :global-vars {*warn-on-reflection* true}
  
  :profiles {:dev {:dependencies [[ch.qos.logback/logback-classic "1.1.2"]
                                  [midje "1.7.0"]]}})