# PlexonKeys 1.2.0

Activity-driven virtual keys and safe physical key claiming for Paper 26.2, created and maintained by **Tonim (ZpkDxGames)** as part of the Plexon plugin family.

**Runtime:** Paper **26.2**, Java **25**  
**PlexonCore:** optional runtime integration with **PlexonCore 1.0.0 / Core API 1.x**  
**Storage:** `plugins/PlexonKeys/plexonkeys.db`  
**Key tiers:** Basic, Rare, Epic, Legendary

PlexonKeys awards virtual keys from eligible natural gameplay activity, stores balances in SQLite, and lets players safely claim complete physical key ItemStacks through `/keys`. Version 1.2.0 keeps the existing key engine intact while adding PlexonCore module visibility, a stable public Bukkit API, and native public events consumed by PlexonQuests 3.1.0.

## 1.2.0 migration goals

PlexonKeys 1.2.0 is deliberately incremental:

```text
PlexonCore adoption
+ stable public API/events
+ PlexonQuests interoperability
+ build/release hardening
```

It does **not** rebalance key chances, replace SQLite, regenerate configuration, redesign the GUI, or change captured physical keys.

## PlexonCore modes

`PlexonCore` is a soft dependency.

```text
PlexonCore present + compatible -> CORE
PlexonCore absent/incompatible  -> STANDALONE
```

Supported Core API range:

```text
>=1.0 <2.0
```

In Core mode, PlexonKeys registers module ID `keys`, publishes lifecycle health/capabilities, and exposes `PLEXON_KEYS` ecosystem integration state. In standalone mode, the key engine, SQLite storage, commands, public API, and public events remain available without Core classloading failures.

Core integration details: [`docs/PLEXONCORE.md`](docs/PLEXONCORE.md)

## Existing gameplay preserved

Players can continue to earn and claim:

- Basic, Rare, Epic, and Legendary virtual keys;
- mining rewards from eligible natural blocks;
- logging rewards;
- fishing rewards;
- mob-kill rewards;
- per-activity cooldowns;
- independent or highest-tier-only rolls;
- configurable virtual balance caps;
- XP bonuses;
- optional Vault money bonuses;
- chat/announcement/sound feedback.

Natural-block provenance tracking continues to prevent player-placed blocks from becoming renewable key-farming sources. Existing world filters, allowed game modes, permissions, material lists, spawn-reason rules, cooldowns, and probabilities remain configuration-driven.

## Physical key compatibility

PlexonKeys stores complete captured Paper `ItemStack` data for physical keys. It does not reduce custom/external keys to only material, name, and lore.

Use:

```text
/keysadmin setitem basic
/keysadmin setitem rare
/keysadmin setitem epic
/keysadmin setitem legendary
```

while holding the real key item supplied by the crate/plugin integration you use.

Captured item templates preserve supported Paper item metadata/data components. The external crate plugin remains responsible for recognizing and redeeming the resulting item.

## Safe claim model

Claims remain inventory-aware and transactional at the PlexonKeys domain level:

```text
calculate delivery plan
-> respect storage capacity
-> debit only deliverable virtual quantities
-> write physical ItemStacks
-> publish claim events after success
```

If the inventory write fails, PlexonKeys silently restores the virtual debit and attempts to restore the previous inventory contents. That restoration does not emit a false earned event.

Claim-all emits one successful public event for each tier actually delivered. All events from the same claim operation share one parent transaction ID while keeping unique per-tier event IDs.

## Public API

PlexonKeys 1.2.0 registers this Bukkit service:

```text
com.antondev.keys.api.PlexonKeysAPI
```

Core operations:

```java
long balance(UUID playerId, KeyTier tier);
Map<KeyTier, Long> balances(UUID playerId);
long grant(UUID playerId, KeyTier tier, long amount, KeySource source);
long take(UUID playerId, KeyTier tier, long amount, KeySource source);
Optional<ItemStack> keyTemplate(KeyTier tier);
boolean isTierEnabled(KeyTier tier);
```

The 1.2.x API requires the primary server thread. Balance-map results are immutable and physical template results are defensive copies.

Full API contract: [`docs/API.md`](docs/API.md)

## Public events

Exact public classes:

```text
com.antondev.keys.event.PlexonKeyEarnedEvent
com.antondev.keys.event.PlexonKeyClaimedEvent
```

### Earned event

`PlexonKeyEarnedEvent` is post-success. It fires only after the virtual balance actually increases and reports the actual credited amount after cap enforcement.

Built-in activity source IDs:

```text
activity:mining
activity:logging
activity:fishing
activity:mobs
```

It does not fire for rejected chance rolls, disabled tiers, permission rejection, cap rejection, zero credit, database loading, checkpoints, corrections, claim debit, or claim rollback restoration.

### Claimed event

`PlexonKeyClaimedEvent` is post-success. It fires only after the virtual debit and physical inventory write both commit.

Normal source:

```text
player-claim
```

For a claim-all transaction such as:

```text
Basic x3
Rare x2
Epic x1
```

PlexonKeys publishes three events with one shared transaction UUID and IDs equivalent to:

```text
<transaction>:basic
<transaction>:rare
<transaction>:epic
```

## PlexonQuests 3.1.0

PlexonQuests discovers the two public event classes directly. A healthy staged ecosystem should report:

```text
PLEXON_KEYS AVAILABLE
```

and support native progression for:

```text
PLEXON_KEY_EARN
PLEXON_KEY_CLAIM
```

No lore parsing or command scraping is required.

## Player commands

| Command | Purpose |
| --- | --- |
| `/keys` | Open the virtual key collection |
| `/keys claim <category>` | Claim keys from one tier |
| `/keys claim all` | Claim all deliverable tiers |
| `/plexonkeys` | Alias for `/keys` |

Players need `plexonkeys.use` to use the collection/claim system and `plexonkeys.earn` to earn activity keys.

## Admin commands

| Command | Purpose |
| --- | --- |
| `/keysadmin` | Open the admin GUI |
| `/keysadmin chances [category] [activity]` | Open chance-management GUI |
| `/keysadmin setitem <category>` | Capture the complete held physical key |
| `/keysadmin chance <category> <activity> <percent>` | Set a precise activity chance |
| `/keysadmin give <player|uuid> <category> <amount>` | Grant virtual keys up to the cap |
| `/keysadmin take <player|uuid> <category> <amount>` | Remove virtual keys |
| `/keysadmin setbalance <player|uuid> <category> <amount>` | Correct an exact balance |
| `/keysadmin balance <player|uuid>` | Inspect tier balances |
| `/keysadmin set <config.path> <value>` | Edit a supported config value |
| `/keysadmin reload` | Validate/reload configuration |
| `/keysadmin save` | Request a data checkpoint |
| `/keysadmin status` | Show compact runtime status |
| `/keysadmin diagnostics` | Show Core/API/storage/event diagnostics |

Administrative `give` uses `admin` acquisition metadata. `take` and `setbalance` are correction/removal operations and do not emit earned events.

## Diagnostics

Useful staging commands:

```text
/plexon modules
/plexon integrations
/plexon diagnostics
/quests diagnostics
/quests validate
/keys
/keysadmin diagnostics
```

Expected Core-enabled state after a successful staged migration:

```text
PlexonQuests — READY
PlexonRanks  — READY
PlexonKeys   — READY

PLEXON_KEYS AVAILABLE
```

If configured cash bonuses have no usable Vault economy provider, PlexonKeys may report `DEGRADED` while the core key gameplay remains operational.

## Storage and data safety

PlexonKeys continues to own:

```text
plugins/PlexonKeys/
└── plexonkeys.db
```

Runtime data continues through the existing `SqliteStore`, `MemoryStore`, and `DataSaver` design. Version 1.2.0 does not merge the database into PlexonCore and does not require a schema-reset migration.

For an upgrade from 1.1.0:

1. stop the server;
2. back up `plugins/PlexonKeys/`;
3. remove the old PlexonKeys JAR;
4. install `PlexonKeys-1.2.0.jar`;
5. keep `config.yml`, `plexonkeys.db`, and the rest of the data folder;
6. start the server and run the diagnostics above.

Full staging/rollback guide: [`docs/MIGRATION_1.2.0.md`](docs/MIGRATION_1.2.0.md)

## Configuration compatibility

The existing 1.1.0 configuration model and paths remain authoritative. Existing server administrators do not need to delete `config.yml` to use 1.2.0.

Key probabilities, cooldowns, economy rewards, XP rewards, world filters, tracking behavior, category enablement, permissions, GUI layout, messages, announcements, and physical templates remain owned by the existing configuration layer.

## Vault

Vault remains optional and is not replaced by PlexonCore. If cash bonuses are enabled, a compatible Vault economy provider is required for the money portion of the reward. Key acquisition and XP remain committed even when a configured cash deposit fails; uncertain external economy writes are not replayed automatically.

## Build

The 1.2.0 Maven build targets Java 25 and Paper 26.2. PlexonCore 1.0.0 is compile-time `provided` only.

CI:

1. downloads the official `PlexonCore-1.0.0.jar` release asset;
2. verifies its pinned SHA-256;
3. installs it to the CI-local Maven repository;
4. runs `mvn -B -ntp clean verify`;
5. verifies the installable JAR contains `plugin.yml`, the main class, SQLite JDBC, `PlexonKeysAPI`, and both key event classes;
6. rejects a distribution containing `com/zpkdxgames/plexoncore/` runtime classes;
7. generates and verifies `SHA256SUMS.txt`.

The release workflow runs only for version tags such as `v1.2.0`, rebuilds the exact tag, repeats distribution verification, and publishes the JAR plus checksum.

## Release validation

The automated suite covers the existing reward/config/storage/GUI behavior plus the 1.2.0 public API/event migration contract. Automated CI is not a replacement for a real Paper staging server with PlexonCore 1.0.0 and PlexonQuests 3.1.0.

Do not publish/deploy the production tag until the staging checklist in `docs/MIGRATION_1.2.0.md` passes.

## License / authorship

PlexonKeys is created and maintained by **Tonim (ZpkDxGames)** for the Plexon plugin family.
