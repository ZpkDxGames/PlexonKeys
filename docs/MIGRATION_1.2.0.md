# Migrating PlexonKeys 1.1.0 to 1.2.0

PlexonKeys 1.2.0 is an incremental PlexonCore/API migration. It does not replace the existing key engine, database, configuration model, GUI, or physical key format.

## Before upgrading

Use a staging server first with:

```text
PlexonCore-1.0.0.jar
PlexonQuests-3.1.0.jar
PlexonRanks-2.1.0.jar
PlexonKeys-1.2.0.jar
```

Keep copies of the production plugin data folders so the staging test uses representative balances, tracked blocks, configuration, and captured key templates.

## Server upgrade

1. Stop the server normally.
2. Back up `plugins/PlexonKeys/`, especially `config.yml` and `plexonkeys.db`.
3. Keep the existing PlexonCore 1.0.0, PlexonQuests 3.1.0, and PlexonRanks 2.1.0 JARs.
4. Remove `PlexonKeys-1.1.0.jar`.
5. Add `PlexonKeys-1.2.0.jar`.
6. Do **not** delete or regenerate `plugins/PlexonKeys/`.
7. Start Paper 26.2 on Java 25.

No database reset or required configuration regeneration is part of this migration.

## First-start validation

Run:

```text
/plexon modules
/plexon integrations
/plexon diagnostics
/quests diagnostics
/quests validate
/keys
/keysadmin diagnostics
```

Expected Core state:

```text
PlexonQuests — READY
PlexonRanks  — READY
PlexonKeys   — READY
```

Expected Quests integration:

```text
PLEXON_KEYS AVAILABLE
```

If cash bonuses are configured without a working Vault economy provider, PlexonKeys may correctly report `DEGRADED` while key gameplay remains functional.

## Earn test

On staging, temporarily make one safe earning path deterministic or sufficiently likely, then perform one eligible activity.

Verify:

- the virtual balance increases by the actual amount;
- the normal PlexonKeys feedback/bonus path still runs;
- one `PlexonKeyEarnedEvent` is produced for each credited tier;
- the event source is the matching `activity:*` ID;
- a matching `PLEXON_KEY_EARN` objective progresses exactly once;
- no duplicate progress occurs.

Restore the production chance configuration afterward.

## Claim test

Give a staging player a virtual Basic key and claim it.

Verify:

- the virtual balance decreases;
- the exact configured physical key appears;
- one `PlexonKeyClaimedEvent` is produced for the delivered tier;
- the event source is `player-claim`;
- the matching `PLEXON_KEY_CLAIM` objective progresses exactly once.

For claim-all, test multiple tiers. Each delivered tier must have one event, all tier events must share one parent transaction ID, and each event ID must be unique.

## Persistence/restart test

Restart the staging server at least twice and verify:

- balances persist unchanged;
- tracked-block state persists unchanged;
- captured physical key templates remain exact;
- `PlexonKeysAPI` registers after every boot;
- Core lists PlexonKeys once;
- Quests keeps `PLEXON_KEYS AVAILABLE`;
- no duplicate listeners, checkpoint tasks, events, or physical keys appear.

## Core-absent test

On a separate staging copy, remove PlexonCore while keeping PlexonKeys 1.2.0.

Expected behavior:

- PlexonKeys starts in `STANDALONE` mode;
- earning, balances, claims, SQLite, commands, API, and events continue to work;
- no `NoClassDefFoundError`, `LinkageError`, or server crash occurs.

## Reload tests

Run `/plexon reload` on the Core-enabled staging composition and verify PlexonKeys remains functional and correctly represented according to Core 1.0.0 reload semantics.

Then run `/keysadmin reload` and verify configuration reload, economy rehook, one checkpoint task, one listener set, valid Core registration, and `PLEXON_KEYS AVAILABLE`.

## Rollback

Before production deployment keep:

```text
PlexonKeys-1.1.0.jar
backup of plugins/PlexonKeys/
```

If rollback is required:

1. stop the server;
2. restore the 1.1.0 JAR;
3. restore the data-folder backup only if actual corruption occurred;
4. start the server and validate balances/claims.

PlexonKeys 1.2.0 intentionally avoids an irreversible database migration so a JAR rollback remains practical.
