/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package at.auroracraft.spawnelytra.setup;

import at.auroracraft.spawnelytra.Main;
import at.auroracraft.spawnelytra.integration.BedrockSupport;
import at.auroracraft.spawnelytra.spawn.Spawn;
import at.auroracraft.spawnelytra.spawn.SpawnRegistry;
import at.auroracraft.spawnelytra.spawn.SpawnShape;
import at.auroracraft.spawnelytra.util.DisplayNames;
import at.auroracraft.spawnelytra.util.MessageUtil;
import at.auroracraft.spawnelytra.util.SchedulerUtil;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public class SetupManager implements Listener {
    private final Main plugin;
    private final Map<UUID, SetupSession> sessions = new HashMap<>();

    public SetupManager(final Main plugin) {
        this.plugin = plugin;
    }

    public boolean isInSetup(final Player player) {
        return this.sessions.containsKey(player.getUniqueId());
    }

    public void start(final Player player, final String spawnName) {
        if (this.isInSetup(player)) {
            MessageUtil.send(player, "setup_already_running");
            return;
        }
        final String name = spawnName.toLowerCase(Locale.ROOT);
        if (!SpawnRegistry.isValidName(name)) {
            MessageUtil.send(player, "spawn_invalid_name");
            return;
        }
        final Spawn existing = this.plugin.getSpawnRegistry().get(name);
        if (existing != null && !existing.worldName().equals(player.getWorld().getName())) {
            MessageUtil.send(player, "setup_wrong_world",
                    Placeholder.unparsed("spawn", existing.name()),
                    Placeholder.unparsed("world", existing.worldName()));
            return;
        }
        final SetupSession session = new SetupSession(this.plugin, player, name);
        this.sessions.put(player.getUniqueId(), session);
        session.begin();
    }

    public void exit(final Player player, final boolean silent) {
        final SetupSession session = this.sessions.remove(player.getUniqueId());
        if (session != null) {
            session.end();
            if (!silent) {
                MessageUtil.send(player, "setup_cancelled");
            }
        } else if (!silent) {
            MessageUtil.send(player, "setup_not_running");
        }
    }

    public void setPosition(final Player player, final int index, final Location location) {
        final SetupSession session = this.sessions.get(player.getUniqueId());
        if (session == null) {
            MessageUtil.send(player, "setup_not_running");
            return;
        }
        final String worldName = Objects.requireNonNull(location.getWorld()).getName();
        final Spawn other = this.plugin.getSpawnRegistry()
                .findContaining(worldName, location.getX(), location.getZ(), session.getSpawnName());
        if (other != null) {
            MessageUtil.send(player, "setup_pos_inside_spawn", Placeholder.unparsed("other", other.name()));
            return;
        }
        if (index == 1) {
            session.setPos1(location);
        } else if (index == 2) {
            session.setPos2(location);
        }
    }

    public void selectActivationMode(final Player player, final String mode) {
        final SetupSession session = this.sessions.get(player.getUniqueId());
        if (session == null) {
            MessageUtil.send(player, "setup_not_running");
            return;
        }
        final String normalized = mode.toLowerCase(Locale.ROOT);
        switch (normalized) {
            case "double_jump":
            case "auto":
            case "sneak_jump":
            case "f_key":
                session.setActivationMode(normalized);
                MessageUtil.send(player, "setup_activation_mode_set", Placeholder.unparsed("value", DisplayNames.activationMode(normalized)));
                break;
            default:
                MessageUtil.send(player, "setup_invalid_mode");
        }
    }

    public void toggleBoostActivatedMessage(final Player player) {
        final SetupSession session = this.sessions.get(player.getUniqueId());
        if (session == null) {
            MessageUtil.send(player, "setup_not_running");
            return;
        }
        final boolean newValue = !session.isShowBoostActivated();
        session.setShowBoostActivated(newValue);
        final String valueLocalized = MessageUtil.plain(newValue ? "state_on" : "state_off");
        MessageUtil.send(player, "setup_toggled_boost_activated", Placeholder.unparsed("value", valueLocalized));
    }

    public void togglePressToBoostMessage(final Player player) {
        final SetupSession session = this.sessions.get(player.getUniqueId());
        if (session == null) {
            MessageUtil.send(player, "setup_not_running");
            return;
        }
        final boolean newValue = !session.isShowPressToBoost();
        session.setShowPressToBoost(newValue);
        final String valueLocalized = MessageUtil.plain(newValue ? "state_on" : "state_off");
        MessageUtil.send(player, "setup_toggled_press_to_boost", Placeholder.unparsed("value", valueLocalized));
    }

    public void save(final Player player) {
        final SetupSession session = this.sessions.get(player.getUniqueId());
        if (session == null) {
            MessageUtil.send(player, "setup_not_running");
            return;
        }
        if (!session.hasBothPositions()) {
            MessageUtil.send(player, "setup_missing_positions");
            return;
        }

        final Location p1 = session.getPos1();
        final Location p2 = session.getPos2();
        final String worldName = Objects.requireNonNull(p1.getWorld()).getName();
        final String name = session.getSpawnName();
        final SpawnShape.Rectangle shape = SpawnShape.Rectangle.ofBlocks(
                p1.getBlockX(), p1.getBlockZ(), p2.getBlockX(), p2.getBlockZ());

        final SpawnRegistry registry = this.plugin.getSpawnRegistry();
        final Spawn overlap = registry.findOverlap(worldName, shape, name);
        if (overlap != null) {
            MessageUtil.send(player, "spawn_overlap", Placeholder.unparsed("other", overlap.name()));
            return;
        }

        this.plugin.getConfig().set("messages.show_boost_activated", session.isShowBoostActivated());
        this.plugin.getConfig().set("messages.show_press_to_boost", session.isShowPressToBoost());
        registry.saveArea(name, worldName, shape);

        final Spawn saved = registry.get(name);
        final String defaultMode = this.plugin.getConfig().getString("defaults.activation_mode", "double_jump");
        if (saved != null && session.getActivationMode() != null) {
            final boolean isDefault = session.getActivationMode().equalsIgnoreCase(defaultMode);
            registry.setValue(saved, "activation_mode", isDefault ? null : session.getActivationMode());
        }

        this.sessions.remove(player.getUniqueId());
        session.end();
        player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, 1.2f);
        MessageUtil.send(player, "setup_saved", Placeholder.unparsed("spawn", name));
        this.plugin.applyLightReload();
    }

    public void showOptions(final Player player) {
        final SetupSession session = this.sessions.get(player.getUniqueId());
        if (session == null || !session.hasBothPositions()) {
            return;
        }
        
        MessageUtil.send(player, "setup_options_header");


        String current = session.getActivationMode();
        if (current == null) {
            current = this.plugin.getConfig().getString("defaults.activation_mode", "double_jump");
        }

        if (BedrockSupport.isBedrockPlayer(player)) {
            this.showOptionsPlain(player, session, current);
            return;
        }

        final String text = "<#91f251>" +
                this.option("/spawnelytra setup mode double_jump", DisplayNames.activationMode("double_jump"), "activation_mode_hover_double_jump", "double_jump".equalsIgnoreCase(current)) + " " +
                this.option("/spawnelytra setup mode auto", DisplayNames.activationMode("auto"), "activation_mode_hover_auto", "auto".equalsIgnoreCase(current)) + " " +
                this.option("/spawnelytra setup mode sneak_jump", DisplayNames.activationMode("sneak_jump"), "activation_mode_hover_sneak_jump", "sneak_jump".equalsIgnoreCase(current)) + " " +
                this.option("/spawnelytra setup mode f_key", DisplayNames.activationMode("f_key"), "activation_mode_hover_f_key", "f_key".equalsIgnoreCase(current));
        MessageUtil.sendRaw(player, MiniMessage.miniMessage().deserialize(text));

        
        final boolean showBoost = session.isShowBoostActivated();
        final boolean showPress = session.isShowPressToBoost();
        final String boostLabel = MessageUtil.plain("setup_toggle_boost_label");
        final String pressLabel = MessageUtil.plain("setup_toggle_press_label");
        final String toggles = "<#5db3ff>" +
                this.labeledToggle(boostLabel, "/spawnelytra setup toggle boost", showBoost) + " " +
                this.labeledToggle(pressLabel, "/spawnelytra setup toggle press", showPress);
        MessageUtil.sendRaw(player, MiniMessage.miniMessage().deserialize(toggles));

        
        final String saveLabel = MessageUtil.plain("setup_actions_save_label");
        final String saveHover = MessageUtil.plain("setup_actions_save_hover");
        final String exitLabel = MessageUtil.plain("setup_actions_exit_label");
        final String exitHover = MessageUtil.plain("setup_actions_exit_hover");
        final String actions = "<#ffd166>[<click:run_command:'/spawnelytra setup save'><hover:show_text:'<#ffd166>" + saveHover + "'>" + saveLabel + "</hover></click>] " +
                "<#aaa8a8>[<click:run_command:'/spawnelytra setup exit'><hover:show_text:'<#fd5e5e>" + exitHover + "'>" + exitLabel + "</hover></click>]";
        MessageUtil.sendRaw(player, MiniMessage.miniMessage().deserialize(actions));
    }

    private void showOptionsPlain(final Player player, final SetupSession session, final String currentMode) {
        MessageUtil.sendRaw(player, MiniMessage.miniMessage().deserialize(
                "<#91f251>/spawnelytra setup mode <#ffd166>double_jump<#aaa8a8> | <#ffd166>auto<#aaa8a8> | <#ffd166>sneak_jump<#aaa8a8> | <#ffd166>f_key"
                        + " <#aaa8a8>(current: <#91f251>" + currentMode + "<#aaa8a8>)"));

        final String onLabel = MessageUtil.plain("state_on");
        final String offLabel = MessageUtil.plain("state_off");
        final String boostLabel = MessageUtil.plain("setup_toggle_boost_label");
        final String pressLabel = MessageUtil.plain("setup_toggle_press_label");
        final String boostState = session.isShowBoostActivated() ? ("<#91f251>" + onLabel) : ("<#fd5e5e>" + offLabel);
        final String pressState = session.isShowPressToBoost() ? ("<#91f251>" + onLabel) : ("<#fd5e5e>" + offLabel);
        MessageUtil.sendRaw(player, MiniMessage.miniMessage().deserialize(
                "<#5db3ff>" + boostLabel + ": " + boostState + " <#aaa8a8>- /spawnelytra setup toggle boost"));
        MessageUtil.sendRaw(player, MiniMessage.miniMessage().deserialize(
                "<#5db3ff>" + pressLabel + ": " + pressState + " <#aaa8a8>- /spawnelytra setup toggle press"));

        MessageUtil.sendRaw(player, MiniMessage.miniMessage().deserialize(
                "<#ffd166>/spawnelytra setup save <#aaa8a8>• <#fd5e5e>/spawnelytra setup exit"));
    }

    private String option(final String cmd, final String label, final String hoverKey, final boolean selected) {
        final String hoverText = MessageUtil.plain(hoverKey);
        final String open = "[<click:run_command:'" + cmd + "'><hover:show_text:'" + hoverText + "'>";
        final String core = selected ? ("<underlined>" + label + "</underlined>") : label;
        return open + core + "</hover></click>]";
    }

    private String labeledToggle(final String label, final String cmd, final boolean on) {
        final String onLabel = MessageUtil.plain("state_on");
        final String offLabel = MessageUtil.plain("state_off");
        final String toggleHover = MessageUtil.plain("toggle_hover");
        final String state = on ? ("<#91f251>" + onLabel) : ("<#fd5e5e>" + offLabel);
        return label + ": [<click:run_command:'" + cmd + "'><hover:show_text:'<#5db3ff>" + toggleHover + "'>" + state + "</hover></click>]";
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent e) {
        final Player p = e.getPlayer();
        final SetupSession session = this.sessions.remove(p.getUniqueId());
        if (session != null) {
            session.end();
        }
    }

    public void stopAll() {
        for (final Player p : Bukkit.getOnlinePlayers()) {
            final SetupSession session = this.sessions.remove(p.getUniqueId());
            if (session != null) session.end();
        }
    }
}

