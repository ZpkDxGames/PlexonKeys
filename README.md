# PlexonKeys 2.0.0-rc.1

Premium key identity, virtual balances, safe physical claims, acquisition provenance and first-party Plexon integrations for **Paper 26.2 / Java 25**.

> **Release status:** Phase 2 release candidate. PlexonCraft runtime certification has **not** been executed; stable `2.0.0` is intentionally unpublished.

## Runtime architecture

PlexonKeys keeps gameplay state memory-authoritative. SQLite is persistence, not an event-time lookup service. One bounded database worker persists revisioned/coalesced snapshots; high-frequency mining/logging/mob/fishing paths do not issue JDBC queries or create per-event tasks.

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

`/keys` opens the key collection. `/keys claim <basic|rare|epic|legendary|all>` converts available virtual balances to the configured exact physical `ItemStack` templates using inventory preflight, atomic virtual debit and rollback on delivery failure.

## Physical keys

Captured key templates preserve full Paper item metadata/data components. PlexonKeys never reconstructs a captured third-party key from material/name/lore. Phase 2 keeps 1.4.1 templates byte/component compatible with the frozen PlexonCrates 5.0 RC.

`PlexonKeysAPI.identifyPhysicalKey(ItemStack)` normalizes stack amount to one and uses full Bukkit item similarity; a similar-looking item with different metadata/components does not authenticate.

Capture real integration items with:

```text
/keysadmin setitem basic
/keysadmin setitem rare
/keysadmin setitem epic
/keysadmin setitem legendary
```

## Acquisition provenance

Natural/artificial block handling preserves the mature PlexonCore/local authority split. `UNKNOWN` is never treated as `NATURAL` by default.

Mob acquisition additionally classifies:

```text
NATURAL
PLEXON_SPAWNERS
EXTERNAL_SPAWNER
UNKNOWN
```

PlexonSpawners 3.x attribution is consumed through its optional Bukkit service API by reflection. The integration is soft, version-safe, and performs no database/history scan. Spawner and unknown origins fail closed unless explicitly enabled.

Optional advanced settings (absent keys safely default to `false`):

```yaml
integrations:
  spawners:
    allow-plexon-origin: false
    allow-external-spawner-origin: false
    allow-unknown-origin: false
  crates:
    mappings:
      basic: basic
      rare: rare
      epic: epic
      legendary: legendary
```

The crate mappings above are definition metadata only; PlexonCrates remains authoritative for crate opening/reward policy.

## Virtual balance API

The 1.x Bukkit service surface remains available:

```java
long balance(UUID playerId, KeyTier tier);
Map<KeyTier, Long> balances(UUID playerId);
long grant(UUID playerId, KeyTier tier, long amount, KeySource source);
long take(UUID playerId, KeyTier tier, long amount, KeySource source);
Optional<ItemStack> keyTemplate(KeyTier tier);
boolean isTierEnabled(KeyTier tier);
```

Phase 2 adds:

```java
Map<String, KeyDefinitionView> keyDefinitions();
Optional<KeyDefinitionView> resolveKeyDefinition(String keyId);
KeyConsumeResult consumeKey(UUID playerId, String keyId, long amount, String transactionId);
Optional<String> identifyPhysicalKey(ItemStack item);
```

`consumeKey` is atomic at the PlexonKeys authority boundary and uses a bounded 4096-entry process-local replay guard. A duplicate transaction ID cannot debit twice. Reusing an ID for a different request fails closed. Successful committed consumes publish `PlexonKeyConsumedEvent`.

Integrations needing crash-spanning reservation/refund semantics must keep their own durable opening transaction journal; PlexonKeys does not claim that a process-local replay cache is durable distributed transaction storage.

## PlaceholderAPI

PlaceholderAPI is optional/provided and never shaded. Values come only from the in-memory authoritative store/config:

```text
%plexonkeys_total%
%plexonkeys_basic%
%plexonkeys_balance_basic%
%plexonkeys_enabled_basic%
%plexonkeys_display_basic%
%plexonkeys_basic_display%
```

Replace `basic` with any stable key ID. Unknown placeholders return unresolved; a missing player balance resolves as zero. No placeholder synchronously queries SQLite.

## Administration

```text
/keysadmin                         admin GUI
/keysadmin definitions             authoritative key definitions
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

The dry-run evaluates configured activity/key rules, provenance, permission, game mode, world, current cap and current cooldown. It performs no RNG roll, cooldown write, balance mutation, event publication, persistence dirty, or physical delivery.

## Diagnostics

`/keysadmin diagnostics` includes the existing Core/reward/provenance/storage metrics plus Phase 2 definition IDs/count, config/database schema, exact physical identity mode, consume replay-guard pressure, PlexonSpawners status, PlaceholderAPI status and PlexonCrates presence. Diagnostics do not run global database/entity scans.

## Configuration and reload safety

Configuration remains schema `1`. Reload/update uses parse -> validate -> construct candidate -> atomic swap. A malformed candidate is rejected while the previous known-good runtime settings stay active. Active balances are not discarded.

## Persistence and migration from 1.4.1

SQLite remains schema `1`; no Phase 2 DDL migration is required. The existing `players` balances and placed/artificial block provenance remain directly readable.

Staging upgrade:

1. Stop the server normally.
2. Back up the current JAR and `plugins/PlexonKeys/` directory.
3. Replace the JAR with `PlexonKeys-2.0.0-rc.1.jar`.
4. Keep the existing `config.yml` and `plexonkeys.db`.
5. Start Paper 26.2 on Java 25.
6. Run `/keysadmin diagnostics` and `/keysadmin definitions`.
7. Complete the runtime matrix before considering stable promotion.

Rollback baseline is `v1.4.1` at `c06a9e4107fc259f45d32d1f0767e5e2b5d1d872`. Because this candidate does not advance config/database schema, rollback does not require schema downgrade.

## Build and release evidence

```bash
mvn clean verify
```

The exact candidate CI requires Java class major 69, all tests with zero skips, SQLite packaging, PlexonCore/PlaceholderAPI/PlexonSpawners/PlexonCrates non-shading, required API/event classes, whitespace checks and verified SHA-256. Candidate artifacts are:

```text
PlexonKeys-2.0.0-rc.1.jar
SHA256SUMS.txt
TEST_SUMMARY.txt
PROVENANCE.txt
```

## Stable runtime gates

Stable `2.0.0` is blocked until PlexonCraft verifies: representative 1.4.1 migration, load/reconnect, virtual balances, physical identity, grant/take/set/consume, natural/player-placed/unknown provenance, Core/local ownership, PlexonSpawners attribution, PlexonCrates consumption/duplicate protection, full-inventory claim, dry-run, restart persistence, transactional reload rollback, PAPI/API/events, cross-plugin integration, Spark/MSPT comparison, at least 30 minutes of soak, and zero HIGH/CRITICAL defects.

Detailed execution specification: [`docs/PlexonKeys_2.0.0_Phase2_Execution_Specification.md`](docs/PlexonKeys_2.0.0_Phase2_Execution_Specification.md)
