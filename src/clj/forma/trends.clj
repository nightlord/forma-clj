;; TODO: -- talk about our assumptions on what data's coming in to
;; here.  This is where we'll be implementing OLS, using either infer
;; or incanter on the resulting matrices. Again, we should make sure
;; that we're operating on vectors, not seqs. (Turn a seq into a
;; vector be calling vec on it.)

(ns forma.trends
  (:use cascalog.api
        [forma.matrix.utils :only (sparse-expander)]
        [forma.matrix.walk :only (walk-matrix)]
        [forma.hadoop.predicate :only (sparse-windower)])
  (:require [forma.hadoop.io :as io]))

;; ### Time Series Generation
;;
;; The goal here is to write an aggregator that takes in a sequence of
;; tuples of the form <tperiod, pixel-vector>, and returns tuples of
;; the form <pixel-index, min-time, max-time, timeseries>.
;;
;; We assume that we're receiving chunks for every month within the
;; range. We measure pixel-index as the position inside the
;; chunk. For example:
;;
;;     (timeseries [[1 [7 4 2 1]]
;;                  [2 [1 2 3 4]]
;;                  [3 [3 4 3 2]]
;;                  [4 [4 8 7 5]]
;;                  [5 [1 5 3 2]]])
;;     ;=> ([0 1 5 [7 1 3 4 1]]
;;          [1 1 5 [4 2 4 8 5]]
;;          [2 1 5 [2 3 3 7 3]]
;;          [3 1 5 [1 4 2 5 2]])
;;
;; Let's define a function to give us chunks to work with. (This
;; should really be in a separate test file... that's the next
;; project.)

(defn test-chunks
  "Returns a sample input to the timeseries creation buffer, or a
  sequence of 2-tuples, structured as <period, int-array>. Each
  int-array is sized to `chunk-size`; the returned sequence contains
  tuples equal to the supplied value for `periods`."
  [periods chunk-size]
  (for [period (range periods)]
    (vector period
            (int-array chunk-size
                       (range chunk-size)))))

;; Here's my first try at timeseries:
;;
;;     (defn timeseries [tuples]
;;       (let [transpose #(apply map vector %)
;;             [periods chunks] (transpose tuples)]
;;         (map-indexed #(vector %1
;;                               (first periods)
;;                               (last periods)
;;                               (int-array %2))
;;                      (transpose chunks))))
;;
;; One issue with this is that we're mapping through the data three
;; times, which is inefficient. we do a first transpose to get
;; separate the data into periods and chunks -- we can see this, in
;; our destructuring vector, inside the `let` form. We map again to
;; transpose the chunks into timeseries, then again over these to
;; build our results tuples. I tried the following to see the speed
;; difference gained by removing one of these maps:
;;
;;     (defn timeseries [tuples]
;;       (let [[periods chunks] (apply map vector tuples)
;;             periodize (partial vector
;;                                (first periods)
;;                                (last periods))
;;             tupleize (comp periodize int-array vector)]
;;         (map-indexed cons (apply map tupleize chunks))))
;;
;; In this version, we gain about 15% speed. benchmarked with
;;
;;     (def chunks (test-chunks 130 24000))
;;     (time (dotimes [n 10] (count (timeseries chunks))))
;;
;; I get a bit under 24 seconds for the first version, a bit over 20
;; for the second version. For hadoop, we'll go with the more
;; efficient one! I think it ends up looking nicer, too.
;;
;; To be honest, I'm not really sure why this is faster. I had a hunch
;; that moving the call to `tupleize` inside of map (previously, we
;; were using `transpose` here, then `map-indexed`) would speed things
;; up, and it did.

;; TODO: Fix how we deal with missing values. Look at the develop
;; branch for the old way.
(defbufferop
  ^{:doc "Takes in a number of `<t-period, modis-chunk>` tuples,
  sorted by time period, and transposes these into (n = chunk-size)
  4-tuples, formatted as <pixel-idx, t-start, t-end, t-series>, where
  the `t-series` field is represented by an instance of
  `forma.schema.DoubleArray`.

  Entering chunks should be sorted by `t-period` in ascending
  order. `modis-chunk` tuple fields must be vectors or instances of
  `forma.schema.DoubleArray` or `forma.schema.IntArray`, as dictated
  by the Thriftable interface in `forma.hadoop.io`."}
  [timeseries [missing-val]] [tuples]
  (let [[periods [val]] (apply map vector tuples)
        missing-struct (io/to-struct (repeat (io/count-vals val) missing-val))
        [fp lp] ((juxt first peek) periods)
        chunks (sparse-expander missing-struct tuples :start fp)
        tupleize (comp (partial vector fp lp)
                       io/to-struct
                       vector)]
    (->> chunks
         (map io/get-vals)
         (apply map tupleize)
         (map-indexed cons))))

;; [This gist](https://gist.github.com/845813) is a solid example of
;; how to get cascalog to sort by time period and provide tuples to
;; our final `timeseries` function. Note again that incoming chunks
;; will be either float or int arrays.

;; ### Walk the Windows
;;
;; Now that we have a way to generate windows, we need some functions
;;to actually walk around.

(defmapcatop [walk-mat [window]]
  {:doc "In progress! Not sure yet how walk-mat's return values work out, here."}
  [m]
  (walk-matrix m window))


(def pixels
  (memory-source-tap
   (for [mod-h  (range 3)
         sample (range 20)
         line   (range 20)
         :let [val sample, mod-v 1]]
     [mod-h mod-v sample line val])))

(def pixel-tap
  (<- [?mh ?mv ?s ?l ?v]
      (pixels ?mh ?mv ?s ?l ?v)))

(defn get-windows
  "IN PROGRESS. Currently, this job fails due to incorrect comparator
  settings for the tuples."
  []
  (let [window-source (sparse-windower pixel-tap ["?s" "?l"] 5 "?v" 0)]
    (?<- (stdout)
         [?tile-h ?tile-v ?window-col ?window-row ?window]
         (window-source ?tile-h ?tile-v ?window-col ?window-row ?window))))

;; I think that I might be able to tag pixels as "edges", based on a
;; combination of pixel value and length of groups. If I can get all
;; of the edge pixels aggregated together... that would be a big win!
;;
;; Can we extend this to deal with the whole world, by calculating the
;; global pixel sample and line? One issue would be that edges
;; sometimes wouldn't be met be anything on the other side.
;;
;; ACTUALLY -- this is a problem now, and if we solve it, we solve the
;; whole mess.
