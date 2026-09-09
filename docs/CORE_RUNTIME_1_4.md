# PlexonKeys 1.4.0 — PlexonCore Runtime

## Runtime modes

PlexonKeys selects block acquisition with `core-runtime.mode`:

- `AUTO` — use Core API 2 block outcomes when available; otherwise use the local 1.3-compatible listener.
- `CORE` — require the Core Runtime block route; startup/reload fails clearly if it cannot be established.
- `LOCAL` — force the local BlockBreak path for rollback or A/B profiling.

Missing configuration defaults to `AUTO`. `core-runtime.activities.blocks` defaults to `true`.

Diagnostics report `CORE_RUNTIME`, `CORE_LEGACY`, `LOCAL`, or `STANDALONE`, the runtime epoch, and ownership for MINING/LOGGING/FISHING/MOBS.

## Activity ownership

In 1.4.0:

- MINING: Core in Runtime mode, local otherwise.
- LOGGING: Core in Runtime mode, local otherwise.
- FISHING: local; Core 2 has no exact compatible fishing outcome.
- MOBS: local; Core 2 has no exact compatible death/spawn-reason outcome.

Only one PlexonKeys block reward source is active at a time. Reload closes the previous Core subscription or unregisters the previous local block listener before installing the new owner.

## Block route

The Core subscription is module-wide, never per-player. It is precomputed from enabled mining/logging activities and configured material sets. Empty mining material configuration preserves the 1.3 wildcard behavior. Empty logging material configuration uses `Tag.LOGS`.

## Final outcome

PlexonCore 2.0.2 is required for the Core-backed path. It captures event-time origin before provenance cleanup and dispatches subscribers at final `MONITOR`, after cancellation and the final `dropItems` state are known.

PlexonKeys resolves preferred-tool state only after origin and material classification pass, and only when `require-drops` is enabled.

## Provenance

Core owns ordinary placement origin. PlexonKeys retains a policy overlay for legacy persisted positions and semantics Core does not model exactly: multi-place completeness, falling blocks, Enderman movement, growth, formation, spread and leaves decay.

Reward origin gate:

```text
Keys overlay artificial -> reject
Core PLAYER_PLACED      -> reject
Core UNKNOWN            -> reject
Core NATURAL            -> continue to activity/drop/reward rules
```

## Threading

Core block callbacks remain on the primary thread. PlexonKeys does not fan out one asynchronous task per block and does not move `RewardService.tryPerform` off-thread.

## Reload and rollback

`/keysadmin reload` rebuilds the activity route and increments the runtime epoch. `core-runtime.mode: LOCAL` is the first rollback/A-B switch. PlexonKeys 1.3.0 remains the binary rollback release; balances and legacy provenance storage are not destructively migrated by 1.4.0.
