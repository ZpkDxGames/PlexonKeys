package com.antondev.keys.gui;

import com.antondev.keys.model.KeyTier;
import java.util.*;
import java.util.function.Consumer;
import org.bukkit.inventory.*;
import org.bukkit.event.inventory.ClickType;
import org.jetbrains.annotations.NotNull;

public final class KeyMenu implements InventoryHolder {
    public enum Kind { PLAYER, ADMIN, CATEGORY, CHANCES, CHANCE_EDITOR, BROWSER }
    final UUID owner;
    final Kind kind;
    final long revision;
    final KeyTier tier;
    final String path;
    final int page;
    final Map<Integer, Consumer<ClickType>> actions = new HashMap<>();
    ChanceDraft chance;
    Inventory inventory;
    KeyMenu(UUID owner, Kind kind, long revision, KeyTier tier, String path, int page) {
        this.owner = owner; this.kind = kind; this.revision = revision; this.tier = tier; this.path = path; this.page = page;
    }
    @Override public @NotNull Inventory getInventory() { return inventory; }
}
