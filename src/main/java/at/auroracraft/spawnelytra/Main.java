/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package at.auroracraft.spawnelytra;

import at.auroracraft.spawnelytra.bedrock.TempElytraManager;
import at.auroracraft.spawnelytra.bedrock.TempElytraTrackListener;
import at.auroracraft.spawnelytra.command.CommandHandler;
import at.auroracraft.spawnelytra.config.ConfigUpdater;
import at.auroracraft.spawnelytra.config.LanguageUpdater;
import at.auroracraft.spawnelytra.data.PlayerDataManager;
import at.auroracraft.spawnelytra.flight.FlightController;
import at.auroracraft.spawnelytra.flight.PaperRocketListener;
import at.auroracraft.spawnelytra.flight.SpigotRocketListener;
import at.auroracraft.spawnelytra.integration.BedrockSupport;
import at.auroracraft.spawnelytra.integration.PlaceholderAPIIntegration;
import at.auroracraft.spawnelytra.setup.SetupManager;
import at.auroracraft.spawnelytra.spawn.Spawn;
import at.auroracraft.spawnelytra.spawn.SpawnRegistry;
import at.auroracraft.spawnelytra.util.Branding;
import at.auroracraft.spawnelytra.util.DisplayNames;
import at.auroracraft.spawnelytra.util.MessageUtil;
import at.auroracraft.spawnelytra.util.SchedulerUtil;
import at.auroracraft.spawnelytra.visual.AreaVisualizer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

public final class Main extends JavaPlugin implements Listener {
    /** Name of the original plugin this fork is based on. Both must not run at the same time. */
    private static final String UPSTREAM_PLUGIN_NAME = "SpawnElytra";

    private PlayerDataManager playerDataManager;
    private TempElytraManager tempElytraManager;
    private SpawnRegistry spawnRegistry;
    private FlightController flightController;
    private AreaVisualizer visualizer;
    private SetupManager setupManager;

    private final Map<String, String> lastMenuSent = new HashMap<>();
    private int remainingFirstInstallShows = 5;

    @Override
    public void onEnable() {
        if (Bukkit.getPluginManager().getPlugin(UPSTREAM_PLUGIN_NAME) != null) {
            this.getLogger().severe("The original '" + UPSTREAM_PLUGIN_NAME + "' plugin is installed as well. "
                    + "Remove its jar from the plugins folder; both use /spawnelytra and would fight over the same players. "
                    + "AuroraCraftSpawnElytra disables itself until then.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        this.migrateFromUpstreamDataFolder();
        this.saveDefaultConfig();

        this.playerDataManager = new PlayerDataManager(this);
        this.playerDataManager.initialize();

        MessageUtil.initialize(this);
        ConfigUpdater.updateConfig(this);
        this.saveLanguageFiles();
        LanguageUpdater.updateLanguages(this, false);
        MessageUtil.loadMessages(this);

        this.spawnRegistry = new SpawnRegistry(this);
        this.spawnRegistry.load();

        this.registerListenersAndCommands();
        this.registerPlaceholders();

        if (!this.getConfig().getBoolean("first_install_completed", false)) {
            for (final Player p : Bukkit.getOnlinePlayers()) {
                if (p.isOp()) {
                    SchedulerUtil.runAtEntityLater(this, p, 40L, () -> this.sendFirstInstallWelcome(p));
                }
            }
        }
        this.getLogger().info("AuroraCraft Spawn Elytra " + this.getDescription().getVersion() + " enabled.");
    }

    private void registerListenersAndCommands() {
        final var pm = Bukkit.getPluginManager();
        pm.registerEvents(this, this);

        BedrockSupport.initialize(this);
        this.tempElytraManager = new TempElytraManager(this);
        pm.registerEvents(this.tempElytraManager, this);
        if (classExists("io.papermc.paper.event.player.PlayerTrackEntityEvent")) {
            pm.registerEvents(new TempElytraTrackListener(this, this.tempElytraManager), this);
        }

        this.flightController = new FlightController(this, this.spawnRegistry);
        pm.registerEvents(this.flightController, this);
        if (classExists("com.destroystokyo.paper.event.player.PlayerElytraBoostEvent")) {
            pm.registerEvents(new PaperRocketListener(this.flightController), this);
        } else {
            pm.registerEvents(new SpigotRocketListener(this.flightController), this);
        }

        this.visualizer = new AreaVisualizer(this);

        this.setupManager = new SetupManager(this);
        pm.registerEvents(this.setupManager, this);

        final CommandHandler commandHandler = new CommandHandler(this);
        Objects.requireNonNull(this.getCommand("spawnelytra")).setExecutor(commandHandler);
        Objects.requireNonNull(this.getCommand("spawnelytra")).setTabCompleter(commandHandler);
    }

    private static boolean classExists(final String name) {
        try {
            Class.forName(name);
            return true;
        } catch (final ClassNotFoundException e) {
            return false;
        }
    }

    private void registerPlaceholders() {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null
                && !new PlaceholderAPIIntegration(this, this.playerDataManager).register()) {
            this.getLogger().warning("Failed to register Spawn Elytra placeholders");
        }
    }

    @Override
    public void onDisable() {
        if (this.tempElytraManager != null) {
            this.tempElytraManager.restoreAll();
        }
        if (this.playerDataManager != null) {
            this.playerDataManager.saveAllPlayerData();
        }
        if (this.setupManager != null) {
            this.setupManager.stopAll();
        }
        if (this.flightController != null) {
            this.flightController.cleanupAll();
        }
        if (this.visualizer != null) {
            this.visualizer.stopAll();
        }
        MessageUtil.shutdown();
    }

    /** Full reload: config, messages, spawns. Running flights end. */
    public void reload() {
        this.reloadConfig();
        MessageUtil.loadMessages(this);
        BedrockSupport.reloadSettings(this);

        this.tempElytraManager.restoreAll();
        this.setupManager.stopAll();
        this.flightController.cleanupAll();
        this.visualizer.stopAll();

        this.spawnRegistry.load();
        this.flightController.reloadSettings();
        this.visualizer.reloadSettings();
    }

    /**
     * Applies config changes made from inside the plugin (setup, settings) without
     * ending anyone's flight. The spawn registry reloads itself on writes.
     */
    public void applyLightReload() {
        MessageUtil.loadMessages(this);
        this.flightController.reloadSettings();
    }

    @EventHandler
    public void onPlayerJoin(final PlayerJoinEvent event) {
        final Player player = event.getPlayer();
        if (player.isOp() && !this.getConfig().getBoolean("first_install_completed", false)) {
            this.sendFirstInstallWelcome(player);
        }
    }

    @EventHandler
    public void onPlayerQuit(final PlayerQuitEvent event) {
        final Player player = event.getPlayer();
        this.lastMenuSent.remove(player.getUniqueId().toString());
        this.visualizer.stop(player);
        BedrockSupport.forget(player.getUniqueId());
    }

    /**
     * On the first start, copies config and player statistics from the original
     * plugin's folder ({@code plugins/SpawnElytra}). The old folder is left untouched.
     */
    private void migrateFromUpstreamDataFolder() {
        final File newDir = this.getDataFolder();
        final File parent = newDir.getParentFile();
        if (parent == null || new File(newDir, "config.yml").exists()) {
            return;
        }
        final File oldDir = new File(parent, UPSTREAM_PLUGIN_NAME);
        if (!new File(oldDir, "config.yml").isFile()) {
            return;
        }
        try {
            Files.createDirectories(newDir.toPath());
            Files.copy(new File(oldDir, "config.yml").toPath(), new File(newDir, "config.yml").toPath());
            final Path oldPlayerData = new File(oldDir, "playerdata").toPath();
            if (Files.isDirectory(oldPlayerData)) {
                final Path target = new File(newDir, "playerdata").toPath();
                try (Stream<Path> files = Files.walk(oldPlayerData)) {
                    for (final Path source : files.toList()) {
                        final Path dest = target.resolve(oldPlayerData.relativize(source).toString());
                        if (Files.isDirectory(source)) {
                            Files.createDirectories(dest);
                        } else {
                            Files.copy(source, dest, StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                }
            }
            this.getLogger().info("Copied config and player data from plugins/" + UPSTREAM_PLUGIN_NAME
                    + ". The old folder was not changed and can be deleted once everything works.");
        } catch (final IOException e) {
            this.getLogger().warning("Could not copy data from plugins/" + UPSTREAM_PLUGIN_NAME + ": " + e.getMessage());
        }
    }

    public void markFirstInstallCompleted() {
        if (!this.getConfig().getBoolean("first_install_completed", false)) {
            this.getConfig().set("first_install_completed", true);
            this.saveConfig();
        }
    }
    
    public void applyLanguageSetting(final CommandSender actor, final String langCode) {
        this.getConfig().set("language", langCode.toLowerCase(Locale.ROOT));
        this.saveConfig();
        MessageUtil.loadMessages(this);
        
        if (actor instanceof final Player p) {
            final String ctx = this.lastMenuSent.get(p.getUniqueId().toString());
            if ("first_install".equals(ctx)) {
                this.markFirstInstallCompleted();
            }
        }
        final Component confirmation = MiniMessage.miniMessage().deserialize(
                "<#91f251>Language set to <#ffd166>" + langCode + "</#ffd166>.");
        if (actor instanceof final Player p) {
            MessageUtil.sendRaw(p, confirmation);
        } else {
            actor.sendMessage(MessageUtil.plain("info_header"));
        }
    }

    public void applyStyleSetting(final CommandSender actor, final String style) {
        final String normalized = ("small_caps".equalsIgnoreCase(style) ? "small_caps" : "classic");
        this.getConfig().set("messages.style", normalized);
        this.saveConfig();
        MessageUtil.loadMessages(this);
        
        if (actor instanceof final Player p) {
            final String ctx = this.lastMenuSent.get(p.getUniqueId().toString());
            if ("first_install".equals(ctx)) {
                this.markFirstInstallCompleted();
            }
        }
        final Component confirmation = MiniMessage.miniMessage().deserialize(
                "<#91f251>Style set to <#ffd166>" + normalized + "</#ffd166>.");
        if (actor instanceof final Player p) {
            MessageUtil.sendRaw(p, confirmation);
        } else {
            actor.sendMessage(MessageUtil.plain("info_header"));
        }
    }

    public void sendFirstInstallWelcome(final Player player) {
        if (player == null) return;
        
        if (this.getConfig().getBoolean("first_install_completed", false)) {
            return;
        }
        
        if (remainingFirstInstallShows <= 0) {
            this.markFirstInstallCompleted();
            return;
        }

        this.remainingFirstInstallShows--;

        final boolean bedrock = BedrockSupport.isBedrockPlayer(player);

        final Component header = MiniMessage.miniMessage().deserialize(Branding.GRADIENT_OPEN + "✦ Welcome to AuroraCraft Spawn Elytra!" + Branding.GRADIENT_CLOSE);
        MessageUtil.sendRaw(player, header);

        this.lastMenuSent.put(player.getUniqueId().toString(), "first_install");

        final Component languagePrompt = MiniMessage.miniMessage().deserialize("<#fdba5e>✎ Choose your preferred language below:");
        MessageUtil.sendRaw(player, languagePrompt);

        final Component languageButtons;
        if (bedrock) {
            languageButtons = MiniMessage.miniMessage().deserialize(
                    "   <#91f251>/spawnelytra set language <#ffd166>en<#aaa8a8> | <#ffd166>de<#aaa8a8> | <#ffd166>es<#aaa8a8> | <#ffd166>fr<#aaa8a8> | <#ffd166>pl");
        } else {
            languageButtons = MiniMessage.miniMessage().deserialize(
                    "   <#91f251>[<click:run_command:'/spawnelytra set language en'><hover:show_text:'<#91f251>Set language to English'>English</hover></click>] " +
                            "[<click:run_command:'/spawnelytra set language de'><hover:show_text:'<#91f251>Set language to German'>Deutsch</hover></click>] " +
                            "[<click:run_command:'/spawnelytra set language es'><hover:show_text:'<#91f251>Set language to Spanish'>Español</hover></click>] " +
                            "[<click:run_command:'/spawnelytra set language fr'><hover:show_text:'<#91f251>Set language to French'>Français</hover></click>] " +
                            "[<click:run_command:'/spawnelytra set language pl'><hover:show_text:'<#91f251>Set language to Polish'>Polski</hover></click>]"
            );
        }
        MessageUtil.sendRaw(player, languageButtons);

        final Component setupPrompt = MiniMessage.miniMessage().deserialize("<#fdba5e>⚐ Set up your spawns (you can create as many as you like, in any world):");
        MessageUtil.sendRaw(player, setupPrompt);

        final Component setupButton;
        if (bedrock) {
            setupButton = MiniMessage.miniMessage().deserialize("   <#91f251>/spawnelytra setup <name>");
        } else {
            setupButton = MiniMessage.miniMessage().deserialize(
                    "   <#91f251>[<click:suggest_command:'/spawnelytra setup '><hover:show_text:'<#91f251>By default the spawn elytra works 100 blocks around the world spawn.\nType a name and press enter to draw your own spawn with the Setup Help,\nor use /se spawn create <name> circle <radius>.'>Start Setup</hover></click>] "
                    + "[<click:run_command:'/spawnelytra spawn list'><hover:show_text:'<#91f251>Show all spawns'>Spawn List</hover></click>]");
        }
        MessageUtil.sendRaw(player, setupButton);

        final Component dismiss;
        if (bedrock) {
            dismiss = MiniMessage.miniMessage().deserialize("<#aaa8a8>Dismiss this message forever: /spawnelytra dismiss");
        } else {
            dismiss = MiniMessage.miniMessage().deserialize(
                    "<#aaa8a8>[<click:run_command:'/spawnelytra dismiss'><hover:show_text:'<#aaa8a8>Hide this message forever'>Dismiss this message</hover></click>]");
        }
        MessageUtil.sendRaw(player, dismiss);

        if (remainingFirstInstallShows == 0) {
            this.markFirstInstallCompleted();
        }
    }

    public void sendSettingsMenu(final Player player) {
        if (player == null) return;

        MessageUtil.send(player, "settings_menu_header");

        final String currentLanguage = this.getConfig().getString("language", "en");
        final String currentStyle = this.getConfig().getString("messages.style", "classic");
        MessageUtil.send(player, "settings_current_language", Placeholder.unparsed("value", DisplayNames.language(currentLanguage)));
        MessageUtil.send(player, "settings_current_style", Placeholder.unparsed("value", this.prettyStyle(currentStyle)));

        final String activeSpawns = String.join(", ", this.spawnRegistry.all().stream()
                .filter(Spawn::enabled).map(Spawn::name).toList());
        MessageUtil.send(player, "settings_active_spawns", Placeholder.unparsed("value", activeSpawns.isEmpty() ? "-" : activeSpawns));

        MessageUtil.sendRaw(player, Component.text(" "));

        this.lastMenuSent.put(player.getUniqueId().toString(), "settings");

        this.sendLanguageAndStyleChoices(player);
    }

    public void sendOptionsMenu(final Player player) {
        if (player == null) return;

        MessageUtil.send(player, "settings_menu_header");

        MessageUtil.sendRaw(player, Component.text(" "));

        this.lastMenuSent.put(player.getUniqueId().toString(), "options");

        this.sendLanguageAndStyleChoices(player);
    }

    private String prettyStyle(final String style) {
        if (style == null) return "-";
        final String lang = this.getConfig().getString("language", "en").toLowerCase(Locale.ROOT);
        return this.localizedStyleName(style.toLowerCase(Locale.ROOT), lang);
    }

    private void sendLanguageAndStyleChoices(final Player player) {
        final String currentLanguage = this.getConfig().getString("language", "en").toLowerCase(Locale.ROOT);
        final String rawStyle = this.getConfig().getString("messages.style", "classic");
        final String currentStyle = (rawStyle == null ? "classic" : rawStyle).toLowerCase(Locale.ROOT);

        if (BedrockSupport.isBedrockPlayer(player)) {
            MessageUtil.send(player, "settings_change_language");
            MessageUtil.sendRaw(player, MiniMessage.miniMessage().deserialize(
                    "<#91f251>/spawnelytra set language <#ffd166>en<#aaa8a8> | <#ffd166>de<#aaa8a8> | <#ffd166>es<#aaa8a8> | <#ffd166>fr<#aaa8a8> | <#ffd166>pl"
                            + " <#aaa8a8>(current: <#91f251>" + currentLanguage + "<#aaa8a8>)"));
            MessageUtil.send(player, "settings_change_style");
            MessageUtil.sendRaw(player, MiniMessage.miniMessage().deserialize(
                    "<#91f251>/spawnelytra set style <#ffd166>classic<#aaa8a8> | <#ffd166>small_caps"
                            + " <#aaa8a8>(current: <#91f251>" + currentStyle + "<#aaa8a8>)"));
            return;
        }

        MessageUtil.send(player, "settings_change_language");
        
        final StringBuilder langBuilder = new StringBuilder("<#91f251>");
        final String[] languages = {"de", "en", "es", "fr", "pl"};
        final String[] langNames = {"Deutsch", "English", "Español", "Français", "Polski"};
        
        for (int i = 0; i < languages.length; i++) {
            final String lang = languages[i];
            final String langName = langNames[i];
            final boolean isSelected = currentLanguage.equals(lang);
            
            final String hoverKey = "language_hover_" + lang;
            final String hoverText = MessageUtil.plain(hoverKey);
            
            langBuilder.append("[");
            langBuilder.append("<click:run_command:'/spawnelytra set language ").append(lang).append("'>");
            langBuilder.append("<hover:show_text:'").append(hoverText).append("'>");
            if (isSelected) {
                langBuilder.append("<underlined>");
            }
            langBuilder.append(langName);
            if (isSelected) {
                langBuilder.append("</underlined>");
            }
            langBuilder.append("</hover></click>")
                      .append("] ");
        }
        
        final Component langs = MiniMessage.miniMessage().deserialize(langBuilder.toString().trim());
        MessageUtil.sendRaw(player, langs);

        MessageUtil.send(player, "settings_change_style");
        
        final StringBuilder styleBuilder = new StringBuilder("<#91f251>");
        final String[] styles = {"classic", "small_caps"};
        final String[] styleNames = {this.localizedStyleName("classic", currentLanguage), this.localizedStyleName("small_caps", currentLanguage) };
        
        for (int i = 0; i < styles.length; i++) {
            final String style = styles[i];
            final String styleName = styleNames[i];
            final boolean isSelected = currentStyle.equals(style);
            
            final String hoverKey = "style_hover_" + style;
            final String hoverText = MessageUtil.plain(hoverKey);
            
            styleBuilder.append("[");
            styleBuilder.append("<click:run_command:'/spawnelytra set style ").append(style).append("'>");
            styleBuilder.append("<hover:show_text:'").append(hoverText).append("'>");
            if (isSelected) {
                styleBuilder.append("<underlined>");
            }
            styleBuilder.append(styleName);
            if (isSelected) {
                styleBuilder.append("</underlined>");
            }
            styleBuilder.append("</hover></click>")
                       .append("] ");
        }
        
        final Component styleComponents = MiniMessage.miniMessage().deserialize(styleBuilder.toString().trim());
        MessageUtil.sendRaw(player, styleComponents);
    }

    private String localizedStyleName(final String style, final String language) {
        final String lang = language == null ? "en" : language.toLowerCase(Locale.ROOT);
        final String s = style == null ? "" : style.toLowerCase(Locale.ROOT);
        return switch (s) {
            case "classic" -> switch (lang) {
                case "de" -> "Klassisch";
                case "es" -> "Clásico";
                case "fr" -> "Classique";
                case "pl" -> "Klasyczny";
                default -> "Classic";
            };
            case "small_caps" -> "ꜱᴍᴀʟʟ ᴄᴀᴘꜱ";
            default -> style;
        };
    }

    private void saveLanguageFiles() {
        SchedulerUtil.runAsync(this, () -> {
            final File langDir = new File(this.getDataFolder(), "lang");
            if (!langDir.exists() && !langDir.mkdirs()) {
                this.getLogger().warning("Failed to create language directory");
                return;
            }

            final String[] languages = {"en", "de", "es", "fr", "pl"};
            for (final String lang : languages) {
                final File langFile = new File(langDir, lang + ".yml");
                if (langFile.exists()) {
                    continue;
                }
                try (final InputStream in = this.getResource("lang/" + lang + ".yml")) {
                    if (in == null) {
                        continue;
                    }
                    java.nio.file.Files.write(langFile.toPath(), in.readAllBytes());
                } catch (final IOException e) {
                    this.getLogger().warning("Failed to write language file " + lang + ".yml: " + e.getMessage());
                }
            }
        });
    }

    public PlayerDataManager getPlayerDataManager() {
        return this.playerDataManager;
    }

    public TempElytraManager getTempElytraManager() {
        return this.tempElytraManager;
    }

    public SetupManager getSetupManager() {
        return this.setupManager;
    }

    public SpawnRegistry getSpawnRegistry() {
        return this.spawnRegistry;
    }

    public FlightController getFlightController() {
        return this.flightController;
    }

    public AreaVisualizer getVisualizer() {
        return this.visualizer;
    }
}
