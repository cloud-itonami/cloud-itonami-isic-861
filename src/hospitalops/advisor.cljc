(ns hospitalops.advisor
  "HospitalAdvisor -- the *contained intelligence node* for the
  ISIC-861 hospital activities operations-coordination actor.

  It drafts exactly five kinds of back-office proposal from a closed
  allowlist: bed/room assignment coordination, visitor access scheduling,
  non-clinical consumable supply coordination, staff shift proposals, and
  facility safety-concern flagging. CRITICAL: it is a smart-but-untrusted
  advisor. It returns a *proposal* (with a rationale + the fields it cited),
  never a committed record and NEVER a direct actuation -- every proposal's
  `:effect` is always `:propose`. Every output is censored downstream by
  `hospitalops.governor` before anything touches the SSoT.

  This advisor NEVER drafts diagnosis, treatment decisions, medication
  administration, clinical procedures, patient assessment, triage/discharge,
  vital signs monitoring, physical restraint use, end-of-life decisions,
  or clinical-authority actions -- those are permanently out of scope for
  this actor (and maximally conservatively scanned given hospitals' inherent
  clinical centrality), not merely un-implemented. `hospitalops.governor`'s
  `scope-exclusion-violations` independently re-scans every proposal for
  exactly this failure mode (a compromised or confused advisor drifting
  into scope it must never touch) and HARD-holds it, regardless of
  confidence or op.

  Like every sibling actor's advisor, this is a deterministic mock so
  the actor graph runs offline and the governor contract is exercised
  end-to-end. In production this calls a real LLM (kotoba-llm or
  equivalent) with the same proposal shape.

  Proposal shape (all kinds):
    {:op         kw             ; echoes the request op
     :bed-id     str
     :summary    str            ; human-facing draft / finding
     :rationale  str            ; why -- SCANNED by the scope-exclusion gate
     :cites      [str ..]       ; facts/sources the advisor used -- SCANNED too
     :effect     :propose       ; ALWAYS :propose -- never a direct actuation
     :value      map            ; the draft payload a human/system would review
     :confidence 0..1}")

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

;; ----------------------------- proposal generators -----------------------------

(defn- propose-bed-assignment
  "Draft a bed/room assignment coordination proposal. Pure logistics:
  which physical bed is available, room scheduling. NEVER a clinical
  triage or admission-readiness decision."
  [_db {:keys [bed-id patch]}]
  {:op         :coordinate-bed-assignment
   :bed-id     bed-id
   :summary    (str bed-id " のベッド割り当て調整: " (pr-str (keys patch)))
   :rationale  "ベッド・室の物理的な可用性と室のターンオーバースケジューリングのみ。臨床判断なし。入院優先度判定なし。"
   :cites      [bed-id]
   :effect     :propose
   :value      (merge {:bed-id bed-id} patch)
   :confidence 0.92})

(defn- propose-visitor-access
  "Draft a visitor/access scheduling coordination proposal (calendar entry,
  never a clinical oversight or patient management decision)."
  [_db {:keys [bed-id patch]}]
  {:op         :schedule-visitor-access
   :bed-id     bed-id
   :summary    (str bed-id " の来院者アクセス予定を提案: " (pr-str (keys patch)))
   :rationale  "来院者の訪問時間調整のみ。訪問可否の最終決定と会院者対応は設備安全・人事が行う。"
   :cites      [bed-id]
   :effect     :propose
   :value      (merge {:bed-id bed-id} patch)
   :confidence 0.88})

(defn- propose-supply-request
  "Draft a NON-CLINICAL consumable supply request coordination
  (linens, food service, administrative supplies -- ABSOLUTELY NEVER
  medication, medical devices, clinical equipment, or medical supplies
  like dressings, catheters, IVs, bandages, or any medication-related items)."
  [_db {:keys [bed-id patch]}]
  {:op         :coordinate-supply-request
   :bed-id     bed-id
   :summary    (str bed-id " に関連する非臨床消耗品リクエスト: " (pr-str (keys patch)))
   :rationale  "シーツ・食事・行政用品などの非臨床消耗品の調達調整のみ。投薬・医療用品・臨床機器なし。"
   :cites      [bed-id]
   :effect     :propose
   :value      (merge {:bed-id bed-id} patch)
   :confidence 0.90})

(defn- propose-staff-shift
  "Draft a staff-shift roster PROPOSAL only (never a binding clinical
  staffing or coverage-adequacy decision). Actual shift finalization is
  always done by shift supervisors."
  [_db {:keys [bed-id patch]}]
  {:op         :schedule-staff-shift-proposal
   :bed-id     bed-id
   :summary    (str bed-id " に関連するスタッフシフト提案: " (pr-str (keys patch)))
   :rationale  "行政スタッフのシフト割り当て提案のみ。確定は人間のシフト管理者が判断する。臨床スタッフ配置判定なし。"
   :cites      [bed-id]
   :effect     :propose
   :value      (merge {:bed-id bed-id} patch)
   :confidence 0.86})

(defn- propose-safety-concern
  "Surface a facility/operational safety concern (equipment malfunction,
  facility hazard, accessibility issue) for HUMAN triage. This op ALWAYS
  escalates in `hospitalops.governor` -- never auto-committed at any phase --
  regardless of how confident the advisor is that the concern is real.
  CRITICAL: this flags FACILITY/OPERATIONAL safety (elevator down, spill on floor),
  NOT patient-safety/clinical-emergency (which must go through actual clinical staff)."
  [_db {:keys [bed-id patch]}]
  {:op         :flag-safety-concern
   :bed-id     bed-id
   :summary    (str bed-id " に関連する施設安全懸念フラグ: " (pr-str (:concern patch "unknown")))
   :rationale  "施設・機器の安全上の観察事実の報告。常に人間の確認・対応が必要。患者安全/臨床上の懸念ではない。"
   :cites      [bed-id]
   :effect     :propose
   :value      (merge {:bed-id bed-id} patch)
   :confidence (or (:confidence patch) 0.84)})

;; ----------------------------- default mock advisor -----------------------------

(defn infer
  "Mock advisor: routes to the correct proposal generator."
  [_db {:keys [op out-of-scope?] :as request}]
  (let [proposal (case op
                   :coordinate-bed-assignment (propose-bed-assignment _db request)
                   :schedule-visitor-access (propose-visitor-access _db request)
                   :coordinate-supply-request (propose-supply-request _db request)
                   :schedule-staff-shift-proposal (propose-staff-shift _db request)
                   :flag-safety-concern (propose-safety-concern _db request)
                   {})]
    ;; Test hook: allow injecting scope-excluded content to exercise the
    ;; governor's scope-exclusion block end-to-end. Must be cleared before
    ;; production use. This injects HOSPITAL-SPECIFIC scope violations.
    (if out-of-scope?
      (update proposal :rationale str " -- patient diagnosis cardiac arrhythmia, treatment plan includes medication IV drip adjustment, clinical assessment for discharge readiness, triage priority decision")
      proposal)))

(defn trace
  "Audit fact for a proposal generated by this advisor."
  [_request proposal]
  {:t       :advisor-proposal
   :op      (:op proposal)
   :bed-id  (:bed-id proposal)
   :summary (:summary proposal)
   :confidence (:confidence proposal)})

(defn mock-advisor
  "The deterministic default advisor for offline demo/test."
  []
  (reify Advisor
    (-advise [_ _store request]
      (infer nil request))))
