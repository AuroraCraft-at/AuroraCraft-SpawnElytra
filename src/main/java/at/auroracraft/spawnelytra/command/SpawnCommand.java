/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package at.auroracraft.spawnelytra.command;

import at.auroracraft.spawnelytra.Main;
import at.auroracraft.spawnelytra.spawn.Spawn;
import at.auroracraft.spawnelytra.spawn.SpawnRegistry;
import at.auroracraft.spawnelytra.spawn.SpawnSettings;
import at.auroracraft.spawnelytra.spawn.SpawnShape;
import at.auroracraft.spawnelytra.util.DisplayNames;
import at.auroracraft.spawnelytra.util.MessageUtil;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/** {@code /spawnelytra spawn ...}: list, inspect, create, change, teleport to and delete spawns. */
final class SpawnCommand {
    /** Keys that {@code /se spawn set} accepts, besides the overridable flight settings. */
    private static final List<String> AREA_KEYS = List.of("enabled", "radius");

    private final Main plugin;

    SpawnCommand(final Main plugin) {
        this.plugin = plugin;
    }

    private SpawnRegistry registry() {
        return this.plugin.getSpawnRegistry();
    }

    void execute(final CommandSender sender, final String[] args) {
        if (args.length < 2) {
            this.list(sender);
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "list" -> this.list(sender);
            case "info" -> this.withSpawn(sender, args, 2, spawn -> this.info(sender, spawn));
            case "create" -> this.create(sender, args);
            case "delete", "remove" -> this.withSpawn(sender, args, 2, spawn -> this.delete(sender, spawn, args));
            case "tp", "teleport" -> this.withSpawn(sender, args, 2, spawn -> this.teleport(sender, spawn));
            case "set" -> this.withSpawn(sender, args, 2, spawn -> this.set(sender, spawn, args));
            default -> MessageUtil.send(sender, "spawn_usage");
        }
    }

    private void withSpawn(final CommandSender sender, final String[] args, final int index, final java.util.function.Consumer<Spawn> action) {
        if (args.length <= index) {
            MessageUtil.send(sender, "spawn_usage");
            return;
        }
        final Spawn spawn = this.registry().get(args[index]);
        if (spawn == null) {
            MessageUtil.send(sender, "spawn_not_found", Placeholder.unparsed("spawn", args[index]));
            return;
        }
        action.accept(spawn);
    }

    // ------------------------------------------------------------------ list / info

    private void list(final CommandSender sender) {
        final var spawns = this.registry().all();
        MessageUtil.send(sender, "spawn_list_header", Placeholder.unparsed("count", String.valueOf(spawns.size())));
        if (spawns.isEmpty()) {
            MessageUtil.send(sender, "spawn_list_empty");
            return;
        }
        for (final Spawn spawn : spawns) {
            MessageUtil.send(sender, "spawn_list_entry",
                    Placeholder.unparsed("spawn", spawn.name()),
                    Placeholder.unparsed("world", spawn.worldName()),
                    Placeholder.component("shape", MessageUtil.component(shapeKey(spawn), shapeResolvers(spawn.shape()))),
                    Placeholder.component("state", MessageUtil.component(spawn.enabled() ? "spawn_state_active" : "spawn_state_disabled")));
        }
    }

    private void info(final CommandSender sender, final Spawn spawn) {
        final SpawnSettings s = spawn.settings();
        MessageUtil.send(sender, "spawn_info_header", Placeholder.unparsed("spawn", spawn.name()));
        MessageUtil.send(sender, "spawn_info_world", Placeholder.unparsed("value", spawn.worldName()));
        MessageUtil.send(sender, "spawn_info_area",
                Placeholder.component("value", MessageUtil.component(shapeKey(spawn), shapeResolvers(spawn.shape()))));
        MessageUtil.send(sender, "spawn_info_state",
                Placeholder.component("value", MessageUtil.component(spawn.enabled() ? "spawn_state_active" : "spawn_state_disabled")));
        MessageUtil.send(sender, "spawn_info_activation",
                Placeholder.unparsed("value", DisplayNames.activationMode(s.activationMode().configName())));
        MessageUtil.send(sender, "spawn_info_boost",
                Placeholder.unparsed("enabled", MessageUtil.plain(s.boostEnabled() ? "state_on" : "state_off")),
                Placeholder.unparsed("strength", fmt(s.boostStrength())),
                Placeholder.unparsed("direction", s.boostDirection().name().toLowerCase(Locale.ROOT)),
                Placeholder.unparsed("max", String.valueOf(s.maxBoosts())),
                Placeholder.unparsed("cooldown", fmt(s.boostCooldownMs() / 1000.0)));
        MessageUtil.send(sender, "spawn_info_rockets", Placeholder.unparsed("value",
                s.rocketsUnlimited() ? MessageUtil.plain("unlimited") : String.valueOf(s.maxRockets())));
        if (SpawnSettings.ActivationMode.F_KEY == s.activationMode()) {
            MessageUtil.send(sender, "spawn_info_launch", Placeholder.unparsed("value", fmt(s.fKeyLaunchStrength())));
        }

        final ConfigurationSection own = this.plugin.getConfig().getConfigurationSection("spawns." + spawn.name());
        final List<String> overrides = new ArrayList<>();
        if (own != null) {
            for (final String key : SpawnSettings.OVERRIDABLE_KEYS) {
                if (own.isSet(key)) {
                    overrides.add(key);
                }
            }
        }
        MessageUtil.send(sender, "spawn_info_overrides", Placeholder.unparsed("value",
                overrides.isEmpty() ? "-" : String.join(", ", overrides)));
    }

    private static String shapeKey(final Spawn spawn) {
        if (spawn.shape() instanceof SpawnShape.Rectangle) {
            return "shape_rectangle";
        }
        return spawn.centerIsWorldSpawn() ? "shape_circle_world_spawn" : "shape_circle";
    }

    private static TagResolver[] shapeResolvers(final SpawnShape shape) {
        return switch (shape) {
            case final SpawnShape.Circle c -> new TagResolver[]{
                    Placeholder.unparsed("radius", fmt(c.radius())),
                    Placeholder.unparsed("x", fmt(c.centerX())),
                    Placeholder.unparsed("z", fmt(c.centerZ()))};
            case final SpawnShape.Rectangle r -> new TagResolver[]{
                    Placeholder.unparsed("width", fmt(r.width())),
                    Placeholder.unparsed("depth", fmt(r.depth())),
                    Placeholder.unparsed("x1", fmt(r.minX())),
                    Placeholder.unparsed("z1", fmt(r.minZ())),
                    Placeholder.unparsed("x2", fmt(r.maxX() - 1)),
                    Placeholder.unparsed("z2", fmt(r.maxZ() - 1))};
        };
    }

    static String fmt(final double value) {
        if (value == Math.rint(value)) {
            return String.valueOf((long) value);
        }
        return String.valueOf(Math.round(value * 100.0) / 100.0);
    }

    // ------------------------------------------------------------------ create / delete

    private void create(final CommandSender sender, final String[] args) {
        if (!(sender instanceof final Player player)) {
            MessageUtil.send(sender, "command_player_only");
            return;
        }
        if (args.length < 4) {
            MessageUtil.send(sender, "spawn_usage_create");
            return;
        }
        final String name = args[2].toLowerCase(Locale.ROOT);
        if (!SpawnRegistry.isValidName(name)) {
            MessageUtil.send(sender, "spawn_invalid_name");
            return;
        }
        if (this.registry().get(name) != null) {
            MessageUtil.send(sender, "spawn_exists", Placeholder.unparsed("spawn", name));
            return;
        }

        final String type = args[3].toLowerCase(Locale.ROOT);
        if ("rectangle".equals(type) || "rect".equals(type)) {
            this.plugin.getSetupManager().start(player, name);
            return;
        }
        if (!"circle".equals(type)) {
            MessageUtil.send(sender, "spawn_usage_create");
            return;
        }

        final double radius;
        try {
            radius = args.length >= 5 ? Double.parseDouble(args[4]) : 100;
        } catch (final NumberFormatException e) {
            MessageUtil.send(sender, "spawn_set_invalid_value", Placeholder.unparsed("setting", "radius"));
            return;
        }
        if (radius <= 0) {
            MessageUtil.send(sender, "spawn_set_invalid_value", Placeholder.unparsed("setting", "radius"));
            return;
        }

        final Location here = player.getLocation();
        final String world = player.getWorld().getName();
        final SpawnShape.Circle shape = new SpawnShape.Circle(here.getBlockX() + 0.5, here.getBlockZ() + 0.5, radius);
        final Spawn overlap = this.registry().findOverlap(world, shape, null);
        if (overlap != null) {
            MessageUtil.send(sender, "spawn_overlap", Placeholder.unparsed("other", overlap.name()));
            return;
        }

        this.registry().saveArea(name, world, shape);
        MessageUtil.send(sender, "spawn_created", Placeholder.unparsed("spawn", name), Placeholder.unparsed("world", world));
    }

    private void delete(final CommandSender sender, final Spawn spawn, final String[] args) {
        if (args.length < 4 || !"confirm".equalsIgnoreCase(args[3])) {
            MessageUtil.send(sender, "spawn_delete_confirm", Placeholder.unparsed("spawn", spawn.name()));
            return;
        }
        this.registry().delete(spawn);
        MessageUtil.send(sender, "spawn_deleted", Placeholder.unparsed("spawn", spawn.name()));
    }

    // ------------------------------------------------------------------ teleport

    private void teleport(final CommandSender sender, final Spawn spawn) {
        if (!(sender instanceof final Player player)) {
            MessageUtil.send(sender, "command_player_only");
            return;
        }
        final World world = Bukkit.getWorld(spawn.worldName());
        if (world == null) {
            MessageUtil.send(sender, "spawn_world_not_loaded", Placeholder.unparsed("world", spawn.worldName()));
            return;
        }
        if (spawn.centerIsWorldSpawn()) {
            this.teleportTo(player, world.getSpawnLocation(), spawn);
            return;
        }
        final int bx = (int) Math.floor(spawn.shape().centerX());
        final int bz = (int) Math.floor(spawn.shape().centerZ());
        world.getChunkAtAsync(bx >> 4, bz >> 4).thenAccept(chunk -> {
            final Location target = new Location(world, bx + 0.5, safeY(world, bx, bz), bz + 0.5,
                    player.getLocation().getYaw(), player.getLocation().getPitch());
            this.teleportTo(player, target, spawn);
        });
    }

    private void teleportTo(final Player player, final Location target, final Spawn spawn) {
        player.teleportAsync(target).thenAccept(success -> {
            if (Boolean.TRUE.equals(success)) {
                MessageUtil.send(player, "spawn_teleported", Placeholder.unparsed("spawn", spawn.name()));
            }
        });
    }

    /** A standing height at x/z. In worlds with a roof (Nether) it searches below the roof. */
    private static int safeY(final World world, final int x, final int z) {
        if (World.Environment.NETHER != world.getEnvironment()) {
            return world.getHighestBlockYAt(x, z) + 1;
        }
        final int top = Math.min(world.getMaxHeight() - 1, 120);
        for (int y = top; y > world.getMinHeight() + 1; y--) {
            final Block feet = world.getBlockAt(x, y, z);
            final Block head = feet.getRelative(0, 1, 0);
            final Block ground = feet.getRelative(0, -1, 0);
            if (feet.isPassable() && head.isPassable() && ground.getType().isSolid() && Material.LAVA != feet.getType()) {
                return y;
            }
        }
        return top;
    }

    // ------------------------------------------------------------------ set

    private void set(final CommandSender sender, final Spawn spawn, final String[] args) {
        if (args.length < 5) {
            MessageUtil.send(sender, "spawn_set_usage", Placeholder.unparsed("keys", String.join(", ", allSetKeys())));
            return;
        }
        final String key = args[3].toLowerCase(Locale.ROOT);
        final String raw = args[4];
        if (!allSetKeys().contains(key)) {
            MessageUtil.send(sender, "spawn_set_invalid_key", Placeholder.unparsed("keys", String.join(", ", allSetKeys())));
            return;
        }

        final boolean reset = "default".equalsIgnoreCase(raw) || "reset".equalsIgnoreCase(raw);
        if (reset && SpawnSettings.OVERRIDABLE_KEYS.contains(key)) {
            this.registry().setValue(spawn, key, null);
            MessageUtil.send(sender, "spawn_set_reset", Placeholder.unparsed("spawn", spawn.name()), Placeholder.unparsed("setting", key));
            return;
        }

        final Object value = parseValue(key, raw);
        if (value == null) {
            MessageUtil.send(sender, "spawn_set_invalid_value", Placeholder.unparsed("setting", key));
            return;
        }

        if ("radius".equals(key)) {
            if (!(spawn.shape() instanceof final SpawnShape.Circle circle)) {
                MessageUtil.send(sender, "spawn_set_radius_rectangle");
                return;
            }
            final SpawnShape.Circle resized = new SpawnShape.Circle(circle.centerX(), circle.centerZ(), (Double) value);
            final Spawn overlap = this.registry().findOverlap(spawn.worldName(), resized, spawn.name());
            if (overlap != null) {
                MessageUtil.send(sender, "spawn_overlap", Placeholder.unparsed("other", overlap.name()));
                return;
            }
        }

        this.registry().setValue(spawn, key, value);
        MessageUtil.send(sender, "spawn_set_success",
                Placeholder.unparsed("spawn", spawn.name()),
                Placeholder.unparsed("setting", key),
                Placeholder.unparsed("value", String.valueOf(value)));
    }

    private static List<String> allSetKeys() {
        final List<String> keys = new ArrayList<>(AREA_KEYS);
        keys.addAll(SpawnSettings.OVERRIDABLE_KEYS);
        return keys;
    }

    /** Parses and validates a value for {@code /se spawn set}; returns null when invalid. */
    private static Object parseValue(final String key, final String raw) {
        return switch (key) {
            case "enabled", "boost.enabled" -> parseBool(raw);
            case "activation_mode" -> {
                final SpawnSettings.ActivationMode mode = SpawnSettings.ActivationMode.parse(raw);
                yield mode == null ? null : mode.configName();
            }
            case "boost.direction" -> {
                final SpawnSettings.BoostDirection dir = SpawnSettings.BoostDirection.parse(raw);
                yield dir == null ? null : dir.name().toLowerCase(Locale.ROOT);
            }
            case "boost.sound" -> SpawnSettings.parseSound(raw) == null ? null : raw.toUpperCase(Locale.ROOT);
            case "radius", "boost.strength", "f_key.launch_strength" -> number(raw, v -> v > 0 ? v : null);
            case "boost.boost_cooldown" -> number(raw, v -> v >= 0 ? v : null);
            case "boost.max_boosts" -> integer(raw, v -> v >= 1 ? v : null);
            case "fireworks.max_rockets" -> {
                if ("unlimited".equalsIgnoreCase(raw) || "infinite".equalsIgnoreCase(raw)) {
                    yield SpawnSettings.UNLIMITED;
                }
                yield integer(raw, v -> v >= SpawnSettings.UNLIMITED ? v : null);
            }
            default -> null;
        };
    }

    private static Boolean parseBool(final String raw) {
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "true", "on", "yes", "an", "ja" -> Boolean.TRUE;
            case "false", "off", "no", "aus", "nein" -> Boolean.FALSE;
            default -> null;
        };
    }

    private static Double number(final String raw, final Function<Double, Double> check) {
        try {
            return check.apply(Double.parseDouble(raw));
        } catch (final NumberFormatException e) {
            return null;
        }
    }

    private static Integer integer(final String raw, final Function<Integer, Integer> check) {
        try {
            return check.apply(Integer.parseInt(raw));
        } catch (final NumberFormatException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ tab completion

    List<String> complete(final String[] args) {
        final List<String> names = this.registry().all().stream().map(Spawn::name).toList();
        return switch (args.length) {
            case 2 -> List.of("list", "info", "create", "set", "tp", "delete");
            case 3 -> "create".equalsIgnoreCase(args[1]) ? List.of() : names;
            case 4 -> switch (args[1].toLowerCase(Locale.ROOT)) {
                case "create" -> List.of("circle", "rectangle");
                case "set" -> allSetKeys();
                case "delete", "remove" -> List.of("confirm");
                default -> List.of();
            };
            case 5 -> switch (args[1].toLowerCase(Locale.ROOT)) {
                case "create" -> List.of("50", "100", "200");
                case "set" -> valueSuggestions(args[3].toLowerCase(Locale.ROOT));
                default -> List.of();
            };
            default -> List.of();
        };
    }

    private static List<String> valueSuggestions(final String key) {
        final List<String> values = new ArrayList<>(switch (key) {
            case "enabled", "boost.enabled" -> List.of("true", "false");
            case "activation_mode" -> List.of("double_jump", "auto", "sneak_jump", "f_key");
            case "boost.direction" -> List.of("forward", "upward");
            case "fireworks.max_rockets" -> List.of("-1", "0", "5", "10");
            case "boost.sound" -> List.of("ENTITY_BAT_TAKEOFF", "ENTITY_FIREWORK_ROCKET_BLAST", "ITEM_ELYTRA_FLYING");
            default -> List.of();
        });
        if (SpawnSettings.OVERRIDABLE_KEYS.contains(key)) {
            values.add("default");
        }
        return values;
    }
}
