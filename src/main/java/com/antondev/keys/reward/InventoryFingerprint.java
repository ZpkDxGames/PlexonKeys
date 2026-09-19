package com.antondev.keys.reward;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.bukkit.inventory.ItemStack;

/** Exact storage-inventory fingerprint used only for conservative claim crash reconciliation. */
public final class InventoryFingerprint {
    private InventoryFingerprint() {}

    public static String of(ItemStack[] contents) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            ByteBuffer length = ByteBuffer.allocate(4);
            for (ItemStack item : contents) {
                byte[] bytes = item == null || item.getType().isAir() ? new byte[0] : item.serializeAsBytes();
                length.clear();
                length.putInt(bytes.length);
                digest.update(length.array());
                digest.update(bytes);
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
