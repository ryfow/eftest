(ns eftest.runner
  "Functions to run tests written with clojure.test or compatible libraries."
  (:require [clojure.java.io :as io]
            [clojure.test :as test]
            [clojure.tools.namespace.find :as find]
            [eftest.report :as report]
            [eftest.report.progress :as progress]
            [eftest.output-capture :as capture]))

(defmethod test/report :begin-test-run [_])

(defn- synchronize [f]
  (let [lock (Object.)] (fn [x] (locking lock (f x)))))

(defn- synchronized? [v]
  (or (-> v meta :eftest/synchronized true?)
      (-> v meta :ns meta :eftest/synchronized true?)))

(defn- known-slow? [v]
  (or (-> v meta :eftest/slow true?)
      (-> v meta :ns meta :eftest/slow true?)))

(defn- failed-test? []
  (or (not (= :pass (get @report/*context* :state :pass)))
      (< 0 (:error @test/*report-counters* 0))
      (< 0 (:fail @test/*report-counters* 0))))

(defn- wrap-test-with-timer [test-fn test-warn-time]
  (fn [v]
    (let [start-time (System/nanoTime)
          result     (test-fn v)
          end-time   (System/nanoTime)
          duration   (/ (- end-time start-time) 1e6)]
      (when (and (not (known-slow? v))
                 (number? test-warn-time)
                 (<= test-warn-time duration))
        (binding [clojure.test/*testing-vars* (conj clojure.test/*testing-vars* v)]
          (test/report {:type     :long-test
                        :duration duration
                        :var      v})))
      result)))

(defn- run-in-parallel [fs]
  (let [executor (java.util.concurrent.Executors/newFixedThreadPool
                   (+ 2 (.availableProcessors (Runtime/getRuntime))))]
    (try
      (->> fs
           (map #(.submit executor ^Runnable %))
           doall
           (map #(.get %))
           dorun)
      (finally
        (.shutdownNow executor)))))

(defn- test-vars
  [ns vars {:as opts :keys [fail-fast? capture-output? test-warn-time]
            :or {capture-output? true}}]
  (let [once-fixtures (-> ns meta ::test/once-fixtures test/join-fixtures)
        each-fixtures (-> ns meta ::test/each-fixtures test/join-fixtures)
        report        (synchronize test/report)
        test-var      (-> (fn [v]
                            (when-not (and fail-fast? (failed-test?))
                              (each-fixtures
                               (if capture-output?
                                 #(binding [test/report report]
                                    (capture/with-test-buffer
                                      (test/test-var v)))
                                 #(binding [test/report report]
                                    (test/test-var v))))))
                          (wrap-test-with-timer test-warn-time))]
    (when-not (and fail-fast? (failed-test?))
      (once-fixtures
       (fn []
         (if (:multithread? opts true)
           (let [test (bound-fn* test-var)]
             (doseq [v (->> vars
                            (filter synchronized?))]
               (test-var v))
             (doall (->> vars (remove synchronized?) (map (fn [v] #(test v))) run-in-parallel)))
           (doseq [v vars] (test-var v))))))))

(defn- test-ns [ns vars {:as opts :keys [capture-output?] :or {capture-output? true}}]
  (let [ns (the-ns ns)]
    (binding [test/*report-counters* (ref test/*initial-report-counters*)]
      (test/do-report {:type :begin-test-ns, :ns ns})
      (if capture-output?
        (capture/with-capture (test-vars ns vars opts))
        (test-vars ns vars opts))
      (test/do-report {:type :end-test-ns, :ns ns})
      @test/*report-counters*)))

(defn- test-all [vars opts]
  (->> (group-by (comp :ns meta) vars)
       (map (fn [[ns vars]] (test-ns ns vars opts)))
       (apply merge-with +)))

(defn- require-namespaces-in-dir [dir]
  (map (fn [ns] (require ns) (find-ns ns)) (find/find-namespaces-in-dir dir)))

(defn- find-tests-in-namespace [ns]
  (->> ns ns-interns vals (filter (comp :test meta))))

(defn- find-tests-in-dir [dir]
  (mapcat find-tests-in-namespace (require-namespaces-in-dir dir)))

(defmulti find-tests
  "Find test vars specified by a source. The source may be a var, symbol
  namespace or directory path, or a collection of any of the previous types."
  {:arglists '([source])}
  type)

(defmethod find-tests clojure.lang.IPersistentCollection [coll]
  (mapcat find-tests coll))

(defmethod find-tests clojure.lang.Namespace [ns]
  (find-tests-in-namespace ns))

(defmethod find-tests clojure.lang.Symbol [sym]
  (if (namespace sym) (find-tests (find-var sym)) (find-tests-in-namespace sym)))

(defmethod find-tests clojure.lang.Var [var]
  (if (-> var meta :test) (list var)))

(defmethod find-tests java.io.File [dir]
  (find-tests-in-dir dir))

(defmethod find-tests java.lang.String [dir]
  (find-tests-in-dir (io/file dir)))

(defn run-tests
  "Run the supplied test vars. Accepts the following options:

    :multithread?    - true if the tests should run in multiple threads
                       (defaults to true)
    :report          - the test reporting function to use
                       (defaults to eftest.report.progress/report)
    :capture-output? - if true, catch test output and print it only if
                       the test fails (defaults to true)
    :fail-fast?      - stop after first failure or error
    :test-warn-time  - print a warning for any test that exceeds this time
                       (measured in milliseconds)"
  ([vars] (run-tests vars {}))
  ([vars opts]
   (let [start-time (System/nanoTime)]
     (if (empty? vars)
       (do
         (println "No tests found.")
         test/*initial-report-counters*)
       (binding [report/*context* (atom {})
                 test/report      (:report opts progress/report)]
         (test/do-report {:type :begin-test-run, :count (count vars)})
         (let [counters (test-all vars opts)
               duration (/ (- (System/nanoTime) start-time) 1e6)
               summary  (assoc counters :type :summary, :duration duration)]
           (test/do-report summary)
           summary))))))
