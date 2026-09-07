package com.antondev.keys;

import com.antondev.keys.api.*;
import com.antondev.keys.command.KeysCommand;
import com.antondev.keys.config.*;
import com.antondev.keys.data.*;
import com.antondev.keys.gui.MenuService;
import com.antondev.keys.integration.EconomyBridge;
import com.antondev.keys.integration.core.*;
import com.antondev.keys.listener.*;
import com.antondev.keys.reward.*;
import com.antondev.keys.service.KeyBalanceService;
import java.util.Objects;
import java.util.logging.Level;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
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
    private BukkitTask checkpoint;

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
            economy = new EconomyBridge(this);
            balances = new KeyBalanceService(this, data);
            rewards = new RewardService(this);
            claims = new ClaimService(this);
            menus = new MenuService(this);

            var manager = getServer().getPluginManager();
            manager.registerEvents(this, this);
            manager.registerEvents(new TrackingListener(this), this);
            manager.registerEvents(new ActivityListener(this), this);
            manager.registerEvents(menus, this);
            manager.registerEvents(economy, this);

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
                    + " Mode=" + (core == null ? "STANDALONE" : core.mode()));
            if (settings().checkpointSeconds() == 0) {
                getLogger().warning("Shutdown-only data saving is enabled. Forced crashes can lose progress or replay claims. Use /keysadmin save or enable optional checkpoints.");
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
        if (checkpoint != null) {
            checkpoint.cancel();
            checkpoint = null;
        }
        if (menus != null) {
            try { menus.closeAll(); }
            catch (RuntimeException error) {
                getLogger().log(Level.WARNING, "Could not close every menu; final database save will still run.", error);
            }
        }
        if (saver != null) {
            try { saver.close(); }
            catch (Exception error) {
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
        economy.hook();
        configureCheckpoint();
        warnEconomy();
        publishCoreHealth();
    }

    private boolean cashBonusUnavailable() {
        return !economy.available() && settings().categories().values().stream()
                .anyMatch(c -> c.moneyEnabled() && c.money() > 0);
    }

    private void warnEconomy() {
        if (cashBonusUnavailable()) {
            getLogger().warning("Cash bonuses are enabled but Vault has no economy provider. Keys/XP work; failed cash bonuses are not retried.");
        }
    }

    private void publishCoreHealth() {
        if (core == null) return;
        String ready = "PlexonKeys API and public key events ready; SQLite persistence active";
        if (cashBonusUnavailable()) core.markDegraded(ready + "; cash bonuses configured but no Vault economy provider is available");
        else core.markReady(ready);
    }

    private void configureCheckpoint() {
        if (checkpoint != null) {
            checkpoint.cancel();
            checkpoint = null;
        }
        if (settings().checkpointSeconds() > 0) {
            long ticks = settings().checkpointSeconds() * 20L;
            checkpoint = Bukkit.getScheduler().runTaskTimer(this, () -> saver.save(), ticks, ticks);
        }
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
                    if (error != null) settings().text().send(sender, "save-failed");
                    else settings().text().send(sender, "saved", Text.value("players", result.players()),
                            Text.value("blocks", result.blocks()), Text.value("time", result.milliseconds()));
                });
            } catch (org.bukkit.plugin.IllegalPluginAccessException ignored) {
                // Shutdown performs its own final save.
            }
        });
    }

    public void diagnostics(CommandSender sender) {
        boolean apiRegistered = Bukkit.getServicesManager().getRegistration(PlexonKeysAPI.class) != null;
        sender.sendMessage("§8§m----------------------------------------");
        sender.sendMessage("§6PlexonKeys diagnostics");
        sender.sendMessage("§7Version: §f" + getPluginMeta().getVersion());
        sender.sendMessage("§7Java: §f" + System.getProperty("java.version"));
        sender.sendMessage("§7Paper/Bukkit: §f" + Bukkit.getBukkitVersion());
        sender.sendMessage("§7PlexonCore plugin/API: §f" + (core == null ? "- / -" : core.pluginVersion() + " / " + core.apiVersion()));
        sender.sendMessage("§7Supported Core API: §f" + CoreBridge.SUPPORTED_API_RANGE);
        sender.sendMessage("§7Mode: §f" + (core == null ? "STANDALONE" : core.mode()));
        sender.sendMessage("§7Module: §f" + (core == null ? "NOT_INSTALLED" : core.registrationState()));
        sender.sendMessage("§7SQLite: §f" + (saver == null ? "UNAVAILABLE" : "READY"));
        sender.sendMessage("§7Players / tracked blocks / dirty: §f" + data.playerCount() + " / " + data.blockCount() + " / " + data.dirtyCount());
        sender.sendMessage("§7Checkpoint seconds: §f" + settings().checkpointSeconds());
        sender.sendMessage("§7Vault economy: §f" + economy.name());
        sender.sendMessage("§7PlexonKeysAPI: §f" + (apiRegistered ? "REGISTERED" : "UNAVAILABLE"));
        sender.sendMessage("§7Earn event: §fcom.antondev.keys.event.PlexonKeyEarnedEvent");
        sender.sendMessage("§7Claim event: §fcom.antondev.keys.event.PlexonKeyClaimedEvent");
        if (core != null && !core.detail().isBlank()) sender.sendMessage("§7Core detail: §f" + core.detail());
        sender.sendMessage("§8§m----------------------------------------");
    }

    public TagResolver[] statusTags() {
        return new TagResolver[]{Text.value("players", data.playerCount()), Text.value("blocks", data.blockCount()),
                Text.value("dirty", data.dirtyCount()), Text.value("checkpoint", settings().checkpointSeconds()),
                Text.value("economy", economy.name())};
    }

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
