package com.antondev.keys;

import com.antondev.keys.command.KeysCommand;
import com.antondev.keys.config.*;
import com.antondev.keys.data.*;
import com.antondev.keys.gui.MenuService;
import com.antondev.keys.integration.EconomyBridge;
import com.antondev.keys.listener.*;
import com.antondev.keys.reward.*;
import java.util.Objects;
import java.util.logging.Level;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public class PlexonKeys extends JavaPlugin implements Listener {
    private Configuration configuration;
    private MemoryStore data;
    private DataSaver saver;
    private EconomyBridge economy;
    private RewardService rewards;
    private ClaimService claims;
    private MenuService menus;
    private BukkitTask checkpoint;
    @Override public void onEnable() {
        try {
            saveDefaultConfig();
            configuration = new Configuration(this); configuration.reload();
            SqliteStore database = new SqliteStore(getDataFolder().toPath().resolve("plexonkeys.db"));
            data = database.load();
            saver = new DataSaver(database, data, getLogger());
            economy = new EconomyBridge(this);
            rewards = new RewardService(this); claims = new ClaimService(this); menus = new MenuService(this);
            var manager = getServer().getPluginManager();
            manager.registerEvents(this, this); manager.registerEvents(new TrackingListener(this), this);
            manager.registerEvents(new ActivityListener(this), this); manager.registerEvents(menus, this); manager.registerEvents(economy, this);
            var commands = new KeysCommand(this);
            for (String name : new String[]{"keys", "keysadmin"}) {
                var command = Objects.requireNonNull(getCommand(name)); command.setExecutor(commands); command.setTabCompleter(commands);
            }
            Bukkit.getOnlinePlayers().forEach(player -> data.remember(player.getUniqueId(), player.getName()));
            configureCheckpoint();
            warnEconomy();
            getLogger().info("PlexonKeys " + getPluginMeta().getVersion() + " by Tonim (ZpkDxGames) enabled. " + data.playerCount() + " players, " + data.blockCount() + " tracked blocks.");
            if (settings().checkpointSeconds() == 0) getLogger().warning("Shutdown-only data saving is enabled. Forced crashes can lose progress or replay claims. Use /keysadmin save or enable optional checkpoints.");
            if (settings().categories().keySet().stream().anyMatch(t -> settings().yaml().getString("categories." + t.id() + ".item.mode", "").equalsIgnoreCase("CONFIG")))
                getLogger().info("CONFIG item templates are active. To use another crate plugin's keys, hold each real key and run /keysadmin setitem <category>.");
        } catch (Exception | LinkageError error) {
            getLogger().log(Level.SEVERE, "PlexonKeys could not start. Existing data/configuration was not reset.", error);
            getServer().getPluginManager().disablePlugin(this);
        }
    }
    @Override public void onDisable() {
        if (checkpoint != null) checkpoint.cancel();
        if (menus != null) {
            try { menus.closeAll(); }
            catch (RuntimeException error) { getLogger().log(Level.WARNING, "Could not close every menu; final database save will still run.", error); }
        }
        if (saver != null) {
            try { saver.close(); }
            catch (Exception error) { getLogger().log(Level.SEVERE, "FINAL DATABASE SAVE FAILED. Investigate disk permissions/free space immediately.", error); }
            saver = null;
        }
    }
    @EventHandler public void join(PlayerJoinEvent event) { data.remember(event.getPlayer().getUniqueId(), event.getPlayer().getName()); }
    public void settingsChanged() {
        menus.closeAll(); rewards.clearCooldowns(); economy.hook(); configureCheckpoint(); warnEconomy();
    }
    private void warnEconomy() {
        if (!economy.available() && settings().categories().values().stream().anyMatch(c -> c.moneyEnabled() && c.money() > 0))
            getLogger().warning("Cash bonuses are enabled but Vault has no economy provider. Keys/XP work; failed cash bonuses are not retried.");
    }
    private void configureCheckpoint() {
        if (checkpoint != null) { checkpoint.cancel(); checkpoint = null; }
        if (settings().checkpointSeconds() > 0) {
            long ticks = settings().checkpointSeconds() * 20L;
            checkpoint = Bukkit.getScheduler().runTaskTimer(this, () -> saver.save(), ticks, ticks);
        }
    }
    public boolean reloadFor(CommandSender sender) {
        try { configuration.reload(); settingsChanged(); settings().text().send(sender, "reloaded"); return true; }
        catch (Exception error) { configError(sender, error); return false; }
    }
    public void configError(CommandSender sender, Exception error) {
        settings().text().send(sender, "config-error", Text.value("error", error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()));
    }
    public void saveData(CommandSender sender) {
        settings().text().send(sender, "saving");
        saver.save().whenComplete((result, error) -> {
            if (!isEnabled()) return;
            try {
                Bukkit.getScheduler().runTask(this, () -> {
                    if (error != null) settings().text().send(sender, "save-failed");
                    else settings().text().send(sender, "saved", Text.value("players", result.players()), Text.value("blocks", result.blocks()), Text.value("time", result.milliseconds()));
                });
            } catch (org.bukkit.plugin.IllegalPluginAccessException ignored) { /* Shutdown performs its own final save. */ }
        });
    }
    public TagResolver[] statusTags() {
        return new TagResolver[]{Text.value("players", data.playerCount()), Text.value("blocks", data.blockCount()), Text.value("dirty", data.dirtyCount()),
                Text.value("checkpoint", settings().checkpointSeconds()), Text.value("economy", economy.name())};
    }
    public Settings settings() { return configuration.settings(); }
    public Configuration configuration() { return configuration; }
    public MemoryStore data() { return data; }
    public EconomyBridge economy() { return economy; }
    public RewardService rewards() { return rewards; }
    public ClaimService claims() { return claims; }
    public MenuService menus() { return menus; }
}
