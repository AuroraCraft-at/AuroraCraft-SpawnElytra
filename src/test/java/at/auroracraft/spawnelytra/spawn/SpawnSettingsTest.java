/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package at.auroracraft.spawnelytra.spawn;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpawnSettingsTest {
    private static final Logger LOG = Logger.getLogger("test");

    private static YamlConfiguration yaml(final String text) throws Exception {
        final YamlConfiguration y = new YamlConfiguration();
        y.loadFromString(text);
        return y;
    }

    @Test
    void spawnValuesOverrideDefaults() throws Exception {
        final YamlConfiguration defaults = yaml("""
                activation_mode: double_jump
                boost: {strength: 4, max_boosts: 1}
                fireworks: {max_rockets: -1}
                """);
        final YamlConfiguration own = yaml("""
                activation_mode: f_key
                fireworks: {max_rockets: 10}
                """);
        final SpawnSettings s = SpawnSettings.load(own, defaults, LOG, "test");
        assertEquals(SpawnSettings.ActivationMode.F_KEY, s.activationMode());
        assertEquals(10, s.maxRockets());
        assertEquals(4, s.boostStrength());
        assertEquals(1, s.maxBoosts());
    }

    @Test
    void minusOneMeansUnlimitedRockets() throws Exception {
        final SpawnSettings s = SpawnSettings.load(yaml("fireworks: {max_rockets: -1}"), null, LOG, "test");
        assertTrue(s.rocketsUnlimited());
    }

    @Test
    void invalidValuesFallBackToSafeDefaults() throws Exception {
        final SpawnSettings s = SpawnSettings.load(yaml("""
                activation_mode: fly_like_a_bird
                boost: {strength: -3, max_boosts: 0, sound: NOT_A_SOUND}
                fireworks: {max_rockets: -7}
                """), null, LOG, "test");
        assertEquals(SpawnSettings.ActivationMode.DOUBLE_JUMP, s.activationMode());
        assertEquals(4, s.boostStrength());
        assertEquals(1, s.maxBoosts());
        assertEquals(SpawnSettings.UNLIMITED, s.maxRockets());
    }
}
