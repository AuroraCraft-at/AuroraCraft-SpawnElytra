/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package at.auroracraft.spawnelytra.command;

import at.auroracraft.spawnelytra.Main;
import at.auroracraft.spawnelytra.spawn.Spawn;
import at.auroracraft.spawnelytra.util.DisplayNames;
import at.auroracraft.spawnelytra.util.MessageUtil;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Entry point of {@code /spawnelytra}. Spawn management lives in {@link SpawnCommand}. */
public class CommandHandler implements CommandExecutor, TabCompleter {
    private static final String ADMIN = "spawnelytra.admin";
    private static final List<String> ACTIVATION_MODES = List.of("double_jump", "auto", "sneak_jump", "f_key");

    private final Main plugin;
    private final SpawnCommand spawnCommand;

    public CommandHandler(final Main plugin) {
        this.plugin = plugin;
        this.spawnCommand = new SpawnCommand(plugin);
    }

    @Override
    public boolean onCommand(final CommandSender sender, final Command command, final String label, final String[] args) {
        if (args.length == 0) {
            this.sendHelp(sender);
            return true;
        }

        final String sub = args[0].toLowerCase(Locale.ROOT);
        if ("info".equals(sub)) {
            this.sendInfo(sender);
            return true;
        }
        if ("help".equals(sub)) {
            this.sendHelp(sender);
            return true;
        }
        if (!sender.hasPermission(ADMIN)) {
            MessageUtil.send(sender, "no_permission");
            return true;
        }

        switch (sub) {
            case "reload" -> {
                this.plugin.reload();
                MessageUtil.send(sender, "reload_success");
            }
            case "spawn", "spawns" -> this.spawnCommand.execute(sender, args);
            case "visualize" -> this.visualize(sender, args);
            case "set" -> this.set(sender, args);
            case "setup" -> this.setup(sender, args);
            case "settings" -> {
                if (sender instanceof final Player p) {
                    this.plugin.sendSettingsMenu(p);
                } else {
                    MessageUtil.send(sender, "command_player_only");
                }
            }
            case "options" -> {
                if (sender instanceof final Player p) {
                    this.plugin.sendOptionsMenu(p);
                } else {
                    MessageUtil.send(sender, "command_player_only");
                }
            }
            case "dismiss" -> {
                this.plugin.markFirstInstallCompleted();
                MessageUtil.sendRaw(sender, MiniMessage.miniMessage().deserialize(
                        "<#91f251>The first install message will no longer be shown."));
            }
            case "debug" -> {
                if (sender instanceof final Player p && args.length >= 2 && "firstinstall".equalsIgnoreCase(args[1])) {
                    this.plugin.getConfig().set("first_install_completed", false);
                    this.plugin.saveConfig();
                    this.plugin.sendFirstInstallWelcome(p);
                }
            }
            default -> this.sendHelp(sender);
        }
        return true;
    }

    // ------------------------------------------------------------------ subcommands

    private void visualize(final CommandSender sender, final String[] args) {
        if (!(sender instanceof final Player player)) {
            MessageUtil.send(sender, "command_player_only");
            return;
        }
        int seconds = 30;
        String target = null;
        for (int i = 1; i < args.length; i++) {
            try {
                final int parsed = Integer.parseInt(args[i]);
                if (parsed > 0) {
                    seconds = Math.min(parsed, 600);
                }
            } catch (final NumberFormatException notANumber) {
                target = args[i];
            }
        }

        if ("stop".equalsIgnoreCase(target)) {
            this.plugin.getVisualizer().stop(player);
            return;
        }

        final String world = player.getWorld().getName();
        final List<Spawn> spawns;
        if ("all".equalsIgnoreCase(target)) {
            spawns = this.plugin.getSpawnRegistry().inWorld(world);
        } else if (target != null) {
            final Spawn spawn = this.plugin.getSpawnRegistry().get(target);
            if (spawn == null) {
                MessageUtil.send(player, "spawn_not_found", Placeholder.unparsed("spawn", target));
                return;
            }
            if (!spawn.worldName().equals(world)) {
                MessageUtil.send(player, "visualize_other_world",
                        Placeholder.unparsed("spawn", spawn.name()), Placeholder.unparsed("world", spawn.worldName()));
                return;
            }
            spawns = List.of(spawn);
        } else {
            spawns = this.nearestSpawn(player).map(List::of).orElse(List.of());
        }

        if (spawns.isEmpty()) {
            MessageUtil.send(player, "visualize_no_area");
            return;
        }
        this.plugin.getVisualizer().visualize(player, spawns, seconds);
    }

    private Optional<Spawn> nearestSpawn(final Player player) {
        final Spawn here = this.plugin.getSpawnRegistry().findAt(player.getLocation());
        if (here != null) {
            return Optional.of(here);
        }
        final double x = player.getLocation().getX();
        final double z = player.getLocation().getZ();
        return this.plugin.getSpawnRegistry().inWorld(player.getWorld().getName()).stream()
                .min(Comparator.comparingDouble(s -> {
                    final double dx = s.shape().centerX() - x;
                    final double dz = s.shape().centerZ() - z;
                    return dx * dx + dz * dz;
                }));
    }

    private void set(final CommandSender sender, final String[] args) {
        if (args.length >= 2 && sender instanceof final Player p) {
            final String what = args[1].toLowerCase(Locale.ROOT);
            if ("pos1".equals(what)) {
                this.plugin.getSetupManager().setPosition(p, 1, p.getLocation());
                return;
            }
            if ("pos2".equals(what)) {
                this.plugin.getSetupManager().setPosition(p, 2, p.getLocation());
                return;
            }
        }
        if (args.length < 3) {
            return;
        }
        final String what = args[1].toLowerCase(Locale.ROOT);
        if ("language".equals(what)) {
            this.plugin.applyLanguageSetting(sender, args[2]);
        } else if ("style".equals(what)) {
            this.plugin.applyStyleSetting(sender, args[2]);
        }
    }

    private void setup(final CommandSender sender, final String[] args) {
        if (!(sender instanceof final Player player)) {
            MessageUtil.send(sender, "command_player_only");
            return;
        }
        final var setup = this.plugin.getSetupManager();
        if (args.length == 1) {
            if (setup.isInSetup(player)) {
                setup.showOptions(player);
            } else {
                MessageUtil.send(player, "setup_usage");
            }
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "exit", "off", "cancel" -> setup.exit(player, false);
            case "save" -> setup.save(player);
            case "mode" -> {
                if (args.length >= 3) {
                    setup.selectActivationMode(player, args[2]);
                }
            }
            case "toggle" -> {
                if (args.length >= 3 && "boost".equalsIgnoreCase(args[2])) {
                    setup.toggleBoostActivatedMessage(player);
                } else if (args.length >= 3 && "press".equalsIgnoreCase(args[2])) {
                    setup.togglePressToBoostMessage(player);
                }
            }
            default -> {
                this.plugin.markFirstInstallCompleted();
                setup.start(player, args[1]);
            }
        }
    }

    // ------------------------------------------------------------------ help and info

    private void sendHelp(final CommandSender sender) {
        MessageUtil.send(sender, "help_header");
        MessageUtil.send(sender, "help_info");
        if (!sender.hasPermission(ADMIN)) {
            return;
        }
        for (final String key : List.of("help_reload", "help_spawn_list", "help_spawn_info", "help_spawn_create",
                "help_spawn_setup", "help_spawn_set", "help_spawn_tp", "help_spawn_delete",
                "help_visualize", "help_settings")) {
            MessageUtil.send(sender, key);
        }
    }

    private void sendInfo(final CommandSender sender) {
        MessageUtil.send(sender, "info_header");
        final String language = this.plugin.getConfig().getString("language", "en");
        MessageUtil.send(sender, "info_version", Placeholder.unparsed("value", this.plugin.getDescription().getVersion()));
        MessageUtil.send(sender, "info_website", Placeholder.unparsed("value",
                String.valueOf(this.plugin.getDescription().getWebsite())));
        MessageUtil.send(sender, "info_language", Placeholder.unparsed("value", DisplayNames.language(language)));

        final var all = this.plugin.getSpawnRegistry().all();
        final long active = all.stream().filter(Spawn::enabled).count();
        MessageUtil.send(sender, "info_spawn_count",
                Placeholder.unparsed("count", String.valueOf(all.size())),
                Placeholder.unparsed("active", String.valueOf(active)));
        MessageUtil.send(sender, "info_credits");
    }

    // ------------------------------------------------------------------ tab completion

    @Override
    public List<String> onTabComplete(final CommandSender sender, final Command command, final String alias, final String[] args) {
        final List<String> options = new ArrayList<>();
        final boolean admin = sender.hasPermission(ADMIN);

        if (args.length == 1) {
            options.add("info");
            options.add("help");
            if (admin) {
                options.addAll(List.of("reload", "spawn", "visualize", "setup", "settings"));
            }
        } else if (admin) {
            final String sub = args[0].toLowerCase(Locale.ROOT);
            switch (sub) {
                case "spawn", "spawns" -> {
                    return filter(this.spawnCommand.complete(args), args[args.length - 1]);
                }
                case "visualize" -> {
                    if (args.length == 2) {
                        options.addAll(this.spawnNames());
                        options.addAll(List.of("all", "stop"));
                    }
                }
                case "setup" -> {
                    if (args.length == 2) {
                        options.addAll(List.of("save", "exit", "mode"));
                        options.addAll(this.spawnNames());
                    } else if (args.length == 3 && "mode".equalsIgnoreCase(args[1])) {
                        options.addAll(ACTIVATION_MODES);
                    } else if (args.length == 3 && "toggle".equalsIgnoreCase(args[1])) {
                        options.addAll(List.of("boost", "press"));
                    }
                }
                case "set" -> {
                    if (args.length == 2) {
                        options.addAll(List.of("pos1", "pos2", "language", "style"));
                    } else if (args.length == 3 && "language".equalsIgnoreCase(args[1])) {
                        options.addAll(List.of("en", "de", "es", "fr", "pl"));
                    } else if (args.length == 3 && "style".equalsIgnoreCase(args[1])) {
                        options.addAll(List.of("classic", "small_caps"));
                    }
                }
                default -> {
                    return Collections.emptyList();
                }
            }
        }
        return filter(options, args[args.length - 1]);
    }

    private List<String> spawnNames() {
        return this.plugin.getSpawnRegistry().all().stream().map(Spawn::name).toList();
    }

    static List<String> filter(final List<String> options, final String prefix) {
        final String p = prefix.toLowerCase(Locale.ROOT);
        return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(p)).toList();
    }
}
