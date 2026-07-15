(ns hospitalops.store
  "SSoT for the ISIC-861 hospital activities COORDINATION actor,
  behind a `Store` protocol so the backend is a swap, not a rewrite -- the
  same seam every `cloud-itonami-isic-*` actor in this fleet uses.

  This actor coordinates the back-office operations of a hospital: bed/room
  assignment logistics, visitor access scheduling, non-clinical consumable
  supply coordination (linens, food service, administrative supplies),
  staff shift proposals, and facility safety-concern flagging (equipment
  malfunction, facility hazards). It never touches clinical decision-making:
  diagnosis, treatment/care plans, medication/pharmaceutical, clinical
  procedures (wound care, IV/catheter management, vital signs), patient
  safety/triage, discharge readiness, or any clinical-authority overrides --
  see `hospitalops.governor`'s `scope-excluded-terms`, a HARD, permanent,
  un-overridable block.

  `MemStore` -- atom of EDN. The deterministic default for dev/tests/demo
  (no deps). A `beds` directory keyed by `:bed-id` STRING (never a
  keyword -- consistent keying from the start, avoiding the silent-miss
  bug that plagued an earlier shepherd attempt).

  A registered/verified bed record must exist before ANY proposal
  for that bed may ever commit or escalate -- `hospitalops.governor`'s
  `bed-unverified-violations` re-derives this from the bed's own
  `:registered?`/`:verified?` fields, never from proposal self-report,
  the SAME 'ground truth, not self-report' discipline every sibling
  actor's own governor uses.

  The ledger stays append-only: which bed a proposal targeted, which
  operation, on what basis, committed/held/escalated and approved by
  whom is always a query over an immutable log.")

(defprotocol Store
  (bed [s bed-id] "Registered bed record, or nil.
    Bed map: {:bed-id .. :location .. :registered? bool :verified? bool}.")
  (all-beds [s])
  (ledger [s] "the append-only immutable decision-fact log")
  (coordination-log [s] "the append-only committed coordination-proposal history")
  (commit-record! [s record] "apply a committed proposal's record to the SSoT")
  (append-ledger! [s fact] "append one immutable decision fact")
  (with-beds [s beds] "replace/seed the bed directory (map bed-id->bed)"))

;; ----------------------------- demo data -----------------------------

(defn demo-data
  "A small, self-contained bed directory covering both the happy path
  and the governor's own hard checks, so the actor + tests run offline."
  []
  {:beds
   {"bed-101" {:bed-id "bed-101" :location "Ward A, Room 101"
               :registered? true :verified? true}
    "bed-102" {:bed-id "bed-102" :location "Ward A, Room 102"
               :registered? true :verified? true}
    "bed-201" {:bed-id "bed-201" :location "Ward B, Room 201, in intake"
               :registered? true :verified? false}}})

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [a]
  Store
  (bed [_ bed-id] (get-in @a [:beds bed-id]))
  (all-beds [_] (sort-by :bed-id (vals (:beds @a))))
  (ledger [_] (:ledger @a))
  (coordination-log [_] (:coordination-log @a))
  (commit-record! [_ record]
    (swap! a update :coordination-log conj record)
    record)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-beds [s beds] (when (seq beds) (swap! a assoc :beds beds)) s))

(defn seed-db
  "A MemStore seeded with the demo bed directory. The deterministic
  default."
  []
  (->MemStore (atom (assoc (demo-data) :ledger [] :coordination-log []))))

(defn mem-store
  "A MemStore seeded with an explicit `beds` map (bed-id string ->
  bed map) -- the primary test/dev entry point. `beds` may be empty
  (an unregistered-everywhere store)."
  [beds]
  (->MemStore (atom {:beds (or beds {}) :ledger [] :coordination-log []})))
