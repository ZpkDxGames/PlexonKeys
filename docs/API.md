# PlexonKeys 2.0 Public API

The Bukkit service remains `com.antondev.keys.api.PlexonKeysAPI`. All calls must run on the primary server thread. Returned maps are immutable and returned physical templates are defensive copies.

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

## Phase 2 definitions

```java
Map<String, KeyDefinitionView> keyDefinitions();
Optional<KeyDefinitionView> resolveKeyDefinition(String keyId);
```

A `KeyDefinitionView` is immutable and includes stable ID, display text, permission, enabled/visible/claimable state, physical mode, crate mapping metadata and immutable acquisition chance values.

## Exact-once virtual consume

```java
KeyConsumeResult consumeKey(UUID playerId, String keyId, long amount, String transactionId);
```

The operation is atomic at the PlexonKeys in-memory authority boundary. Result statuses are:

- `SUCCESS`: the full amount was debited exactly once;
- `INSUFFICIENT`: nothing was debited;
- `DUPLICATE`: this transaction ID was already processed and nothing is debited again.

A transaction ID reused with a different player/key/amount fails closed with `IllegalArgumentException`. The replay guard is bounded to 4096 process-local entries. This is sufficient to prevent normal duplicate/retry consumption inside one server process; a crate engine that requires crash-spanning reservation/refund guarantees must keep its own durable opening transaction journal.

Successful consumes publish the synchronous, non-cancellable, post-commit `com.antondev.keys.event.PlexonKeyConsumedEvent` containing player UUID, tier, amount and transaction ID. Listener exceptions cannot roll back or duplicate an already committed debit.

## Physical identity

```java
Optional<String> identifyPhysicalKey(ItemStack item);
```

The candidate amount is normalized to one and compared against configured/captured templates with full Bukkit metadata/component equality (`ItemStack.isSimilar`). Name/lore/material alone do not authenticate. Captured third-party PDC/components are preserved and remain part of exact identity.

The 2.0 RC deliberately does not inject new PlexonKeys PDC into legacy/frozen templates because PlexonCrates 5.0 RC consumes the existing exact template contract.

## Existing earned and claimed events

`PlexonKeyEarnedEvent` remains synchronous/post-success and fires only after positive balance credit. `PlexonKeyClaimedEvent` remains synchronous/post-success and fires only after virtual debit plus physical delivery commit. Their existing class names/accessors remain compatible.

## Acquisition provenance

Live block provenance remains owned by the existing PlexonCore/local block-origin path. Mob source classification can use PlexonSpawners through its optional service API; PlexonKeys does not expose mutable Spawners or persistence internals. `UNKNOWN` is conservatively ineligible unless explicitly opted in.

## PlaceholderAPI

PlaceholderAPI is a separate optional integration. Its expansion only reads authoritative in-memory balances/config; it does not call SQLite. This means placeholder evaluation is not an alternative persistence/API mutation path.
