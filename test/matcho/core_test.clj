(ns matcho.core-test
  (:require [clojure.test :refer :all]
            [clojure.spec.alpha :as s]
            [matcho.core :refer [match match* matcho matcho* to-spec] :as m]))

#_(deftest fail
  (match
   {:status 500
    :body "some logn string with exception"}
   {:status 200
    :body {:entry [{:resourceType "Patient"
                    :id #(integer? %)}]}}))

(defn count-4? [xs]
  (= 2 (count xs)))

(defn patch-article [patch]
  {:status 200
   :body   (assoc {:article (merge patch
                                   {:text "nice article text"})}
                  :article-id 1
                  :meta {:tags ["nature" "bears"]})})

(def patch {:title       "Article about bears"
            :description "Very good article"})
(def resp  (patch-article patch))

(s/def ::str-coll (s/coll-of string?))

(deftest general-patch-test
  (let [body (:body resp)]
    (is (< (:status resp) 300))
    (is (= (:title patch) (get-in body [:article :title])))
    (is (= (:description patch) (get-in body [:article :description])))
    (is (s/valid? ::str-coll (get-in body [:meta :tags])))))

(deftest matcho-patch-test
  (def pattern
    {:status #(< % 300)
     :body   {:article patch
              :meta    {:tags ::str-coll}}})
  (m/assert pattern resp))

(s/def ::pos-coll (s/coll-of pos?))

(m/valid? [int? string?] [1 "test"])
;; => true

(deftest int-str-pair-test
  (m/assert [int? string?] [1 "test"]))

(m/explain-data [int? int? string?] [1 "test"])
;; => [{:expected "#function[clojure.core/int?]", :but "test", :path [1]} {:expected "#function[clojure.core/string?--5132]", :but nil, :path [2]}]

;; (deftest int-str-pair-fail-test
;;   (m/assert [int? int? string?] [1 "test"]))

;; [{:expected "#function[clojure.core/int?]", :but "test", :path [1]} {:expected "#function[clojure.core/string?--5132]", :but nil, :path [2]}] [1 "test"] [[#function[clojure.core/int?] #function[clojure.core/int?] #function[clojure.core/string?--5132]]]

(deftest dessert-test
  (m/dessert [int? int?] [1 "test"]))
;; is ok!

(deftest user-sensitive-data-test

  (testing "open-world exposes sensitive data"
    (m/assert
     {:body
      {:username string?
       :age      int?}}
     {:body
      {:username "bob"
       :age      42
       :password "my-password"}}))

  (testing "closed-world will catch accidentially exposed password"
    (m/dessert
     {:body
      ^:matcho/strict
      {:username string?
       :age      int?}}
     {:body
      {:username "bob"
       :age      42
       :password "my-password"}})))

(deftest vector-strict-match
  (def vector-123 [1 2 3])
  (m/assert [1 2] [1 2 3])
  (m/dessert ^:matcho/strict [1 2] [1 2 3])
  (m/assert ^:matcho/strict [1 2] [1 2])
  ;; ^:matcho/strict works only for current element of the pattern and
  ;; not inherited by nested nodes
  (m/assert ^:matcho/strict {:a [1 2]} {:a [1 2 3]}))

(m/valid? {:status 200} {:status 200 :body "ok"})
;; => true

(m/valid? {:status 200 :body string?} {:status 200})
;; => false

(def sample-resp
  {:status 200
   :body {:total 10
          :elems ["some" "string" "elements"]}})


(deftest readme-test
  (is (m/valid? pos? 1))
  (m/assert 1 1)
  (m/assert {:status #(< % 300)
             :body   #(not (empty? %))}
            {:status 200
             :body   "hello"})
  (m/assert ::pos-coll [1 2 3])
  (m/assert [{:expected #"conforms.*pos-coll"}]
            (m/explain-data ::pos-coll [1 -1 2])))

(deftest spec-like-interface-test
  (is (m/valid? [1 2] [1 2 3]))
  (is (m/explain-data #(= 3 (count %)) [1 2]))
  (is (nil? (m/explain-data [1 2] [1 2 3])))
  (m/assert [1 2] [1 2 3]))

(deftest matcho-test
  (testing "Matches"
    (match 1 1)
    (match {:a 1 :b 2} {:a 1})
    (match [1] [1])
    (match {:a 1 :b 2} {:a 1})
    (match {:a 1 :b 2} {:a odd?})
    (match {:a 2} {:a pos?})
    (match {:a [1 2 3]} {:a #(= 3 (count %))})

    (match [1 2 3] [1 2])

    (match {:a {:b [{:c 1 :x 5} {:c 2 :x 6}]}}
           {:a {:b [{:c 1} {:c 2}]}})

    (match {:a [1 2 3]} {:a ::pos-coll})

    (match '(1 2 3) [1 2 3])


    #_(match {:a {:b [{:c 1 :x 5} {:c 2 :x 6}]}}
             {:a {:b #{{:c odd?}}}})

    )

  (testing "Many predicates"
    (match [{:a 1} {:b 2}]
           #(= 2 (count %))
           [{:a odd?} {:b even?}])

    (match (match* [{:a 2} {:b 1}]
             #(= 2 (count %))
             [{:a odd?} {:b even?}])
           [{:path [0 :a]} {:path [1 :b]}]))

  (testing "spec integration"

    (match [1 2 3] (s/coll-of number?))
    (match (match* [1 2 3] (s/coll-of even?))
           [{:path []}]))

  (testing "Errors"

    (match (match* {:a 1} [1 2])
           #(not (empty? %)))

    (match (match*  {:a [1 2 3]} {:a count-4?})
           [{:path [:a] :expected #"count" :but [1 2 3]}])

    (match (match*  {:a [1 -2 3]} {:a ::pos-coll})
           [{:path     [:a]
             :expected "conforms to spec: :matcho.core-test/pos-coll"
             :but      map?}])

    (match (match* {:a 2} {:a 1})
           [{:path [:a], :expected 1, :but 2}])

    (match (match* {:a 2} {:a neg?})
           [{:path [:a], :expected #"neg" :but 2}])

    (match (match* {} {:x ""})
           [{:path [:x], :expected "", :but nil}] )

    (match (match* {:a [1]} {:a [2]})
           [{:path [:a 0], :expected 2, :but 1}])

    (match (match* {:a {:b "baaa"}}
                   {:a {:b #"^a"}} )
           [{:path [:a :b], :expected "match regexp: ^a", :but "baaa"}])

    (match (match* {:a [1 {:c 3}]} {:a [1 {:c 4}]})
           [{:path [:a 1 :c], :expected 4, :but 3}])))




(deftest test-matcho
  (matcho {:a 1} {:a odd?})

  (matcho [1 2 3] (s/coll-of int?))

  (matcho {:status 200
           :body  "hello"}
          {:status #(< % 300)
           :body  #(not (empty? %))})
  (matcho
   (matcho* {:status 200
             :body "hello"}
            {:status 404
             :body empty?})
   [{:path [:status], :val 200 :in [:status]}
    {:path [:body]  :val "hello"}])

  (matcho [1 2 3] [1 2])

  (matcho
   (matcho* [1 2 3] [1 3])
   [{:path [keyword?]  :val 2}])

  (matcho
   (matcho* [{:a 2}]
            [{:a odd?}])
   [{:path [keyword? :a]  :val 2}])

  (matcho* [{:a 2}]
           [{:a even?}])


  (matcho (matcho* {:a -2 :b {:c {:d 5}}}
                  {:a neg? :b {:c {:d even?}}})
         [{:path [:b :c :d]}]))


(def response {:status 200
               :body   "ok"})

(deftest with-spec-test
  (s/def ::status #(= 200 %))
  (s/def ::body #(not-empty %))
  (s/def ::response (s/keys :req-un [::status ::body]))
  (is (s/valid? ::response response)))

(deftest without-spec-test
  (m/assert {:status 200 :body not-empty} response))

(deftest strict-match-test

  (testing "vector strict mode"

    (m/dessert
     {:a ^:matcho/strict [1 2 4]}
     {:a [1 2 4 5]})

    (m/assert
     {:a ^:matcho/strict [1 2 4]}
     {:a [1 2 4]}))

  (testing "map strict mode"

    (m/dessert
     ^:matcho/strict
     {:a :b}
     {:a :b :c :d})

    (m/assert
     ^:matcho/strict
     {:a :b}
     {:a :b}))

  (testing "non-inheritance of strict mode"
    (m/assert
     ^:matcho/strict
     {:a [1 2]}
     {:a [1 2 3]})

    (m/dessert
     ^:matcho/strict
     {:a ^:matcho/strict [1 2]}
     {:a [1 2 3]})

    (m/assert
     ^:matcho/strict
     {:a ^:matcho/strict [1 2]}
     {:a [1 2]}))

  (testing "compare vector as map"
    (m/assert
    [{:id "1" :a 1 } {:id "2" :a 2}] 
    ^{:matcho/as-map-by :id}
    [{:id "2" :a 2} {:id "1" :a 1}])) 
  )
