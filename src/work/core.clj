(ns work.core
  (:refer-clojure :exclude [peek sync])
  (:require [clj-json [core :as json]]
            [clojure.contrib.logging :as log])
  (:use work.queue
        clj-serializer.core
        [clojure.contrib.def :only [defvar]]
        [plumbing.core :only [with-accumulator]]
        [plumbing.error :only [with-ex logger]])
  (:import (java.util.concurrent
            Executors ExecutorService TimeUnit
            LinkedBlockingQueue)
           clojure.lang.RT))

(defn available-processors []
  (.availableProcessors (Runtime/getRuntime)))

(defn schedule-work
  "schedules work. cron for clojure fns. Schedule a single fn with a pool to run every n seconds,
  where n is specified by the rate arg, or supply a vector of fn-rate tuples to schedule a bunch of fns at once."
  ([f rate]
     (let [pool (Executors/newSingleThreadScheduledExecutor)]
       (.scheduleAtFixedRate
        pool (partial with-ex (logger) f)
	(long 0)
	(long rate)
	TimeUnit/SECONDS)
       pool))
  ([jobs]
     (let [pool (Executors/newSingleThreadScheduledExecutor)] 
       (doall (for [[f rate] jobs]
                (schedule-work pool f rate)))
       pool)))

(defn shutdown
  "Initiates an orderly shutdown in which previously submitted tasks are executed, but no new tasks will be accepted. Invocation has no additional effect if already shut down."
  [executor]
  (do (.shutdown executor) executor))

(defn shutdown-now [executor]
  "Attempts to stop all actively executing tasks, halts the processing of waiting tasks, and returns a list of the tasks that were awaiting execution.

  There are no guarantees beyond best-effort attempts to stop processing actively executing tasks. For example, typical implementations will cancel via Thread.interrupt(), so if any tasks mask or fail  to respond to interrupts, they may never terminate."
  (do (.shutdownNow executor) executor))

(defn two-phase-shutdown
  "Shuts down an ExecutorService in two phases.
  Call shutdown to reject incoming tasks.
  Calling shutdownNow, if necessary, to cancel any lingering tasks.
  From: http://download-llnw.oracle.com/javase/6/docs/api/java/util/concurrent/ExecutorService.html"
  [^ExecutorService pool]
  (do (.shutdown pool)  ;; Disable new tasks from being submitted
      (with-ex ;; Wait a while for existing tasks to terminate
	(fn [e _ _]
	  (when (instance? InterruptedException e)
	    ;;(Re-)Cancel if current thread also interrupted
	    (.shutdownNow pool)
	    ;; Preserve interrupt status
	    (.interrupt (Thread/currentThread))))	
        #(if (not (.awaitTermination pool 60 TimeUnit/SECONDS))
	  (.shutdownNow pool) ; // Cancel currently executing tasks
          ;;wait a while for tasks to respond to being cancelled
          (if (not (.awaitTermination pool 60 TimeUnit/SECONDS))
            (println "Pool did not terminate" *err*))))))

(defn- work*
  [fns num-threads-or-pool]
  (let [pool (if (integer? num-threads-or-pool)	       
	       (Executors/newFixedThreadPool num-threads-or-pool)
	       num-threads-or-pool)]
    [pool (.invokeAll pool ^java.util.Collection fns)]))

(defn seq-work
  "takes a seq of fns executes them in parallel on n threads, blocking until all work is done."
  [fns num-threads-or-pool]
  (let [[pool futures] (work* fns num-threads-or-pool)
	res (map
	     (fn [^java.util.concurrent.Future f] (.get f))
	     futures)]
    (when (integer? num-threads-or-pool) (shutdown pool))
    res))

(defn map-work
  [f num-threads-or-pool xs]
  (seq-work
   (doall (map (fn [x] #(f x)) xs))
   num-threads-or-pool))

(defn filter-work
  [f num-threads xs]
  (filter identity
	  (map-work
	   (fn [x] (if (f x) x nil))	       
	   num-threads
	   xs)))

;;Steps toward pulling out composiiton strategy.  need to do same for input so calcs ca be push through or pill through.
(defn async [f task out] (f task out))
(defn sync [f task out] (out (f task)))

(defn sleeper [& [sleep-time]]
  (fn [] (Thread/sleep (or sleep-time 5000))))

(defn work [scheduler & [wait]]
 (fn []
  (let [yield (or wait (sleeper))
	{:keys [f in out exec]} (scheduler)
	exec (or exec sync)]
    (if-let [task (in)]
      (exec f task out)
      (yield)))))

;;TODO; unable to shutdown pool. seems recursive fns are not responding to interrupt. http://download.oracle.com/javase/tutorial/essential/concurrency/interrupt.html
;;TODO: use another thread to check futures and make sure workers don't fail, don't hang, and call for work within their time limit?
(defn queue-work
  [work & [threads]]
  (let [threads (or threads (available-processors))
        pool (Executors/newFixedThreadPool threads)
        ^java.lang.Runnable f
	  (fn [] (when-not (.isShutdown pool)
		   (work)
		   (recur)))]
    (dotimes [_ threads] (.submit pool f))     
    pool))

(defn do-work
  ([f num-threads-or-pool tasks]
     (when-not (empty? tasks)
     (let [tasks (seq tasks)
	   latch (java.util.concurrent.CountDownLatch.
		  (int (count tasks)))
	   pool (if (integer? num-threads-or-pool)
		  (Executors/newFixedThreadPool num-threads-or-pool)
		  num-threads-or-pool)]
       (doseq [t tasks :let [work (fn []
				    (try
				      (f t)
				      (finally
				        (.countDown latch))))]]	       
	 (.submit pool ^java.lang.Runnable work))
       (.await latch)
       (when (integer? pool)
	 (shutdown-now pool)))))
  ([f tasks] (do-work f (available-processors) tasks)))

(defn reduce-work
  ([f init num-threads-or-pool xs]
     (let [[f-accum res] (with-accumulator f init)]
       (do-work f-accum num-threads-or-pool xs)
       @res))
  ([f num-threads-or-pool xs] (reduce-work f nil num-threads-or-pool xs))
  ([f xs] (reduce-work f (available-processors) xs)))


