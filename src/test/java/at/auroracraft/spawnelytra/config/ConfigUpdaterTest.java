/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package at.auroracraft.spawnelytra.config;

import at.auroracraft.spawnelytra.spawn.SpawnSettings;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigUpdaterTest {
    private static final Logger LOG = Logger.getLogger("test");

    private static YamlConfiguration resource(final String path) throws Exception {
        try (InputStream in = ConfigUpdaterTest.class.getClassLoader().getResourceAsStream(path)) {
            assertNotNull(in, path);
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
    }

    private static YamlConfiguration convert(final String legacy) throws Exception {
        final YamlConfiguration result = ConfigUpdater.convert(resource(legacy), resource("config.yml"), LOG);
        assertNotNull(result);
        // Round trip through text, the way the file is written and read on the server.
        final YamlConfiguration reloaded = new YamlConfiguration();
        reloaded.loadFromString(result.saveToString());
        return reloaded;
    }

    @Test
    void defaultConfigOf15BecomesOneWorldSpawnCircle() throws Exception {
        final YamlConfiguration c = convert("legacy/config-1.5-default.yml");
        assertEquals("world", c.getString("spawns.world.world"));
        assertEquals("circle", c.getString("spawns.world.shape"));
        assertEquals(100, c.getDouble("spawns.world.radius"));
        assertFalse(c.isSet("spawns.world.center"), "auto mode keeps following the world spawn");
        assertFalse(c.isSet("spawns.world.activation_mode"), "values equal to the defaults are not copied");
        assertFalse(c.contains("worlds"));
        assertEquals("2.0", c.getString("config_version"));
    }

    @Test
    void multiWorldConfigKeepsEveryAreaAndSetting() throws Exception {
        final YamlConfiguration c = convert("legacy/config-1.5-multiworld.yml");

        assertEquals("de", c.getString("language"));
        assertTrue(c.getBoolean("first_install_completed"));
        assertTrue(c.getBoolean("game_modes.disable_in_adventure"));
        assertFalse(c.getBoolean("bedrock.enabled"));
        assertFalse(c.isSet("bedrock.sneak_to_boost"), "obsolete keys are dropped");
        assertEquals("small_caps", c.getString("messages.style"));
        assertEquals("distance", c.getString("hunger_consumption.mode"));
        assertEquals(80.0, c.getDouble("hunger_consumption.distance.blocks_per_point"));
        assertEquals(0, c.getInt("defaults.fireworks.max_rockets"), "disable_in_spawn_elytra -> 0 rockets");
        assertFalse(c.contains("fireworks"));

        assertEquals("rectangle", c.getString("spawns.world.shape"));
        assertEquals(-51, c.getInt("spawns.world.pos1.x"));
        assertEquals(20, c.getInt("spawns.world.pos1.z"));
        assertEquals(49, c.getInt("spawns.world.pos2.x"));
        assertEquals(-31, c.getInt("spawns.world.pos2.z"));

        assertEquals("world_nether", c.getString("spawns.world_nether.world"));
        assertEquals("circle", c.getString("spawns.world_nether.shape"));
        assertEquals(10, c.getDouble("spawns.world_nether.center.x"));
        assertEquals(-5, c.getDouble("spawns.world_nether.center.z"));
        assertEquals(40, c.getDouble("spawns.world_nether.radius"));
        assertEquals("f_key", c.getString("spawns.world_nether.activation_mode"));
        assertEquals(6, c.getInt("spawns.world_nether.boost.strength"));
        assertEquals(3, c.getInt("spawns.world_nether.boost.max_boosts"));
        assertEquals(2.0, c.getDouble("spawns.world_nether.f_key.launch_strength"));

        assertEquals("World The End", c.getString("spawns.world_the_end.world"), "unsafe characters become _");
        assertFalse(c.getBoolean("spawns.world_the_end.enabled"));

        final SpawnSettings nether = SpawnSettings.load(
                c.getConfigurationSection("spawns.world_nether"), c.getConfigurationSection("defaults"), LOG, "nether");
        assertEquals(SpawnSettings.ActivationMode.F_KEY, nether.activationMode());
        assertEquals(0, nether.maxRockets());
        assertEquals(2000, nether.boostCooldownMs());
    }

    @Test
    void flat13ConfigIsMigrated() throws Exception {
        final YamlConfiguration c = convert("legacy/config-1.3.yml");
        assertEquals("circle", c.getString("spawns.world.shape"));
        assertEquals(100, c.getDouble("spawns.world.center.x"));
        assertEquals(200, c.getDouble("spawns.world.center.z"));
        assertEquals(75, c.getDouble("spawns.world.radius"));
        assertEquals("sneak_jump", c.getString("spawns.world.activation_mode"));
        assertEquals("upward", c.getString("spawns.world.boost.direction"));
        assertEquals(3, c.getInt("spawns.world.boost.strength"));
    }

    @Test
    void convertedConfigKeepsComments() throws Exception {
        final YamlConfiguration result = ConfigUpdater.convert(resource("legacy/config-1.5-default.yml"), resource("config.yml"), LOG);
        final String text = result.saveToString();
        assertTrue(text.contains("-1 = unlimited, 0 = no rockets"), text);
        assertTrue(text.contains("Spawns must not overlap"), text);
    }

    @Test
    void currentConfigIsNotRewrittenAgain() throws Exception {
        final YamlConfiguration first = convert("legacy/config-1.5-multiworld.yml");
        assertNull(ConfigUpdater.convert(first, resource("config.yml"), LOG), "a 2.0 config without gaps stays untouched");
    }

    @Test
    void missingKeysAreAddedToA20Config() throws Exception {
        final YamlConfiguration partial = new YamlConfiguration();
        partial.loadFromString("""
                config_version: '2.0'
                language: de
                defaults:
                  activation_mode: auto
                spawns:
                  hub: {world: world, shape: circle, radius: 30, center: {x: 0, z: 0}}
                """);
        final YamlConfiguration result = ConfigUpdater.convert(partial, resource("config.yml"), LOG);
        assertNotNull(result);
        assertEquals("auto", result.getString("defaults.activation_mode"), "existing values stay");
        assertEquals(-1, result.getInt("defaults.fireworks.max_rockets"));
        assertTrue(result.isSet("messages.show_rockets_remaining"));
        assertFalse(result.isSet("spawns.world"), "the example spawn is never added");
        assertEquals(30, result.getDouble("spawns.hub.radius"));
    }
}
