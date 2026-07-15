(ns hospitalops.sim
  "Demo driver -- `clojure -M:run`. Walks a clean bed-assignment
  coordination request through intake -> advise -> govern -> decide -> approval ->
  commit at phase 1 (assisted-logistics, always approval), then re-runs
  the same op at phase 3 (supervised-auto, clean + high confidence ->
  auto-commit), then a visitor-access-scheduling request, supply-request
  coordination, and staff-shift-proposal (all auto-commit clean at
  phase 3), then a facility-safety-concern flag (ALWAYS escalates, at any phase
  -- approve, then commit), then HARD-hold scenarios: an unregistered
  bed, a bed registered but not yet verified, a proposal whose own
  `:effect` is not `:propose`, and a proposal that has drifted into the
  permanently-excluded clinical/diagnosis/treatment scope."
  (:require [langgraph.graph :as g]
            [hospitalops.advisor :as advisor]
            [hospitalops.store :as store]
            [hospitalops.operation :as op]))

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "facilities-coordinator-1"}} {:thread-id tid :resume? true}))

(defn -main [& _]
  (let [db (store/seed-db)
        coordinator-phase-1 {:actor-id "coord-1" :actor-role :facilities-coordinator :phase 1}
        coordinator-phase-3 {:actor-id "coord-1" :actor-role :facilities-coordinator :phase 3}
        actor (op/build db)]

    (println "== coordinate-bed-assignment bed-101 (phase 1, escalates -- human approves) ==")
    (let [r (exec-op actor "t1" {:op :coordinate-bed-assignment :bed-id "bed-101"
                                  :patch {:status "available" :room-turnover "cleaned"}} coordinator-phase-1)]
      (println r)
      (println "-- human facilities coordinator approves --")
      (println (approve! actor "t1")))

    (println "\n== coordinate-bed-assignment bed-101 (phase 3, clean -- auto-commits) ==")
    (println (exec-op actor "t2" {:op :coordinate-bed-assignment :bed-id "bed-101"
                                  :patch {:status "occupied" :occupant "admission-123"}} coordinator-phase-3))

    (println "\n== schedule-visitor-access bed-101 (phase 3, clean -- auto-commits) ==")
    (println (exec-op actor "t3" {:op :schedule-visitor-access :bed-id "bed-101"
                                  :patch {:visitor-name "family member" :date "2026-07-20" :time "14:00"}} coordinator-phase-3))

    (println "\n== coordinate-supply-request bed-101 (phase 3, clean -- auto-commits) ==")
    (println (exec-op actor "t4" {:op :coordinate-supply-request :bed-id "bed-101"
                                  :patch {:item "bed linens" :quantity 2 :urgency "routine"}} coordinator-phase-3))

    (println "\n== schedule-staff-shift-proposal bed-101 (phase 3, clean -- auto-commits) ==")
    (println (exec-op actor "t5" {:op :schedule-staff-shift-proposal :bed-id "bed-101"
                                  :patch {:staff-member "admin clerk Johnson" :shift "morning" :date "2026-07-21"}} coordinator-phase-3))

    (println "\n== flag-safety-concern bed-101 (ALWAYS escalates, even at phase 3) ==")
    (let [r (exec-op actor "t6" {:op :flag-safety-concern :bed-id "bed-101"
                                 :patch {:concern "elevator maintenance required near ward A" :confidence 0.92}} coordinator-phase-3)]
      (println r)
      (println "-- human facilities coordinator reviews & approves --")
      (println (approve! actor "t6")))

    (println "\n== coordinate-bed-assignment bed-999 (unregistered bed -> HARD hold) ==")
    (println (exec-op actor "t7" {:op :coordinate-bed-assignment :bed-id "bed-999"
                                  :patch {:status "available"}} coordinator-phase-3))

    (println "\n== coordinate-bed-assignment bed-201 (registered but unverified -> HARD hold) ==")
    (println (exec-op actor "t8" {:op :coordinate-bed-assignment :bed-id "bed-201"
                                  :patch {:status "available"}} coordinator-phase-3))

    (println "\n== schedule-visitor-access bed-101, advisor attempts direct actuation (:effect :commit) -> HARD hold ==")
    (let [actor-direct (op/build db {:advisor (reify advisor/Advisor
                                                (-advise [_ _ req]
                                                  (assoc (advisor/infer nil req) :effect :commit)))})]
      (println (exec-op actor-direct "t9" {:op :schedule-visitor-access :bed-id "bed-101"
                                           :patch {:visitor-name "guest" :date "2026-07-22"}} coordinator-phase-3)))

    (println "\n== coordinate-bed-assignment bed-101, advisor drifts into clinical scope -> HARD hold, permanent ==")
    (println (exec-op actor "t10" {:op :coordinate-bed-assignment :bed-id "bed-101"
                                   :out-of-scope? true
                                   :patch {}} coordinator-phase-3))

    (println "\n== audit ledger ==")
    (doseq [f (store/ledger db)] (println f))

    (println "\n== committed coordination log ==")
    (doseq [r (store/coordination-log db)] (println r))))
