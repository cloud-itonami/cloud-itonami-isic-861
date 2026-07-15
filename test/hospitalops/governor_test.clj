(ns hospitalops.governor-test
  "Pure unit tests of `hospitalops.governor/check` against hand-built
  proposals -- the fast, focused complement to `governor-contract-test`'s
  full-graph integration coverage."
  (:require [clojure.test :refer [deftest is testing]]
            [hospitalops.governor :as gov]
            [hospitalops.store :as store]))

(def bed-101 {:bed-id "bed-101" :location "Ward A, Room 101" :registered? true :verified? true})
(def bed-201 {:bed-id "bed-201" :location "Ward B, Room 201" :registered? true :verified? false})

(defn- clean-proposal [op bed-id]
  {:op op :bed-id bed-id :summary "s" :rationale "routine facility coordination"
   :cites [bed-id] :effect :propose :value {} :confidence 0.85})

(deftest bed-unregistered-is-hard
  (testing "no bed record at all -> HARD hold"
    (let [s (store/mem-store {"bed-101" bed-101})
          verdict (gov/check {} nil (clean-proposal :coordinate-bed-assignment "unknown-bed") s)]
      (is (true? (:hard? verdict)))
      (is (some #{:bed-unverified} (map :rule (:violations verdict)))))))

(deftest bed-unverified-is-hard
  (testing "bed registered but not yet verified -> HARD hold"
    (let [s (store/mem-store {"bed-201" bed-201})
          verdict (gov/check {} nil (clean-proposal :coordinate-bed-assignment "bed-201") s)]
      (is (true? (:hard? verdict)))
      (is (some #{:bed-unverified} (map :rule (:violations verdict)))))))

(deftest effect-not-propose-is-hard
  (testing "any :effect other than :propose is a HARD, un-overridable block"
    (let [s (store/mem-store {"bed-101" bed-101})
          verdict (gov/check {} nil (assoc (clean-proposal :schedule-visitor-access "bed-101") :effect :commit) s)]
      (is (true? (:hard? verdict)))
      (is (some #{:effect-not-propose} (map :rule (:violations verdict)))))))

(deftest op-outside-allowlist-is-hard
  (testing "an op outside the closed five-op allowlist is a scope violation"
    (let [s (store/mem-store {"bed-101" bed-101})
          verdict (gov/check {} nil (clean-proposal :authorize-treatment "bed-101") s)]
      (is (true? (:hard? verdict)))
      (is (some #{:op-not-allowed} (map :rule (:violations verdict)))))))

(deftest diagnosis-content-is-hard-and-permanent
  (testing "a proposal whose rationale touches diagnosis/clinical scope is HARD-blocked regardless of op/confidence"
    (let [s (store/mem-store {"bed-101" bed-101})
          poisoned (assoc (clean-proposal :coordinate-bed-assignment "bed-101")
                          :rationale "patient diagnosis cardiac arrhythmia, requires ICU monitoring"
                          :confidence 0.99)
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest treatment-content-is-hard
  (testing "a proposal touching treatment-plan/clinical-decision is HARD-blocked"
    (let [s (store/mem-store {"bed-101" bed-101})
          poisoned (assoc (clean-proposal :coordinate-bed-assignment "bed-101")
                          :rationale "initiate treatment plan and adjust therapy"
                          :confidence 0.90)
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest medication-content-is-hard
  (testing "a proposal touching medication/pharmaceutical content is HARD-blocked"
    (let [s (store/mem-store {"bed-101" bed-101})
          poisoned (assoc (clean-proposal :schedule-visitor-access "bed-101")
                          :summary "administer medication IV drip and adjust dosing"
                          :confidence 0.92)
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest patient-safety-content-is-hard
  (testing "a proposal touching patient-safety/clinical-emergency is HARD-blocked"
    (let [s (store/mem-store {"bed-101" bed-101})
          poisoned (assoc (clean-proposal :coordinate-supply-request "bed-101")
                          :value {:concern "patient safety emergency, critical condition"})
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest discharge-readiness-content-is-hard
  (testing "a proposal touching discharge-readiness/triage decisions is HARD-blocked"
    (let [s (store/mem-store {"bed-101" bed-101})
          poisoned (assoc (clean-proposal :schedule-staff-shift-proposal "bed-101")
                          :summary "assess patient discharge readiness and clinical triage priority")
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest end-of-life-content-is-hard
  (testing "a proposal touching end-of-life or DNR decisions is HARD-blocked"
    (let [s (store/mem-store {"bed-101" bed-101})
          poisoned (assoc (clean-proposal :coordinate-supply-request "bed-101")
                          :summary "recommend do not resuscitate order and end-of-life planning")
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest legitimate-facility-safety-concern-is-not-scope-excluded
  (testing "flagging facility/operational safety concerns (equipment malfunction, facility hazard) as a FACILITY SAFETY CONCERN never trips scope-exclusion -- this actor's core valid use case must not be self-blocked"
    (let [s (store/mem-store {"bed-101" bed-101})
          concern (assoc (clean-proposal :flag-safety-concern "bed-101")
                         :value {:concern "elevator maintenance required near ward, floor cleaning in progress"})
          verdict (gov/check {} nil concern s)]
      (is (empty? (filter #(= :scope-excluded (:rule %)) (:violations verdict)))
          "raw observation content (facility/operational safety) is exactly what this op exists to surface"))))
