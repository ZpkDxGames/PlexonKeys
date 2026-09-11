package com.antondev.keys;

import com.antondev.keys.activity.BlockActivityProcessor;
import com.antondev.keys.activity.BlockActivityProcessor.Origin;
import com.antondev.keys.api.*;
import com.antondev.keys.command.KeysCommand;
import com.antondev.keys.config.*;
import com.antondev.keys.data.*;
import com.antondev.keys.gui.MenuService;
import com.antondev.keys.integration.EconomyBridge;
import com.antondev.keys.integration.core.*;
import com.antondev.keys.integration.core.runtime.*;
import com.antondev.keys.listener.*;
import com.antondev.keys.model.Activity;
import com.antondev.keys.reward.*;
import com.antondev.keys.service.KeyBalanceService;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.*;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public class PlexonKeys extends JavaPlugin implements Listener {
    private Configuration configuration;
    private MemoryStore data;
    private DataSaver saver;
    private EconomyBridge economy;
    private KeyBalanceService balances;
    private RewardService rewards;
    private ClaimService claims;
    private MenuService menus;
    private PlexonKeysAPI api;
    private CoreBridge core;
    private CoreRuntimeBridge coreRuntime;
    private AutoCloseable coreBlockSubscription;
    private BlockActivityProcessor blockProcessor;
    private BlockActivityListener localBlockListener;
    private TrackingListener trackingListener;
    private boolean coreBlocksOwned;
    private String blockOwnership = "LOCAL";
    private long runtimeEpoch;
    private final AtomicLong coreBlockOutcomes = new AtomicLong();
    private final AtomicLong coreNatural = new AtomicLong();
    private final AtomicLong coreArtificial = new AtomicLong();
    private final AtomicLong coreUnknown = new AtomicLong();
    private final AtomicLong coreMaterialRejected = new AtomicLong();
    private final AtomicLong coreDropRejected = new AtomicLong();
    private final AtomicLong coreRewardAttempts = new AtomicLong();
    private BukkitTask checkpoint;
    private int pressureProbe;

    @Override public void onEnable() {
        try {
            core = CoreBridgeFactory.resolve(this);
            core.registerStarting();

            saveDefaultConfig();
            configuration = new Configuration(this);
            configuration.reload();

            SqliteStore database = new SqliteStore(getDataFolder().toPath().resolve("plexonkeys.db"));
            data = database.load();
            saver = new DataSaver(database, data, getLogger());
            configureSaver();
            economy = new EconomyBridge(this);
            balances = new KeyBalanceService(this, data);
            rewards = new RewardService(this);
            claims = new ClaimService(this);
            menus = new MenuService(this);
            blockProcessor = new BlockActivityProcessor(this);
            coreRuntime = CoreRuntimeBridgeFactory.resolve(this, core);

            var manager = getServer().getPluginManager();
            manager.registerEvents(this, this);
            trackingListener = new TrackingListener(this);
            manager.registerEvents(trackingListener, this);
            manager.registerEvents(new ActivityListener(this), this);
            manager.registerEvents(menus, this);
            manager.registerEvents(economy, this);
            configureBlockRuntime();

            var commands = new KeysCommand(this);
            for (String name : new String[]{"keys", "keysadmin"}) {
                var command = Objects.requireNonNull(getCommand(name));
                command.setExecutor(commands);
                command.setTabCompleter(commands);
            }

            api = new PlexonKeysApiImpl(this, balances);
            Bukkit.getServicesManager().register(PlexonKeysAPI.class, api, this, ServicePriority.Normal);

            Bukkit.getOnlinePlayers().forEach(player -> data.remember(player.getUniqueId(), player.getName()));
            configureCheckpoint();
            warnEconomy();
            publishCoreHealth();

            getLogger().info("PlexonKeys " + getPluginMeta().getVersion() + " by Tonim (ZpkDxGames) enabled. "
                    + data.playerCount() + " players, " + data.blockCount() + " tracked blocks."
                    + " Mode=" + runtimeMode() + " Blocks=" + blockOwnership);
            if (!settings().checkpointsEnabled()) {
                getLogger().warning("Periodic checkpoints are explicitly disabled. Forced crashes can lose ordinary reward/provenance progress; critical consume/claim barriers remain enabled.");
            }
            if (settings().categories().keySet().stream().anyMatch(t -> settings().yaml()
                    .getString("categories." + t.id() + ".item.mode", "").equalsIgnoreCase("CONFIG"))) {
                getLogger().info("CONFIG item templates are active. To use another crate plugin's keys, hold each real key and run /keysadmin setitem <category>.");
            }
        } catch (Exception | LinkageError error) {
            if (core != null) core.markFailed("Key startup failed: " + error.getClass().getSimpleName());
            getLogger().log(Level.SEVERE, "PlexonKeys could not start. Existing data/configuration was not reset.", error);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override public void onDisable() {
        closeCoreBlockSubscription();
        if (coreRuntime != null) {
            try { coreRuntime.close(); }
            catch (Exception error) { getLogger().log(Level.WARNING, "Could not close Core Runtime bridge cleanly.", error); }
            coreRuntime = null;
        }
        if (localBlockListener != null) {
            HandlerList.unregisterAll(localBlockListener);
            localBlockListener = null;
        }
        coreBlocksOwned = false;

        if (checkpoint != null) {
            checkpoint.cancel();
            checkpoint = null;
        }
        if (menus != null) {
            try {
                menus.closeAll();
            } catch (RuntimeException error) {
                getLogger().log(Level.WARNING, "Could not close every menu; final database save will still run.", error);
            }
        }
        if (saver != null) {
            try {
                saver.close();
            } catch (Exception error) {
                getLogger().log(Level.SEVERE, "FINAL DATABASE SAVE FAILED. Investigate disk permissions/free space immediately.", error);
            }
            saver = null;
        }

        Bukkit.getServicesManager().unregisterAll(this);
        api = null;

        if (core != null) {
            core.unregister();
            core = null;
        }
    }

    @EventHandler public void join(PlayerJoinEvent event) {
        data.remember(event.getPlayer().getUniqueId(), event.getPlayer().getName());
    }

    public void settingsChanged() {
        menus.closeAll();
        rewards.clearCooldowns();
        rewards.reload();
        economy.hook();
        configureSaver();
        configureCheckpoint();
        configureBlockRuntime();
        warnEconomy();
        publishCoreHealth();
    }

    private void configureBlockRuntime() {
        runtimeEpoch++;
        closeCoreBlockSubscription();
        if (localBlockListener != null) {
            HandlerList.unregisterAll(localBlockListener);
            localBlockListener = null;
        }
        coreBlocksOwned = false;
        blockOwnership = "LOCAL";

        String requested = settings().yaml().getString("core-runtime.mode", "AUTO").trim().toUpperCase(Locale.ROOT);
        if (!Set.of("AUTO", "CORE", "LOCAL").contains(requested)) {
            throw new IllegalArgumentException("core-runtime.mode must be AUTO, CORE, or LOCAL");
        }
        boolean blocksEnabled = settings().yaml().getBoolean("core-runtime.activities.blocks", true);
        boolean tryCore = !requested.equals("LOCAL") && blocksEnabled;
        Set<Material> route = blockRouteMaterials();

        if (tryCore && coreRuntime != null && coreRuntime.available() && !route.isEmpty()) {
            try {
                coreBlockSubscription = coreRuntime.subscribeBlocks(route, this::handleCoreBlock);
                coreBlocksOwned = true;
                blockOwnership = "CORE";
                getLogger().info("Core Runtime owns PlexonKeys block rewards across " + route.size() + " routed materials (epoch " + runtimeEpoch + ").");
                return;
            } catch (Exception error) {
                if (requested.equals("CORE")) throw new IllegalStateException("Forced Core Runtime block subscription failed", error);
                getLogger().log(Level.WARNING, "Core Runtime block subscription failed; using local block acquisition.", error);
            }
        } else if (requested.equals("CORE") && blocksEnabled) {
            String detail = coreRuntime == null ? "Runtime bridge is unavailable" : coreRuntime.detail();
            throw new IllegalStateException("core-runtime.mode=CORE requested but Core block runtime cannot start: " + detail);
        }

        localBlockListener = new BlockActivityListener(this, blockProcessor);
        getServer().getPluginManager().registerEvents(localBlockListener, this);
        blockOwnership = core != null && core.mode().equals("CORE_LEGACY") ? "LOCAL (CORE_LEGACY)" : "LOCAL";
    }

    private Set<Material> blockRouteMaterials() {
        var settings = settings();
        boolean mining = settings.tasks().get(Activity.MINING).enabled();
        boolean logging = settings.tasks().get(Activity.LOGGING).enabled();
        if (!mining && !logging) return Set.of();

        EnumSet<Material> route = EnumSet.noneOf(Material.class);
        if (mining) {
            if (settings.miningMaterials().isEmpty()) {
                for (Material material : Material.values()) if (material.isBlock() && !material.isAir()) route.add(material);
            } else {
                route.addAll(settings.miningMaterials());
            }
        }
        if (logging) {
            if (settings.loggingMaterials().isEmpty()) {
                for (Material material : Material.values()) if (material.isBlock() && Tag.LOGS.isTagged(material)) route.add(material);
            } else {
                route.addAll(settings.loggingMaterials());
            }
        }
        return Set.copyOf(route);
    }

    private void handleCoreBlock(CoreRuntimeBridge.BlockFact fact) {
        if (!coreBlocksOwned) return;
        coreBlockOutcomes.incrementAndGet();
        Player player = Bukkit.getPlayer(fact.playerId());
        if (player == null) return;

        MemoryStore.Position position = MemoryStore.Position.of(fact.worldId(), fact.x(), fact.y(), fact.z());
        // Legacy and Keys-specific derived-artificial provenance remains a conservative overlay in 1.4.
        if (data.unmark(position)) {
            pressureSaveProbe(1);
            coreArtificial.incrementAndGet();
            return;
        }

        Origin origin = switch (fact.origin()) {
            case NATURAL -> {
                coreNatural.incrementAndGet();
                yield Origin.NATURAL;
            }
            case ARTIFICIAL -> {
                coreArtificial.incrementAndGet();
                yield Origin.ARTIFICIAL;
            }
            case UNKNOWN -> {
                coreUnknown.incrementAndGet();
                yield Origin.UNKNOWN;
            }
        };
        if (origin != Origin.NATURAL) return;

        Activity activity = blockProcessor.classify(fact.material());
        if (activity == null) {
            coreMaterialRejected.incrementAndGet();
            return;
        }

        boolean preferred = true;
        if (settings().requireDrops()) {
            if (!fact.dropItems()) {
                coreDropRejected.incrementAndGet();
                return;
            }
            World world = Bukkit.getWorld(fact.worldId());
            if (world == null) {
                coreDropRejected.incrementAndGet();
                return;
            }
            preferred = world.getBlockAt(fact.x(), fact.y(), fact.z())
                    .isPreferredTool(player.getInventory().getItemInMainHand());
            if (!preferred) {
                coreDropRejected.incrementAndGet();
                return;
            }
        }

        if (blockProcessor.tryPerform(player, activity, origin, fact.dropItems(), preferred)) {
            coreRewardAttempts.incrementAndGet();
        }
    }

    private void closeCoreBlockSubscription() {
        AutoCloseable subscription = coreBlockSubscription;
        coreBlockSubscription = null;
        if (subscription == null) return;
        try { subscription.close(); }
        catch (Exception error) { getLogger().log(Level.WARNING, "Could not close Core block subscription cleanly.", error); }
    }

    private boolean cashBonusUnavailable() {
        return !economy.available() && settings().categories().values().stream()
                .anyMatch(category -> category.moneyEnabled() && category.money() > 0);
    }

    private void warnEconomy() {
        if (cashBonusUnavailable()) {
            getLogger().warning("Cash bonuses are enabled but Vault has no economy provider. Keys/XP work; failed cash bonuses are not retried.");
        }
    }

    private void publishCoreHealth() {
        if (core == null) return;
        String ready = "PlexonKeys API/events and SQLite ready; blocks=" + blockOwnership + "; mobs=LOCAL; fishing=LOCAL";
        if (cashBonusUnavailable()) {
            core.markDegraded(ready + "; cash bonuses configured but no Vault economy provider is available");
        } else {
            core.markReady(ready);
        }
    }

    private void configureSaver() {
        if (saver == null) return;
        int maximum = clamp(settings().yaml().getInt("storage.maximum-snapshot-records", 4096), 64, 1_000_000);
        int timeout = clamp(settings().yaml().getInt("storage.shutdown-timeout-seconds", 15), 1, 300);
        saver.configure(maximum, timeout);
    }

    private void configureCheckpoint() {
        if (checkpoint != null) {
            checkpoint.cancel();
            checkpoint = null;
        }
        pressureProbe = 0;
        if (settings().checkpointsEnabled()) {
            long ticks = settings().checkpointSeconds() * 20L;
            checkpoint = Bukkit.getScheduler().runTaskTimer(this, () -> {
                if (data.dirtyCount() > 0) saver.save();
            }, ticks, ticks);
        }
    }

    /**
     * Cheap, throttled pressure probe for provenance-heavy activity. It never performs JDBC work itself;
     * crossing the configured dirty threshold only extends/coalesces the background saver revision.
     */
    public void pressureSaveProbe(int mutations) {
        if (mutations <= 0 || saver == null) return;
        pressureProbe += mutations;
        if (pressureProbe < 64) return;
        pressureProbe = 0;
        int threshold = clamp(settings().yaml().getInt("storage.pressure-dirty-threshold", 2048), 0, 10_000_000);
        if (threshold > 0 && data.dirtyCount() >= threshold) saver.save();
    }

    /** Critical low-frequency durability barrier; all actual SQLite work remains on DataSaver's single worker. */
    public DataSaver.Result persistCriticalState(String reason) {
        Objects.requireNonNull(reason, "reason");
        if (reason.isBlank()) throw new IllegalArgumentException("reason must not be blank");
        if (saver == null) throw new IllegalStateException("PlexonKeys persistence is unavailable");
        try {
            return saver.saveAndWait();
        } catch (RuntimeException error) {
            getLogger().log(Level.SEVERE, "Critical PlexonKeys persistence barrier failed: " + reason, error);
            throw error;
        }
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    public boolean reloadFor(CommandSender sender) {
        try {
            configuration.reload();
            settingsChanged();
            settings().text().send(sender, "reloaded");
            return true;
        } catch (Exception error) {
            configError(sender, error);
            return false;
        }
    }

    public void configError(CommandSender sender, Exception error) {
        settings().text().send(sender, "config-error",
                Text.value("error", error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()));
    }

    public void saveData(CommandSender sender) {
        settings().text().send(sender, "saving");
        saver.save().whenComplete((result, error) -> {
            if (!isEnabled()) return;
            try {
                Bukkit.getScheduler().runTask(this, () -> {
                    if (error != null) {
                        settings().text().send(sender, "save-failed");
                    } else {
                        settings().text().send(sender, "saved", Text.value("players", result.players()),
                                Text.value("blocks", result.blocks()), Text.value("time", result.milliseconds()));
                    }
                });
            } catch (org.bukkit.plugin.IllegalPluginAccessException ignored) {
                // Shutdown performs its own final save.
            }
        });
    }

    public void diagnostics(CommandSender sender) {
        boolean apiRegistered = Bukkit.getServicesManager().getRegistration(PlexonKeysAPI.class) != null;
        DataSaver.Metrics storage = saver == null ? null : saver.metrics();
        RewardService.Metrics reward = rewards.metrics();
        MemoryStore.ProvenanceMetrics provenance = data.provenanceMetrics();
        sender.sendMessage("§8§m----------------------------------------");
        sender.sendMessage("§6PlexonKeys diagnostics");
        sender.sendMessage("§7Version: §f" + getPluginMeta().getVersion());
        sender.sendMessage("§7Java: §f" + System.getProperty("java.version"));
        sender.sendMessage("§7Paper/Bukkit: §f" + Bukkit.getBukkitVersion());
        sender.sendMessage("§7PlexonCore plugin/API: §f" + (core == null ? "- / -" : core.pluginVersion() + " / " + core.apiVersion()));
        sender.sendMessage("§7Supported Core API: §f" + CoreBridge.SUPPORTED_API_RANGE);
        sender.sendMessage("§7Runtime mode / epoch: §f" + runtimeMode() + " / " + runtimeEpoch);
        sender.sendMessage("§7Activity ownership: §fMINING=" + blockOwnership + ", LOGGING=" + blockOwnership + ", FISHING=LOCAL, MOBS=LOCAL");
        sender.sendMessage("§7Module: §f" + (core == null ? "NOT_INSTALLED" : core.registrationState()));
        sender.sendMessage("§7SQLite: §f" + (saver == null ? "UNAVAILABLE" : "READY / schema " + SqliteStore.SCHEMA_VERSION));
        sender.sendMessage("§7Persistence mode: §fASYNC_DELTA_CHECKPOINTS + CRITICAL_COMMIT_BARRIERS");
        sender.sendMessage("§7Players / tracked blocks: §f" + data.playerCount() + " / " + data.blockCount());
        sender.sendMessage("§7Dirty accounts / blocks / replay: §f" + data.dirtyAccounts() + " / " + data.dirtyBlocks() + " / " + data.dirtyConsumes());
        sender.sendMessage("§7Replay records / limit / retention: §f" + data.consumeReplayCount() + " / "
                + MemoryStore.MAX_CONSUME_REPLAY_RECORDS + " / 7 days");
        sender.sendMessage("§7Provenance provider: §f" + (coreBlocksOwned ? "CORE + KEYS_DERIVED_OVERLAY" : "LOCAL"));
        sender.sendMessage("§7Provenance worlds / largest batch: §f" + provenance.perWorld().size() + " / " + provenance.largestBatch());
        sender.sendMessage("§7Provenance mark / unmark / move per sec: §f" + rate(provenance.marksPerSecond())
                + " / " + rate(provenance.unmarksPerSecond()) + " / " + rate(provenance.movesPerSecond()));
        sender.sendMessage("§7Core block outcomes N/A/U: §f" + coreBlockOutcomes.get() + " / " + coreNatural.get()
                + " / " + coreArtificial.get() + " / " + coreUnknown.get());
        sender.sendMessage("§7Core material/drop rejects / reward attempts: §f" + coreMaterialRejected.get() + " / "
                + coreDropRejected.get() + " / " + coreRewardAttempts.get());
        sender.sendMessage("§7Checkpoint enabled / seconds: §f" + settings().checkpointsEnabled() + " / " + settings().checkpointSeconds());
        sender.sendMessage("§7Vault economy: §f" + economy.name());
        sender.sendMessage("§7PlexonKeysAPI: §f" + (apiRegistered ? "REGISTERED" : "UNAVAILABLE"));
        sender.sendMessage("§7Earn event: §fcom.antondev.keys.event.PlexonKeyEarnedEvent");
        sender.sendMessage("§7Claim event: §fcom.antondev.keys.event.PlexonKeyClaimedEvent");
        sender.sendMessage("§7Reward eligible / ineligible per sec: §f" + rate(reward.eligiblePerSecond())
                + " / " + rate(reward.ineligiblePerSecond()));
        sender.sendMessage("§7Reward rolls per sec / wins: §f" + rate(reward.rollsPerSecond()) + " / " + reward.wins());
        sender.sendMessage("§7Rejects cooldown / cap / permission: §f" + reward.cooldownRejects() + " / "
                + reward.capRejects() + " / " + reward.permissionRejects());
        if (storage != null) {
            sender.sendMessage("§7Save in flight / requested: §f" + storage.inFlight() + " / " + storage.saveRequested());
            sender.sendMessage("§7Storage revision current / requested / ack: §f" + data.revision() + " / "
                    + storage.requestedRevision() + " / " + storage.acknowledgedRevision());
            sender.sendMessage("§7Persistence queue depth / high-water / capacity: §f" + storage.queueDepth() + " / "
                    + storage.queueHighWaterMark() + " / " + storage.queueCapacity());
            sender.sendMessage("§7Last snapshot / DB / save ms: §f" + storage.lastSnapshotMilliseconds()
                    + " / " + storage.lastDatabaseMilliseconds() + " / " + storage.lastSaveMilliseconds());
            sender.sendMessage("§7P95 save ms / max dirty / batch cap: §f" + storage.p95SaveMilliseconds() + " / "
                    + storage.maximumDirtyCount() + " / " + storage.maximumSnapshotRecords());
            sender.sendMessage("§7Last save rows / failures: §f" + storage.lastPlayers() + "+" + storage.lastBlocks()
                    + " / " + storage.saveFailures());
            sender.sendMessage("§7Last successful save: §f" + (storage.lastSuccessfulSaveEpochMillis() == 0
                    ? "never" : java.time.Instant.ofEpochMilli(storage.lastSuccessfulSaveEpochMillis())));
            sender.sendMessage("§7Last failed save: §f" + (storage.lastFailedSaveEpochMillis() == 0
                    ? "never" : java.time.Instant.ofEpochMilli(storage.lastFailedSaveEpochMillis())));
        }
        if (coreRuntime != null && !coreRuntime.detail().isBlank()) sender.sendMessage("§7Core Runtime detail: §f" + coreRuntime.detail());
        if (core != null && !core.detail().isBlank()) sender.sendMessage("§7Core detail: §f" + core.detail());
        sender.sendMessage("§8§m----------------------------------------");
    }

    private String runtimeMode() {
        if (coreBlocksOwned) return "CORE_RUNTIME";
        if (core != null && core.mode().equals("CORE_LEGACY")) return "CORE_LEGACY";
        return core != null && core.installed() ? "LOCAL" : "STANDALONE";
    }

    private static String rate(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }

    public TagResolver[] statusTags() {
        return new TagResolver[]{
                Text.value("players", data.playerCount()),
                Text.value("blocks", data.blockCount()),
                Text.value("dirty", data.dirtyCount()),
                Text.value("checkpoint", settings().checkpointSeconds()),
                Text.value("economy", economy.name())
        };
    }

    public boolean coreBlocksOwned() { return coreBlocksOwned; }
    public long runtimeEpoch() { return runtimeEpoch; }
    public Settings settings() { return configuration.settings(); }
    public Configuration configuration() { return configuration; }
    public MemoryStore data() { return data; }
    public EconomyBridge economy() { return economy; }
    public KeyBalanceService balances() { return balances; }
    public RewardService rewards() { return rewards; }
    public ClaimService claims() { return claims; }
    public MenuService menus() { return menus; }
    public PlexonKeysAPI api() { return Objects.requireNonNull(api, "PlexonKeys API is not registered"); }
    public CoreBridge core() { return core; }
}
