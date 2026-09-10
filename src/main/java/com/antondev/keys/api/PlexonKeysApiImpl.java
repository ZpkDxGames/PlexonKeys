package com.antondev.keys.api;

import com.antondev.keys.PlexonKeys;
import com.antondev.keys.config.Settings;
import com.antondev.keys.integration.papi.OptionalPlaceholderIntegration;
import com.antondev.keys.model.KeyTier;
import com.antondev.keys.service.KeyBalanceService;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Internal implementation registered through Bukkit ServicesManager. */
public final class PlexonKeysApiImpl implements PlexonKeysAPI {
    private final PlexonKeys plugin;
    private final KeyBalanceService balances;

    public PlexonKeysApiImpl(PlexonKeys plugin, KeyBalanceService balances) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.balances = Objects.requireNonNull(balances, "balances");
        OptionalPlaceholderIntegration.install(plugin);
    }

    @Override public long balance(UUID playerId, KeyTier tier) {
        requirePrimaryThread();
        return balances.balance(playerId, tier);
    }

    @Override public Map<KeyTier, Long> balances(UUID playerId) {
        requirePrimaryThread();
        return balances.balances(playerId);
    }

    @Override public long grant(UUID playerId, KeyTier tier, long amount, KeySource source) {
        requirePrimaryThread();
        UUID id = Objects.requireNonNull(playerId, "playerId");
        KeySource keySource = Objects.requireNonNull(source, "source");
        Player player = Bukkit.getPlayer(id);
        if (player == null) {
            throw new IllegalStateException("PlexonKeysAPI.grant requires an online player so the earned-event contract can be fulfilled");
        }
        return balances.grant(player, tier, amount, keySource.id());
    }

    @Override public long take(UUID playerId, KeyTier tier, long amount, KeySource source) {
        requirePrimaryThread();
        Objects.requireNonNull(source, "source");
        return balances.take(playerId, tier, amount);
    }

    @Override public Optional<ItemStack> keyTemplate(KeyTier tier) {
        requirePrimaryThread();
        KeyTier keyTier = Objects.requireNonNull(tier, "tier");
        ItemStack template = plugin.settings().categories().get(keyTier).itemCopy();
        return Optional.of(template.clone());
    }

    @Override public boolean isTierEnabled(KeyTier tier) {
        requirePrimaryThread();
        return plugin.settings().categories().get(Objects.requireNonNull(tier, "tier")).enabled();
    }

    @Override public Map<String, KeyDefinitionView> keyDefinitions() {
        requirePrimaryThread();
        Map<String, KeyDefinitionView> result = new LinkedHashMap<>();
        for (KeyTier tier : KeyTier.values()) result.put(tier.id(), definition(tier));
        return Map.copyOf(result);
    }

    @Override public Optional<KeyDefinitionView> resolveKeyDefinition(String keyId) {
        requirePrimaryThread();
        try {
            return Optional.of(definition(KeyTier.parse(Objects.requireNonNull(keyId, "keyId"))));
        } catch (IllegalArgumentException error) {
            return Optional.empty();
        }
    }

    @Override public KeyConsumeResult consumeKey(UUID playerId, String keyId, long amount, String transactionId) {
        requirePrimaryThread();
        KeyTier tier = KeyTier.parse(Objects.requireNonNull(keyId, "keyId"));
        return balances.consume(playerId, tier, amount, transactionId);
    }

    @Override public Optional<String> identifyPhysicalKey(ItemStack item) {
        requirePrimaryThread();
        Objects.requireNonNull(item, "item");
        if (item.getType().isAir()) return Optional.empty();
        ItemStack candidate = item.clone();
        candidate.setAmount(1);
        for (KeyTier tier : KeyTier.values()) {
            ItemStack template = plugin.settings().categories().get(tier).itemCopy();
            template.setAmount(1);
            if (candidate.isSimilar(template)) return Optional.of(tier.id());
        }
        return Optional.empty();
    }

    private KeyDefinitionView definition(KeyTier tier) {
        Settings.Category category = plugin.settings().categories().get(tier);
        String path = "categories." + tier.id();
        String mode = plugin.settings().yaml().getString(path + ".item.mode", "CONFIG").trim().toUpperCase(Locale.ROOT);
        String crate = plugin.settings().yaml().getString("integrations.crates.mappings." + tier.id(), tier.id());
        boolean visible = plugin.settings().yaml().getBoolean(path + ".visible", true);
        boolean claimable = plugin.settings().yaml().getBoolean(path + ".claimable", true);
        return new KeyDefinitionView(tier.id(), category.display(), category.permission(), category.enabled(),
                visible, claimable, mode, crate == null ? tier.id() : crate, category.chances());
    }

    private static void requirePrimaryThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("PlexonKeysAPI 2.x must be called from the primary server thread");
        }
    }
}
