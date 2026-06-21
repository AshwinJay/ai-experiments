# Features

## To Do

- [x] **Release & hotfix branch names in header** — Add input fields at the top of the dashboard to capture the release branch name and the hotfix branch name for the current week (e.g. `release/2026-W10`, `hotfix/auth-token-expiry`). These should be stored in the weekly JSON file alongside existing release metadata.

- [x] **Inline hotfix tracking per service** — Remove the separate Hotfix Tracker tab. Instead, add a hotfix section directly on each service card in the service board. All functionality from the hotfix tab (hotfix branch label, notes, and merge status checkboxes for main/release/hotfix branch) should be accessible inline per service, toggled by a "Request Hotfix" option on the service.

- [x] **Hotfix merge status checkboxes** — For each hotfix entry, add a sub-section with three checkboxes to track where the hotfix has been merged:
  - Merged to main
  - Merged to release branch
  - Merged to hotfix branch

- [x] **Remove phase bar** — The "📋 Planning → ✂️ Branch Cut → 🏷️ Labeled → 🧪 Testing → 👀 Mon Review → 🚀 Deploying → ✅ Done" bar at the top is clickable but has no functional effect. Remove it entirely.

- [x] **Fix or remove summary counters** — The stat counters at the top of the dashboard (Services, Approved, Deployed, Hotfixes, Failed) are broken: Approved and Deployed always show 0 even when services are in those states. Either fix the counts to reflect actual service statuses or remove the counter strip entirely.

- [x] **Add pre-production to regional deployment tracker** — Add a `pre-production` entry to the regional deployment tracker alongside the existing regions (`us-east-1`, `us-west-2`, `eu-west-1`, `ap-southeast-1`).

- [x] **Move label/tag to regional deployment tab** — Remove the label/tag field from the service board and display it in the regional deployment tracker tab instead.

- [x] **Wire up service status labels** — On the "Services in Release" tab, each service card has a row of selectable status labels (`pending`, `branch-cut`, `labeled`, `testing`, `approved`, `needs-hotfix`, `hotfix-ready`, `deploying`, `deployed`, `failed`) that currently have no functional effect. Selecting a label should update the service's status in the data and reflect correctly in the summary counters and any other status-dependent UI.

- [x] **Rename "Label / Tag" to "Label"** — In the service form and service card on the "Services in Release" tab, the field currently labelled "Label / Tag" should be renamed to just "Label".

- [x] **Move hotfix details below the service info panel** — On each service card, the hotfix section (hotfix label input, notes input, and the three merge status checkboxes for main, release branch, and hotfix branch) should appear directly below the panel that shows service name, repository, and change type — not at the very bottom of the card after the status row.

- [x] **Merge "Hotfix" and "Needs Hotfix" labels; replace "Request Hotfix" button** — Remove the dedicated "Request Hotfix" button from the service card. Instead, clicking the `needs-hotfix` status label should toggle hotfix mode on (and off) for that service, replacing both the button and the separate HOTFIX active-state pill. There should be a single combined label that reflects whether the service is in hotfix mode.

- [x] **Merge Regional Deployment Tracker with Services in this Release** — The Regional Deployment Tracker should be merged into the "Services in this Release" view. The `deployed` status chip on each service card should be driven by the region deployments (i.e. a service is considered deployed when its regional deployment data indicates it has been deployed to the relevant regions).

- [x] **Notes tab** — Dedicated Notes tab next to Release Checklist for capturing freeform notes. Each note supports: tags, rich links, text content, a mark-as-done toggle, drag-to-reorder, and up to 3 levels of subnesting (child/grandchild items).

- [x] **Multi-user sync via shared folder (File System Access API)** — Allow multiple users to collaborate via a shared cloud folder (Google Drive, OneDrive, Dropbox, etc.) mounted locally. Each user writes their own per-user JSON file (`release-YYYY-WNN-<username>.json`). On load, the app reads all matching files in the folder and merges them into a unified view. Merge strategy: last-write-wins per item by `updatedAt` timestamp for services and notes; OR-merge for checklist; last-write-wins by `savedAt` for scalar fields. Deletions use a `deletedAt` tombstone.

## Momentum Tab (not yet started)

A unified "Release Momentum" tab that consolidates release health into a single holistic view. Replaces the need for separate Risks, Buffer, and Slippage tabs. Grounded in the Momentum Framework (`momentum-framework.md`).

**Formula:** `Momentum = (Power ÷ 100) × (Capacity ÷ 100) × (1 − Risk Drag) × 100`
**Status thresholds:** ≥ 70 ON TRACK · 40–69 AT RISK · < 40 STALLED

### ⚠️ Open question: clarify Capacity vs Buffer before building

These two terms are currently conflated in common usage and need a decision before any UI is built:

- **Capacity (framework definition):** Team availability — what fraction of the release team is actually able to work this week. Reduced by PTO, oncall rotation, unplanned interruptions. A people/bandwidth measure. Feeds directly into the Momentum formula.
- **Buffer (release-specific):** Schedule slack — days remaining before the hard deployment deadline minus the time realistically needed. A time measure. Currently proposed to surface as a risk (tight buffer = auto-generated HIGH risk) rather than as a capacity input.

**Why it matters:** If buffer is folded into capacity (e.g. "we have plenty of time so capacity feels high"), the formula loses precision — you could have a fully available team with zero schedule slack, or a half-staffed team with two weeks of runway. They need to stay separate.

**Decision needed:** Confirm whether this separation (capacity = people, buffer = time-as-risk) makes sense for how the release team actually talks about these concepts, or redefine before implementation.

### Inputs (manual)

- [ ] **Power dials** — Strength (1–10) and Speed (1–10) sliders; display derived `Power = Strength × Speed` (range 1–100).
- [ ] **Capacity** — Team availability this week as a % (0–100). Distinct from time buffer; reflects how much of the release team is actually engaged vs. on PTO/oncall/pulled away. Separate from slippage and schedule buffer.
- [ ] **Schedule buffer** — Days of slack remaining before the hard deployment deadline (manual number input). Feeds into risk drag as an auto-generated risk if buffer ≤ N days, rather than being conflated with capacity.

### Risk Register

- [ ] **Auto-detected risks** (read from existing service data, non-editable):
  - Failed services → HIGH risk (Chance 90%, Impact 8)
  - Services with active hotfixes → MED risk (Chance 60%, Impact 5)
  - Unresolved upstream dependencies → risk per blocked service
  - De-scoped / slipped services → risk (Chance 50%, Impact 4)
  - Tight schedule buffer (≤ 1 day) → HIGH risk
- [ ] **Manual risks** — Release manager can add arbitrary risks with Chance (0–100%) and Impact (1–10). Shows per-risk score and level (LOW / MED / HIGH).
- [ ] **Slippage flag per service** — Add a "de-scope from release" toggle on each service card. De-scoped services appear in the auto-detected risk list.
- [ ] **Total Risk Drag** — Computed as `min(0.85, Σ(risk scores) ÷ (count × 6))`; displayed alongside the register.

### Derived outputs

- [ ] **Net Momentum** — Big prominent number (0–100) with ON TRACK / AT RISK / STALLED badge.
- [ ] **Release Goal score** — Progress = % of in-scope services deployed; priority = P2 default (user-overridable to P1 or P3). Full formulas:
  - Risk score per risk: `(Chance ÷ 100) × Impact` → LOW < 2.5, MED 2.5–4.99, HIGH ≥ 5.0
  - Priority tiers: P1 weight 1.0× threshold 25 · P2 weight 1.5× threshold 40 · P3 weight 2.0× threshold 60
  - `Feasibility Multiplier = min(1.0, Momentum ÷ Threshold)`
  - `Goal Score = (Progress% × Priority Weight × Feasibility Multiplier) ÷ 2 × 100`
- [ ] **Portfolio Health** — If multiple goals tracked: `Σ(Goal Score × Priority) ÷ Σ(100 × Priority) × 100`

### Persistence

- [ ] Store momentum inputs (strength, speed, capacity, buffer, manual risks, goal priority, slippage flags) in the weekly JSON file alongside existing release data.

### Design notes

- Capacity ≠ buffer: capacity is team bandwidth (people), buffer is schedule slack (time). Keep them as separate inputs. Buffer surfaces as a risk; capacity directly feeds the momentum formula.
- Auto-detected risks are re-derived on load from live service data — they are not stored separately.
- The tab replaces the need for standalone Risks, Buffer, and Slippage sections elsewhere in the app.
