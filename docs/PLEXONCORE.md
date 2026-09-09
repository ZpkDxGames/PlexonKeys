# PlexonKeys 1.2.0 — PlexonCore Integration

PlexonKeys 1.2.0 adopts PlexonCore as shared ecosystem infrastructure without moving key gameplay or persistence into Core.

## Runtime modes

PlexonCore is an optional runtime dependency.

```text
PlexonCore present + API compatible  -> CORE
PlexonCore absent/incompatible       -> STANDALONE
```

Supported Core API range:

```text
>=1.0 <2.0
```

The plugin uses `softdepend: [PlexonCore, Vault]`. Core API classes are a Maven `provided` dependency and are not shaded into the PlexonKeys JAR.

The bridge is isolated under:

```text
com.antondev.keys.integration.core.CoreBridge
com.antondev.keys.integration.core.CoreBridgeFactory
com.antondev.keys.integration.core.PlexonCoreBridge
com.antondev.keys.integration.core.StandaloneCoreBridge
```

`CoreBridgeFactory` avoids eager Core classloading. If Core is absent, disabled, incompatible, or its Bukkit service cannot be linked, PlexonKeys keeps its key gameplay available in standalone compatibility mode.

## Ownership boundary

### PlexonCore owns

- module registration and lifecycle visibility;
- ecosystem health state;
- integration discovery/diagnostic infrastructure;
- shared provider hints.

### PlexonKeys owns

- Basic/Rare/Epic/Legendary balances;
- activity eligibility, cooldowns, chances, caps, and reward logic;
- physical key templates and exact item delivery;
- claim planning, debit, rollback, and reentrancy protection;
- `plexonkeys.db`, `SqliteStore`, `MemoryStore`, and `DataSaver`;
- the `/keys` and admin GUIs;
- configuration and text rendering;
- `PlexonKeysAPI`;
- `PlexonKeyEarnedEvent` and `PlexonKeyClaimedEvent`.

PlexonCore does not become a second database or key engine.

## Module identity

```text
module id: keys
display:   PlexonKeys
```

Current module capabilities:

```text
key-engine
virtual-keys
physical-key-claims
activity-key-rewards
key-api
key-earned-event
key-claimed-event
sqlite-persistence
custom-item-templates
vault-bonuses
```

After successful startup the module publishes `READY`. If cash bonuses are configured but no Vault economy provider is available, key gameplay remains operational and the module publishes `DEGRADED`. Critical startup failure publishes `FAILED` when Core is available before the plugin disables itself.

## PLEXON_KEYS integration state

After API/event initialization, PlexonKeys publishes `PLEXON_KEYS` with capabilities:

```text
key-api
key-earned-event
key-claimed-event
virtual-balances
physical-claims
```

A healthy Core-enabled server should show PlexonKeys as READY under `/plexon modules` and `PLEXON_KEYS` as READY/available in ecosystem diagnostics.

PlexonQuests 3.1.0 still performs its own public-event reflection validation. Core health is ecosystem metadata; the public event classes remain authoritative for key objectives.

## Lifecycle

Startup order is intentionally conservative:

```text
resolve Core
register STARTING
load existing config
load existing SQLite state
initialize saver/economy/balance domain
initialize rewards/claims/menus
register listeners and commands
register PlexonKeysAPI
configure checkpoint
publish READY/DEGRADED
```

Shutdown:

```text
cancel checkpoint
close menus
final save / close DataSaver
unregister Bukkit services
unregister Core module
```

Core is not queried in hot activity paths. There is no per-tick Core polling and no repeated ServicesManager lookup for each key award or claim.

## Reloads

`/keysadmin reload` retains the existing configuration reload behavior, rehooks optional economy state, replaces the checkpoint task rather than duplicating it, and republishes Core health.

`/plexon reload` must be validated on staging with the released Core 1.0.0 runtime. PlexonKeys deliberately does not add a polling loop solely to monitor Core.

## Diagnostics

Run:

```text
/keysadmin diagnostics
```

It reports the PlexonKeys version, Java/Paper version, Core plugin/API version, supported API range, CORE/STANDALONE mode, module state, SQLite state, player/tracked-block/dirty counts, checkpoint interval, Vault state, Bukkit API registration, and the two public event contracts.
