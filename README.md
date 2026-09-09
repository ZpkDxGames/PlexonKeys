# PlexonKeys 1.3.0

Activity-driven virtual keys and safe physical key claiming for Paper 26.2, created and maintained by **Tonim (ZpkDxGames)** as part of the Plexon plugin family.

**Runtime:** Paper **26.2**, Java **25**  
**PlexonCore:** optional runtime integration with **PlexonCore 1.0.0 / Core API 1.x**  
**Storage:** `plugins/PlexonKeys/plexonkeys.db`  
**Key tiers:** Basic, Rare, Epic, Legendary

PlexonKeys awards virtual keys from eligible natural gameplay activity, stores exact balances/provenance in SQLite, and lets players claim complete physical key `ItemStack`s through `/keys`.

Version **1.3.0** is a performance/reliability release. It keeps the 1.2.0 public API/events, existing database, configuration model, probabilities, anti-abuse rules, and physical key identity while reducing work on high-volume activity, provenance, save, claim, and GUI paths.

## 1.3.0 focus

```text
single-pass reward eligibility
+ precomputed reward runtime
+ batched provenance mutations
+ atomic balance mutations
+ bounded/coalesced SQLite saves
+ bounded dirty snapshots
+ lower claim allocation pressure
+ coalesced open-menu refreshes
+ richer storage diagnostics
```

This release intentionally does **not** rebalance gameplay or reset data.

## Performance architecture

### Activity rewards

High-frequency listeners return before reward work whenever event-local requirements fail. The authoritative reward service then performs permission/world/game-mode/activity eligibility once, rather than repeating it in both the listener and reward service.

Configuration-derived roll data is prepared per configuration revision:

- enabled activity state;
- cooldown duration in nanoseconds;
- enabled tier candidates in highest-first order;
- fixed-precision chance thresholds;
- category display components;
- notification/sound settings.

A reward credit replaces one authoritative account record once and returns its committed post-credit balance, avoiding a second synchronized balance lookup for message rendering.

### Artificial-block provenance

Player-placed/artificial provenance remains authoritative. Multi-place, growth/fertilization, piston, explosion, and other grouped changes use batch store operations rather than repeating bookkeeping boundaries for every position.

Piston movement snapshots source provenance before mutation so adjacent moved blocks cannot overwrite one another's provenance state.

No world scans and no per-block SQLite writes are introduced.

### Persistence

PlexonKeys keeps one SQLite worker. Save requests are revision-coalesced, and executor queue growth is bounded: repeated checkpoints extend the requested revision instead of creating an arbitrary queue of database tasks.

Dirty state is handed to SQLite in bounded immutable snapshots. Exact version acknowledgement means:

- mutations newer than a snapshot remain dirty;
- a failed save remains dirty and retryable;
- a database slowdown cannot grow an unbounded executor queue;
- JDBC remains off the gameplay event thread.

New-install defaults:

```yaml
storage:
  checkpoint-seconds: 60
  pressure-dirty-threshold: 2048
  maximum-snapshot-records: 4096
  shutdown-timeout-seconds: 15

performance:
  menu-refresh-ticks: 2
```

Existing `config.yml` files are not overwritten. In particular, an existing explicit `checkpoint-seconds: 0` remains shutdown-only unless an administrator changes it.

`pressure-dirty-threshold` requests an early **coalesced** save after large provenance bursts. It does not execute JDBC from the event listener.

### Claims

Claims preserve rollback safety while reducing allocation:

```text
read balances once
-> plan stack delivery with copy-on-write inventory slots
-> atomically debit all delivered tiers in one account mutation
-> write physical ItemStacks
-> publish claim events only after success
```

If inventory delivery throws after debit, all debited tiers are restored in one authoritative account mutation and the previous inventory contents are restored where possible.

## PlexonCore modes

`PlexonCore` remains a soft dependency.

```text
PlexonCore present + compatible -> CORE
PlexonCore absent/incompatible  -> STANDALONE
```

Supported Core API range:

```text
>=1.0 <2.0
```

In Core mode, PlexonKeys registers module ID `keys` and publishes lifecycle/integration health. In standalone mode, the key engine, SQLite storage, commands, public API, and public events remain available.

Core integration details: [`docs/PLEXONCORE.md`](docs/PLEXONCORE.md)

## Existing gameplay preserved

Players continue to receive configurable rewards from:

- mining natural eligible blocks;
- logging;
- fishing;
- mob kills;
- independent or highest-tier-only rolls;
- per-activity cooldowns;
- category permissions;
- world/game-mode restrictions;
- XP and optional Vault money bonuses.

Natural/artificial block tracking, preferred-tool/drop requirements, material filters, spawn-reason filters, balance caps, and exact virtual balances remain enforced.

## Physical key compatibility

PlexonKeys stores complete captured Paper `ItemStack` data for physical keys. It does not reduce third-party keys to material/name/lore matching.

Use:

```text
/keysadmin setitem basic
/keysadmin setitem rare
/keysadmin setitem epic
/keysadmin setitem legendary
```

while holding the real key item supplied by the crate integration.

Captured item templates preserve supported Paper item metadata/data components. `PlexonKeysAPI.keyTemplate(...)` returns a defensive copy.

## Public API

The stable Bukkit service remains:

```text
com.antondev.keys.api.PlexonKeysAPI
```

The **1.2.x method surface is preserved in 1.3.x**:

```java
long balance(UUID playerId, KeyTier tier);
Map<KeyTier, Long> balances(UUID playerId);
long grant(UUID playerId, KeyTier tier, long amount, KeySource source);
long take(UUID playerId, KeyTier tier, long amount, KeySource source);
Optional<ItemStack> keyTemplate(KeyTier tier);
boolean isTierEnabled(KeyTier tier);
```

The 1.3.x API requires the primary server thread. Balance-map results are immutable and template results are defensive copies.

Full contract: [`docs/API.md`](docs/API.md)

## Public events

Stable exact classes:

```text
com.antondev.keys.event.PlexonKeyEarnedEvent
com.antondev.keys.event.PlexonKeyClaimedEvent
```

`PlexonKeyEarnedEvent` fires only after a positive committed balance increase and reports the actual credited amount after cap enforcement.

Built-in sources:

```text
activity:mining
activity:logging
activity:fishing
activity:mobs
```

`PlexonKeyClaimedEvent` fires only after both virtual debit and physical delivery commit. Normal claims use source `player-claim`. Claim-all preserves one event per delivered tier, one parent transaction ID, and unique per-tier event IDs.

This contract remains compatible with PlexonQuests' event integration and PlexonCrates' public service/template integration.

## Player commands

| Command | Purpose |
| --- | --- |
| `/keys` | Open the virtual key collection |
| `/keys claim <category>` | Claim keys from one tier |
| `/keys claim all` | Claim all deliverable tiers |
| `/plexonkeys` | Alias for `/keys` |

Players need `plexonkeys.use` for collection/claim actions and `plexonkeys.earn` for activity earning.

## Admin commands

| Command | Purpose |
| --- | --- |
| `/keysadmin` | Open the admin GUI |
| `/keysadmin chances [category] [activity]` | Open chance-management GUI |
| `/keysadmin setitem <category>` | Capture the complete held physical key |
| `/keysadmin chance <category> <activity> <percent>` | Set an exact activity chance |
| `/keysadmin give <player|uuid> <category> <amount>` | Grant virtual keys up to the cap |
| `/keysadmin take <player|uuid> <category> <amount>` | Remove virtual keys |
| `/keysadmin setbalance <player|uuid> <category> <amount>` | Correct an exact balance |
| `/keysadmin balance <player|uuid>` | Inspect tier balances |
| `/keysadmin set <config.path> <value>` | Edit a supported config value |
| `/keysadmin reload` | Validate/reload configuration |
| `/keysadmin save` | Request a data checkpoint |
| `/keysadmin status` | Show compact runtime status |
| `/keysadmin diagnostics` | Show Core/API/storage/event diagnostics |

## Diagnostics

`/keysadmin diagnostics` includes:

- plugin/Paper/Java version;
- cached players and tracked artificial positions;
- dirty account and block counts;
- checkpoint interval;
- database save in-flight/requested state;
- requested and acknowledged revisions;
- last snapshot, database, and total-save timing;
- last saved account/block row counts;
- save failure count;
- Vault/Core/API state.

This is intended to make obvious persistence pressure visible without requiring continuous file logging.

## Upgrade from 1.2.0

1. Stop the server normally.
2. Back up `plugins/PlexonKeys/`.
3. Replace the old JAR with `PlexonKeys-1.3.0.jar`.
4. Keep the existing `config.yml` and `plexonkeys.db`.
5. Start Paper 26.2 on Java 25.
6. Run `/keysadmin diagnostics`.
7. Verify PlexonCrates/PlexonQuests staging behavior.
8. Run the Spark/stress scenarios before production rollout.

No database reset or configuration deletion is required.

## Build and distribution

Run:

```bash
mvn clean verify
```

Expected artifact:

```text
PlexonKeys-1.3.0.jar
SHA256SUMS.txt
```

CI provisions the pinned PlexonCore 1.0.0 API, runs the Java 25 Maven verification suite, verifies required runtime/API/event classes inside the shaded JAR, rejects accidentally shaded PlexonCore runtime classes, asserts the exact 1.3.0 filename, and generates/verifies SHA-256.

The tag workflow additionally requires the Git tag to equal the Maven project version before it can publish the JAR/checksum.

## Stable-release validation

Automated CI cannot substitute for a real Paper workload. Before creating/publishing `v1.3.0`, run at minimum:

- baseline and post-change Spark profiles;
- idle profile;
- 10-player rapid natural mining;
- player-placed block abuse workload;
- piston provenance stress;
- large explosions;
- mob-farm and fishing throughput;
- concurrent/bulk claim bursts;
- checkpoint pressure after thousands of provenance mutations;
- PlexonCrates/PlexonQuests staging;
- a mixed 30-minute soak with heap/dirty/save-worker observation.

Do not publish the stable tag merely because unit/CI verification passes.

## License / authorship

PlexonKeys is created and maintained by **Tonim (ZpkDxGames)** for the Plexon plugin family.
