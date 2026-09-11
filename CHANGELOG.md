# Changelog

## 2.0.0 — stable

### Premium key authority and integrations
- Promoted the accepted 2.0 premium key identity/provenance/API line and Phase 3 RC2 reliability boundary to stable.
- Preserved stable `basic`, `rare`, `epic`, and `legendary` IDs; exact physical `ItemStack` identity; virtual balances; safe claims; public Bukkit API/events; PlaceholderAPI; PlexonSpawners provenance; and PlexonCrates definition/consume integration boundaries.
- Preserved Core/local block-reward ownership and fail-closed UNKNOWN provenance.

### Durability and persistence
- Retained production-safe effective periodic checkpoints with explicit disable support.
- Retained one bounded revision-aware persistence worker, bounded dirty snapshots, coalesced saves, retryable failed writes, pressure-triggered checkpoint requests, and controlled-shutdown final persistence.
- Retained database schema 2 with one-time schema-1 safety backup `plexonkeys.db.pre-v2.bak`.
- Retained persisted consume transaction replay: successful debit + replay record share one revision/SQLite transaction, exact duplicate retries cannot debit twice after restart, conflicting ID reuse fails closed, and replay retention remains bounded.
- Retained crash-durable physical claims: virtual debit is durably committed before inventory mutation; failed delivery restores and persists compensation.

### Final stability fix
- Made validated configuration **runtime application** transactional rather than only parse/validation atomic.
- A failing subsystem application now restores the last runtime-accepted settings and rebuilds the prior runtime state.
- In-game configuration edits restore the exact previous `config.yml` bytes if application fails.
- Externally edited candidates remain on disk for administrator correction while the last-good runtime remains active.
- Configuration revision stays monotonic across rollback, keeping stale GUI/runtime caches invalid.
- Checkpoint/runtime rebuilding is covered against duplicate task leakage.
- Added regression coverage for external reload failure and internal edit failure, including last-good settings, exact disk behavior, revision advancement, task count, and successful later edits.

### Repository/release closure
- Promoted Maven/project metadata from `2.0.0-rc.2` to stable `2.0.0`.
- Replaced legacy/one-shot release paths with one canonical Build workflow and one exact-current-`main` stable Release workflow.
- Stable publication rebuilds/retests exact source, publishes JAR/checksum/test/provenance evidence, downloads the public assets, and independently verifies checksum/source/accepted-RC2 provenance before success.
- Historical releases remain immutable; stable rollback baseline remains `v1.4.1` at `c06a9e4107fc259f45d32d1f0767e5e2b5d1d872`.
- Historical RC2 evidence remains `v2.0.0-rc.2` from accepted source `f5f8656a4cc879e449a8b822386892a659d8e228`, JAR SHA-256 `0cc87449754b7e5dec34e57091f856f2bfa98a3c256eb41617b510911cbe0ee4`.
- Live PlexonCraft runtime certification remains a deployment follow-up and may be recorded as `NOT_EXECUTED` in stable release provenance.

## 1.3.0 — 2026-09-09

### Performance
- Reconciled the release branch with the published 1.2.0 baseline before optimization so the stable `PlexonKeysAPI` and earned/claimed event contracts remain present.
- Consolidated activity reward eligibility into one authoritative hot path, eliminating the listener + reward-service duplicate permission/world/game-mode validation pass.
- Added immutable revision-aware reward runtime data with pre-indexed enabled candidates, prevalidated exact chance values (including sub-0.001% percentages), nanosecond cooldowns, cached notification settings, and one random source per attempt.
- Added atomic balance mutation results so reward messages receive the committed post-credit balance without an additional synchronized lookup.
- Changed multi-tier claim debit and rollback restore to one account mutation/dirty version instead of one mutation per tier.
- Added normalized player-name indexing and cached sorted-name results to remove repeated linear account scans from admin lookup/tab completion.
- Added batch provenance APIs for multi-place, growth/fertilization, piston, explosion, and other grouped block changes while preserving artificial-block anti-abuse semantics.
- Preserved piston provenance by snapshotting source state before clearing/moving adjacent blocks.
- Changed claim inventory planning to copy-on-write: untouched inventory stacks are no longer cloned a second time during delivery planning.
- Coalesced `/keys` balance refreshes into one configurable refresh window and skip refresh work unless the relevant PlexonKeys player menu is still open.

### Persistence and reliability
- Replaced the unbounded single-thread executor queue with one bounded database worker and revision-coalesced save requests.
- Added bounded dirty snapshots (`storage.maximum-snapshot-records`, default `4096`) with exact version acknowledgement so newer mutations remain dirty and failed saves remain retryable.
- Added pressure-triggered coalesced checkpoint requests (`storage.pressure-dirty-threshold`, default `2048`) without performing JDBC work on activity/provenance event threads.
- New installations now use a recommended 60-second periodic checkpoint; existing `config.yml` files are not overwritten, so an explicit `checkpoint-seconds: 0` remains shutdown-only.
- Added configurable controlled-shutdown database timeout (`storage.shutdown-timeout-seconds`, default `15`) and executor termination handling.
- Checkpoints skip clean state and configuration reload cancels/recreates the scheduled checkpoint exactly once.
- Expanded `/keysadmin diagnostics` with reward/provenance rates, dirty account/block counts, save state/revisions, snapshot/database timings, rolling P95 save duration, maximum dirty count, last save size, and failure count.

### Claims and integrations
- Preserved complete physical `ItemStack` templates and the 1.2.x Bukkit service API surface used by PlexonCrates.
- Preserved post-success `PlexonKeyEarnedEvent` and `PlexonKeyClaimedEvent` semantics used by PlexonQuests, including one claim event per delivered tier and shared parent transaction IDs.
- Claim events still publish only after both virtual debit and physical inventory delivery commit; failed delivery silently restores balances without a false earned event.

### Configuration / build
- Added `performance.menu-refresh-ticks` (default `2`).
- Updated Maven/project metadata to `1.3.0`, Java 25 compiler warnings (`-Xlint:all`), verified `PlexonKeys-1.3.0.jar` distribution naming, and `SHA256SUMS.txt` generation.
- Updated build/tag-release workflows to reject a mismatched release JAR name and publish only the 1.3.0 JAR plus checksum.
- Expanded automated coverage for atomic multi-tier mutations, bounded snapshot draining, combined name+credit mutation, 1.3 checkpoint defaults, and existing chance-editor behavior with an intentional recurring checkpoint task.

### Release gate
- Automated CI is necessary but not sufficient for the stable tag. Run the documented Spark scenarios, 10-player mining/provenance/claim stress, checkpoint-pressure test, integration staging, and mixed 30-minute soak before publishing `v1.3.0`.

## 1.2.0 — 2026-09-07

- Adopted PlexonCore 1.0.0 as an optional Core-native integration while preserving standalone operation when Core is absent, disabled, incompatible, or unavailable through Bukkit services.
- Added Core module registration under module ID `keys`, lifecycle health publishing, `PLEXON_KEYS` integration capabilities, clean unregistration, and `/keysadmin diagnostics`.
- Added the stable Bukkit `PlexonKeysAPI` service for balance reads, capped grants, removals, tier state, and defensive physical key-template access.
- Added `KeySource` for stable acquisition metadata and documented the 1.2.x primary-thread API contract.
- Added `com.antondev.keys.event.PlexonKeyEarnedEvent` with post-success, actual-credit semantics and non-empty event IDs.
- Added `com.antondev.keys.event.PlexonKeyClaimedEvent` with post-delivery semantics, one event per delivered tier, shared parent claim transaction IDs, and unique per-tier event IDs.
- Centralized virtual balance mutations through a domain service so activity/API/admin grants, removals, claim debits, corrections, and silent rollback recovery use explicit event policies.
- Wired activity earning sources as `activity:mining`, `activity:logging`, `activity:fishing`, and `activity:mobs`; normal physical claims publish `player-claim`.
- Preserved failed-claim recovery as a silent balance restoration so a rollback cannot create false PlexonQuests progress.
- Hardened listener-failure isolation so external event-listener exceptions cannot undo completed credits/deliveries or duplicate physical keys.
- Added reflection-contract and behavior coverage for the public API/events, standalone startup, cap handling, silent recovery, claim-all IDs, defensive API results, and service unregistration.
- Modernized CI for Java 25 with pinned/verified PlexonCore 1.0.0 provisioning, Maven verification, installable-JAR checks, Core-class exclusion, and SHA-256 generation.
- Replaced the legacy main-push release mechanism with a tag-driven workflow that rebuilds and verifies the exact release tag before publishing `PlexonKeys-1.2.0.jar` and `SHA256SUMS.txt`.
- Added API, PlexonCore ownership/lifecycle, and 1.1.0 → 1.2.0 migration/staging documentation.
- Preserved the existing configuration layout, `plexonkeys.db`, balances, tracked-block state, activity probabilities, reward progression, GUIs, and complete captured physical key ItemStacks.

## 1.1.0 — 2026-08-30

- Added a chance overview for all 16 category/activity pairs through `/keysadmin chances` and the main admin GUI.
- Category chance buttons now open a percentage editor with fine/coarse adjustments, 0%/100% shortcuts, reset, Apply, and Cancel.
- Decimal draft arithmetic preserves small percentages; only Apply saves to `config.yml` and activates the selected chance. No repeating task or per-click disk write is added.
- Optional exact chat input returns to the draft without saving; permissions, stale configuration checks, and inventory protections also cover the new menus.
- All new labels and messages support configurable MiniMessage styling; existing 1.0.0 configurations pick up missing defaults without replacing custom settings.

## 1.0.0 — 2026-08-30

- Initial PlexonKeys plugin for Paper 26.2 / Java 25, created by Tonim (ZpkDxGames).
- Basic, Rare, Epic, and Legendary virtual keys from configurable mining, logging, fishing, and mob-kill activities.
- Independent percentages per category/activity, optional highest-rarity-only mode, permissions, world filters, caps, and activity cooldowns.
- Natural-block provenance tracking, including placements, multi-place, pistons, falling blocks, endermen, growth, formation, spread, and cleanup events.
- `/keys` collection menu with category claim panels, one-key right-click, claim-all, and inventory-capacity protection.
- Complete held-item capture through Paper binary item serialization, without injecting or replacing key metadata.
- Category admin GUIs plus a paginated configuration browser, private input prompts, validation, immediate file saves, and safe reloads.
- Configurable MiniMessage messages, gradients, names, lore, menu titles, layout, per-category broadcasts, sound, Vault cash, and raw XP bonuses.
- In-memory runtime state, SQLite dirty-row transactions, final shutdown save, manual asynchronous checkpoint, and optional low-frequency checkpoints disabled by default.
- JUnit/SQLite/MockBukkit coverage, GitHub Actions build artifacts, and staging/install documentation.
