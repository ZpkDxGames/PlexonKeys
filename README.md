# PlexonKeys 1.1.0

Activity rewards and virtual key collection, created and maintained by **[Tonim (ZpkDxGames)](https://github.com/ZpkDxGames)** as part of the **Plexon** plugin family.

**Target:** Paper **26.2**, Java **25**. Uses Paper's Adventure/MiniMessage API throughout. Not a Spigot, Folia, or legacy Minecraft build.

Players earn **Basic**, **Rare**, **Epic**, and **Legendary** virtual keys from natural mining, woodcutting, successful fishing, and player mob kills. `/keys` opens a collection menu with a claim panel directly beneath each category and a separate **Claim all** button.

## Downloads

| Version | Release | Plugin download |
| --- | --- | --- |
| **1.1.0 — latest** | [Chance editor release](https://github.com/ZpkDxGames/PlexonKeys/releases/tag/v1.1.0) | [PlexonKeys-1.1.0.jar](https://github.com/ZpkDxGames/PlexonKeys/releases/download/v1.1.0/PlexonKeys-1.1.0.jar) |
| 1.0.0 | [Initial release](https://github.com/ZpkDxGames/PlexonKeys/releases/tag/v1.0.0) | [PlexonKeys-1.0.0.jar](https://github.com/ZpkDxGames/PlexonKeys/releases/download/v1.0.0/PlexonKeys-1.0.0.jar) |

Download the `.jar` asset, not GitHub's source-code ZIP. Each release includes `SHA256SUMS.txt` to verify the download. Both versions require Paper 26.2 and Java 25; install only one PlexonKeys JAR at a time.

## Install and bind your keys

1. Stop your server normally. Put `PlexonKeys-1.1.0.jar` in `plugins/` and start Paper 26.2 on Java 25. When upgrading, remove the old plugin jar; keep `config.yml` and `plexonkeys.db`.
2. As an operator, run `/keysadmin`. Toggle **Reward drops** off while setting up your real keys.
3. Obtain your **actual crate key** from the plugin that owns the crate. Hold it in your **main hand**, then run:

   ```text
   /keysadmin setitem basic
   /keysadmin setitem rare
   /keysadmin setitem epic
   /keysadmin setitem legendary
   ```

   Hold the matching item before each command. The category editor's **Capture held key** button does the same thing. Nothing is consumed.
4. Configure world filters, chances, announcements, and optional bonuses in `/keysadmin` or `plugins/PlexonKeys/config.yml`. After a file edit, use `/keysadmin reload`.
5. Turn **Reward drops** back on and test with `/keysadmin give YourName basic 1`, then `/keys`.

> The bundled `CONFIG` items are standalone sample keys. They are **not pre-bound to another crate plugin**. PlexonKeys distributes items; the external crate plugin remains responsible for recognizing and redeeming them. It does not create physical crates, loot tables, or a second crate-opening system.

Capture uses `ItemStack.serializeAsBytes()` and restores with `ItemStack.deserializeBytes()`. Paper's full binary item representation preserves display names, lore, enchantments, attributes, custom model components, PDC, custom data/NBT, and other supported item data. **The stack amount alone is normalized to one**: one virtual key always delivers one item. No PlexonKeys tag is added to a captured key. Menu cosmetics never modify the delivered item. If an external plugin uses non-item server-side state or one-use serial numbers, copying its item cannot manufacture that external state; verify redemption with that plugin before opening rewards to players.

## Player controls

| Action | Result |
| --- | --- |
| `/keys` or `/plexonkeys` | Open the collection menu |
| Left-click a category's lower panel | Claim as many keys of that category as fit |
| Right-click a category's lower panel | Claim one key |
| Click **Claim all keys** | Claim all categories, highest rarity first, up to inventory capacity |
| `/keys claim basic` | Claim a category without opening the GUI |
| `/keys claim all` | Claim all categories without opening the GUI |

Inventory space is calculated using **storage slots only** and each item's effective maximum stack size. Keys that do not fit remain virtual. Nothing is dropped on the ground, armor/offhand slots are not used, and rapid or duplicate clicks cannot spend the same balance twice during a running session. Disabling a category or reward drops does **not** strand existing balances: they can still be claimed.

Cash and raw XP-point bonuses are paid **when an activity awards a virtual key**, never again at claim time. Administrative balance corrections do not trigger bonuses or announcements.

## Admin controls

`/keysadmin`, `/pkeys`, and `/pka` open the same admin menu. `/keys admin` also opens it for admins.

| Command | Purpose |
| --- | --- |
| `/keysadmin setitem <category>` | Save the complete held item as that category's key |
| `/keysadmin chances [category] [activity]` | Open the chance overview, a category page, or its percentage editor |
| `/keysadmin chance <category> <activity> <percent>` | Set a precise drop percentage |
| `/keysadmin give <player\|uuid> <category> <amount>` | Add virtual keys, up to the configured cap |
| `/keysadmin take <player\|uuid> <category> <amount>` | Remove virtual keys without going below zero |
| `/keysadmin setbalance <player\|uuid> <category> <amount>` | Set an exact virtual balance, including zero |
| `/keysadmin balance <player\|uuid>` | Inspect all four balances |
| `/keysadmin set <config.path> <value>` | Edit a scalar or inline YAML list and save it |
| `/keysadmin reload` | Validate and reload `config.yml`; keeps active settings if invalid |
| `/keysadmin save` | Manually checkpoint changed data on a background worker |
| `/keysadmin status` | Show players, tracked blocks, dirty records, checkpoint interval, economy |
| `/keysadmin help` | Show in-game help |

Category IDs: `basic`, `rare`, `epic`, `legendary`. Activity IDs: `mining`, `logging`, `fishing`, `mobs`.

Online names and previously recorded names are resolved locally. A UUID can be used for a player who has not joined. Commands do not make blocking profile/name lookups on the server thread. Changing an offline player's balance does not require them to be online.

### GUI editing

- The main admin page offers category settings, **Chance editor**, global rewards, database save, reload, status, and **Browse all settings**.
- Each category editor exposes enabling, chat announcements, four independent activity chances, cash/XP toggles and amounts, full item capture, and its advanced section.
- **Chance editor** shows all 16 category/activity percentages in a grid. Click one to adjust it with **±10**, **±1**, **±0.1**, **±0.01**, or **±0.001** percentage-point buttons. The category pages open the same editor.
- Chance edits stay in a private draft until **Apply**. **Never (0%)**, **Always (100%)**, and **Reset draft** are shortcuts; **Cancel** or closing the inventory discards the draft. Values cannot go below 0% or above 100%. The saved value and draft are shown together, with decimal arithmetic to avoid accumulating binary addition errors.
- **Type exact percentage** is an optional private chat input for any finer value; it returns to the draft and still requires **Apply**. Typing `cancel` keeps the existing draft. The numeric GUI works without typing in chat.
- **Left-click** cash/XP to toggle them; **right-click** to edit their amount.
- **Browse all settings** traverses every config section, with pagination. Booleans toggle immediately; text, numbers, and lists open a chat prompt. Lists accept `[STONE, DEEPSLATE]` or `[Survival_World]`. Enter `[]` for an empty list, `""` for empty text, or `cancel` to exit.
- Chat prompts expire after 120 seconds by default. They are private, recheck permissions, and reject stale edits after another configuration change.
- Every saved edit validates the whole configuration, atomically replaces `config.yml` when the filesystem supports it, and applies immediately. Chance adjustments do not write to disk before **Apply**; applying an unchanged draft also makes no write. Invalid values do not replace working settings. Successful configuration changes close other open menus and discard their drafts so old buttons cannot execute with new settings.
- If the file was edited externally since the last load, in-game edits are rejected until `/keysadmin reload`, so they do not silently overwrite those file changes.
- Raw captured NBT is intentionally protected from text editing; use **Capture held key** or `setitem` instead.
- Admin navigation uses a fixed layout. Its labels, materials, and lore are configurable. The player menu additionally supports custom size, category slots, claim slots, filler, summary, claim-all, admin, and close buttons.
- Chance editor styling is under `gui.admin.chance-editor`; the overview uses `gui.admin.chances`, `chance-category`, `chance`, and `chances-title`. All names, titles, lore, and messages support MiniMessage. Version 1.0.0 configurations load the new defaults automatically; existing custom values are preserved. Missing defaults are written into `config.yml` on the next accepted in-game edit, so deleting your configuration is unnecessary.

For a multi-field layout change, editing the YAML and reloading once is convenient: all button slots must be unique and inside the configured inventory size. A single GUI edit that would temporarily create overlapping slots is rejected.

## Drop configuration

All chances are **percentages**: `1.0` means **1%**, `0.05` means **0.05%**, `0` disables that category/activity roll, and `100` always succeeds for an eligible action.

| Category | Mining | Woodcutting | Fishing | Mobs |
| --- | ---: | ---: | ---: | ---: |
| Basic | 0.20% | 0.35% | 3.00% | 0.75% |
| Rare | 0.06% | 0.10% | 1.00% | 0.25% |
| Epic | 0.015% | 0.025% | 0.25% | 0.07% |
| Legendary | 0.003% | 0.005% | 0.05% | 0.015% |

`INDEPENDENT`, the default, rolls each enabled category once per eligible action; several categories can win together. `HIGHEST_ONLY` tries Legendary → Epic → Rare → Basic and stops at the first success. In that mode a lower category's effective probability is its configured chance multiplied by the probability that all higher eligible categories fail. Disabled, permission-restricted, or capped categories do not roll.

Activity cooldowns apply **per player, per activity**, after eligibility checks, whether or not a roll wins. Mining/logging/mob cooldowns default to zero. Fishing defaults to 1500 ms. Set a mining/logging cooldown if multi-block tools generate more rolls than you want; a nonzero value means only the first eligible event during that interval rolls.

World names are case-insensitive. `settings.worlds: []` allows every world; `excluded-worlds` takes priority. Only Survival is enabled by default. `plexonkeys.earn` and an optional per-category permission control earning. The virtual balance cap defaults to 1,000,000 per category; lowering it never deletes existing balances.

### Natural blocks and abuse prevention

- Successful placements, including multi-block placements, are tracked even when rewards, worlds, or categories are disabled.
- Breaking a tracked block removes its tracking record and does **not** roll for a key. Canceled breaks retain the record. Canceled placements do not add one.
- Pistons carry provenance with moved blocks; both extension and retraction handle adjacent moves without overwriting the next block's state.
- Falling blocks and enderman-carried blocks retain artificial provenance using entity PDC while in transit. Unknown externally spawned falling blocks are conservatively excluded.
- Structures, sapling growth, fertilization, and block growth after installation are considered non-natural by default. Set `tracking.exclude-grown-blocks: false` to allow future grown blocks. This does not retroactively reclassify existing records.
- Block formation/spread is excluded by default, including common cobblestone/stone/obsidian generators. Explosions, burning, and leaf decay clean up affected tracking records.
- Mining defaults to a material list of natural stone/ores. Empty means any non-logging block. Logging takes priority, so a log never also rolls as mining; an empty logging list uses Paper's logs tag.
- With `activities.mining.require-drops: true`, both mining and logging honor suppressed drops and require an appropriate tool. Protection plugins that cancel the event are respected.
- Fishing requires the `CAUGHT_FISH` state, an actual item catch, and open water by default. Casts, failed bites, and hooked mobs do not qualify.
- Mobs require a player killer. Players and armor stands never count. Paper spawn-reason filters exclude spawners, eggs, commands, breeding, and custom spawns by default. An empty allowlist permits all reasons. Pet/environment-only kills without a player killer do not qualify.

**Limits:** Minecraft does not record who placed every historical block. Blocks placed **before installation**, changes made while the plugin is disabled/uninstalled, and WorldEdit or other plugins that bypass Bukkit events cannot be reliably classified. This plugin does not scan existing worlds or pretend to reconstruct that history. For strict event rewards, use a fresh resource world and ensure any multi-block tool emits normal, uncanceled break events. Mob spawn filters are configurable and are not a universal AFK-farm detector.

## Storage and performance

Data is stored in **`plugins/PlexonKeys/plexonkeys.db`** (SQLite). Full key item templates and configuration remain in `config.yml` so they are reviewable and editable by the administrator.

- Balances and artificial-block positions are held in memory. Hot activity handlers never run SQL, save files, scan chunks, or call external name services.
- By default there is **no recurring save task**, no repeating menu refresh, and no polling task. Menus update on relevant actions.
- Normal shutdown/plugin disable waits for any earlier save, then commits a fresh final snapshot before the database worker exits.
- `/keysadmin save` requests a background checkpoint. Optional `storage.checkpoint-seconds: 300` enables a five-minute checkpoint; `0` disables it, and nonzero values must be at least 60 seconds.
- Checkpoints write only dirty player/block records, in one transaction with bounded prepared-statement batches. Versioned acknowledgements prevent an old save from clearing newer changes. Failed saves keep dirty data for retry and log the error.
- SQLite uses full synchronous commits. Startup checks database integrity and rejects newer schemas/corruption rather than replacing the database with an empty one.
- RAM use grows with recorded players and currently tracked artificial positions; this is the deliberate tradeoff for shutdown-only disk writes. Status shows player, block, and dirty-record counts. There is no silent eviction of block history.
- Vault payments run on the server thread to respect economy-provider APIs. Their latency depends on the provider; cash bonuses are disabled by default.

> **Shutdown-only is not crash-proof.** A power loss, forced kill, watchdog termination, disk failure, or crash can lose everything since the last successful save. A crash can also restore already-claimed virtual keys if Minecraft saved the inventory but PlexonKeys did not save the debit. Vault, Minecraft inventories/XP, and SQLite do not share a transaction, so even optional checkpoints cannot guarantee atomic recovery across them. Use normal `/stop`, monitor save errors, keep backups, and enable periodic checkpoints if the risk is unacceptable. Avoid production plugin hot-reload tools.

Back up **both `config.yml` and `plexonkeys.db` while the server is stopped**, together with the server/player/economy data when consistent recovery matters. Do not edit or swap the database while the plugin is running. `/keysadmin reload` reloads configuration only; it does not reload or reset balances.

## Cash and XP

Cash uses the registered **Vault economy provider**. Install Vault and a compatible economy plugin if enabling it. Each category has independent enabled/amount fields. XP uses raw points (`points`), not levels, and needs no external plugin. Both bonuses default to disabled so this release does not silently change an existing server economy.

If a cash payment fails or no provider is available, the virtual key and any XP are still awarded, and the player receives a configurable warning. The failed cash payment is **not automatically retried**; contact the admin for reconciliation. Repeating an uncertain economy payment could pay twice. Administrative `give`, `take`, and `setbalance` do not run reward bonuses.

## MiniMessage and appearance

Every configurable player/admin message, title, name, lore line, gradient, and announcement uses Adventure MiniMessage. Use `<gray>`, `<bold>`, `<gradient:#56B9F2:#92E1FF>`, `<click:run_command:/keys>`, and other MiniMessage tags. Legacy `&a` colors are not translated.

Common placeholders are `<player>`, `<category>`, `<activity>`, `<amount>`, `<balance>`, and `<total>` where relevant. `config.yml` shows the placeholders available in each template. Runtime player names, counts, paths, and input are inserted as **unparsed values** so they cannot inject MiniMessage actions. Names and lore for menu items have italics disabled by default. Captured physical items retain their own exact style.

Epic and Legendary announcements are enabled by default; Basic and Rare announcements are off. Each category can supply its own broadcast message. Broadcasts are server messages containing the player's name, not forced player chat, so they do not interfere with normal chat routing.

## Permissions

| Permission | Default | Allows |
| --- | --- | --- |
| `plexonkeys.use` | Everyone | `/keys`, menu, claims |
| `plexonkeys.earn` | Everyone | Activity rewards |
| `plexonkeys.admin` | Operators | All admin commands and editors |
| `categories.<id>.permission` value | Empty | Optional additional permission for earning that category |

Admin permission is checked when commands execute, when GUI clicks run, and when a pending chat edit is submitted. Menu items cannot be extracted by shift-clicking, hotbar swaps, dragging, double-clicking, or creative cloning.

## Build and verification

Requires JDK 25 and Maven 3.9+:

```sh
mvn -B -ntp clean verify
```

Output: `target/PlexonKeys-1.1.0.jar`, with SQLite bundled. Paper, Adventure, and the test framework are **not** bundled. Install only this jar, not `original-PlexonKeys-1.1.0.jar`. GitHub Actions runs the same build and uploads the plugin and test reports for each push/PR.

The build pins Paper API `26.2.build.121-stable`. Automated coverage uses JUnit, SQLite, and MockBukkit for Paper 26.2. It exercises startup, shutdown persistence, canceled/placed-block events, generation tracking, chance boundaries, spawn filters, inventory capacity, menu security, configuration validation, and item capture/claim behavior. These tests do not replace a live staging check against the specific crate, protection, economy, and multi-block-tool plugins installed on your server. See [TESTING.md](TESTING.md).

Primary API references: [Paper requirements](https://docs.papermc.io/paper/getting-started/), [Paper project setup](https://docs.papermc.io/paper/dev/project-setup/), [ItemStack binary serialization](https://jd.papermc.io/paper/26.2/org/bukkit/inventory/ItemStack.html#serializeAsBytes()), [MiniMessage](https://docs.papermc.io/adventure/minimessage/).
