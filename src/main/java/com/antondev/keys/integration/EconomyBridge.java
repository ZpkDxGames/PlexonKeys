package com.antondev.keys.integration;

import java.lang.reflect.Method;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.event.*;
import org.bukkit.event.server.*;
import org.bukkit.plugin.*;

/** Optional Vault integration; reflection keeps Vault classes out of the plugin's bundled dependencies. */
public final class EconomyBridge implements Listener {
    private final Plugin plugin;
    private Object provider;
    private Method deposit, success;
    private String name = "Unavailable";
    public EconomyBridge(Plugin plugin) { this.plugin = plugin; hook(); }
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void hook() {
        provider = null; name = "Unavailable";
        Plugin vault = Bukkit.getPluginManager().getPlugin("Vault");
        if (vault == null || !vault.isEnabled()) return;
        try {
            Class<?> api = Class.forName("net.milkbowl.vault.economy.Economy", true, vault.getClass().getClassLoader());
            RegisteredServiceProvider<?> registration = Bukkit.getServicesManager().getRegistration((Class) api);
            if (registration == null) return;
            deposit = api.getMethod("depositPlayer", OfflinePlayer.class, double.class);
            success = deposit.getReturnType().getMethod("transactionSuccess");
            provider = registration.getProvider();
            name = String.valueOf(api.getMethod("getName").invoke(provider));
        } catch (ReflectiveOperationException | LinkageError error) {
            provider = null;
            plugin.getLogger().log(Level.WARNING, "Could not connect to Vault economy. Keys and XP are still available.", error);
        }
    }
    public boolean deposit(OfflinePlayer player, double amount) {
        if (amount <= 0) return true;
        if (provider == null) return false;
        try { return Boolean.TRUE.equals(success.invoke(deposit.invoke(provider, player, amount))); }
        catch (ReflectiveOperationException | RuntimeException error) {
            plugin.getLogger().log(Level.WARNING, "Economy deposit failed for " + player.getUniqueId() + "; not retried to avoid duplicate payments", error);
            return false;
        }
    }
    public boolean available() { return provider != null; }
    public String name() { return name; }
    @EventHandler public void registered(ServiceRegisterEvent event) { hook(); }
    @EventHandler public void unregistered(ServiceUnregisterEvent event) { hook(); }
}
