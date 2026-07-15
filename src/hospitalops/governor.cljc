(ns hospitalops.governor
  "HospitalGovernor -- the independent compliance layer for
  ISIC-861 hospital activities coordination. The advisor has no notion
  of whether a bed/resource is actually registered and verified, whether
  its own proposed `:effect` secretly claims a direct actuation instead
  of a mere proposal, or whether it has silently drifted into a
  permanently out-of-scope decision area, so this MUST be a separate
  system able to *reject* a proposal and fall back to HOLD.

  This actor's scope is deliberately narrow -- ADMINISTRATIVE/FACILITY
  COORDINATION ONLY (bed/room assignment logistics, visitor access
  scheduling, non-clinical consumable supply coordination, staff shift
  proposals, facility safety-concern flagging). It NEVER performs or
  authorizes:
    - diagnosis, assessment, or clinical decision-making
    - treatment planning or care-plan changes
    - medication administration, dosing, prescribing, or any pharma handling
    - medical procedures (wound care, IV/catheter management, etc.)
    - patient safety decisions, triage, admission prioritization, discharge readiness
    - vital signs monitoring or patient assessment
    - physical restraint, seclusion, or mobility restrictions
    - end-of-life or DNR decisions
    - any clinical-authority overrides

  Hospitals are inherently clinical settings, so scope exclusions are
  MAXIMALLY CONSERVATIVE -- any clinical/patient-care content
  whatsoever (even phrased as a \"safety concern\") is a HARD block.

  Three HARD checks, ALL permanent, un-overridable by any human
  approval:

    1. Facility-resource unverified    -- the target bed/room/resource record
                                   must exist AND be independently
                                   confirmed :registered?/:verified?
                                   in the store before ANY proposal
                                   for it may commit or even escalate.
                                   Never trusts a proposal's own claim
                                   about the resource -- re-derived from
                                   the resource's own store record, the
                                   same 'ground truth, not self-report'
                                   discipline every sibling actor's
                                   governor uses.
    2. Effect not :propose      -- every proposal's :effect MUST
                                   be :propose. Any other effect value
                                   is, by construction, a claim to
                                   directly actuate/commit outside
                                   governance -- HARD block, not merely
                                   low-confidence.
    3. Scope exclusion          -- ANY proposal (regardless of op)
                                   whose op, rationale, summary,
                                   citations or draft value touches
                                   diagnosis/treatment/medication/
                                   clinical-decision/patient-care/
                                   clinical-procedure/triage/discharge/
                                   vital-signs/end-of-life/clinical-
                                   authority territory is a HARD,
                                   PERMANENT block. Hospitals are
                                   clinically central, so MAXIMALLY-
                                   CONSERVATIVE scope exclusions apply.

  One ESCALATE (SOFT) gate: LLM confidence below the floor, OR the op
  is :flag-safety-concern -- ALWAYS escalates to a human, regardless
  of confidence, regardless of how clean the proposal otherwise is.
  `hospitalops.phase` independently agrees: :flag-safety-concern is
  never a member of any phase's :auto set either -- two layers, not
  one."
  (:require [clojure.string :as str]
            [hospitalops.store :as store]))

(def confidence-floor 0.6)

(def allowed-ops
  "The closed proposal-op allowlist for hospital ADMINISTRATIVE/FACILITY
  COORDINATION ONLY. An op outside this set is a scope violation by construction."
  #{:coordinate-bed-assignment :schedule-visitor-access :coordinate-supply-request
    :schedule-staff-shift-proposal :flag-safety-concern})

(def always-escalate-ops
  "Ops that ALWAYS require human sign-off, clean or not."
  #{:flag-safety-concern})

(def scope-excluded-terms
  "Case-insensitive substrings that mark a proposal as touching a
  permanently out-of-scope decision area. Hospitals are clinically
  central, so scope exclusions are MAXIMALLY CONSERVATIVE. Covers
  diagnosis, treatment, medication, clinical procedures, patient
  assessment, triage/discharge decisions, end-of-life, or clinical-
  authority enforcement. Scanned across the proposal's op/summary/
  rationale/cites/value, never trusting the advisor's own intent."
  ;; Diagnosis & clinical assessment
  ["diagnosis" "diagnos" "診断" "assessment" "clinical assessment"
   "clinical-assessment" "臨床評価" "evaluate patient"
   ;; Treatment & care planning
   "treatment" "treatment plan" "treatment-plan" "care plan" "care-plan"
   "ケアプラン" "therapeutic" "therapy plan" "medical decision"
   ;; Medication & pharmaceutical
  "medicatio" "薬" "dosing" "処方" "prescription" "rx" "pharma" "drug"
   "iv fluid" "infusion" "inject" "intravenous" "subcutaneous"
   "antibiotic" "drug administration" "medication administration"
   ;; Clinical procedures
   "procedure" "手術" "wound care" "wound-care" "創傷" "dressing"
   "catheter" "カテーテル" "foley" "central line" "feeding tube"
   "peg tube" "tracheostomy" "ostomy" "suture"
   ;; Patient assessment & monitoring
   "vital sign" "vital-sign" "vitals" "blood pressure" "heart rate"
   "respiration" "temperature" "blood glucose" "o2 saturation"
   "patient assessment" "nursing assessment" "nursing-assessment"
   ;; Triage, admission, discharge
   "triage" "admission" "discharge" "discharge-ready" "readiness"
   "준비도" "患者分類" "入退院"
   ;; Physical restrictions
   "physical restraint" "physical-restraint" "restraint" "拘束" "身体拘束"
   "seclusion" "隔離" "mobility restriction" "mobility-restriction"
   ;; End-of-life & clinical authority
   "end of life" "end-of-life" "dnr" "do not resuscitate" "終末期"
   "advance directive" "code status" "palliative" "hospice"
   "clinical decision" "clinical-decision" "clinical authority"
   "license suspension" "license-suspension" "compliance enforcement"
   "investigat" "complaint" "patient safety" "patient-safety"
   "医療安全"])

;; ----------------------------- checks -----------------------------

(defn- bed-unverified-violations
  "The target bed/resource must exist AND be independently `:registered?`/
  `:verified?` in the store -- never trust the proposal's own
  `:bed-id` claim without a store lookup."
  [{:keys [bed-id]} st]
  (let [b (store/bed st bed-id)]
    (when-not (and b (:registered? b) (:verified? b))
      [{:rule :bed-unverified
        :detail (str bed-id " は未登録または未検証の施設リソース -- いかなる提案も進められない")}])))

(defn- effect-not-propose-violations
  "`:effect` must ALWAYS be `:propose` -- any other value is a claim
  to directly actuate/commit outside governance."
  [proposal]
  (when (not= :propose (:effect proposal))
    [{:rule :effect-not-propose
      :detail (str ":effect は :propose のみ許可されるが " (pr-str (:effect proposal)) " が提案された")}]))

(defn- text-blob
  "Flatten every advisor-authored field on a proposal into one
  lower-cased blob the scope-exclusion scan checks."
  [proposal]
  (str/lower-case (pr-str (select-keys proposal [:op :summary :rationale :cites :value]))))

(defn- scope-exclusion-violations
  "HARD, PERMANENT block: a proposal outside the closed op allowlist,
  or one whose content touches diagnosis/treatment/medication/clinical/
  patient-care territory, regardless of confidence or how clean
  every other check is. Evaluated UNCONDITIONALLY on every proposal."
  [proposal]
  (let [op (:op proposal)
        blob (text-blob proposal)]
    (cond
      (not (contains? allowed-ops op))
      [{:rule :op-not-allowed
        :detail (str (pr-str op) " は許可された操作(closed allowlist)に含まれない")}]

      (some #(str/includes? blob %) scope-excluded-terms)
      [{:rule :scope-excluded
        :detail "診断/治療/投薬/臨床判断/患者ケア/患者安全/終末期判断/臨床当局の領域に触れる提案は永久に禁止"}])))

(defn check
  "Censors a HospitalAdvisor proposal against the governor rules.
  Returns {:ok? bool :violations [..] :confidence c :escalate? bool
  :high-stakes? bool :hard? bool}."
  [request _context proposal store]
  (let [bed-id (or (:bed-id proposal) (:bed-id request))
        hard (into []
                   (concat (bed-unverified-violations {:bed-id bed-id} store)
                           (effect-not-propose-violations proposal)
                           (scope-exclusion-violations proposal)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (boolean (always-escalate-ops (:op proposal)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :bed-id     (:bed-id request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
