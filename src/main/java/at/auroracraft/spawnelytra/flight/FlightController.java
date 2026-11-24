/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package at.auroracraft.spawnelytra.flight;

import at.auroracraft.spawnelytra.Main;
import at.auroracraft.spawnelytra.bedrock.TempElytraManager;
import at.auroracraft.spawnelytra.data.PlayerDataManager;
import at.auroracraft.spawnelytra.integration.BedrockSupport;
import at.auroracraft.spawnelytra.spawn.Spawn;
import at.auroracraft.spawnelytra.spawn.SpawnRegistry;
import at.auroracraft.spawnelytra.spawn.SpawnSettings;
import at.auroracraft.spawnelytra.spawn.SpawnSettings.ActivationMode;
import at.auroracraft.spawnelytra.util.MessageUtil;
import at.auroracraft.spawnelytra.util.SchedulerUtil;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.Vector;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The single listener that runs the spawn elytra for every spawn. Which spawn a
 * player is in decides the settings; once a flight has started it keeps the
 * settings of the spawn it started in until the player lands.
 *
 * <p>State is kept in concurrent maps keyed by UUID because on Folia events for
 * different players arrive on different region threads.</p>
 */
public final class FlightController implements Listener {
    private static final long FLIGHT_END_GRACE_MS = 1000L;
    private static final long SNEAK_JUMP_COOLDOWN_MS = 1000L;

    private enum HungerMode { ACTIVATION, DISTANCE, TIME }

    /** Settings that are the same for every spawn. */
    private record GlobalSettings(
            boolean disableInCreative,
            boolean disableInAdventure,
            boolean showPressToBoost,
            boolean showBoostActivated,
            boolean showRocketsRemaining,
            boolean hungerEnabled,
            HungerMode hungerMode,
            int hungerMinimumFoodLevel,
            int hungerActivationCost,
            double hungerDistanceBlocksPerPoint,
            int hungerDistanceCost,
            long hungerTimeIntervalMillis,
            int hungerTimeCost
    ) {
        static GlobalSettings load(final FileConfiguration config) {
            final ConfigurationSection hunger = config.getConfigurationSection("hunger_consumption");
            final boolean hungerEnabled = hunger != null && hunger.getBoolean("enabled", false);
            final HungerMode mode = switch (hunger == null ? "" : hunger.getString("mode", "").toLowerCase(Locale.ROOT)) {
                case "distance" -> HungerMode.DISTANCE;
                case "time" -> HungerMode.TIME;
                default -> HungerMode.ACTIVATION;
            };
            final double secondsPerPoint = hunger == null ? 30 : hunger.getDouble("time.seconds_per_point", 30);
            return new GlobalSettings(
                    config.getBoolean("game_modes.disable_in_creative", true),
                    config.getBoolean("game_modes.disable_in_adventure", false),
                    config.getBoolean("messages.show_press_to_boost", true),
                    config.getBoolean("messages.show_boost_activated", true),
                    config.getBoolean("messages.show_rockets_remaining", true),
                    hungerEnabled,
                    mode,
                    hunger == null ? 0 : Math.max(0, hunger.getInt("minimum_food_level", 0)),
                    hunger == null ? 0 : Math.max(0, hunger.getInt("activation.hunger_cost", 1)),
                    hunger == null ? 50 : Math.max(1.0, hunger.getDouble("distance.blocks_per_point", 50)),
                    hunger == null ? 0 : Math.max(0, hunger.getInt("distance.hunger_cost", 1)),
                    Math.max(1L, (long) Math.ceil(secondsPerPoint * 1000)),
                    hunger == null ? 0 : Math.max(0, hunger.getInt("time.hunger_cost", 1)));
        }
    }

    /** One running spawn-elytra flight. */
    private static final class FlightSession {
        final Spawn spawn;
        final boolean ownElytra;
        int boostsUsed;
        long lastBoostAt;
        int rocketsUsed;
        double hungerDistance;
        long hungerLastConsumption;
        Location hungerLastLocation;

        FlightSession(final Spawn spawn, final boolean ownElytra) {
            this.spawn = spawn;
            this.ownElytra = ownElytra;
        }

        SpawnSettings settings() {
            return this.spawn.settings();
        }
    }

    private final Main plugin;
    private final SpawnRegistry registry;
    private volatile GlobalSettings global;

    private final Map<UUID, FlightSession> sessions = new ConcurrentHashMap<>();
    private final Set<UUID> grantedAllowFlight = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> flightEndGrace = new ConcurrentHashMap<>();
    private final Map<UUID, Long> sneakJumpCooldown = new ConcurrentHashMap<>();
    private final Set<UUID> sneakPressed = ConcurrentHashMap.newKeySet();

    public FlightController(final Main plugin, final SpawnRegistry registry) {
        this.plugin = plugin;
        this.registry = registry;
        this.reloadSettings();
    }

    public void reloadSettings() {
        this.global = GlobalSettings.load(this.plugin.getConfig());
    }

    // ------------------------------------------------------------------ public queries

    public boolean isFlying(final Player player) {
        return this.sessions.containsKey(player.getUniqueId());
    }

    /** The spawn whose settings the player's current flight uses, or null. */
    public Spawn getFlightSpawn(final Player player) {
        final FlightSession session = this.sessions.get(player.getUniqueId());
        return session == null ? null : session.spawn;
    }

    public int getBoostsRemaining(final Player player) {
        final FlightSession session = this.sessions.get(player.getUniqueId());
        final SpawnSettings settings = session != null ? session.settings() : this.settingsAt(player);
        if (settings == null || !settings.boostEnabled()) {
            return 0;
        }
        final int used = session == null ? 0 : session.boostsUsed;
        return Math.max(0, settings.maxBoosts() - used);
    }

    /** Remaining rockets of the current flight; -1 means unlimited. */
    public int getRocketsRemaining(final Player player) {
        final FlightSession session = this.sessions.get(player.getUniqueId());
        final SpawnSettings settings = session != null ? session.settings() : this.settingsAt(player);
        if (settings == null || settings.rocketsUnlimited() || (session != null && session.ownElytra)) {
            return SpawnSettings.UNLIMITED;
        }
        final int used = session == null ? 0 : session.rocketsUsed;
        return Math.max(0, settings.maxRockets() - used);
    }

    private SpawnSettings settingsAt(final Player player) {
        final Spawn spawn = this.registry.findAt(player.getLocation());
        return spawn == null ? null : spawn.settings();
    }

    /** Called after a Bedrock player logs back in mid-air with the temporary elytra still equipped. */
    public boolean resumeBedrockFlight(final Player player) {
        Spawn spawn = this.registry.findAt(player.getLocation());
        if (spawn == null) {
            spawn = this.registry.firstActiveIn(player.getWorld().getName());
        }
        if (spawn == null) {
            return false;
        }
        this.sessions.putIfAbsent(player.getUniqueId(), new FlightSession(spawn, false));
        return true;
    }

    // ------------------------------------------------------------------ helpers

    private boolean isElytraAllowedInMode(final Player player) {
        final GameMode mode = player.getGameMode();
        return GameMode.SURVIVAL == mode || (GameMode.ADVENTURE == mode && !this.global.disableInAdventure());
    }

    private boolean isExternalFlyActive(final Player player) {
        final GameMode mode = player.getGameMode();
        if (GameMode.CREATIVE == mode || GameMode.SPECTATOR == mode || player.isFlying()) {
            return true;
        }
        return player.getAllowFlight() && !this.grantedAllowFlight.contains(player.getUniqueId());
    }

    /** True when the player wears a real, usable elytra (not the temporary Bedrock one). */
    public boolean wearsOwnElytra(final Player player) {
        final ItemStack chest = player.getInventory().getChestplate();
        if (chest == null || Material.ELYTRA != chest.getType()) {
            return false;
        }
        if (this.plugin.getTempElytraManager().isTempElytra(chest)) {
            return false;
        }
        final ItemMeta meta = chest.getItemMeta();
        if (meta instanceof final Damageable damageable && !meta.isUnbreakable()) {
            return damageable.getDamage() < chest.getType().getMaxDurability() - 1;
        }
        return true;
    }

    private boolean canUseSpawnElytra(final Player player) {
        return player.hasPermission("spawnelytra.use")
                && this.isElytraAllowedInMode(player)
                && !this.isExternalFlyActive(player);
    }

    private static boolean hasAirBelow(final Player player) {
        final Location loc = player.getLocation();
        for (int i = 1; i <= 3; i++) {
            final Block block = loc.clone().subtract(0, i, 0).getBlock();
            if (Material.AIR != block.getType() && Material.CAVE_AIR != block.getType()) {
                return false;
            }
        }
        return true;
    }

    private static boolean isFlyToggleCommand(final String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        final String trimmed = message.charAt(0) == '/' ? message.substring(1) : message;
        final int space = trimmed.indexOf(' ');
        final String command = (space >= 0 ? trimmed.substring(0, space) : trimmed).toLowerCase(Locale.ROOT);
        return "fly".equals(command) || "essentials:fly".equals(command) || "essentialsx:fly".equals(command);
    }

    private void grantAllowFlight(final Player player) {
        this.grantedAllowFlight.add(player.getUniqueId());
        // Checked separately: a game mode change resets allowFlight while the UUID stays marked.
        if (!player.getAllowFlight()) {
            player.setAllowFlight(true);
        }
    }

    private void revokeAllowFlight(final Player player) {
        if (this.grantedAllowFlight.remove(player.getUniqueId())) {
            player.setAllowFlight(false);
        }
    }

    // ------------------------------------------------------------------ flight lifecycle

    /** Starts the invisible spawn elytra (or the player's own elytra) from an activation mode. */
    private void activateFlight(final Player player, final Spawn spawn) {
        if (this.isExternalFlyActive(player) || this.sessions.containsKey(player.getUniqueId())) {
            return;
        }
        final boolean ownElytra = this.wearsOwnElytra(player);
        if (!ownElytra && !this.chargeActivationHunger(player)) {
            return;
        }
        player.setGliding(true);
        this.revokeAllowFlight(player);
        this.startSession(player, spawn, ownElytra);
    }

    private void startSession(final Player player, final Spawn spawn, final boolean ownElytra) {
        final FlightSession session = new FlightSession(spawn, ownElytra);
        if (this.sessions.putIfAbsent(player.getUniqueId(), session) != null) {
            return;
        }

        final PlayerDataManager data = this.plugin.getPlayerDataManager();
        if (data != null) {
            data.incrementFlyCount(player);
        }

        final SpawnSettings settings = spawn.settings();
        if (this.global.showPressToBoost() && settings.boostEnabled()) {
            final boolean bedrock = BedrockSupport.isManaged(player);
            if (settings.maxBoosts() > 1) {
                MessageUtil.sendActionBar(player, bedrock ? "press_to_boost_remaining_bedrock" : "press_to_boost_remaining",
                        Placeholder.unparsed("remaining", String.valueOf(settings.maxBoosts())));
            } else {
                MessageUtil.sendActionBar(player, bedrock ? "press_to_boost_bedrock" : "press_to_boost");
            }
        }

        if (!ownElytra && this.global.hungerEnabled()) {
            if (HungerMode.DISTANCE == this.global.hungerMode()) {
                session.hungerLastLocation = player.getLocation().clone();
            } else if (HungerMode.TIME == this.global.hungerMode()) {
                session.hungerLastConsumption = System.currentTimeMillis();
            }
        }
    }

    private void endFlight(final Player player) {
        final UUID uuid = player.getUniqueId();
        final FlightSession session = this.sessions.remove(uuid);
        this.revokeAllowFlight(player);
        if (session == null) {
            return;
        }
        if (!session.ownElytra) {
            player.setGliding(false);
        }
        player.setFallDistance(0);
        this.flightEndGrace.put(uuid, System.currentTimeMillis());
    }

    /** Ends every flight, e.g. before a reload. */
    public void cleanupAll() {
        for (final Player player : this.plugin.getServer().getOnlinePlayers()) {
            this.cleanupPlayer(player);
        }
    }

    public void cleanupPlayer(final Player player) {
        this.endFlight(player);
        final UUID uuid = player.getUniqueId();
        this.sneakJumpCooldown.remove(uuid);
        this.sneakPressed.remove(uuid);
        this.flightEndGrace.remove(uuid);
    }

    // ------------------------------------------------------------------ hunger

    private boolean shouldConsumeHunger(final Player player) {
        final GlobalSettings g = this.global;
        if (!g.hungerEnabled()) {
            return false;
        }
        final GameMode mode = player.getGameMode();
        if (GameMode.CREATIVE == mode || GameMode.SPECTATOR == mode) {
            return false;
        }
        return player.getFoodLevel() > g.hungerMinimumFoodLevel();
    }

    private boolean hasActivationHungerAvailable(final Player player) {
        final GlobalSettings g = this.global;
        return !g.hungerEnabled() || HungerMode.ACTIVATION != g.hungerMode() || this.shouldConsumeHunger(player);
    }

    private boolean chargeActivationHunger(final Player player) {
        final GlobalSettings g = this.global;
        if (!g.hungerEnabled() || HungerMode.ACTIVATION != g.hungerMode()) {
            return true;
        }
        if (!this.shouldConsumeHunger(player)) {
            MessageUtil.sendActionBar(player, "not_enough_hunger");
            return false;
        }
        this.consumeHunger(player, g.hungerActivationCost());
        return true;
    }

    private void consumeHunger(final Player player, final int amount) {
        final int min = this.global.hungerMinimumFoodLevel();
        final int current = player.getFoodLevel();
        if (amount <= 0 || current <= min) {
            return;
        }
        final int newLevel = Math.max(min, current - amount);
        player.setFoodLevel(newLevel);
        if (player.getSaturation() > newLevel) {
            player.setSaturation(newLevel);
        }
    }

    private void handleHungerWhileFlying(final Player player, final FlightSession session) {
        final GlobalSettings g = this.global;
        if (HungerMode.DISTANCE == g.hungerMode()) {
            final Location current = player.getLocation();
            final Location last = session.hungerLastLocation;
            session.hungerLastLocation = current.clone();
            if (last == null || !this.shouldConsumeHunger(player) || last.getWorld() != current.getWorld()) {
                session.hungerDistance = 0;
                return;
            }
            session.hungerDistance += last.distance(current);
            if (session.hungerDistance >= g.hungerDistanceBlocksPerPoint() && g.hungerDistanceCost() > 0) {
                final int steps = (int) (session.hungerDistance / g.hungerDistanceBlocksPerPoint());
                this.consumeHunger(player, g.hungerDistanceCost() * steps);
                session.hungerDistance -= g.hungerDistanceBlocksPerPoint() * steps;
            }
        } else if (HungerMode.TIME == g.hungerMode()) {
            final long now = System.currentTimeMillis();
            if (session.hungerLastConsumption == 0 || !this.shouldConsumeHunger(player)) {
                session.hungerLastConsumption = now;
                return;
            }
            if (now - session.hungerLastConsumption >= g.hungerTimeIntervalMillis()) {
                this.consumeHunger(player, g.hungerTimeCost());
                session.hungerLastConsumption = now;
            }
        }
    }

    // ------------------------------------------------------------------ boost

    private boolean tryBoost(final Player player) {
        final FlightSession session = this.sessions.get(player.getUniqueId());
        if (session == null || !player.isGliding() || !player.hasPermission("spawnelytra.useboost")) {
            return false;
        }
        final SpawnSettings settings = session.settings();
        if (!settings.boostEnabled() || session.boostsUsed >= settings.maxBoosts()) {
            return false;
        }
        final long now = System.currentTimeMillis();
        if (settings.boostCooldownMs() > 0 && session.lastBoostAt > 0 && now - session.lastBoostAt < settings.boostCooldownMs()) {
            return false;
        }

        session.boostsUsed++;
        session.lastBoostAt = now;

        final Vector velocity = SpawnSettings.BoostDirection.UPWARD == settings.boostDirection()
                ? new Vector(0, settings.boostStrength(), 0)
                : player.getLocation().getDirection().multiply(settings.boostStrength());
        player.setVelocity(velocity);
        player.playSound(player.getLocation(), settings.boostSound(), 1.0f, 1.0f);

        final PlayerDataManager data = this.plugin.getPlayerDataManager();
        if (data != null) {
            data.incrementBoostCount(player);
        }

        final int remaining = settings.maxBoosts() - session.boostsUsed;
        if (this.global.showBoostActivated()) {
            if (remaining > 0 && settings.maxBoosts() > 1) {
                MessageUtil.sendActionBar(player, "boost_activated_remaining",
                        Placeholder.unparsed("remaining", String.valueOf(remaining)));
            } else {
                MessageUtil.sendActionBar(player, "boost_activated");
            }
        }

        if (remaining > 0 && this.global.showPressToBoost() && settings.maxBoosts() > 1) {
            final boolean bedrock = BedrockSupport.isManaged(player);
            final long delayTicks = settings.boostCooldownMs() > 0 ? Math.max(20L, settings.boostCooldownMs() / 50) : 30L;
            SchedulerUtil.runAtEntityLater(this.plugin, player, delayTicks, () -> {
                final FlightSession current = this.sessions.get(player.getUniqueId());
                if (current != null && player.isGliding()) {
                    final int left = current.settings().maxBoosts() - current.boostsUsed;
                    if (left > 0) {
                        MessageUtil.sendActionBar(player, bedrock ? "press_to_boost_remaining_bedrock" : "press_to_boost_remaining",
                                Placeholder.unparsed("remaining", String.valueOf(left)));
                    }
                }
            });
        }
        return true;
    }

    // ------------------------------------------------------------------ rockets

    /**
     * Decides whether a firework rocket may boost the player. Called by the rocket
     * listeners right before the rocket would be used.
     *
     * @return false when the rocket must be blocked
     */
    public boolean tryUseRocket(final Player player) {
        final FlightSession session = this.sessions.get(player.getUniqueId());
        if (session == null || session.ownElytra || this.wearsOwnElytra(player)) {
            return true;
        }
        final SpawnSettings settings = session.settings();
        if (settings.rocketsUnlimited()) {
            return true;
        }
        if (settings.maxRockets() == 0) {
            MessageUtil.sendActionBar(player, "rockets_disabled");
            return false;
        }
        if (session.rocketsUsed >= settings.maxRockets()) {
            MessageUtil.sendActionBar(player, "rockets_depleted");
            return false;
        }
        session.rocketsUsed++;
        if (this.global.showRocketsRemaining()) {
            MessageUtil.sendActionBar(player, "rockets_remaining",
                    Placeholder.unparsed("remaining", String.valueOf(settings.maxRockets() - session.rocketsUsed)),
                    Placeholder.unparsed("max", String.valueOf(settings.maxRockets())));
        }
        return true;
    }

    // ------------------------------------------------------------------ Bedrock

    private void updateBedrockPriming(final Player player, final Spawn spawn) {
        if (this.sessions.containsKey(player.getUniqueId()) || player.isGliding()) {
            return;
        }
        final TempElytraManager manager = this.plugin.getTempElytraManager();
        final boolean wantPrimed = spawn != null
                && this.canUseSpawnElytra(player)
                && !this.wearsOwnElytra(player)
                && this.hasActivationHungerAvailable(player);
        if (wantPrimed) {
            manager.ensureEquipped(player);
        } else if (manager.hasTempElytraEquipped(player)) {
            manager.ensureRestored(player);
        }
    }

    private void handleBedrockGlideStart(final Player player, final EntityToggleGlideEvent event) {
        final TempElytraManager manager = this.plugin.getTempElytraManager();
        final Spawn spawn = this.registry.findAt(player.getLocation());
        final boolean allowed = spawn != null
                && this.canUseSpawnElytra(player)
                && this.chargeActivationHunger(player);
        if (!allowed) {
            event.setCancelled(true);
            manager.ensureRestored(player);
            return;
        }
        this.startSession(player, spawn, false);
    }

    // ------------------------------------------------------------------ events

    @EventHandler
    public void onDoubleJump(final PlayerToggleFlightEvent event) {
        if (!event.isFlying()) {
            return;
        }
        final Player player = event.getPlayer();
        if (BedrockSupport.isManaged(player) || !this.grantedAllowFlight.contains(player.getUniqueId())) {
            return;
        }
        final Spawn spawn = this.registry.findAt(player.getLocation());
        if (spawn == null || ActivationMode.DOUBLE_JUMP != spawn.settings().activationMode() || !this.canUseSpawnElytra(player)) {
            return;
        }
        event.setCancelled(true);
        if (!player.isGliding()) {
            this.activateFlight(player, spawn);
        }
    }

    @EventHandler
    public void onPlayerSneak(final PlayerToggleSneakEvent event) {
        final Player player = event.getPlayer();
        final UUID uuid = player.getUniqueId();

        if (event.isSneaking() && BedrockSupport.isManaged(player) && player.isGliding() && this.sessions.containsKey(uuid)) {
            this.tryBoost(player);
            return;
        }
        if (!event.isSneaking()) {
            this.sneakPressed.remove(uuid);
            return;
        }

        final Spawn spawn = this.registry.findAt(player.getLocation());
        if (spawn == null || ActivationMode.SNEAK_JUMP != spawn.settings().activationMode() || !this.canUseSpawnElytra(player)) {
            return;
        }

        this.sneakPressed.add(uuid);
        SchedulerUtil.runAtEntityLater(this.plugin, player, 10L, () -> {
            if (this.sneakPressed.remove(uuid) && !player.isOnGround() && !this.sessions.containsKey(uuid)) {
                final long now = System.currentTimeMillis();
                final Long last = this.sneakJumpCooldown.get(uuid);
                if (last == null || now - last >= SNEAK_JUMP_COOLDOWN_MS) {
                    final Spawn current = this.registry.findAt(player.getLocation());
                    if (current != null) {
                        this.activateFlight(player, current);
                        this.sneakJumpCooldown.put(uuid, now);
                    }
                }
            }
        });
    }

    @EventHandler
    public void onPlayerCommandPreprocess(final PlayerCommandPreprocessEvent event) {
        if (this.grantedAllowFlight.contains(event.getPlayer().getUniqueId()) && isFlyToggleCommand(event.getMessage())) {
            this.revokeAllowFlight(event.getPlayer());
        }
    }

    @EventHandler
    public void onPlayerMove(final PlayerMoveEvent event) {
        final Player player = event.getPlayer();
        final UUID uuid = player.getUniqueId();
        final Spawn spawn = this.registry.findAt(event.getTo() != null ? event.getTo() : player.getLocation());

        if (BedrockSupport.isManaged(player)) {
            this.updateBedrockPriming(player, spawn);
        } else if (!this.isExternalFlyActive(player)) {
            final boolean ready = spawn != null
                    && this.isElytraAllowedInMode(player)
                    && player.hasPermission("spawnelytra.use")
                    && !player.isGliding()
                    && !this.sessions.containsKey(uuid);

            // Double jump needs allowFlight so the client reports the second jump.
            // Players with their own elytra deploy it the vanilla way instead.
            if (ready && ActivationMode.DOUBLE_JUMP == spawn.settings().activationMode() && !this.wearsOwnElytra(player)) {
                this.grantAllowFlight(player);
            } else if (!this.sessions.containsKey(uuid)) {
                this.revokeAllowFlight(player);
            }

            if (ready && ActivationMode.AUTO == spawn.settings().activationMode()
                    && !player.isOnGround() && !player.isFlying() && hasAirBelow(player)) {
                this.activateFlight(player, spawn);
            }
        }

        final FlightSession session = this.sessions.get(uuid);
        if (session == null) {
            return;
        }
        if (player.isOnGround() || player.getLocation().getBlock().getRelative(BlockFace.DOWN).isLiquid()) {
            this.endFlight(player);
            return;
        }
        player.setFallDistance(0);
        if (!session.ownElytra && player.isGliding() && this.global.hungerEnabled()) {
            this.handleHungerWhileFlying(player, session);
        }
    }

    @EventHandler
    public void onEntityDamage(final EntityDamageEvent event) {
        if (!(event.getEntity() instanceof final Player player)) {
            return;
        }
        if (DamageCause.FALL != event.getCause() && DamageCause.FLY_INTO_WALL != event.getCause()) {
            return;
        }
        final UUID uuid = player.getUniqueId();
        if (this.sessions.containsKey(uuid)
                || this.plugin.getTempElytraManager().isTempElytra(player.getInventory().getChestplate())) {
            event.setCancelled(true);
            return;
        }
        final Long flightEnd = this.flightEndGrace.get(uuid);
        if (flightEnd != null) {
            if (System.currentTimeMillis() - flightEnd <= FLIGHT_END_GRACE_MS) {
                event.setCancelled(true);
            } else {
                this.flightEndGrace.remove(uuid);
            }
        }
    }

    @EventHandler
    public void onSwapItem(final PlayerSwapHandItemsEvent event) {
        final Player player = event.getPlayer();
        final UUID uuid = player.getUniqueId();

        if (!BedrockSupport.isManaged(player) && !this.sessions.containsKey(uuid)) {
            final Spawn spawn = this.registry.findAt(player.getLocation());
            if (spawn != null && ActivationMode.F_KEY == spawn.settings().activationMode() && this.canUseSpawnElytra(player)) {
                event.setCancelled(true);
                player.setVelocity(new Vector(0, spawn.settings().fKeyLaunchStrength(), 0));
                SchedulerUtil.runAtEntityLater(this.plugin, player, 5L, () -> this.activateFlight(player, spawn));
                return;
            }
        }

        if (this.tryBoost(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onToggleGlide(final EntityToggleGlideEvent event) {
        if (!(event.getEntity() instanceof final Player player)) {
            return;
        }
        final UUID uuid = player.getUniqueId();
        final FlightSession session = this.sessions.get(uuid);

        if (session == null) {
            if (!event.isGliding()) {
                return;
            }
            if (BedrockSupport.isManaged(player) && this.plugin.getTempElytraManager().hasTempElytraEquipped(player)) {
                this.handleBedrockGlideStart(player, event);
                return;
            }
            // Vanilla deploy of the player's own elytra inside a spawn: give the spawn perks.
            if (this.wearsOwnElytra(player)) {
                final Spawn spawn = this.registry.findAt(player.getLocation());
                if (spawn != null && this.canUseSpawnElytra(player)) {
                    this.revokeAllowFlight(player);
                    this.startSession(player, spawn, true);
                }
            }
            return;
        }

        if (event.isGliding()) {
            this.revokeAllowFlight(player);
            return;
        }
        if (BedrockSupport.isManaged(player)) {
            return;
        }
        if (this.wearsOwnElytra(player)) {
            // The vanilla elytra stops on landing or in water; end the spawn flight with it.
            this.endFlight(player);
            return;
        }
        // Without a glider the server stops gliding every tick; keep the invisible elytra open.
        event.setCancelled(true);
    }

    @EventHandler
    public void onGameModeChange(final PlayerGameModeChangeEvent event) {
        if (!this.global.disableInCreative() || GameMode.CREATIVE != event.getNewGameMode()) {
            return;
        }
        final Player player = event.getPlayer();
        if (this.sessions.containsKey(player.getUniqueId())) {
            SchedulerUtil.runAtEntityLater(this.plugin, player, 1L, () -> {
                this.endFlight(player);
                SchedulerUtil.runAtEntityLater(this.plugin, player, 5L, () -> player.setAllowFlight(true));
            });
        }
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        this.cleanupPlayer(event.getPlayer());
    }
}
