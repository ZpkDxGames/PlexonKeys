package com.antondev.keys.gui;

import com.antondev.keys.PlexonKeys;
import com.antondev.keys.config.*;
import com.antondev.keys.model.*;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

public final class MenuService implements Listener {
    private record Prompt(String path, long revision, long expiresAt, KeyMenu previous) {}
    private final PlexonKeys plugin;
    private final Map<UUID, Prompt> prompts = new ConcurrentHashMap<>();
    private final Set<UUID> pendingClicks = new HashSet<>();
    public MenuService(PlexonKeys plugin) { this.plugin = plugin; }
    private ConfigurationSection config() { return plugin.settings().yaml(); }
    private KeyMenu create(Player player, KeyMenu.Kind kind, int size, Component title, KeyTier tier, String path, int page) {
        prompts.remove(player.getUniqueId());
        KeyMenu menu = new KeyMenu(player.getUniqueId(), kind, plugin.configuration().revision(), tier, path, page);
        menu.inventory = Bukkit.createInventory(menu, size, title);
        ItemStack filler = Items.fromConfig(config(), kind == KeyMenu.Kind.PLAYER ? "gui.player.filler" : "gui.admin.filler");
        for (int slot = 0; slot < size; slot++) menu.inventory.setItem(slot, filler);
        return menu;
    }
    private boolean allowed(Player player, String permission) {
        if (player.hasPermission(permission)) return true;
        plugin.settings().text().send(player, "no-permission"); return false;
    }
    private void button(KeyMenu menu, int slot, String path, Consumer<ClickType> action, TagResolver... tags) {
        menu.inventory.setItem(slot, Items.fromConfig(config(), path, tags));
        if (action != null) menu.actions.put(slot, action);
    }
    public void openPlayer(Player player) {
        if (!allowed(player, "plexonkeys.use")) return;
        KeyMenu menu = create(player, KeyMenu.Kind.PLAYER, config().getInt("gui.player.size"), Text.parse(config().getString("gui.player.title")), null, "", 0);
        renderPlayer(player, menu); player.openInventory(menu.inventory);
    }
    private void renderPlayer(Player player, KeyMenu menu) {
        long total = 0;
        for (KeyTier tier : KeyTier.values()) {
            long balance = plugin.data().balance(player.getUniqueId(), tier); total += balance;
            String base = "categories." + tier.id();
            TagResolver[] tags = {Text.component("category", Text.parse(plugin.settings().categories().get(tier).display())), Text.value("balance", balance)};
            button(menu, config().getInt(base + ".menu.icon-slot"), base + ".menu", null, tags);
            button(menu, config().getInt(base + ".menu.claim-slot"), balance > 0 ? "gui.player.claimable" : "gui.player.empty",
                    click -> plugin.claims().claim(player, tier, click == ClickType.RIGHT), tags);
        }
        var totalTag = Text.value("total", total);
        button(menu, config().getInt("gui.player.summary.slot"), "gui.player.summary", null, totalTag);
        button(menu, config().getInt("gui.player.claim-all.slot"), "gui.player.claim-all", click -> plugin.claims().claim(player, null, false), totalTag);
        button(menu, config().getInt("gui.player.close.slot"), "gui.player.close", click -> player.closeInventory());
        if (player.hasPermission("plexonkeys.admin")) button(menu, config().getInt("gui.player.admin.slot"), "gui.player.admin", click -> openAdmin(player));
    }
    public void refreshPlayer(Player player) {
        if (currentMenu(player) instanceof KeyMenu menu && menu.kind == KeyMenu.Kind.PLAYER
                && menu.owner.equals(player.getUniqueId()) && menu.revision == plugin.configuration().revision()) renderPlayer(player, menu);
    }
    private Object currentMenu(Player player) {
        var view = player.getOpenInventory();
        var top = view == null ? null : view.getTopInventory();
        return top == null ? null : top.getHolder();
    }
    public void openAdmin(Player player) {
        if (!allowed(player, "plexonkeys.admin")) return;
        KeyMenu menu = create(player, KeyMenu.Kind.ADMIN, 54, Text.parse(config().getString("gui.admin.title")), null, "", 0);
        int slot = 10;
        for (KeyTier tier : KeyTier.values()) {
            button(menu, slot, "gui.admin.category", click -> openCategory(player, tier), category(tier)); slot += 2;
        }
        button(menu, 4, "gui.admin.status", null, plugin.statusTags());
        button(menu, 31, "gui.admin.enabled", click -> toggle(player, menu, "settings.enabled"), Text.value("value", plugin.settings().enabled()));
        button(menu, 39, "gui.admin.save", click -> plugin.saveData(player));
        button(menu, 40, "gui.admin.advanced", click -> openBrowser(player, "", 0));
        button(menu, 41, "gui.admin.reload", click -> {
            if (plugin.reloadFor(player)) openAdmin(player);
        });
        button(menu, 49, "gui.admin.close", click -> player.closeInventory());
        player.openInventory(menu.inventory);
    }
    public void openCategory(Player player, KeyTier tier) {
        if (!allowed(player, "plexonkeys.admin")) return;
        String path = "categories." + tier.id();
        KeyMenu menu = create(player, KeyMenu.Kind.CATEGORY, 54, Text.parse(config().getString("gui.admin.category-title"), category(tier)), tier, path, 0);
        var c = plugin.settings().categories().get(tier);
        menu.inventory.setItem(4, c.itemCopy());
        button(menu, 10, "gui.admin.enabled", click -> toggle(player, menu, path + ".enabled"), Text.value("value", c.enabled()));
        button(menu, 12, "gui.admin.announce", click -> toggle(player, menu, path + ".announce.enabled"), Text.value("value", c.announce()));
        button(menu, 14, "gui.admin.money", click -> {
            if (click == ClickType.RIGHT) prompt(player, menu, path + ".bonus.money.amount"); else toggle(player, menu, path + ".bonus.money.enabled");
        }, Text.value("value", c.moneyEnabled()), Text.value("amount", c.money()));
        button(menu, 16, "gui.admin.xp", click -> {
            if (click == ClickType.RIGHT) prompt(player, menu, path + ".bonus.xp.points"); else toggle(player, menu, path + ".bonus.xp.enabled");
        }, Text.value("value", c.xpEnabled()), Text.value("amount", c.xp()));
        button(menu, 22, "gui.admin.capture", click -> {
            try {
                plugin.configuration().capture(tier, player.getInventory().getItemInMainHand()); plugin.settingsChanged();
                plugin.settings().text().send(player, "item-captured", category(tier)); openCategory(player, tier);
            } catch (Exception error) { plugin.configError(player, error); }
        });
        int slot = 28;
        for (Activity activity : Activity.values()) {
            button(menu, slot, "gui.admin.chance", click -> prompt(player, menu, path + ".chances." + activity.id()),
                    Text.value("activity", plugin.settings().tasks().get(activity).display()), Text.value("value", c.chances().get(activity)));
            slot += 2;
        }
        button(menu, 45, "gui.admin.back", click -> openAdmin(player));
        button(menu, 49, "gui.admin.advanced", click -> openBrowser(player, path, 0));
        button(menu, 53, "gui.admin.close", click -> player.closeInventory());
        player.openInventory(menu.inventory);
    }
    private TagResolver category(KeyTier tier) { return Text.component("category", Text.parse(plugin.settings().categories().get(tier).display())); }
    public void openBrowser(Player player, String path, int page) {
        if (!allowed(player, "plexonkeys.admin")) return;
        ConfigurationSection section = path.isEmpty() ? config() : config().getConfigurationSection(path);
        if (section == null) { openAdmin(player); return; }
        List<String> keys = new ArrayList<>(section.getKeys(false)); keys.remove("config-version");
        int pages = Math.max(1, (keys.size() + 44) / 45), current = Math.max(0, Math.min(page, pages - 1));
        KeyMenu menu = create(player, KeyMenu.Kind.BROWSER, 54, Text.parse(config().getString("gui.admin.browser-title"), Text.value("page", (current + 1) + "/" + pages)), null, path, current);
        for (int i = 0; i < 45 && i + current * 45 < keys.size(); i++) {
            String key = keys.get(i + current * 45), full = path.isEmpty() ? key : path + "." + key;
            boolean group = section.isConfigurationSection(key);
            String itemPath = group ? "gui.admin.section" : full.endsWith(".base64") ? "gui.admin.captured" : "gui.admin.setting";
            button(menu, i, itemPath, click -> {
                if (group) openBrowser(player, full, 0);
                else if (full.endsWith(".base64")) plugin.settings().text().send(player, "admin-help");
                else if (config().get(full) instanceof Boolean) toggle(player, menu, full);
                else prompt(player, menu, full);
            }, Text.value("key", key), Text.value("path", full), Text.value("value", preview(section.get(key))));
        }
        button(menu, 45, "gui.admin.back", click -> {
            if (path.isEmpty()) openAdmin(player);
            else openBrowser(player, path.contains(".") ? path.substring(0, path.lastIndexOf('.')) : "", 0);
        });
        if (current > 0) button(menu, 48, "gui.admin.previous", click -> openBrowser(player, path, current - 1));
        button(menu, 49, "gui.admin.advanced", click -> openBrowser(player, "", 0));
        if (current + 1 < pages) button(menu, 50, "gui.admin.next", click -> openBrowser(player, path, current + 1));
        button(menu, 53, "gui.admin.close", click -> player.closeInventory());
        player.openInventory(menu.inventory);
    }
    private static String preview(Object value) {
        if (value instanceof ConfigurationSection) return "";
        String string = String.valueOf(value);
        return string.length() > 80 ? string.substring(0, 77) + "..." : string;
    }
    private void toggle(Player player, KeyMenu menu, String path) { edit(player, menu, path, String.valueOf(!config().getBoolean(path))); }
    private void edit(Player player, KeyMenu previous, String path, String value) {
        if (!allowed(player, "plexonkeys.admin")) return;
        try {
            plugin.configuration().set(path, value); plugin.settingsChanged();
            plugin.settings().text().send(player, "setting-saved", Text.value("path", path)); reopen(player, previous);
        } catch (Exception error) { plugin.configError(player, error); reopen(player, previous); }
    }
    private void reopen(Player player, KeyMenu menu) {
        switch (menu.kind) {
            case PLAYER -> openPlayer(player);
            case ADMIN -> openAdmin(player);
            case CATEGORY -> openCategory(player, menu.tier);
            case BROWSER -> openBrowser(player, menu.path, menu.page);
        }
    }
    private void prompt(Player player, KeyMenu menu, String path) {
        player.closeInventory();
        prompts.put(player.getUniqueId(), new Prompt(path, plugin.configuration().revision(), System.currentTimeMillis()
                + config().getInt("gui.admin.prompt-timeout-seconds") * 1000L, menu));
        plugin.settings().text().send(player, "prompt", Text.value("path", path));
        plugin.settings().text().send(player, "prompt-current", Text.value("value", preview(config().get(path))));
    }
    @EventHandler(priority = EventPriority.LOWEST)
    public void chat(AsyncChatEvent event) {
        Prompt prompt = prompts.remove(event.getPlayer().getUniqueId());
        if (prompt == null) return;
        event.setCancelled(true);
        String input = PlainTextComponentSerializer.plainText().serialize(event.originalMessage()).trim();
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player player = event.getPlayer();
            if (!player.isOnline() || !allowed(player, "plexonkeys.admin")) return;
            if (System.currentTimeMillis() > prompt.expiresAt() || prompt.revision() != plugin.configuration().revision()) {
                plugin.settings().text().send(player, "prompt-expired"); return;
            }
            if (input.equalsIgnoreCase("cancel")) { plugin.settings().text().send(player, "prompt-cancelled"); reopen(player, prompt.previous()); return; }
            edit(player, prompt.previous(), prompt.path(), input);
        });
    }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void clicked(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof KeyMenu menu)) return;
        event.setCancelled(true); // Includes bottom inventory, shift-click, number keys, drop, double-click, and creative clones.
        if (!(event.getWhoClicked() instanceof Player player) || !menu.owner.equals(player.getUniqueId())) return;
        if (event.getRawSlot() < 0 || event.getRawSlot() >= menu.inventory.getSize()) return;
        if (event.getClick() != ClickType.LEFT && event.getClick() != ClickType.RIGHT) return;
        Consumer<ClickType> action = menu.actions.get(event.getRawSlot());
        if (action == null || !pendingClicks.add(player.getUniqueId())) return;
        ClickType click = event.getClick();
        Bukkit.getScheduler().runTask(plugin, () -> {
            pendingClicks.remove(player.getUniqueId());
            if (!player.isOnline() || player.getOpenInventory().getTopInventory() != menu.inventory) return;
            if (menu.revision != plugin.configuration().revision()) { player.closeInventory(); return; }
            if (!allowed(player, menu.kind == KeyMenu.Kind.PLAYER ? "plexonkeys.use" : "plexonkeys.admin")) { player.closeInventory(); return; }
            action.accept(click);
        });
    }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void dragged(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof KeyMenu) event.setCancelled(true);
    }
    @EventHandler public void quit(PlayerQuitEvent event) {
        prompts.remove(event.getPlayer().getUniqueId()); pendingClicks.remove(event.getPlayer().getUniqueId());
        plugin.rewards().forget(event.getPlayer().getUniqueId());
    }
    public void closeAll() {
        prompts.clear(); pendingClicks.clear();
        for (Player player : List.copyOf(Bukkit.getOnlinePlayers())) if (currentMenu(player) instanceof KeyMenu) player.closeInventory();
    }
}
