package net.millyland.authdiscord;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Messages from plugins/DiscordTgAuth/lang/&lt;language&gt;.yml (language is set in config.yml). Keys missing from
 * a file fall back to the bundled version, so updating the plugin never leaves blanks.
 */
final class Msg {

    private final DiscordTgAuthPlugin plugin;
    private volatile YamlConfiguration lang = new YamlConfiguration();

    Msg(DiscordTgAuthPlugin plugin) {
        this.plugin = plugin;
        load();
    }

    /** (Re)loads the language file chosen in config.yml, creating the default files on first start. */
    void load() {
        File dir = new File(plugin.getDataFolder(), "lang");
        for (String name : new String[]{"en", "ru"}) {
            if (!new File(dir, name + ".yml").exists()) {
                plugin.saveResource("lang/" + name + ".yml", false);
            } else {
                YamlMerger.mergeMissingKeys(plugin, "lang/" + name + ".yml", new File(dir, name + ".yml"));
            }
        }

        String code = String.valueOf(plugin.getConfig().getString("language", "en")).trim().toLowerCase(Locale.ROOT);
        File file = new File(dir, code + ".yml");
        if (!file.exists()) {
            plugin.getLogger().warning("Language file lang/" + code + ".yml does not exist, using English.");
            code = "en";
            file = new File(dir, "en.yml");
        }

        YamlConfiguration loaded = YamlConfiguration.loadConfiguration(file);
        InputStream bundled = plugin.getResource("lang/" + code + ".yml");
        if (bundled == null) bundled = plugin.getResource("lang/en.yml");
        if (bundled != null) {
            loaded.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(bundled, StandardCharsets.UTF_8)));
        }
        this.lang = loaded;
    }

    /** Raw text with placeholders replaced; pairs are (placeholder, value, placeholder, value ...). */
    String raw(String key, String... pairs) {
        String text = lang.getString(key);
        if (text == null) text = key;
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            text = text.replace(pairs[i], pairs[i + 1]);
        }
        return text;
    }

    /** In-game message ({@code &} colour codes) as an Adventure component. */
    Component comp(String key, String... pairs) {
        return fromRaw(raw(key, pairs));
    }

    /** Makes every occurrence of {@code code} in the message clickable: a click copies it to the clipboard. */
    Component withCopy(Component message, String code) {
        Component hover = comp("copy.hover");
        return message.replaceText(net.kyori.adventure.text.TextReplacementConfig.builder()
                .matchLiteral(code)
                .replacement((match, builder) -> builder
                        .clickEvent(net.kyori.adventure.text.event.ClickEvent.copyToClipboard(code))
                        .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(hover)))
                .build());
    }

    /** An already looked-up line ({@code &} colour codes) as an Adventure component. */
    Component fromRaw(String text) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(text);
    }

    /** A list of lines (e.g. the body of a dialog) with placeholders replaced. */
    List<String> list(String key, String... pairs) {
        List<String> out = new ArrayList<>();
        for (String line : lang.getStringList(key)) {
            for (int i = 0; i + 1 < pairs.length; i += 2) {
                line = line.replace(pairs[i], pairs[i + 1]);
            }
            out.add(line);
        }
        return out;
    }
}
