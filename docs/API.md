# PlexonKeys 1.2.0 Public API

PlexonKeys 1.2.0 exposes a stable Bukkit service API and post-success Bukkit events without exposing `MemoryStore` or SQLite internals.

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

All `PlexonKeysAPI` methods in the 1.2.x line must be called on the primary server thread. Calls from another thread fail with `IllegalStateException`.

This rule keeps Bukkit inventory/item access and public event delivery deterministic. Plugins doing asynchronous work should schedule the PlexonKeys API call back onto the primary thread.

## API surface

```java
long balance(UUID playerId, KeyTier tier);
Map<KeyTier, Long> balances(UUID playerId);
long grant(UUID playerId, KeyTier tier, long amount, KeySource source);
long take(UUID playerId, KeyTier tier, long amount, KeySource source);
Optional<ItemStack> keyTemplate(KeyTier tier);
boolean isTierEnabled(KeyTier tier);
```

`balances` returns an immutable map. `keyTemplate` returns a defensive `ItemStack` copy.

`grant` enforces the configured per-tier virtual balance cap and returns the amount actually credited. To preserve the post-success earned-event contract, `grant` requires the target player to be online; an offline target is rejected before mutation. `take` can operate on a known UUID, never lowers a balance below zero, and returns the amount actually removed. Invalid or non-positive mutation amounts are rejected.

Balance reads remain UUID-based. Normal activity earning and claiming are online by definition and always publish their successful event contracts. Administrative corrections may still operate on known offline accounts because `take`/`setbalance` do not represent earning events.

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

The machine-readable IDs are lower-case. Built-in activity rewards use the more specific source strings:

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

This is a non-cancellable, post-success event. It fires only after the virtual balance actually increases. `amount` is the actual credited amount after cap enforcement.

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

This is a non-cancellable, post-success event. It fires only after the virtual debit and physical inventory delivery have both committed.

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

If Basic x3, Rare x2 and Epic x1 are delivered in one operation, PlexonKeys fires three claimed events with the same parent transaction ID and unique per-tier event IDs. Amounts always match the quantities actually delivered.

## Event failure isolation

Earned events are published only after the balance commit. Claimed events are published only after the inventory delivery commit. If another plugin's listener throws, PlexonKeys logs the listener failure but does not undo committed state, duplicate keys, or restore already-delivered balances.

## PlexonQuests 3.1.0

PlexonQuests discovers the two event classes reflectively. A successful Basic mining acquisition reaches Quests approximately as:

```text
key.category = basic
key.source   = activity:mining
amount       = 1
```

A successful physical claim reaches Quests approximately as:

```text
key.category = basic
key.source   = player-claim
amount       = actual delivered count
```

Quests uses the non-empty event ID as its durable integration deduplication token.
