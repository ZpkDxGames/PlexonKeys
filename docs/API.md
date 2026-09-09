# PlexonKeys 1.3.0 Public API

PlexonKeys 1.3.0 preserves the stable Bukkit service API and post-success Bukkit events introduced in 1.2.0. Integrations do not need access to `MemoryStore`, SQLite internals, GUI configuration, or persistence implementation details.

## Compatibility

The **1.2.x method and event surface is intentionally unchanged in 1.3.x**. Existing PlexonCrates/PlexonQuests integrations can continue using the same class names and accessors.

## Lookup

```java
RegisteredServiceProvider<PlexonKeysAPI> registration =
        Bukkit.getServicesManager().getRegistration(PlexonKeysAPI.class);
if (registration == null) {
    // PlexonKeys is not enabled or its API did not finish registering.
    return;
}
PlexonKeysAPI keys = registration.getProvider();
```

Package:

```text
com.antondev.keys.api.PlexonKeysAPI
```

## Threading contract

All `PlexonKeysAPI` methods in the 1.3.x line must be called on the primary server thread. Calls from another thread fail with `IllegalStateException`.

This keeps Bukkit inventory/item access, balance mutation, and public event delivery deterministic. Plugins doing asynchronous work should schedule the PlexonKeys API call back onto the primary thread.

## API surface

```java
long balance(UUID playerId, KeyTier tier);
Map<KeyTier, Long> balances(UUID playerId);
long grant(UUID playerId, KeyTier tier, long amount, KeySource source);
long take(UUID playerId, KeyTier tier, long amount, KeySource source);
Optional<ItemStack> keyTemplate(KeyTier tier);
boolean isTierEnabled(KeyTier tier);
```

`balances` returns an immutable map. `keyTemplate` returns a defensive `ItemStack` copy preserving the configured/captured Paper item data.

`grant` enforces the configured per-tier virtual balance cap and returns the amount actually credited. To preserve the post-success earned-event contract, `grant` requires the target player to be online; an offline target is rejected before mutation.

`take` never lowers a balance below zero and returns the amount actually removed. Invalid or non-positive mutation amounts are rejected.

1.3.0 internally performs balance mutations in a single authoritative account pass where possible; this is an implementation optimization and does not change public semantics.

## KeySource

```text
ACTIVITY
ADMIN
API
VOTE
QUEST
CRATE
DAILY_REWARD
OTHER
```

Machine-readable IDs are lower-case. Built-in activity rewards use:

```text
activity:mining
activity:logging
activity:fishing
activity:mobs
```

## PlexonKeyEarnedEvent

Exact class:

```text
com.antondev.keys.event.PlexonKeyEarnedEvent
```

This is a non-cancellable, synchronous, post-success event. It fires only after the virtual balance actually increases. `amount` is the actual credited amount after cap enforcement.

Compatibility accessors include:

```text
getPlayer() / player()
category() / getCategory()
tier() / getTier()
amount() / getAmount()
source() / getSource()
eventId() / getEventId()
transactionId()
```

`category()` returns the lower-case tier ID such as `basic`. `tier()` returns `KeyTier`.

The event does not fire for failed chance rolls, disabled/permission-rejected tiers, zero credit, cap rejection, database load, checkpoints, take/set corrections, claim debit, or silent claim rollback restoration.

## PlexonKeyClaimedEvent

Exact class:

```text
com.antondev.keys.event.PlexonKeyClaimedEvent
```

This is a non-cancellable, synchronous, post-success event. It fires only after both the virtual debit and physical inventory delivery have committed.

Compatibility accessors include:

```text
getPlayer() / player()
category() / getCategory()
tier() / getTier()
amount() / getAmount()
source() / getSource()
eventId() / getEventId()
transactionId()
```

Normal player claims use:

```text
source = player-claim
```

## Claim-all IDs

One claim operation creates one parent UUID transaction ID. Each delivered tier receives its own unique event ID:

```text
<transactionId>:basic
<transactionId>:rare
<transactionId>:epic
<transactionId>:legendary
```

If Basic x3, Rare x2, and Epic x1 are delivered in one operation, PlexonKeys fires three claimed events with the same parent transaction ID and unique per-tier event IDs. Amounts always match quantities actually delivered.

## Event failure isolation

Earned events are published only after balance commit. Claimed events are published only after inventory delivery commit. If another plugin listener throws, PlexonKeys logs the listener failure but does not undo committed state, duplicate physical keys, or restore already-delivered balances.

## Physical template identity

`keyTemplate(KeyTier)` exposes the authoritative configured/captured template as a defensive copy. PlexonKeys does not intentionally reduce an external key to a material/name/lore triple. Captured Paper item metadata/data components remain part of the template.

This is the supported integration path for PlexonCrates-style consumers that need an exact physical key template. Consumers should not parse PlexonKeys GUI/config internals.

## PlexonQuests integration

PlexonQuests can discover the two event classes reflectively. A successful Basic mining acquisition is exposed approximately as:

```text
key.category = basic
key.source   = activity:mining
amount       = 1
```

A successful physical claim is exposed approximately as:

```text
key.category = basic
key.source   = player-claim
amount       = actual delivered count
```

The non-empty event ID is suitable as the integration deduplication token. Event allocation/dispatch occurs only for authoritative successful mutations; failed awards and failed claims do not emit progress events.
