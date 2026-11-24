/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package at.auroracraft.spawnelytra.config;

import at.auroracraft.spawnelytra.spawn.SpawnRegistry;
import at.auroracraft.spawnelytra.spawn.SpawnSettings;
import at.auroracraft.spawnelytra.util.BackupUtil;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Brings an existing config.yml up to the current layout.
 *
 * <ul>
 *   <li>2.0 ({@code defaults} + {@code spawns}): adds keys that are missing.</li>
 *   <li>1.4 / 1.5 ({@code worlds.<world>}): every world becomes one spawn.</li>
 *   <li>1.2 / 1.3 (flat keys): converted to the 1.5 layout first, then as above.</li>
 * </ul>
 * The previous file is always backed up to {@code backups/} before it changes.
 */
public enum ConfigUpdater {
    ;
    public static final String CURRENT_CONFIG_VERSION = "2.0";

    /** Global sections copied 1:1 from an old config (only keys the new template still knows). */
    private static final List<String> GLOBAL_SECTIONS = List.of(
            "language", "game_modes", "bedrock", "messages", "visualization", "hunger_consumption");

    public static void updateConfig(final JavaPlugin plugin) {
        final File configFile = new File(plugin.getDataFolder(), "config.yml");
        if (!configFile.exists()) {
            plugin.saveDefaultConfig();
            plugin.reloadConfig();
            return;
        }

        final YamlConfiguration current = YamlConfiguration.loadConfiguration(configFile);
        final YamlConfiguration template = ConfigUpdater.loadTemplate(plugin);
        if (template == null) {
            plugin.getLogger().warning("Bundled config.yml is missing, skipping config update.");
            return;
        }

        try {
            final YamlConfiguration result = ConfigUpdater.convert(current, template, plugin.getLogger());
            if (result != null) {
                BackupUtil.backupFile(plugin, configFile, "config/config.yml");
                result.save(configFile);
                plugin.getLogger().info("config.yml updated to " + CURRENT_CONFIG_VERSION + ". The previous file is in the backups folder.");
            }
        } catch (final IOException e) {
            plugin.getLogger().severe("Failed to update config.yml: " + e.getMessage());
        }
        plugin.reloadConfig();
    }

    /**
     * Converts a loaded config to the current layout.
     *
     * @return the config to write, or null when nothing changed
     */
    static YamlConfiguration convert(final YamlConfiguration current, final YamlConfiguration template, final Logger logger) {
        if (current.contains("spawns") || current.contains("defaults")) {
            return ConfigUpdater.fillMissing(current, template) ? current : null;
        }
        if (current.contains("worlds")) {
            logger.info("Converting 1.4/1.5 config (one area per world) to " + CURRENT_CONFIG_VERSION + " spawns...");
            ConfigUpdater.migrateWorlds(logger, current, template);
        } else if (ConfigUpdater.isFlatLegacy(current)) {
            logger.info("Converting 1.2/1.3 config to " + CURRENT_CONFIG_VERSION + " spawns...");
            ConfigUpdater.migrateWorlds(logger, ConfigUpdater.flatToWorlds(current), template);
        } else {
            logger.warning("Unknown config format. The old file is backed up, a fresh config is used.");
        }
        return template;
    }

    private static YamlConfiguration loadTemplate(final JavaPlugin plugin) {
        try (InputStream in = plugin.getResource("config.yml")) {
            if (in == null) {
                return null;
            }
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (final IOException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ 2.0: fill gaps

    /** Adds every template key (with its comments) that the config lacks. The example spawn is never added. */
    private static boolean fillMissing(final YamlConfiguration config, final YamlConfiguration template) {
        boolean changed = false;
        for (final String key : template.getKeys(true)) {
            if ("spawns".equals(key) || key.startsWith("spawns.")) {
                continue;
            }
            if (template.isConfigurationSection(key)) {
                if (!config.contains(key)) {
                    config.createSection(key);
                    config.setComments(key, template.getComments(key));
                    changed = true;
                }
            } else if (!config.isSet(key)) {
                config.set(key, template.get(key));
                config.setComments(key, template.getComments(key));
                changed = true;
            }
        }
        if (!config.contains("spawns")) {
            config.createSection("spawns");
            config.setComments("spawns", template.getComments("spawns"));
            changed = true;
        }
        if (!CURRENT_CONFIG_VERSION.equals(config.getString("config_version"))) {
            config.set("config_version", CURRENT_CONFIG_VERSION);
            changed = true;
        }
        return changed;
    }

    // ------------------------------------------------------------------ 1.4 / 1.5 -> 2.0

    /** Writes globals and one spawn per {@code worlds.<world>} entry of {@code old} into {@code target}. */
    private static void migrateWorlds(final Logger logger, final YamlConfiguration old, final YamlConfiguration target) {
        for (final String global : GLOBAL_SECTIONS) {
            if (old.isConfigurationSection(global)) {
                final ConfigurationSection section = old.getConfigurationSection(global);
                for (final String key : section.getKeys(true)) {
                    final String path = global + "." + key;
                    if (!section.isConfigurationSection(key) && target.isSet(path)) {
                        target.set(path, section.get(key));
                    }
                }
            } else if (old.isSet(global) && target.isSet(global)) {
                target.set(global, old.get(global));
            }
        }
        if (old.isSet("first_install_completed")) {
            target.set("first_install_completed", old.getBoolean("first_install_completed"));
        }
        if (old.getBoolean("fireworks.disable_in_spawn_elytra", false)) {
            target.set("defaults.fireworks.max_rockets", 0);
        }

        target.set("spawns.world", null);
        final ConfigurationSection worlds = old.getConfigurationSection("worlds");
        if (worlds == null) {
            return;
        }
        final Set<String> used = new HashSet<>();
        for (final String worldName : worlds.getKeys(false)) {
            final ConfigurationSection ws = worlds.getConfigurationSection(worldName);
            if (ws == null) {
                continue;
            }
            final String name = ConfigUpdater.uniqueName(worldName, used);
            final String base = "spawns." + name;
            target.set(base + ".world", worldName);
            target.set(base + ".enabled", ws.getBoolean("enabled", true));

            final boolean advanced = "advanced".equalsIgnoreCase(ws.getString("spawn_area.mode", "auto"));
            final boolean rectangular = "rectangular".equalsIgnoreCase(ws.getString("spawn_area.area_type", "circular"));
            final double x = ws.getDouble("spawn_area.x");
            final double z = ws.getDouble("spawn_area.z");
            final double x2 = ws.getDouble("spawn_area.x2");
            final double y2 = ws.getDouble("spawn_area.y2");
            final double z2 = ws.getDouble("spawn_area.z2");
            final boolean secondCornerSet = x2 != 0 || y2 != 0 || z2 != 0;

            if (advanced && rectangular && secondCornerSet) {
                target.set(base + ".shape", "rectangle");
                target.set(base + ".pos1.x", (int) Math.floor(x));
                target.set(base + ".pos1.z", (int) Math.floor(z));
                target.set(base + ".pos2.x", (int) Math.floor(x2));
                target.set(base + ".pos2.z", (int) Math.floor(z2));
            } else {
                target.set(base + ".shape", "circle");
                target.set(base + ".radius", ws.getDouble("radius", 100));
                if (advanced) {
                    target.set(base + ".center.x", x);
                    target.set(base + ".center.z", z);
                }
            }

            for (final String key : SpawnSettings.OVERRIDABLE_KEYS) {
                if (ws.isSet(key) && !ConfigUpdater.sameValue(ws.get(key), target.get("defaults." + key))) {
                    target.set(base + "." + key, ws.get(key));
                }
            }
            logger.info("  world '" + worldName + "' -> spawn '" + name + "'");
        }
    }

    private static String uniqueName(final String worldName, final Set<String> used) {
        String base = worldName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_");
        if (base.isEmpty()) {
            base = "spawn";
        }
        if (base.length() > 28) {
            base = base.substring(0, 28);
        }
        String name = base;
        for (int i = 2; !SpawnRegistry.isValidName(name) || !used.add(name); i++) {
            name = base + "_" + i;
        }
        return name;
    }

    private static boolean sameValue(final Object a, final Object b) {
        if (a == null || b == null) {
            return a == b;
        }
        if (a instanceof final Number na && b instanceof final Number nb) {
            return Double.compare(na.doubleValue(), nb.doubleValue()) == 0;
        }
        return String.valueOf(a).equalsIgnoreCase(String.valueOf(b));
    }

    // ------------------------------------------------------------------ 1.2 / 1.3 -> 1.5 layout

    private static boolean isFlatLegacy(final YamlConfiguration config) {
        return config.contains("activation_mode") || config.contains("radius")
                || config.contains("world") || config.contains("boost_enabled");
    }

    /** Re-shapes the flat 1.2/1.3 keys into the 1.5 {@code worlds.<world>} layout. */
    private static YamlConfiguration flatToWorlds(final YamlConfiguration old) {
        final YamlConfiguration out = new YamlConfiguration();
        ConfigUpdater.copy(old, "language", out, "language");
        ConfigUpdater.copy(old, "disable_in_creative", out, "game_modes.disable_in_creative");
        ConfigUpdater.copy(old, "disable_in_adventure", out, "game_modes.disable_in_adventure");
        ConfigUpdater.copy(old, "messages.show_press_to_boost", out, "messages.show_press_to_boost");
        ConfigUpdater.copy(old, "messages.show_boost_activated", out, "messages.show_boost_activated");
        ConfigUpdater.copy(old, "disable_fireworks_in_spawn_elytra", out, "fireworks.disable_in_spawn_elytra");

        final String p = "worlds." + old.getString("world", "world") + ".";
        ConfigUpdater.copy(old, "activation_mode", out, p + "activation_mode");
        ConfigUpdater.copy(old, "radius", out, p + "radius");
        ConfigUpdater.copy(old, "strength", out, p + "boost.strength");
        ConfigUpdater.copy(old, "boost_enabled", out, p + "boost.enabled");
        ConfigUpdater.copy(old, "boost_direction", out, p + "boost.direction");
        ConfigUpdater.copy(old, "boost_sound", out, p + "boost.sound");
        ConfigUpdater.copy(old, "f_key_launch_strength", out, p + "f_key.launch_strength");

        out.set(p + "spawn_area.mode", old.getString("spawn.mode", old.getString("mode", "auto")));
        for (final String axis : List.of("x", "y", "z", "x2", "y2", "z2")) {
            ConfigUpdater.copy(old, "spawn." + axis, out, p + "spawn_area." + axis);
        }
        final boolean corner = old.getDouble("spawn.x2") != 0 || old.getDouble("spawn.y2") != 0 || old.getDouble("spawn.z2") != 0;
        out.set(p + "spawn_area.area_type", corner ? "rectangular" : "circular");
        return out;
    }

    private static void copy(final YamlConfiguration from, final String fromPath, final YamlConfiguration to, final String toPath) {
        if (from.isSet(fromPath) && !from.isConfigurationSection(fromPath)) {
            to.set(toPath, from.get(fromPath));
        }
    }
}
