package com.antondev.keys.gui;

import com.antondev.keys.PlexonKeys;
import com.antondev.keys.config.*;
import com.antondev.keys.model.*;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private static final List<BigDecimal> CHANCE_STEPS = List.of("10", "1", "0.1", "0.01", "0.001").stream().map(BigDecimal::new).toList();
    private record Prompt(String path, long revision, long expiresAt, KeyMenu previous, AtomicBoolean received) {}
    private final PlexonKeys plugin;
    private final Map<UUID, Prompt> prompts = new ConcurrentHashMap<>();
    private final Set<UUID> pendingClicks = new HashSet<>();
    private final Set<UUID> refreshQueued = new HashSet<>();
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
        Map<KeyTier, Long> balances = plugin.data().balances(player.getUniqueId());
        long total = 0;
        for (KeyTier tier : KeyTier.values()) {
            long balance = balances.getOrDefault(tier, 0L); total += balance;
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
        if (!(currentMenu(player) instanceof KeyMenu menu) || menu.kind != KeyMenu.Kind.PLAYER
                || !menu.owner.equals(player.getUniqueId()) || menu.revision != plugin.configuration().revision()) return;
        UUID playerId = player.getUniqueId();
        if (!refreshQueued.add(playerId)) return;
        long delay = Math.max(1L, Math.min(20L, config().getInt("performance.menu-refresh-ticks", 2)));
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            refreshQueued.remove(playerId);
            if (!player.isOnline()) return;
            if (currentMenu(player) instanceof KeyMenu current && current.kind == KeyMenu.Kind.PLAYER
                    && current.owner.equals(playerId) && current.revision == plugin.configuration().revision()) {
                renderPlayer(player, current);
            }
        }, delay);
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
        button(menu, 22, "gui.admin.chances", click -> openChances(player));
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
            button(menu, slot, "gui.admin.chance", click -> openChanceEditor(player, tier, activity, menu), chanceTags(tier, activity));
            slot += 2;
        }
        button(menu, 45, "gui.admin.back", click -> openAdmin(player));
        button(menu, 49, "gui.admin.advanced", click -> openBrowser(player, path, 0));
        button(menu, 53, "gui.admin.close", click -> player.closeInventory());
        player.openInventory(menu.inventory);
    }
    private TagResolver category(KeyTier tier) { return Text.component("category", Text.parse(plugin.settings().categories().get(tier).display())); }
    private TagResolver activity(Activity activity) { return Text.component("activity", Text.parse(plugin.settings().tasks().get(activity).display())); }
    private TagResolver[] chanceTags(KeyTier tier, Activity activity) {
        return new TagResolver[] {category(tier), activity(activity),
                Text.value("value", BigDecimal.valueOf(plugin.settings().categories().get(tier).chances().get(activity)).stripTrailingZeros().toPlainString())};
    }
    public void openChances(Player player) {
        if (!allowed(player, "plexonkeys.admin")) return;
        KeyMenu menu = create(player, KeyMenu.Kind.CHANCES, 54, Text.parse(config().getString("gui.admin.chances-title")), null, "", 0);
        button(menu, 4, "gui.admin.chances", null);
        int column = 1;
        for (KeyTier tier : KeyTier.values()) {
            button(menu, column, "gui.admin.chance-category", click -> openCategory(player, tier), category(tier));
            for (Activity activity : Activity.values()) {
                int slot = column + 9 * (activity.ordinal() + 1);
                button(menu, slot, "gui.admin.chance", click -> openChanceEditor(player, tier, activity, menu), chanceTags(tier, activity));
            }
            column += 2;
        }
        button(menu, 45, "gui.admin.back", click -> openAdmin(player));
        button(menu, 53, "gui.admin.close", click -> player.closeInventory());
        player.openInventory(menu.inventory);
    }
    public void openChanceEditor(Player player, KeyTier tier, Activity activity) { openChanceEditor(player, tier, activity, null); }
    private void openChanceEditor(Player player, KeyTier tier, Activity activity, KeyMenu previous) {
        if (!allowed(player, "plexonkeys.admin")) return;
        KeyMenu menu = create(player, KeyMenu.Kind.CHANCE_EDITOR, 54,
                Text.parse(config().getString("gui.admin.chance-editor.title"), category(tier), activity(activity)),
                tier, "categories." + tier.id() + ".chances." + activity.id(), 0);
        menu.chance = new ChanceDraft(activity, plugin.settings().categories().get(tier).chances().get(activity), previous);
        renderChanceEditor(player, menu); player.openInventory(menu.inventory);
    }
    private void renderChanceEditor(Player player, KeyMenu menu) {
        ChanceDraft draft = menu.chance;
        String path = "gui.admin.chance-editor.";
        TagResolver[] tags = {category(menu.tier), activity(draft.activity), Text.value("value", draft.valueText()),
                Text.value("original", draft.originalText()), Text.value("changed", draft.changed()),
                Text.value("category_enabled", plugin.settings().categories().get(menu.tier).enabled()),
                Text.value("activity_enabled", plugin.settings().tasks().get(draft.activity).enabled()),
                Text.value("rewards_enabled", plugin.settings().enabled())};
        button(menu, 4, path + "context", null, tags);
        button(menu, 22, path + "value", null, tags);
        for (int i = 0; i < CHANCE_STEPS.size(); i++) {
            BigDecimal step = CHANCE_STEPS.get(i);
            button(menu, 11 + i, path + "decrease", click -> { draft.adjust(step.negate()); renderChanceEditor(player, menu); }, Text.value("step", step.toPlainString()));
            button(menu, 29 + i, path + "increase", click -> { draft.adjust(step); renderChanceEditor(player, menu); }, Text.value("step", step.toPlainString()));
        }
        button(menu, 38, path + "never", click -> { draft.never(); renderChanceEditor(player, menu); }, tags);
        button(menu, 40, path + "reset", click -> { draft.reset(); renderChanceEditor(player, menu); }, tags);
        button(menu, 42, path + "always", click -> { draft.always(); renderChanceEditor(player, menu); }, tags);
        button(menu, 45, path + "cancel", click -> returnFromChance(player, draft), tags);
        button(menu, 49, path + "apply", click -> applyChance(player, menu), tags);
        button(menu, 53, path + "exact", click -> prompt(player, menu, menu.path), tags);
    }
    private void returnFromChance(Player player, ChanceDraft draft) {
        if (draft.previous == null) openChances(player); else reopen(player, draft.previous);
    }
    private void applyChance(Player player, KeyMenu menu) {
        if (!menu.chance.changed()) { returnFromChance(player, menu.chance); return; }
        try {
            plugin.configuration().set(menu.path, menu.chance.valueText()); plugin.settingsChanged();
            plugin.settings().text().send(player, "chance-saved", category(menu.tier), activity(menu.chance.activity), Text.value("value", menu.chance.valueText()));
            returnFromChance(player, menu.chance);
        } catch (Exception error) { plugin.configError(player, error); }
    }
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
            case CHANCES -> openChances(player);
            case CHANCE_EDITOR -> { renderChanceEditor(player, menu); player.openInventory(menu.inventory); }
            case BROWSER -> openBrowser(player, menu.path, menu.page);
        }
    }
    private void prompt(Player player, KeyMenu menu, String path) {
        player.closeInventory();
        prompts.put(player.getUniqueId(), new Prompt(path, plugin.configuration().revision(), System.currentTimeMillis()
                + config().getInt("gui.admin.prompt-timeout-seconds") * 1000L, menu, new AtomicBoolean()));
        boolean chance = menu.kind == KeyMenu.Kind.CHANCE_EDITOR;
        plugin.settings().text().send(player, chance ? "chance-prompt" : "prompt", Text.value("path", path));
        plugin.settings().text().send(player, "prompt-current", Text.value("value", chance ? menu.chance.valueText() : preview(config().get(path))));
    }
    @EventHandler(priority = EventPriority.LOWEST)
    public void chat(AsyncChatEvent event) {
        Prompt prompt = prompts.get(event.getPlayer().getUniqueId());
        if (prompt == null || !prompt.received().compareAndSet(false, true)) return;
        event.setCancelled(true);
        String input = PlainTextComponentSerializer.plainText().serialize(event.originalMessage()).trim();
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player player = event.getPlayer();
            // Opening another menu, reloading, or quitting invalidates even an already queued reply.
            if (!prompts.remove(player.getUniqueId(), prompt)) return;
            if (!player.isOnline() || !allowed(player, "plexonkeys.admin")) return;
            if (System.currentTimeMillis() > prompt.expiresAt() || prompt.revision() != plugin.configuration().revision()) {
                plugin.settings().text().send(player, "prompt-expired"); return;
            }
            if (input.equalsIgnoreCase("cancel")) { plugin.settings().text().send(player, "prompt-cancelled"); reopen(player, prompt.previous()); return; }
            if (prompt.previous().kind == KeyMenu.Kind.CHANCE_EDITOR) {
                try { prompt.previous().chance.exact(input); }
                catch (IllegalArgumentException error) { plugin.settings().text().send(player, "chance-invalid"); }
                reopen(player, prompt.previous());
            } else edit(player, prompt.previous(), prompt.path(), input);
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
        UUID playerId = event.getPlayer().getUniqueId();
        prompts.remove(playerId); pendingClicks.remove(playerId); refreshQueued.remove(playerId);
        plugin.rewards().forget(playerId);
    }
    public void closeAll() {
        prompts.clear(); pendingClicks.clear(); refreshQueued.clear();
        for (Player player : List.copyOf(Bukkit.getOnlinePlayers())) if (currentMenu(player) instanceof KeyMenu) player.closeInventory();
    }
}
