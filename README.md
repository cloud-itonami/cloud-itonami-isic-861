# cloud-itonami-isic-861

**ISIC-861: Hospital activities coordination actor**

A langgraph-clj StateGraph actor for hospital back-office operations coordination. This is an **administrative/facility coordination actor only** — it has no clinical authority or decision-making power whatsoever.

## Scope

This actor coordinates the logistics and administration of hospital operations:
- **Bed/room assignment logistics** — physical bed availability, room turnover scheduling (never clinical triage or admission-priority decisions)
- **Visitor access scheduling** — visitor/family member time coordination
- **Non-clinical supply coordination** — linens, food service, administrative supplies (never medication or medical equipment)
- **Staff shift proposals** — administrative roster proposals (never clinical staffing adequacy decisions)
- **Facility safety flagging** — equipment maintenance, facility hazards (never patient-safety/clinical-emergency content)

### Hard-blocked scope

This actor **NEVER** touches:
- Diagnosis, clinical assessment, or patient evaluation
- Treatment planning or care-plan modifications
- Medication administration, dosing, prescribing, or pharmaceutical handling
- Medical procedures (wound care, IV/catheter management, vital signs, etc.)
- Patient safety decisions, triage, admission prioritization, or discharge readiness
- Physical restraint, seclusion, or mobility restrictions
- End-of-life or DNR decisions
- Any clinical-authority overrides

These boundaries are enforced by three HARD, permanent, un-overridable governor checks:
1. **Facility-resource unverified** — target bed/resource must exist AND be registered/verified in the store
2. **Effect not `:propose`** — every proposal's `:effect` must be `:propose` (never direct actuation)
3. **Scope exclusion** — any proposal touching clinical/diagnostic/treatment/patient-care content is HARD-blocked via substring scanning

## Module shape

- `hospitalops.store` — MemStore (SSoT, append-only audit ledger)
- `hospitalops.advisor` — HospitalAdvisor (deterministic mock, real-LLM seam)
- `hospitalops.governor` — HospitalGovernor (independent compliance censor)
- `hospitalops.phase` — Phase 0→3 rollout control
- `hospitalops.operation` — langgraph-clj StateGraph (intake → advise → govern → decide → commit | hold | approval)
- `hospitalops.sim` — demo driver

## Testing

Run tests:
```bash
clojure -M:test
```

Run linter:
```bash
clojure -M:lint
```

Run demo:
```bash
clojure -M:run
```

## Development

Use the `:dev` alias to override dependencies with local checkouts:
```bash
clojure -M:dev:test
clojure -M:dev:run
```

## License

AGPL-3.0-or-later
