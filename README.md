# matcho [![CircleCI](https://circleci.com/gh/HealthSamurai/matcho.svg?style=shield)](https://circleci.com/gh/HealthSamurai/matcho) [![Join gitter](https://badges.gitter.im/Join%20Chat.svg)](https://gitter.im/healthsamurai/matcho)

Simpliest pattern matching you've ever seen.

[![Clojars Project](http://clojars.org/healthsamurai/matcho/latest-version.svg)](http://clojars.org/healthsamurai/matcho)

## Idea

The main goal is to provide the simpliest DSL to describe pattern of expected
value.

### Problem

Not so easy to write tests with multiple asserts. Code grows fast, a lot of
repetitions, hard to read.

```clj
(ns hello-world.core-test
  (:require [clojure.test :refer :all]
            [clojure.spec.alpha :as s]
            [matcho.core :refer :all :as m]))

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
```

### Solution

More declarative and readable approach:
* Describe part of the value with nodes, which have to be checked
* Place a predicate, spec or regexp instead of node value or not
* Assert with `(m/assert pattern value)`
* Profit

```clj
(deftest matcho-patch-test
  (def pattern
    {:status #(< % 300)
     :body   {:article patch
              :meta    {:tags ::str-coll}}})
  (m/assert pattern resp))
```

Full example can be found [here](./test/matcho/core_test.clj).

## Usage

### Deps
Add following project dependency to deps.edn:

```clj
{healthsamurai/matcho {:mvn/version "RELEASE"}}
```

Understand and pick out needed parts:

### Require

```clj
(ns hello-world.core
  (:require [clojure.test :refer :all]
            [clojure.spec.alpha :as s]
            [matcho.core :refer :all :as m]))
```

### Spec-like interface

There are few main vars in core ns: `valid?`, `explain-data`, `assert` and
`dessert`. First one is a function, which takes pattern and value returns true
if value conforms the pattern and false in other case. The second one is a
function, which returns a vector of errors or nil. The third one is a macro, it
works the same way as `valid?`, but additionally asserts with `is` and provide a
vector of errors using `explain-data`. `dessert` is opposit to `assert`, test
fails only if value is `valid?`.

```clj
(m/valid? [int? string?] [1 "test"])
;; => true

(deftest int-str-pair-test
  (m/assert [int? string?] [1 "test"]))

(m/explain-data [int? int? string?] [1 "test"])
;; => [{:expected "#function[clojure.core/int?]", :but "test", :path [1]} {:expected "#function[clojure.core/string?--5132]", :but nil, :path [2]}]

(deftest int-str-pair-fail-test
  (m/assert [int? int? string?] [1 "test"]))

;; [{:expected "#function[clojure.core/int?]", :but "test", :path [1]} {:expected "#function[clojure.core/string?--5132]", :but nil, :path [2]}] [1 "test"] [[#function[clojure.core/int?] #function[clojure.core/int?] #function[clojure.core/string?--5132]]]

(deftest dessert-test
  (m/dessert [int? int?] [1 "test"]))
;; is ok!

```

### How it works?

`matcho` takes a data structure with "special" leaf nodes (a pattern) and
smartly compares them with corresponding nodes in the original value. If nodes
looks too different an explanation will be added to list of errors and the
process will continue.

The pattern can be much smaller (has less keys, elements in vector and so on)
than the original value and it is a common case. The [open-world
assumption](https://en.wikipedia.org/wiki/Open-world_assumption) implemented in
`matcho` allows developer to check only "interesting" parts, but if it needed
some parts of patterns can be marked as closed-world.

```clj
(m/valid? {:status 200} {:status 200 :body "ok"})
;; => true

(m/valid? {:status 200 :body string?} {:status 200})
;; => false
```

### Strict match

In some cases it necessary to check that there are no additional elements in a
vector or no additional keys in a map. To make sure that no sensetive data
exposed for example. This can be done using metadata inside pattern. Needed node
should be prepended with `^{:matcho/strict true}` or `^:matcho/strict`
(alternative shorter form).

```clj
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
```

In examples above the presence of unnecessary will be catched. Strictness not
inherited by child nodes of data structure.

### Special leaf nodes

There are several options for pattern leaf values. It can be:

* Any simple value
* Regular expression
* Clojure spec (keyword or spec directly)
* Predicate (boolean-valued function)

```clj
(s/def ::pos-coll (s/coll-of pos?))

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
```

More advanced examples can be found [here](./test/matcho/core_test.clj).

## Why not just use a clojure.spec?

Because `matcho`, that's why.

```clj
(def response {:status 200
               :body   "ok"})

(deftest with-spec-test
  (s/def ::status #(= 200 %))
  (s/def ::body #(not-empty %))
  (s/def ::response (s/keys :req-un [::status ::body]))
  (is (s/valid? ::response response)))

(deftest without-spec-test
  (m/assert {:status 200 :body not-empty} response))
```

## License

Copyright © 2016 HealthSamurai

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
