/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package at.auroracraft.spawnelytra.spawn;

import org.bukkit.Location;
import org.bukkit.World;

/**
 * One spawn area where players can use the spawn elytra.
 *
 * @param name               the key under {@code spawns} in config.yml
 * @param worldName          the world the spawn is in
 * @param shape              the horizontal outline; the area covers the full build height
 * @param settings           flight settings (own overrides merged with the defaults)
 * @param enabled            disabled spawns still block their area for other spawns
 * @param centerIsWorldSpawn true when the circle follows the world spawn point (no {@code center} in config)
 */
public record Spawn(String name, String worldName, SpawnShape shape, SpawnSettings settings,
                    boolean enabled, boolean centerIsWorldSpawn) {

    public boolean contains(final Location location) {
        final World world = location.getWorld();
        return world != null
                && world.getName().equals(this.worldName)
                && this.shape.contains(location.getX(), location.getZ());
    }
}
