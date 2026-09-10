(ns hospitalops.store-contract-test
  "Contract tests for `hospitalops.store/Store` protocol."
  (:require [clojure.test :refer [deftest is testing]]
            [hospitalops.store :as store]))

(deftest mem-store-bed-lookup
  (testing "MemStore can store and retrieve beds by ID (string keys)"
    (let [beds {"b1" {:bed-id "b1" :location "Ward A" :registered? true :verified? true}}
          s (store/mem-store beds)]
      (is (some? (store/bed s "b1")))
      (is (nil? (store/bed s "b99"))))))

(deftest mem-store-all-beds
  (testing "MemStore returns all beds in sorted order"
    (let [beds {"b2" {:bed-id "b2" :location "Ward B"}
                "b1" {:bed-id "b1" :location "Ward A"}
                "b3" {:bed-id "b3" :location "Ward C"}}
          s (store/mem-store beds)
          all-b (store/all-beds s)]
      (is (= 3 (count all-b)))
      (is (= "b1" (:bed-id (first all-b))))
      (is (= "b3" (:bed-id (last all-b)))))))

(deftest mem-store-ledger-append
  (testing "MemStore append-ledger! adds facts to immutable log"
    (let [s (store/mem-store {})
          fact1 {:t :test :data "fact1"}
          fact2 {:t :test :data "fact2"}]
      (is (= 0 (count (store/ledger s))))
      (store/append-ledger! s fact1)
      (is (= 1 (count (store/ledger s))))
      (store/append-ledger! s fact2)
      (is (= 2 (count (store/ledger s)))))))

(deftest mem-store-coordination-log
  (testing "MemStore commit-record! appends to coordination-log"
    (let [s (store/mem-store {})
          record {:op :coordinate-bed-assignment :bed-id "b1" :value {:status "available"}}]
      (is (= 0 (count (store/coordination-log s))))
      (store/commit-record! s record)
      (is (= 1 (count (store/coordination-log s))))
      (is (= record (first (store/coordination-log s)))))))

(deftest mem-store-with-beds
  (testing "MemStore with-beds replaces the bed directory"
    (let [s (store/mem-store {})
          new-beds {"b1" {:bed-id "b1" :location "Ward A"}}]
      (is (= 0 (count (store/all-beds s))))
      (store/with-beds s new-beds)
      (is (= 1 (count (store/all-beds s)))))))

(deftest seed-db-has-demo-data
  (testing "seed-db creates a populated MemStore with demo beds"
    (let [s (store/seed-db)]
      (is (> (count (store/all-beds s)) 0))
      (is (some? (store/bed s "bed-101")))
      (is (some? (store/bed s "bed-102")))
      (is (some? (store/bed s "bed-201"))))))

(deftest demo-data-string-key-consistency
  (testing "demo-data uses string keys, not keywords, for bed-id"
    (let [demo (store/demo-data)
          beds (:beds demo)]
      (doseq [[k v] beds]
        (is (string? k) "keys must be strings")
        (is (string? (:bed-id v)) "bed-id must be string")
        (is (= k (:bed-id v)) "key must match bed-id")))))

(deftest store-is-append-only
  (testing "appended facts are immutable and never removed"
    (let [s (store/seed-db)
          fact1 {:t :event1 :data "a"}
          fact2 {:t :event2 :data "b"}]
      (store/append-ledger! s fact1)
      (let [ledger-after-1 (store/ledger s)]
        (store/append-ledger! s fact2)
        (let [ledger-after-2 (store/ledger s)]
          (is (= (count ledger-after-1) (dec (count ledger-after-2))))
          (is (every? #(some (fn [x] (= x %)) ledger-after-2) ledger-after-1)
              "all prior facts must still be present"))))))
