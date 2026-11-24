/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package at.auroracraft.spawnelytra.spawn;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Loads the spawns from the {@code spawns} section of config.yml and answers
 * "which spawn is at this location". It is the only class that writes spawn
 * data back to the config.
 *
 * <p>The maps are replaced as a whole on every load, so readers on other
 * (Folia region) threads always see a consistent snapshot.</p>
 */
public final class SpawnRegistry {
    public static final Pattern NAME_PATTERN = Pattern.compile("[a-z0-9_-]{1,32}");
    private static final double DEFAULT_RADIUS = 100;

    private final JavaPlugin plugin;
    private volatile Map<String, Spawn> byName = Map.of();
    private volatile Map<String, List<Spawn>> byWorld = Map.of();

    public SpawnRegistry(final JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        final FileConfiguration config = this.plugin.getConfig();
        final ConfigurationSection defaults = config.getConfigurationSection("defaults");
        final ConfigurationSection spawns = config.getConfigurationSection("spawns");

        final Map<String, Spawn> names = new LinkedHashMap<>();
        final Map<String, List<Spawn>> worlds = new HashMap<>();

        if (spawns != null) {
            for (final String key : spawns.getKeys(false)) {
                final ConfigurationSection section = spawns.getConfigurationSection(key);
                if (section == null) {
                    continue;
                }
                final Spawn spawn = this.parse(key, section, defaults);
                if (spawn == null) {
                    continue;
                }
                final Spawn clash = SpawnRegistry.findOverlapIn(worlds.get(spawn.worldName()), spawn.shape(), null);
                if (clash != null) {
                    this.plugin.getLogger().warning("[" + key + "] Overlaps spawn '" + clash.name()
                            + "' in world '" + spawn.worldName() + "'. Spawns must not overlap, skipping '" + key + "'.");
                    continue;
                }
                names.put(key.toLowerCase(Locale.ROOT), spawn);
                worlds.computeIfAbsent(spawn.worldName(), w -> new ArrayList<>()).add(spawn);
            }
        }

        worlds.replaceAll((w, list) -> List.copyOf(list));
        this.byName = Collections.unmodifiableMap(names);
        this.byWorld = Map.copyOf(worlds);

        final long active = names.values().stream().filter(Spawn::enabled).count();
        this.plugin.getLogger().info("Loaded " + names.size() + " spawn(s), " + active + " active.");
    }

    private Spawn parse(final String name, final ConfigurationSection s, final ConfigurationSection defaults) {
        final String worldName = s.getString("world");
        if (worldName == null || worldName.isBlank()) {
            this.plugin.getLogger().warning("[" + name + "] Missing 'world', skipping spawn.");
            return null;
        }

        final String type = s.getString("shape", "circle").toLowerCase(Locale.ROOT);
        final SpawnShape shape;
        boolean followsWorldSpawn = false;
        try {
            if ("rectangle".equals(type)) {
                if (!s.isConfigurationSection("pos1") || !s.isConfigurationSection("pos2")) {
                    this.plugin.getLogger().warning("[" + name + "] Rectangle spawns need 'pos1' and 'pos2', skipping.");
                    return null;
                }
                shape = SpawnShape.Rectangle.ofBlocks(
                        (int) Math.floor(s.getDouble("pos1.x")), (int) Math.floor(s.getDouble("pos1.z")),
                        (int) Math.floor(s.getDouble("pos2.x")), (int) Math.floor(s.getDouble("pos2.z")));
            } else if ("circle".equals(type)) {
                final double radius = s.getDouble("radius", DEFAULT_RADIUS);
                final double cx;
                final double cz;
                if (s.isConfigurationSection("center")) {
                    cx = s.getDouble("center.x");
                    cz = s.getDouble("center.z");
                } else {
                    final World world = Bukkit.getWorld(worldName);
                    if (world == null) {
                        this.plugin.getLogger().warning("[" + name + "] World '" + worldName
                                + "' is not loaded, so its world spawn is unknown. Set 'center' or load the world first. Skipping.");
                        return null;
                    }
                    final Location spawnPoint = world.getSpawnLocation();
                    cx = spawnPoint.getX();
                    cz = spawnPoint.getZ();
                    followsWorldSpawn = true;
                }
                shape = new SpawnShape.Circle(cx, cz, radius);
            } else {
                this.plugin.getLogger().warning("[" + name + "] Unknown shape '" + type + "' (use circle or rectangle), skipping.");
                return null;
            }
        } catch (final IllegalArgumentException e) {
            this.plugin.getLogger().warning("[" + name + "] Invalid area: " + e.getMessage() + ", skipping.");
            return null;
        }

        if (Bukkit.getWorld(worldName) == null) {
            this.plugin.getLogger().warning("[" + name + "] World '" + worldName + "' is not loaded yet. The spawn becomes active once the world is loaded.");
        }

        final SpawnSettings settings = SpawnSettings.load(s, defaults, this.plugin.getLogger(), name);
        return new Spawn(name, worldName, shape, settings, s.getBoolean("enabled", true), followsWorldSpawn);
    }

    // ------------------------------------------------------------------ lookups

    /** The active spawn at a location, or null. */
    public Spawn findAt(final Location location) {
        final World world = location.getWorld();
        if (world == null) {
            return null;
        }
        final List<Spawn> list = this.byWorld.get(world.getName());
        if (list == null) {
            return null;
        }
        final double x = location.getX();
        final double z = location.getZ();
        for (final Spawn spawn : list) {
            if (spawn.enabled() && spawn.shape().contains(x, z)) {
                return spawn;
            }
        }
        return null;
    }

    /** Any spawn (active or not) whose area contains the point, ignoring the spawn called {@code ignoreName}. */
    public Spawn findContaining(final String worldName, final double x, final double z, final String ignoreName) {
        for (final Spawn spawn : this.inWorld(worldName)) {
            if (!spawn.name().equalsIgnoreCase(ignoreName) && spawn.shape().contains(x, z)) {
                return spawn;
            }
        }
        return null;
    }

    /** The first spawn (active or not) that overlaps {@code shape}, ignoring the spawn called {@code ignoreName}. */
    public Spawn findOverlap(final String worldName, final SpawnShape shape, final String ignoreName) {
        return SpawnRegistry.findOverlapIn(this.byWorld.get(worldName), shape, ignoreName);
    }

    private static Spawn findOverlapIn(final List<Spawn> spawns, final SpawnShape shape, final String ignoreName) {
        if (spawns == null) {
            return null;
        }
        for (final Spawn spawn : spawns) {
            if (!spawn.name().equalsIgnoreCase(ignoreName) && spawn.shape().overlaps(shape)) {
                return spawn;
            }
        }
        return null;
    }

    public Spawn get(final String name) {
        return name == null ? null : this.byName.get(name.toLowerCase(Locale.ROOT));
    }

    public Collection<Spawn> all() {
        return this.byName.values();
    }

    public List<Spawn> inWorld(final String worldName) {
        return this.byWorld.getOrDefault(worldName, List.of());
    }

    /** The first active spawn in a world, used when a flight has to be resumed outside any area. */
    public Spawn firstActiveIn(final String worldName) {
        for (final Spawn spawn : this.inWorld(worldName)) {
            if (spawn.enabled()) {
                return spawn;
            }
        }
        return null;
    }

    public static boolean isValidName(final String name) {
        return name != null && NAME_PATTERN.matcher(name).matches();
    }

    // ------------------------------------------------------------------ writes

    /**
     * Creates a spawn or replaces the area of an existing one. Overrides of an
     * existing spawn are kept. The caller must have checked for overlaps.
     */
    public void saveArea(final String name, final String worldName, final SpawnShape shape) {
        final Spawn existing = this.get(name);
        final String key = existing != null ? existing.name() : name.toLowerCase(Locale.ROOT);
        final String path = "spawns." + key;
        final FileConfiguration config = this.plugin.getConfig();

        config.set(path + ".world", worldName);
        if (!config.isSet(path + ".enabled")) {
            config.set(path + ".enabled", true);
        }
        config.set(path + ".shape", shape.typeName());
        config.set(path + ".center", null);
        config.set(path + ".radius", null);
        config.set(path + ".pos1", null);
        config.set(path + ".pos2", null);

        switch (shape) {
            case final SpawnShape.Circle c -> {
                config.set(path + ".center.x", round(c.centerX()));
                config.set(path + ".center.z", round(c.centerZ()));
                config.set(path + ".radius", round(c.radius()));
            }
            case final SpawnShape.Rectangle r -> {
                config.set(path + ".pos1.x", (int) Math.floor(r.minX()));
                config.set(path + ".pos1.z", (int) Math.floor(r.minZ()));
                config.set(path + ".pos2.x", (int) Math.floor(r.maxX()) - 1);
                config.set(path + ".pos2.z", (int) Math.floor(r.maxZ()) - 1);
            }
        }
        this.saveAndReload();
    }

    /** Sets (or with {@code value == null} removes) a value inside a spawn's section. */
    public void setValue(final Spawn spawn, final String key, final Object value) {
        this.plugin.getConfig().set("spawns." + spawn.name() + "." + key, value);
        this.saveAndReload();
    }

    public void delete(final Spawn spawn) {
        this.plugin.getConfig().set("spawns." + spawn.name(), null);
        this.saveAndReload();
    }

    private void saveAndReload() {
        this.plugin.saveConfig();
        this.load();
    }

    private static double round(final double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
