# Changelog

## 1.1.0 — 2026-08-30

- Added a chance overview for all 16 category/activity pairs through `/keysadmin chances` and the main admin GUI.
- Category chance buttons now open a percentage editor with fine/coarse adjustments, 0%/100% shortcuts, reset, Apply, and Cancel.
- Decimal draft arithmetic preserves small percentages; only Apply saves to `config.yml` and activates the selected chance. No repeating task or per-click disk write is added.
- Optional exact chat input returns to the draft without saving; permissions, stale configuration checks, and inventory protections also cover the new menus.
- All new labels and messages support configurable MiniMessage styling; existing 1.0.0 configurations pick up missing defaults without replacing custom settings.

## 1.0.0 — 2026-08-30

- Initial PlexonKeys plugin for Paper 26.2 / Java 25, authored for Tonim (ZpkDxGames).
- Basic, Rare, Epic, and Legendary virtual keys from configurable mining, logging, fishing, and mob-kill activities.
- Independent percentages per category/activity, optional highest-rarity-only mode, permissions, world filters, caps, and activity cooldowns.
- Natural-block provenance tracking, including placements, multi-place, pistons, falling blocks, endermen, growth, formation, spread, and cleanup events.
- `/keys` collection menu with category claim panels, one-key right-click, claim-all, and inventory-capacity protection.
- Complete held-item capture through Paper binary item serialization, without injecting or replacing key metadata.
- Category admin GUIs plus a paginated configuration browser, private input prompts, validation, immediate file saves, and safe reloads.
- Configurable MiniMessage messages, gradients, names, lore, menu titles, layout, per-category broadcasts, sound, Vault cash, and raw XP bonuses.
- In-memory runtime state, SQLite dirty-row transactions, final shutdown save, manual asynchronous checkpoint, and optional low-frequency checkpoints disabled by default.
- JUnit/SQLite/MockBukkit coverage, GitHub Actions build artifacts, and staging/install documentation.
