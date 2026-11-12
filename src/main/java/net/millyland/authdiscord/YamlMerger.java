package net.millyland.authdiscord;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Adds settings that a plugin update introduced to an existing user file, keeping everything the
 * user already has (values and their comments) and carrying over the bundled comments of the new keys.
 */
public final class YamlMerger {

    private YamlMerger() {
    }

    public static boolean mergeMissingKeys(JavaPlugin plugin, String resourcePath, File targetFile) {
        if (!targetFile.exists()) {
            return false;
        }

        FileConfiguration defaultConfig = loadBundledResource(plugin, resourcePath);
        if (defaultConfig == null) {
            return false;
        }

        FileConfiguration userConfig = YamlConfiguration.loadConfiguration(targetFile);

        // Decide what is missing before touching anything, so adding a section's children
        // does not hide the section itself from the comment pass below.
        List<String> missing = new ArrayList<>();
        for (String key : defaultConfig.getKeys(true)) {
            if (!userConfig.contains(key)) {
                missing.add(key);
            }
        }
        if (missing.isEmpty()) {
            return false;
        }

        for (String key : missing) {
            if (defaultConfig.isConfigurationSection(key)) {
                continue; // created implicitly by its children; empty sections are not worth adding
            }
            userConfig.set(key, defaultConfig.get(key));
        }
        for (String key : missing) {
            copyComments(defaultConfig, userConfig, key);
        }

        try {
            userConfig.save(targetFile);
            plugin.getLogger().info(targetFile.getName() + ": added " + missing.stream()
                    .filter(k -> !defaultConfig.isConfigurationSection(k)).count()
                    + " new setting(s) from the plugin update.");
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save merged " + targetFile.getName() + ": " + e.getMessage());
            return false;
        }
        return true;
    }

    private static void copyComments(ConfigurationSection from, ConfigurationSection to, String key) {
        if (!to.contains(key)) return;
        try {
            List<String> above = from.getComments(key);
            if (!above.isEmpty()) to.setComments(key, above);
            List<String> inline = from.getInlineComments(key);
            if (!inline.isEmpty()) to.setInlineComments(key, inline);
        } catch (RuntimeException ignored) {
            // Comments are cosmetic; never fail a merge over them.
        }
    }

    private static FileConfiguration loadBundledResource(JavaPlugin plugin, String resourcePath) {
        try (InputStream in = plugin.getResource(resourcePath)) {
            if (in == null) return null;
            try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return YamlConfiguration.loadConfiguration(reader);
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Could not read bundled resource " + resourcePath + ": " + e.getMessage());
            return null;
        }
    }
}
