(ns hospitalops.advisor-test
  "Unit tests of `hospitalops.advisor` proposal generation."
  (:require [clojure.test :refer [deftest is testing]]
            [hospitalops.advisor :as adv]
            [hospitalops.store :as store]))

(def db (store/seed-db))

(deftest propose-bed-assignment-shape
  (testing "bed-assignment proposal has correct shape and fields"
    (let [p (adv/infer db {:op :coordinate-bed-assignment
                           :bed-id "bed-101"
                           :patch {:status "available" :room "cleaned"}})]
      (is (= :coordinate-bed-assignment (:op p)))
      (is (= "bed-101" (:bed-id p)))
      (is (= :propose (:effect p)))
      (is (<= 0 (:confidence p) 1))
      (is (map? (:value p)))
      (is (contains? (:value p) :bed-id)))))

(deftest propose-visitor-access-shape
  (testing "visitor-access proposal has correct shape"
    (let [p (adv/infer db {:op :schedule-visitor-access
                           :bed-id "bed-102"
                           :patch {:visitor "family member" :date "2026-07-20"}})]
      (is (= :schedule-visitor-access (:op p)))
      (is (= "bed-102" (:bed-id p)))
      (is (= :propose (:effect p))))))

(deftest propose-supply-request-shape
  (testing "supply-request proposal has correct shape"
    (let [p (adv/infer db {:op :coordinate-supply-request
                           :bed-id "bed-101"
                           :patch {:item "linens" :quantity 2}})]
      (is (= :coordinate-supply-request (:op p)))
      (is (= :propose (:effect p)))
      (is (string? (:summary p))))))

(deftest propose-staff-shift-shape
  (testing "staff-shift proposal has correct shape"
    (let [p (adv/infer db {:op :schedule-staff-shift-proposal
                           :bed-id "bed-101"
                           :patch {:staff "clerk Johnson" :shift "morning"}})]
      (is (= :schedule-staff-shift-proposal (:op p)))
      (is (= :propose (:effect p)))
      (is (>= (:confidence p) 0.85)))))

(deftest propose-safety-concern-shape
  (testing "safety-concern proposal always escalates"
    (let [p (adv/infer db {:op :flag-safety-concern
                           :bed-id "bed-101"
                           :patch {:concern "elevator maintenance required"}})]
      (is (= :flag-safety-concern (:op p)))
      (is (= :propose (:effect p)))
      (is (string? (:summary p))))))

(deftest all-proposals-effect-is-always-propose
  (testing "every proposal type has :effect :propose, never direct actuation"
    (doseq [op [:coordinate-bed-assignment :schedule-visitor-access :coordinate-supply-request
                :schedule-staff-shift-proposal :flag-safety-concern]]
      (let [p (adv/infer db {:op op :bed-id "bed-101" :patch {}})]
        (is (= :propose (:effect p))
            (str "op " op " must have :effect :propose"))))))

(deftest rationale-string-is-present
  (testing "every proposal has a rationale explaining the advisor's thinking"
    (doseq [op [:coordinate-bed-assignment :schedule-visitor-access :coordinate-supply-request
                :schedule-staff-shift-proposal :flag-safety-concern]]
      (let [p (adv/infer db {:op op :bed-id "bed-101" :patch {}})]
        (is (string? (:rationale p))
            (str "op " op " must have a :rationale string"))))))
