# PlexonKeys 2.0.0 Phase 2 Execution Specification

## Baseline and version decision

- Repository: `ZpkDxGames/PlexonKeys`
- Published rollback: `v1.4.1`
- Rollback SHA: `c06a9e4107fc259f45d32d1f0767e5e2b5d1d872`
- Phase 2 branch: `phase2/2.0.0-premium-key-provenance`
- Candidate version: `2.0.0-rc.1`
- Runtime: Paper 26.2, Java 25
- PlexonCore: 2.0.4 provided/non-shaded

`2.0.0` is justified because the public Bukkit service contract grows new key-definition, exact physical-identity and idempotent-consume primitives, acquisition provenance becomes a first-class integration boundary, PlaceholderAPI is added, and administration/diagnostics gain new product surfaces. The existing stable API methods, four authoritative key IDs, config schema and database schema remain compatible.

## Audited stable architecture to preserve

The 1.4.1 source already provides the correct high-frequency architecture and must not be replaced:

- fixed stable IDs: `basic`, `rare`, `epic`, `legendary`;
- complete captured `ItemStack` serialization instead of material/name/lore reconstruction;
- memory-authoritative player balances;
- synchronized atomic account mutations and multi-tier claim debit/restore;
- one bounded SQLite worker with revision-coalesced writes;
- schema-1 future-schema fail-closed startup;
- transactional config parse/validate/swap;
- local artificial-block provenance plus mutually exclusive PlexonCore ownership;
- no SQLite query per gameplay event;
- post-commit earned/claim events.

Phase 2 does not introduce a per-player task, per-key task, global entity scan, event-time database lookup, second block-origin database, or duplicate Core/local reward path.

## Phase 2 implementation

### Key identity and definitions

The enum-backed tier ID remains authoritative. Display name, lore, material and GUI title never become IDs. `PlexonKeysAPI.keyDefinitions()` returns immutable definition views keyed by stable ID. Captured/configured physical templates remain byte/component compatible with 1.4.1 and the already-frozen PlexonCrates 5.0 RC. `identifyPhysicalKey` uses exact Bukkit `ItemStack.isSimilar` metadata/component equality after normalizing amount to one.

No PlexonKeys-owned PDC is injected into existing templates in this candidate because changing exact template bytes would break the frozen PlexonCrates discovery/matching contract. Captured third-party PDC/components remain preserved and therefore participate in exact identity.

### Virtual balances and exact-once consume

All mutations continue through `KeyBalanceService`. `consumeKey(player,key,amount,transactionId)` uses the existing atomic `MemoryStore.debit` boundary and a bounded 4096-entry process-local replay guard. The same transaction cannot debit twice. Reuse of one ID for a different request fails closed. Successful consumes publish synchronous post-commit `PlexonKeyConsumedEvent`.

The replay guard is intentionally bounded and process-local; integrations requiring crash-spanning reservations must retain their own durable opening transaction journal and use PlexonKeys as the authoritative balance debit boundary.

### Acquisition provenance

Mining/logging keep the existing natural-vs-artificial PlexonCore/local provenance path. Mob acquisition adds conservative first-party classification:

- natural-like reasons -> `NATURAL`;
- `SPAWNER` with first-party PlexonSpawners API attribution -> `PLEXON_SPAWNERS`;
- other `SPAWNER` -> `EXTERNAL_SPAWNER`;
- other/custom origin -> `UNKNOWN`.

`PLAYER_PLACED`, `PLEXON_SPAWNERS`, `EXTERNAL_SPAWNER`, `UNKNOWN` and `DISABLED` are rejected by default. Mob-origin exceptions require explicit optional configuration. `UNKNOWN` is never silently promoted to `NATURAL`.

PlexonSpawners is optional and reflective. The bridge reads its registered `PlexonSpawnersApi.isSpawnerOrigin(Entity)` contract when available and otherwise fails gracefully. No Spawners database/history scan is performed.

### Non-granting dry run

`/keysadmin dryrun <online-player> <key> <activity> <origin>` evaluates the immutable reward runtime, provenance, permission, game mode, world, key permission, current cap and current cooldown. It does not roll RNG, change cooldowns, mutate a balance, publish events, mark persistence dirty, or deliver an item.

### PlaceholderAPI

PlaceholderAPI is provided/non-shaded and optional. `%plexonkeys_total%`, `%plexonkeys_<id>%`, `%plexonkeys_balance_<id>%`, `%plexonkeys_enabled_<id>%`, `%plexonkeys_display_<id>%` and `%plexonkeys_<id>_display%` are backed only by in-memory state/config. No placeholder performs SQLite I/O.

### Diagnostics/admin

Existing diagnostics remain intact and are extended with definition count/IDs, config and database schema, physical identity mode, replay-guard pressure, PlexonSpawners status, PlaceholderAPI status and PlexonCrates presence. `/keysadmin definitions` exposes authoritative IDs and key modes.

## Migration and rollback

There is no database DDL migration and no config-version bump in 2.0.0-rc.1. Existing schema-1 `players` and artificial-position data are loaded unchanged. New optional integration settings use safe absent-key defaults. Existing captured ItemStacks remain unchanged.

Upgrade staging:

1. Stop the server and back up the 1.4.1 JAR plus `plugins/PlexonKeys/`.
2. Replace only the JAR with the candidate.
3. Keep the existing `config.yml` and `plexonkeys.db`.
4. Start Paper 26.2 / Java 25.
5. Run `/keysadmin diagnostics` and `/keysadmin definitions`.
6. Complete the runtime certification matrix before stable promotion.

Rollback before stable certification: stop the server, restore `v1.4.1` and its backed-up data directory. Because this candidate does not advance DB/config schema, no downgrade migration is required.

## Required automated gates

CI must compile production/tests, execute all tests with zero skips, verify the exact candidate version and filename, require Java class major 69, confirm SQLite packaging, reject shaded PlexonCore/PlaceholderAPI/PlexonSpawners/PlexonCrates runtime classes, run whitespace checks, create SHA-256, and publish `TEST_SUMMARY.txt` plus `PROVENANCE.txt`.

The release-candidate workflow must rebuild from the exact candidate SHA, require the Phase 2 PR to remain open/draft/unmerged, create an immutable `v2.0.0-rc.1` tag at that SHA, publish a GitHub prerelease only, and re-download/verify all published evidence.

## Runtime certification boundary

Automated verification is not runtime certification. Stable `2.0.0` remains blocked until PlexonCraft verifies representative 1.4.1 migration, player load/reconnect, virtual balances, exact physical identity, grant/take/set, all acquisition origins, Core/local ownership, Spawners provenance, Crates consumption/duplicate protection, full-inventory claim behavior, dry-run, restart persistence, reload rollback, PAPI/API/events, Spark/MSPT comparison and at least a 30-minute soak with zero HIGH/CRITICAL defects.
