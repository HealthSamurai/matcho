(ns matcho.core
  (:refer-clojure :exclude [assert])
  (:require
   [clojure.pprint]
   [clojure.spec.alpha :as s]
   [clojure.test :refer :all]))

(defn smart-explain-data [p x]
  (cond
    (s/spec? p)
    (when-not (s/valid? p x)
      {:expected (str "conforms to spec: " p) :but (s/explain-data p x)})

    (and (string? x) (instance? java.util.regex.Pattern p))
    (when-not (re-find p x)
      {:expected (str "match regexp: " p) :but x})

    (fn? p)
    (when-not (p x)
      {:expected (pr-str p) :but x})

    (and (keyword? p) (s/get-spec p))
    (let [sp (s/get-spec p)]
      (when-not (s/valid? p x)
        {:expected (str "conforms to spec: " p) :but (s/explain-data p x)}))

    :else (when-not (= p x)
            {:expected p :but x})))

(defn- normolize [coll n-fn]
  (->> coll (map (juxt n-fn identity)) (into {})))

(defn- match-recur [errors path x pattern]
  (let [n-fn (or (:matcho/as-map-by (meta x))
                   (:matcho/as-map-by (meta pattern)))
        as-map? (and (sequential? x) (sequential? pattern) n-fn)]
    (cond
      (or (and (map? x) (map? pattern)) as-map?)
      (let [pattern (cond-> pattern as-map? (normolize n-fn))
            x (cond-> x as-map? (normolize n-fn))
            strict? (:matcho/strict (meta pattern))
            errors  (if (and strict? (not (= (set (keys pattern))
                                             (set (keys x)))))
                      (conj errors {:expected "Same keys in pattern and x"
                                    :but      (str "Got " (vec (keys pattern))
                                                   " in pattern and " (vec (keys x)) " in x")
                                    :path     path})
                      errors)]
        (reduce (fn [errors [k v]]
                  (let [path (conj path k)
                        ev   (get x k)]
                    (match-recur errors path ev v)))
                errors pattern))

      (and (sequential? pattern)
           (sequential? x))
      (let [strict? (:matcho/strict (meta pattern))
            errors  (if (and strict? (not (= (count pattern) (count x))))
                      (conj errors {:expected "Same number of elements in sequences"
                                    :but      (str "Got " (count pattern)
                                                   " in pattern and " (count x) " in x")
                                    :path     path})
                      errors)]
        (reduce (fn [errors [k v]]
                  (let [path (conj path k)
                        ev   (nth (vec x) k nil)]
                    (match-recur errors path ev v)))
                errors
                (map (fn [x i] [i x]) pattern (range))))

      :else (let [err (smart-explain-data pattern x)]
              (if err
                (conj errors (assoc err :path path))
                errors)))))

(defn- match-recur-strict [errors path x pattern]
  (cond
    (and (map? x)
         (map? pattern))
    (reduce (fn [errors [k v]]
              (let [path (conj path k)
                    ev   (get x k)]
                (match-recur-strict errors path ev v)))
            errors pattern)

    (and (sequential? pattern)
         (sequential? x))
    (reduce (fn [errors [k v]]
              (let [path (conj path k)
                    ev   (nth (vec x) k nil)]
                (match-recur-strict errors path ev v)))
            (if (= (count pattern) (count x))
              errors
              (conj errors {:expected "Same number of elements in sequences"
                            :but      (str "Got " (count pattern)
                                           " in pattern and " (count x) " in x")
                            :path     path}))
            (map (fn [x i] [i x]) pattern (range)))

    :else (let [err (smart-explain-data pattern x)]
            (if err
              (conj errors (assoc err :path path))
              errors))))



(defn match*
  "Match against each pattern"
  [x & patterns]
  (reduce (fn [acc pattern] (match-recur acc [] x pattern)) [] patterns))

(defn build-expected-actual [errors]
  (reduce
   (fn [acc v]
     (-> acc
         (update :actual assoc-in (:path v) (:but v))
         (update :expected assoc-in (:path v) (:expected v))))
   {:expected {} :actual {}}
   errors))

(defn build-diff [errors]
  (reduce
   (fn [acc {:keys [but expected path]}]
     (assoc-in acc path {'- expected '+ but}))
   {} errors))

(defmacro match
  "Match against each pattern and assert with is"
  [x & pattern]
  `(let [x#        ~x
         patterns# [~@pattern]
         errors#   (apply match* x# patterns#)]
     (if-not (empty? errors#)
       (let [builded# (build-expected-actual errors#)]
         (do-report {:message  (str "Matcho pattern mismatch:\n\n"(with-out-str (clojure.pprint/pprint (build-diff errors#))))
                     :type     :fail
                     :actual   x#
                     :expected (:expected builded#)}))
       (is true))))

(defmacro not-match
  "Match against each pattern and dessert with is"
  [x & pattern]
  `(let [x#        ~x
         patterns# [~@pattern]
         errors#   (apply match* x# patterns#)]
     (if (empty? errors#)
       (is false "expected some errors")
       (is true))))


(defmacro to-spec
  [pattern]
  (cond
    (symbol? pattern) pattern
    (instance? clojure.lang.Cons pattern) pattern
    (list? pattern) pattern
    ;; (instance? clojure.spec.alpha.Specize pattern)
    ;; (throw (Exception. "ups")) ;;pattern
    (fn? pattern)
    pattern
    (map? pattern)
    (let [nns (name (gensym "n"))
          nks (mapv #(keyword nns (name %)) (keys pattern))
          ks  (map (fn [[k v]] (list 's/def (keyword nns (name k)) (list 'to-spec v))) pattern)]
      `(do ~@ks (s/keys :req-un ~nks)))

    (sequential? pattern)
    (let [nns (name (gensym "n"))
          cats (loop [i 0
                      [p & ps] pattern
                      cats []]
                 (if p
                   (recur (inc i)
                          ps
                          (conj cats (keyword nns (str "i" i)) (list 'to-spec p)))
                   cats))]
      `(s/cat ~@cats :rest (s/* (constantly true))))

    :else `(conj #{} ~pattern)))

(defmacro matcho*
  "Match against one pattern"
  [x pattern]
  `(let [sp# (to-spec ~pattern)]
     (::s/problems (s/explain-data sp# ~x))))

(defmacro matcho
  "Match against one pattern and assert with is"
  [x pattern]
  `(let [sp# (to-spec ~pattern)
         res# (s/valid? sp#  ~x)
         es# (s/explain-str sp# ~x)]
     (is res# (str (pr-str ~x) "\n" es#))))

(defn valid? [pattern x]
  (empty? (match* x pattern)))

(defn explain-data
  "Returns list of errors or nil"
  [pattern x]
  (let [errors (match* x pattern)]
    (when (not-empty errors) errors)))

(defmacro assert [pattern x]
  `(match ~x ~pattern))

(defmacro dessert [pattern x]
  `(not-match ~x ~pattern))

(comment

  (def person
    {:age      42
     :name     "Health Samurai"
     :email    "samurai@hs.io"
     :favorite {:numbers [1 3 17]}})

  (def person-pattern
    {:age      #(even? %)
     :name     #"Health.*"
     :favorite {:numbers [1 3 17]}})

  (valid? person-pattern person)
  (valid? [1 3] [1 2])

  (smart-explain-data pos? -1)

  (matcho* -1 pos?)

  (matcho* [1 -2 3] [neg? neg? neg?])
  (to-spec [neg? neg? neg?])

  (matcho* [1 2] (s/coll-of keyword?))

  (to-spec (s/coll-of keyword?))

  )
