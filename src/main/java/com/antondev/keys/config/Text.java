package com.antondev.keys.config;

import java.util.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.*;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;

public final class Text {
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private final String prefix;
    private final Map<String, String> messages;
    public Text(ConfigurationSection config) {
        prefix = config.getString("messages.prefix", "");
        Map<String, String> map = new HashMap<>();
        var section = config.getConfigurationSection("messages");
        if (section != null) section.getKeys(false).forEach(key -> map.put(key, section.getString(key, "")));
        messages = Map.copyOf(map);
    }
    public static Component parse(String template, TagResolver... placeholders) {
        return MM.deserialize(template == null ? "" : template, placeholders);
    }
    public static TagResolver value(String name, Object value) { return Placeholder.unparsed(name, String.valueOf(value)); }
    public static TagResolver component(String name, Component value) { return Placeholder.component(name, value); }
    public Component message(String key, TagResolver... placeholders) {
        return parse(prefix + messages.getOrDefault(key, "<red>Missing message: " + key), placeholders);
    }
    public void send(CommandSender sender, String key, TagResolver... placeholders) { sender.sendMessage(message(key, placeholders)); }
}
