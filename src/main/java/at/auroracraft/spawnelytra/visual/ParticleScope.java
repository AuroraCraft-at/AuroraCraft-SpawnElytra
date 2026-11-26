/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package at.auroracraft.spawnelytra.visual;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Limits particles to what one player can actually see: only points within a
 * horizontal radius around the player, and at most a fixed number per draw.
 *
 * <p>Without this, large spawns (e.g. a 335x353 rectangle) send tens of
 * thousands of particle packets per draw, the client freezes and is kicked
 * with a keepalive timeout.</p>
 */
public final class ParticleScope {
    public static final double DEFAULT_RADIUS = 40.0;
    public static final int DEFAULT_MAX_PARTICLES = 1500;

    private final Player player;
    private final World world;
    private final double px;
    private final double pz;
    private final double radius;
    private final double radiusSq;
    private int budget;

    public ParticleScope(final Player player, final double radius, final int maxParticles) {
        this.player = player;
        this.world = player.getWorld();
        final Location loc = player.getLocation();
        this.px = loc.getX();
        this.pz = loc.getZ();
        this.radius = Math.max(4.0, radius);
        this.radiusSq = this.radius * this.radius;
        this.budget = Math.max(1, maxParticles);
    }

    /** Reads {@code visualization.view_radius} and {@code visualization.max_particles}. */
    public static ParticleScope of(final JavaPlugin plugin, final Player player) {
        final ConfigurationSection s = plugin.getConfig().getConfigurationSection("visualization");
        final double radius = s == null ? DEFAULT_RADIUS : s.getDouble("view_radius", DEFAULT_RADIUS);
        final int max = s == null ? DEFAULT_MAX_PARTICLES : s.getInt("max_particles", DEFAULT_MAX_PARTICLES);
        return new ParticleScope(player, radius, max);
    }

    public boolean exhausted() {
        return this.budget <= 0;
    }

    public boolean near(final double x, final double z) {
        final double dx = x - this.px;
        final double dz = z - this.pz;
        return dx * dx + dz * dz <= this.radiusSq;
    }

    /** Spawns one particle effect if it is near the player and the budget allows it. */
    public void spawn(final Particle particle, final double x, final double y, final double z, final int count,
                      final double ox, final double oy, final double oz, final double extra, final Object data) {
        if (this.budget <= 0 || !this.near(x, z)) {
            return;
        }
        this.budget--;
        final Location loc = new Location(this.world, x, y, z);
        if (data == null) {
            this.player.spawnParticle(particle, loc, count, ox, oy, oz, extra);
        } else {
            this.player.spawnParticle(particle, loc, count, ox, oy, oz, extra, data);
        }
    }

    public void dust(final double x, final double y, final double z, final Particle.DustOptions dust) {
        this.spawn(Particle.DUST, x, y, z, 1, 0, 0, 0, 0, dust);
    }

    /** Line along X at a fixed z, only the part near the player. Points stay on a fixed grid so they don't flicker. */
    public void lineX(final double minX, final double maxX, final double y, final double z, final double step,
                      final Particle.DustOptions dust) {
        if (Math.abs(z - this.pz) > this.radius) {
            return;
        }
        final double lo = Math.max(minX, this.px - this.radius);
        final double hi = Math.min(maxX, this.px + this.radius);
        for (double x = alignedStart(minX, lo, step); x <= hi && !this.exhausted(); x += step) {
            this.dust(x, y, z, dust);
        }
    }

    /** Line along Z at a fixed x, only the part near the player. */
    public void lineZ(final double minZ, final double maxZ, final double y, final double x, final double step,
                      final Particle.DustOptions dust) {
        if (Math.abs(x - this.px) > this.radius) {
            return;
        }
        final double lo = Math.max(minZ, this.pz - this.radius);
        final double hi = Math.min(maxZ, this.pz + this.radius);
        for (double z = alignedStart(minZ, lo, step); z <= hi && !this.exhausted(); z += step) {
            this.dust(x, y, z, dust);
        }
    }

    /** Filled plane, only the part near the player. */
    public void plane(final double minX, final double minZ, final double maxX, final double maxZ, final double y,
                      final double step, final Particle.DustOptions dust) {
        final double loX = Math.max(minX, this.px - this.radius);
        final double hiX = Math.min(maxX, this.px + this.radius);
        final double loZ = Math.max(minZ, this.pz - this.radius);
        final double hiZ = Math.min(maxZ, this.pz + this.radius);
        for (double x = alignedStart(minX, loX, step); x <= hiX && !this.exhausted(); x += step) {
            for (double z = alignedStart(minZ, loZ, step); z <= hiZ && !this.exhausted(); z += step) {
                this.dust(x, y, z, dust);
            }
        }
    }

    static double alignedStart(final double origin, final double from, final double step) {
        if (from <= origin) {
            return origin;
        }
        return origin + Math.ceil((from - origin) / step) * step;
    }
}
