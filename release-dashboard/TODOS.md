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
