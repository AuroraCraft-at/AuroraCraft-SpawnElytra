/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package at.auroracraft.spawnelytra.config;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LanguageFilesTest {
    private static final List<String> LANGUAGES = List.of("en", "de", "es", "fr", "pl");

    private static YamlConfiguration lang(final String code) throws Exception {
        try (InputStream in = LanguageFilesTest.class.getClassLoader().getResourceAsStream("lang/" + code + ".yml")) {
            assertNotNull(in, code);
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
    }

    @Test
    void everyFileHasTheCurrentVersion() throws Exception {
        for (final String code : LANGUAGES) {
            assertEquals("2.0", lang(code).getString("lang-version"), code);
        }
    }

    @Test
    void germanHasEveryEnglishKey() throws Exception {
        final Set<String> missing = new HashSet<>(lang("en").getKeys(false));
        missing.removeAll(lang("de").getKeys(false));
        assertTrue(missing.isEmpty(), "missing in de.yml: " + missing);
    }

    @Test
    void everyMessageParses() throws Exception {
        final MiniMessage mm = MiniMessage.miniMessage();
        for (final String code : LANGUAGES) {
            final YamlConfiguration y = lang(code);
            for (final String key : y.getKeys(false)) {
                final String value = y.getString(key);
                assertDoesNotThrow(() -> mm.deserialize(value), code + ":" + key);
            }
        }
    }

    @Test
    void usageHintsKeepTheirAngleBrackets() throws Exception {
        final String plain = PlainTextComponentSerializer.plainText()
                .serialize(MiniMessage.miniMessage().deserialize(lang("en").getString("help_spawn_set")));
        assertTrue(plain.contains("<name> <setting> <value>"), plain);
        assertFalse(plain.contains("<#"), plain);
    }
}
