# Changelog

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
