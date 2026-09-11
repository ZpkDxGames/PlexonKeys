# PlexonKeys 2.0 Public API

The Bukkit service remains `com.antondev.keys.api.PlexonKeysAPI`. All mutation calls must run on the primary server thread. Returned maps are immutable and returned physical templates are defensive copies.

## Compatibility

The complete 1.x service surface is preserved for PlexonCrates and other existing integrations:

```java
long balance(UUID playerId, KeyTier tier);
Map<KeyTier, Long> balances(UUID playerId);
long grant(UUID playerId, KeyTier tier, long amount, KeySource source);
long take(UUID playerId, KeyTier tier, long amount, KeySource source);
Optional<ItemStack> keyTemplate(KeyTier tier);
boolean isTierEnabled(KeyTier tier);
```

Stable tier IDs are `basic`, `rare`, `epic`, `legendary`. Display text is never identity.

## 2.0 definitions

```java
Map<String, KeyDefinitionView> keyDefinitions();
Optional<KeyDefinitionView> resolveKeyDefinition(String keyId);
```

A `KeyDefinitionView` is immutable and includes stable ID, display text, permission, enabled/visible/claimable state, physical mode, crate mapping metadata and immutable acquisition chance values.

## Crash-durable exact-once virtual consume

```java
KeyConsumeResult consumeKey(UUID playerId, String keyId, long amount, String transactionId);
```

The operation is atomic at the PlexonKeys authority boundary and is backed by database-schema-2 persisted idempotency. Result statuses are:

- `SUCCESS`: the full amount was debited exactly once and the debit/replay record crossed the critical persistence barrier;
- `INSUFFICIENT`: nothing was debited;
- `DUPLICATE`: the exact transaction ID/request was already processed and nothing is debited again.

A transaction ID reused with a different player/key/amount fails closed with `IllegalArgumentException`. Successful balance debit and its replay record share one memory revision and are committed in the same SQLite transaction before `SUCCESS` is returned. Exact duplicate retries therefore cannot debit twice after restart.

Persisted replay retention is bounded to 4,096 records and seven days; unsaved overflow is also bounded so a failed database cannot create an unbounded in-memory replay cache. Consumers that need a larger cross-system reservation/refund state machine may still keep their own durable opening journal, but PlexonKeys itself now provides crash-spanning consume idempotency for the authoritative key debit.

Successful consumes publish the synchronous, non-cancellable, post-commit `com.antondev.keys.event.PlexonKeyConsumedEvent` containing player UUID, tier, amount and transaction ID. Listener exceptions cannot roll back or duplicate an already committed debit.

## Physical identity

```java
Optional<String> identifyPhysicalKey(ItemStack item);
```

The candidate amount is normalized to one and compared against configured/captured templates with full Bukkit metadata/component equality (`ItemStack.isSimilar`). Name/lore/material alone do not authenticate. Captured third-party PDC/components are preserved and remain part of exact identity.

PlexonKeys 2.0 does not inject new PlexonKeys PDC into established third-party/frozen templates because PlexonCrates consumes the exact physical-template contract.

## Existing earned and claimed events

`PlexonKeyEarnedEvent` remains synchronous/post-success and fires only after positive balance credit. `PlexonKeyClaimedEvent` remains synchronous/post-success and fires only after the virtual debit has crossed the durability barrier and physical delivery commits. Failed delivery compensates the virtual debit without publishing a false claimed event.

## Acquisition provenance

Live block provenance remains owned by the PlexonCore/local block-origin path. Mob source classification can use PlexonSpawners through its optional service API; PlexonKeys does not expose mutable Spawners or persistence internals. `UNKNOWN` is conservatively ineligible unless explicitly opted in.

## PlaceholderAPI

PlaceholderAPI is a separate optional integration. Its expansion only reads authoritative in-memory balances/config and does not call SQLite. Placeholder evaluation is not an alternative persistence/API mutation path.
