# PlexonKeys 2.0.0

Premium key identity, virtual balances, safe physical claims, acquisition provenance and first-party Plexon integrations for **Paper 26.2 / Java 25**.

## Runtime architecture

PlexonKeys keeps gameplay state memory-authoritative. SQLite is persistence, not an event-time lookup service. One bounded database worker persists revisioned/coalesced snapshots; mining, logging, mob and fishing reward paths do not perform JDBC queries or create per-event tasks.

Authoritative key IDs are fixed and stable:

```text
basic
rare
epic
legendary
```

Display names, lore, GUI titles and materials are presentation only and never become key identity.

## Player flow

```text
ACQUIRE -> UNDERSTAND -> CLAIM / STORE -> USE -> AUDIT
```

`/keys` opens the key collection. `/keys claim <basic|rare|epic|legendary|all>` converts available virtual balances to configured exact physical `ItemStack` templates using inventory preflight, a crash-durable virtual debit, and persisted compensation if physical delivery fails.

## Physical keys

Captured key templates preserve full Paper item metadata/data components. PlexonKeys does not reconstruct captured third-party keys from material/name/lore.

`PlexonKeysAPI.identifyPhysicalKey(ItemStack)` normalizes stack amount to one and uses full Bukkit item similarity; a similar-looking item with different metadata/components does not authenticate.

Capture real integration items with:

```text
/keysadmin setitem basic
/keysadmin setitem rare
/keysadmin setitem epic
/keysadmin setitem legendary
```

## Acquisition provenance

Natural/artificial block handling preserves the PlexonCore/local authority split. Core Runtime mode owns routed block rewards; LOCAL mode owns `BlockBreakEvent`. Both are never active as reward authorities at the same time. `UNKNOWN` is not treated as `NATURAL` by default.

Mob acquisition additionally classifies:

```text
NATURAL
PLEXON_SPAWNERS
EXTERNAL_SPAWNER
UNKNOWN
```

PlexonSpawners attribution is consumed through its optional Bukkit service API by reflection. PlexonCrates remains authoritative for crate opening/reward policy.

## Virtual balance API

The 1.x Bukkit service surface remains available, while 2.0 adds key-definition resolution, exact physical-key identification, and transactional consume semantics.

`consumeKey(UUID playerId, String keyId, long amount, String transactionId)` uses **persisted crash-spanning idempotency**. The successful virtual debit and replay record are committed in one SQLite transaction before `SUCCESS` is returned. Exact duplicate transaction IDs cannot debit twice after restart; conflicting reuse fails closed. Replay retention is bounded to 4,096 records and seven days.

## Persistence and migration

PlexonKeys 2.0 uses **database schema 2**. Upgrading a schema-1 database creates the one-time safety copy:

```text
plugins/PlexonKeys/plexonkeys.db.pre-v2.bak
```

Existing player balances and placed/artificial block provenance are retained in place. Schema 2 adds the bounded consume-replay table/index; unknown newer schemas are refused.

Normal dirty state is persisted by revision-aware, bounded asynchronous checkpoints. Legacy configurations with `storage.checkpoint-seconds: 0` inherit an effective 60-second checkpoint unless `storage.checkpoints.enabled: false` explicitly disables checkpoints. Low-frequency irreversible consume/claim boundaries use the same database worker through a synchronous durability barrier; high-frequency acquisition events still perform no SQLite work.

## Configuration and reload safety

Configuration parsing and **runtime application** are transactional at the PlexonKeys boundary.

- A candidate is parsed/validated before activation.
- The previous runtime-accepted settings remain available until all subsystem application steps succeed.
- If runtime application fails, last-good settings are restored and the previous menu/reward/economy/checkpoint/Core block runtime is rebuilt.
- In-game edits restore the exact previous `config.yml` bytes on failure.
- External file edits remain on disk for correction while the last-good runtime stays active.
- Configuration revision remains monotonic through rollback so stale GUI sessions/caches cannot become valid again.
- Checkpoint rescheduling does not leak duplicate tasks.

## PlaceholderAPI

PlaceholderAPI is optional/provided and never shaded. Values come from in-memory authoritative state/config and never synchronously query SQLite.

Representative identifiers:

```text
%plexonkeys_total%
%plexonkeys_basic%
%plexonkeys_balance_basic%
%plexonkeys_enabled_basic%
%plexonkeys_display_basic%
```

## Administration

```text
/keysadmin
/keysadmin definitions
/keysadmin dryrun <player> <key> <activity> <origin>
/keysadmin give <player|uuid> <key> <amount>
/keysadmin take <player|uuid> <key> <amount>
/keysadmin setbalance <player|uuid> <key> <amount>
/keysadmin balance <player|uuid>
/keysadmin setitem <key>
/keysadmin chances [key] [activity]
/keysadmin chance <key> <activity> <percent>
/keysadmin set <path> <value>
/keysadmin reload
/keysadmin save
/keysadmin status
/keysadmin diagnostics
```

The dry-run evaluates rules/provenance/permissions/game mode/world/cap/cooldown without RNG, balance mutation, event publication, persistence dirties, or physical delivery.

## Build and stable release

Canonical verification provisions PlexonCore 2.0.4 by SHA-256 and runs:

```bash
mvn -B -ntp clean verify
```

The installable distribution is:

```text
target/PlexonKeys-2.0.0.jar
```

Build CI requires accepted RC2 ancestry, a non-empty zero-failure/error/skip test suite, Java class major 69, SQLite packaging, required public API/event/runtime classes, provided-integration non-shading, a basename SHA-256 file, and exact-source provenance.

Stable publication runs only from `release/stable` when it points to exact current `main`. It rebuilds/retests exact source, publishes the JAR plus `SHA256SUMS.txt`, `TEST_SUMMARY.txt`, and `PROVENANCE.txt`, then downloads and verifies those public assets before the workflow succeeds.

Live PlexonCraft migration, crash recovery, acquisition/provenance, cross-plugin, MSPT and soak certification is a deployment follow-up. Missing live evidence is recorded as `runtime_certification=NOT_EXECUTED`; it is not inferred from CI and does not block reproducible GitHub source/release closure.

Stable rollback baseline: `v1.4.1` at `c06a9e4107fc259f45d32d1f0767e5e2b5d1d872`. Historical RC2 evidence remains immutable at `v2.0.0-rc.2`, accepted source `f5f8656a4cc879e449a8b822386892a659d8e228`, JAR SHA-256 `0cc87449754b7e5dec34e57091f856f2bfa98a3c256eb41617b510911cbe0ee4`.

Detailed 2.0 design/history: [`docs/PlexonKeys_2.0.0_Phase2_Execution_Specification.md`](docs/PlexonKeys_2.0.0_Phase2_Execution_Specification.md)
