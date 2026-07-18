(ns hospitalops.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 (com-junkawasaki/root ADR-2607189300,
  Wave5 rollout): this repo previously had NO demo page and no generator
  at all. This namespace drives the REAL actor stack
  (`hospitalops.operation` -> `hospitalops.governor` -> `hospitalops.store`)
  through a scenario built from the actor's OWN seeded demo data
  (`hospitalops.store/seed-db`, beds bed-101/bed-102/bed-201) and renders
  the result deterministically -- no invented numbers, no timestamps in
  the page content, byte-identical across reruns against the same seed
  (verified by diffing two consecutive runs before shipping).

  NOTE for future porters of this template: this repo's OWN
  `hospitalops.sim` demo driver (`clojure -M:dev:run`) was run directly
  before writing this file, and its ids ARE trustworthy (bed-101/bed-102
  are the store's own registered+verified beds, bed-201 is registered
  but NOT verified, bed-999 is deliberately absent from the seed) --
  unlike a prior repo in this cluster whose sim.cljc referenced ids from
  an unrelated actor. However two of sim.cljc's own comment claims do
  NOT match what actually happens when run, so this renderer does not
  blindly mirror sim.cljc's scenario or its claims:

  1. sim.cljc labels its `t2` (`:coordinate-bed-assignment` bed-101,
     patch `{:occupant \"admission-123\"}`) as \"phase 3, clean --
     auto-commits\", but running it shows a HARD hold on
     `:scope-excluded` instead -- the patch VALUE `\"admission-123\"`
     itself contains the substring \"admission\", which
     `hospitalops.governor/scope-excluded-terms` bans (admission is a
     clinical-authority/triage term). This is a real, reproducible
     governor result, just not the disposition the sim's own comment
     claims -- an artifact of that demo patch value, not of the op
     itself. This renderer picks patch values that don't collide with
     any banned substring for its own clean auto-commit rows.
  2. sim.cljc labels its `t4` (`:coordinate-supply-request`) as
     \"phase 3, clean -- auto-commits\", but running it ALSO HARD holds
     on `:scope-excluded`, for every possible patch, always -- this one
     IS a structural, permanent fact about the current advisor code, not
     a demo-value artifact: `hospitalops.advisor/propose-supply-request`
     hard-codes the rationale \"...投薬・医療用品・臨床機器なし。\"
     (\"...no medication, medical supplies, or clinical
     equipment.\") -- the NEGATION itself contains \"投薬\", which
     contains the banned substring \"薬\" (medication). The maximally-
     conservative scope filter (by design, per this actor's own
     governor docstring: \"any clinical/patient-care content
     whatsoever... is a HARD block\") cannot tell a negation from an
     assertion, so this op currently HARD-holds unconditionally. This
     renderer does NOT claim `:coordinate-supply-request` as a clean
     auto-commit path (it verifiably is not, today) and instead shows
     it truthfully as a fourth HARD-hold row -- a real, confirmed-by-
     running structural fact about this actor's own current code, not a
     fabricated scenario. Fixing `hospitalops.advisor`'s rationale text
     is out of scope for this build-time-renderer change.

  Ids, ops and violations below are otherwise exactly what
  `hospitalops.governor`/`hospitalops.phase` document and what running
  the actor confirms -- three distinct real HARD-hold *rules* appear
  (`:bed-unverified`, `:scope-excluded`, `:effect-not-propose`), across
  four HARD-hold rows.

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [clojure.string :as str]
            [hospitalops.store :as store]
            [hospitalops.advisor :as advisor]
            [hospitalops.operation :as op]
            [langgraph.graph :as g]))

;; ----------------------------- harness -----------------------------

(def ^:private operator
  {:actor-id "op-1" :actor-role :facilities-coordinator :phase 3})

(defn- exec! [actor tid request]
  (g/run* actor {:request request :context operator} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}}
          {:thread-id tid :resume? true}))

(defn run-demo!
  "Runs a fresh seeded store through a scenario mixing every disposition
  this actor can reach, using ONLY real bed ids from
  `hospitalops.store/demo-data`:

  bed-101 and bed-102 (both registered AND verified) each clear a clean
  administrative-coordination proposal that auto-commits at phase 3
  (`:coordinate-bed-assignment` for bed-101, `:schedule-visitor-access`
  and `:schedule-staff-shift-proposal` for bed-102 -- all three ops are
  phase-3 auto-eligible per `hospitalops.phase`). bed-101's
  `:flag-safety-concern` ALWAYS escalates (per
  `hospitalops.governor/always-escalate-ops`, regardless of confidence
  or phase) and is approved by a human facilities coordinator.

  Then four HARD-hold rows, none of which ever reach a human (a human
  approver cannot override a HARD violation), covering three distinct
  real governor rules:
    - bed-201 (registered but NOT `:verified?` in the seed data):
      `:coordinate-bed-assignment` HARD-holds on `:bed-unverified` --
      never re-derived from the proposal's own claim, only from the
      bed's own store record.
    - bed-101, advisor deliberately drifts into clinical-scope content
      (`:out-of-scope? true`, the same governor-contract test hook
      `hospitalops.advisor/infer` documents): HARD-holds on
      `:scope-excluded`.
    - bed-102, `:schedule-visitor-access` via a wrapped advisor that
      forces `:effect :commit` on an otherwise-clean proposal (the same
      technique `hospitalops.sim`'s own `t9` case uses to exercise this
      exact governor rule): HARD-holds on `:effect-not-propose` --
      independent proof the governor never trusts an advisor's own
      `:effect` claim.
    - bed-101, `:coordinate-supply-request` with an ordinary clean
      patch: HARD-holds on `:scope-excluded` again, but for a
      structurally different, PERMANENT reason (see namespace
      docstring) -- the advisor's own rationale text for this op always
      trips the conservative scope filter, confirmed by running the
      actor directly.

  Returns the resulting store -- every field `render` below reads is
  real governor/store output, not a hand-typed copy."
  []
  (let [db (store/seed-db)
        actor (op/build db)
        direct-actor (op/build db {:advisor (reify advisor/Advisor
                                              (-advise [_ _ req]
                                                (assoc (advisor/infer nil req) :effect :commit)))})]

    ;; bed-101: clean bed/room assignment coordination -- phase-3 auto-commit.
    (exec! actor "b101-bed" {:op :coordinate-bed-assignment :bed-id "bed-101"
                              :patch {:new-room "Ward A, Room 102" :effective-date "2026-07-20"}})

    ;; bed-102: clean visitor-access scheduling -- phase-3 auto-commit.
    (exec! actor "b102-visitor" {:op :schedule-visitor-access :bed-id "bed-102"
                                  :patch {:visitor-name "family member" :date "2026-07-20" :time "14:00"}})

    ;; bed-102: clean staff-shift proposal -- phase-3 auto-commit.
    (exec! actor "b102-shift" {:op :schedule-staff-shift-proposal :bed-id "bed-102"
                                :patch {:staff-member "R. Sato" :shift "PM" :date "2026-07-21"}})

    ;; bed-101: facility safety-concern flag -- ALWAYS escalates, approved
    ;; by a human facilities coordinator.
    (exec! actor "b101-safety" {:op :flag-safety-concern :bed-id "bed-101"
                                 :patch {:concern "hallway handrail loose near ward A entrance"
                                         :confidence 0.9}})
    (approve! actor "b101-safety")

    ;; bed-201: registered but NOT verified -> HARD hold on
    ;; :bed-unverified, never reaches a human.
    (exec! actor "b201-bed" {:op :coordinate-bed-assignment :bed-id "bed-201"
                              :patch {:new-room "Ward B, Room 202"}})

    ;; bed-101: advisor drifts into permanently-excluded clinical scope
    ;; -> HARD hold on :scope-excluded, never reaches a human.
    (exec! actor "b101-scope" {:op :coordinate-bed-assignment :bed-id "bed-101"
                                :out-of-scope? true
                                :patch {:new-room "Ward A, Room 104"}})

    ;; bed-102: advisor attempts a direct actuation (:effect :commit on an
    ;; otherwise-clean proposal) -> HARD hold on :effect-not-propose,
    ;; never reaches a human.
    (exec! direct-actor "b102-effect" {:op :schedule-visitor-access :bed-id "bed-102"
                                        :patch {:visitor-name "guest" :date "2026-07-22"}})

    ;; bed-101: coordinate-supply-request, ordinary clean patch -> HARD
    ;; holds on :scope-excluded too, but permanently/unconditionally (see
    ;; namespace docstring) -- a real, confirmed structural fact, not a
    ;; fabricated scenario.
    (exec! actor "b101-supply" {:op :coordinate-supply-request :bed-id "bed-101"
                                 :patch {:item "bed linens" :quantity 4}})

    db))

;; ----------------------------- rendering -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- last-fact-for [ledger bed-id]
  (last (filter #(= (:bed-id %) bed-id) ledger)))

(defn- status-cell [ledger bed-id]
  (let [f (last-fact-for ledger bed-id)]
    (cond
      (nil? f) "<span class=\"muted\">no activity</span>"
      (= :committed (:t f)) "<span class=\"ok\">committed</span>"
      (= :approval-granted (:t f)) "<span class=\"ok\">approved &amp; committed</span>"
      (= :governor-hold (:t f))
      (let [rule (-> f :violations first :rule)]
        (case rule
          :bed-unverified "<span class=\"critical\">HARD hold &middot; unverified bed</span>"
          :scope-excluded "<span class=\"critical\">HARD hold &middot; scope-excluded</span>"
          :effect-not-propose "<span class=\"critical\">HARD hold &middot; effect-not-propose</span>"
          (str "<span class=\"critical\">HARD hold &middot; " (esc (name (or rule :unknown))) "</span>")))
      (= :approval-requested (:t f)) "<span class=\"warn\">awaiting approval</span>"
      :else "<span class=\"muted\">in progress</span>")))

(defn- bed-row [ledger {:keys [bed-id location registered? verified?]}]
  (format "        <tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>"
          (esc bed-id) (esc location)
          (if (and registered? verified?) "<span class=\"ok\">registered &amp; verified</span>"
              "<span class=\"warn\">registered, unverified</span>")
          (status-cell ledger bed-id)))

(defn- ledger-row [{:keys [t op bed-id disposition basis]}]
  (format "        <tr><td>%s</td><td><code>%s</code></td><td>%s</td><td>%s</td></tr>"
          (esc (name t)) (esc (name (or op :n-a))) (esc bed-id)
          (esc (or (some->> basis (map #(if (keyword? %) (name %) %)) (str/join ", "))
                    (some-> disposition name) ""))))

(def ^:private action-gate-rows
  ;; Static description of this actor's own closed op contract
  ;; (README `Scope`/`hospitalops.governor`/`hospitalops.phase`) --
  ;; documentation of fixed behavior, not runtime telemetry, so it is
  ;; legitimately hand-described rather than derived from a live run.
  ["        <tr><td><code>:coordinate-bed-assignment</code></td><td><span class=\"ok\">phase-3 auto when clean</span></td></tr>"
   "        <tr><td><code>:schedule-visitor-access</code></td><td><span class=\"ok\">phase-3 auto when clean</span></td></tr>"
   "        <tr><td><code>:coordinate-supply-request</code></td><td><span class=\"err\">currently always HARD-holds &middot; advisor rationale text self-triggers the scope filter (confirmed by running the actor, see namespace docstring)</span></td></tr>"
   "        <tr><td><code>:schedule-staff-shift-proposal</code></td><td><span class=\"ok\">phase-3 auto when clean</span></td></tr>"
   "        <tr><td><code>:flag-safety-concern</code></td><td><span class=\"warn\">ALWAYS human approval &middot; never auto, any phase</span></td></tr>"])

(defn render
  "Renders the full operator-console.html document from a store `db`
  that has already run `run-demo!` (or any other real scenario)."
  [db]
  (let [ledger (vec (store/ledger db))
        beds (store/all-beds db)
        bed-rows (str/join "\n" (map (partial bed-row ledger) beds))
        ledger-rows (str/join "\n" (map ledger-row ledger))]
    (str
     "<html><head><meta charset=\"utf-8\"><title>cloud-itonami-isic-861 &middot; hospital activities coordination</title><style>\n"
     "table { width: 100%; border-collapse: collapse; font-size: 14px; }\n"
     ".ok { color: #137a3f; }\n"
     "body { font-family: system-ui,-apple-system,sans-serif; margin: 0; color: #1a1a1a; background: #fafafa; }\n"
     "header.bar { display: flex; align-items: center; gap: 12px; padding: 12px 20px; background: #fff; border-bottom: 1px solid #e5e5e5; }\n"
     "th, td { text-align: left; padding: 8px 10px; border-bottom: 1px solid #f0f0f0; }\n"
     "h2 { margin-top: 0; font-size: 15px; }\n"
     ".warn { color: #b25c00; background: #fff8e1; padding: 2px 6px; border-radius: 4px; }\n"
     "main { max-width: 980px; margin: 24px auto; padding: 0 20px; }\n"
     "header.bar h1 { font-size: 18px; margin: 0; font-weight: 600; }\n"
     ".muted { color: #888; font-size: 13px; }\n"
     ".critical { color: #fff; background: #b3261e; padding: 2px 6px; border-radius: 4px; font-weight: 600; }\n"
     ".card { background: #fff; border: 1px solid #e5e5e5; border-radius: 8px; padding: 16px; margin-bottom: 16px; }\n"
     ".err { color: #b3261e; background: #fbe9e7; padding: 2px 6px; border-radius: 4px; }\n"
     "th { font-weight: 600; color: #555; font-size: 12px; text-transform: uppercase; letter-spacing: 0.04em; }\n"
     "header.bar .badge { margin-left: auto; font-size: 12px; color: #666; }\n"
     "code { font-size: 12px; background: #f4f4f4; padding: 1px 4px; border-radius: 3px; }\n"
     "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Hospital activities administrative coordination (ISIC 861) — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample · governor-gated · never touches diagnosis/treatment/medication/patient-care</span>\n"
     "</header>\n"
     "<main>\n"
     "  <section class=\"card\">\n"
     "    <h2>Beds</h2>\n"
     "    <p class=\"muted\">Demo snapshot — build-time-generated from <code>hospitalops.store</code> via <code>hospitalops.render-html</code> (<code>clojure -M:dev:render-html</code>), regenerated nightly.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Bed</th><th>Location</th><th>Facility-resource status</th><th>Last coordination status</th></tr></thead>\n"
     "      <tbody>\n"
     bed-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Action gate (Hospital Governor)</h2>\n"
     "    <p class=\"muted\">HARD holds cannot be overridden by a human approver. Diagnosis, treatment, medication, clinical procedures, patient assessment, triage/discharge and clinical-authority territory are permanently out of scope — see governor scope-exclusion.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Gate</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" action-gate-rows) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Audit ledger (this run)</h2>\n"
     "    <p class=\"muted\">Append-only decision-fact log — every proposal, hold and commit this scenario produced.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Fact</th><th>Op</th><th>Bed</th><th>Basis</th></tr></thead>\n"
     "      <tbody>\n"
     ledger-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "</main>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        db (run-demo!)
        html (render db)]
    (spit out html)
    (println "wrote" out "(" (count (store/ledger db)) "ledger facts,"
             (count (store/coordination-log db)) "committed coordination records )")))
