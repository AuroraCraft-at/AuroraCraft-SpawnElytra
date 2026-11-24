/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package at.auroracraft.spawnelytra.spawn;

import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;

import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;

/**
 * Flight settings of one spawn. Every value is read from the spawn's own section
 * first and falls back to the global {@code defaults} section.
 */
public record SpawnSettings(
        ActivationMode activationMode,
        boolean boostEnabled,
        double boostStrength,
        BoostDirection boostDirection,
        int maxBoosts,
        long boostCooldownMs,
        Sound boostSound,
        double fKeyLaunchStrength,
        int maxRockets
) {
    /** {@code fireworks.max_rockets} value that means "no limit". */
    public static final int UNLIMITED = -1;

    /** Keys a spawn may override, in the order {@code /se spawn info} shows them. */
    public static final List<String> OVERRIDABLE_KEYS = List.of(
            "activation_mode",
            "boost.enabled",
            "boost.strength",
            "boost.direction",
            "boost.max_boosts",
            "boost.boost_cooldown",
            "boost.sound",
            "f_key.launch_strength",
            "fireworks.max_rockets");

    public enum ActivationMode {
        DOUBLE_JUMP, AUTO, SNEAK_JUMP, F_KEY;

        public String configName() {
            return this.name().toLowerCase(Locale.ROOT);
        }

        public static ActivationMode parse(final String value) {
            if (value == null) {
                return null;
            }
            try {
                return ActivationMode.valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (final IllegalArgumentException e) {
                return null;
            }
        }
    }

    public enum BoostDirection {
        FORWARD, UPWARD;

        public static BoostDirection parse(final String value) {
            if (value == null) {
                return null;
            }
            try {
                return BoostDirection.valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (final IllegalArgumentException e) {
                return null;
            }
        }
    }

    public boolean rocketsUnlimited() {
        return this.maxRockets < 0;
    }

    /**
     * Reads the settings of a spawn.
     *
     * @param own      the spawn's own section (may be null)
     * @param defaults the global {@code defaults} section (may be null)
     * @param logger   receives warnings about invalid values
     * @param context  name used in warnings, usually the spawn name
     */
    public static SpawnSettings load(final ConfigurationSection own, final ConfigurationSection defaults,
                                     final Logger logger, final String context) {
        final Resolver r = new Resolver(own, defaults);

        ActivationMode mode = ActivationMode.parse(r.string("activation_mode", "double_jump"));
        if (mode == null) {
            logger.warning("[" + context + "] Invalid activation_mode '" + r.string("activation_mode", "") + "', using double_jump");
            mode = ActivationMode.DOUBLE_JUMP;
        }

        BoostDirection direction = BoostDirection.parse(r.string("boost.direction", "forward"));
        if (direction == null) {
            logger.warning("[" + context + "] Invalid boost.direction, using forward");
            direction = BoostDirection.FORWARD;
        }

        double strength = r.number("boost.strength", 4);
        if (strength <= 0) {
            logger.warning("[" + context + "] Invalid boost.strength (" + strength + "), using 4");
            strength = 4;
        }

        int maxBoosts = (int) r.number("boost.max_boosts", 1);
        if (maxBoosts < 1) {
            logger.warning("[" + context + "] Invalid boost.max_boosts (" + maxBoosts + "), using 1");
            maxBoosts = 1;
        }

        double cooldown = r.number("boost.boost_cooldown", 0);
        if (cooldown < 0) {
            logger.warning("[" + context + "] Invalid boost.boost_cooldown (" + cooldown + "), using 0");
            cooldown = 0;
        }

        double launch = r.number("f_key.launch_strength", 1.5);
        if (launch <= 0) {
            logger.warning("[" + context + "] Invalid f_key.launch_strength (" + launch + "), using 1.5");
            launch = 1.5;
        }

        int maxRockets = (int) r.number("fireworks.max_rockets", UNLIMITED);
        if (maxRockets < UNLIMITED) {
            logger.warning("[" + context + "] Invalid fireworks.max_rockets (" + maxRockets + "), using -1 (unlimited)");
            maxRockets = UNLIMITED;
        }

        final String soundName = r.string("boost.sound", "ENTITY_BAT_TAKEOFF");
        Sound sound = SpawnSettings.parseSound(soundName);
        if (sound == null) {
            logger.warning("[" + context + "] Invalid boost.sound '" + soundName + "', using ENTITY_BAT_TAKEOFF");
            sound = Sound.ENTITY_BAT_TAKEOFF;
        }

        return new SpawnSettings(mode, r.bool("boost.enabled", true), strength, direction, maxBoosts,
                (long) (cooldown * 1000), sound, launch, maxRockets);
    }

    @SuppressWarnings("deprecation")
    public static Sound parseSound(final String name) {
        if (name == null) {
            return null;
        }
        try {
            return Sound.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (final IllegalArgumentException e) {
            return null;
        }
    }

    /** Looks a key up in the spawn's own section first, then in the defaults. */
    private record Resolver(ConfigurationSection own, ConfigurationSection defaults) {
        private ConfigurationSection source(final String path) {
            if (this.own != null && this.own.isSet(path)) {
                return this.own;
            }
            if (this.defaults != null && this.defaults.isSet(path)) {
                return this.defaults;
            }
            return null;
        }

        String string(final String path, final String fallback) {
            final ConfigurationSection s = this.source(path);
            return s == null ? fallback : s.getString(path, fallback);
        }

        double number(final String path, final double fallback) {
            final ConfigurationSection s = this.source(path);
            return s == null ? fallback : s.getDouble(path, fallback);
        }

        boolean bool(final String path, final boolean fallback) {
            final ConfigurationSection s = this.source(path);
            return s == null ? fallback : s.getBoolean(path, fallback);
        }
    }
}
