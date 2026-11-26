/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package at.auroracraft.spawnelytra.visual;

import at.auroracraft.spawnelytra.spawn.Spawn;
import at.auroracraft.spawnelytra.spawn.SpawnShape;
import at.auroracraft.spawnelytra.util.MessageUtil;
import at.auroracraft.spawnelytra.util.SchedulerUtil;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Color;
import org.bukkit.Particle;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Draws spawn borders with particles that only the viewing player sees. */
public final class AreaVisualizer {
    private static final Color GOLD = Color.fromRGB(255, 215, 0);
    private static final Color BRIGHT_GOLD = Color.fromRGB(255, 255, 100);

    private final JavaPlugin plugin;
    private final Map<UUID, SchedulerUtil.TaskHandle> tasks = new ConcurrentHashMap<>();

    private int verticalRange;
    private int pillarRange;
    private int updateFrequency;
    private float particleSize;
    private boolean enhanced;

    public AreaVisualizer(final JavaPlugin plugin) {
        this.plugin = plugin;
        this.reloadSettings();
    }

    public void reloadSettings() {
        final ConfigurationSection s = this.plugin.getConfig().getConfigurationSection("visualization");
        this.verticalRange = s == null ? 20 : s.getInt("vertical_range", 20);
        this.pillarRange = s == null ? 25 : s.getInt("pillar_vertical_range", 25);
        this.updateFrequency = Math.max(1, s == null ? 10 : s.getInt("update_frequency", 10));
        this.particleSize = (float) Math.max(0.1, s == null ? 2.0 : s.getDouble("particle_size", 2.0));
        this.enhanced = s == null || s.getBoolean("enhanced_particles", true);
    }

    public void visualize(final Player player, final List<Spawn> spawns, final int seconds) {
        this.cancel(player.getUniqueId());
        final String names = String.join(", ", spawns.stream().map(Spawn::name).toList());
        MessageUtil.send(player, "visualize_start",
                Placeholder.unparsed("seconds", String.valueOf(seconds)),
                Placeholder.unparsed("spawn", names));

        final UUID uuid = player.getUniqueId();
        final int maxTicks = seconds * 20;
        final SchedulerUtil.TaskHandle task = SchedulerUtil.runAtEntityTimer(this.plugin, player, 0L, 1L, new Runnable() {
            private int ticks;

            @Override
            public void run() {
                if (this.ticks >= maxTicks || !player.isOnline()) {
                    AreaVisualizer.this.cancel(uuid);
                    if (player.isOnline()) {
                        MessageUtil.send(player, "visualize_end");
                    }
                    return;
                }
                if (this.ticks % AreaVisualizer.this.updateFrequency == 0) {
                    for (final Spawn spawn : spawns) {
                        if (spawn.worldName().equals(player.getWorld().getName())) {
                            AreaVisualizer.this.draw(player, spawn.shape());
                        }
                    }
                }
                this.ticks++;
            }
        });
        this.tasks.put(uuid, task);
    }

    public void stop(final Player player) {
        if (this.cancel(player.getUniqueId())) {
            MessageUtil.send(player, "visualize_stop");
        }
    }

    public void stopAll() {
        for (final UUID uuid : List.copyOf(this.tasks.keySet())) {
            this.cancel(uuid);
        }
    }

    private boolean cancel(final UUID uuid) {
        final SchedulerUtil.TaskHandle task = this.tasks.remove(uuid);
        if (task != null) {
            task.cancel();
            return true;
        }
        return false;
    }

    private void draw(final Player player, final SpawnShape shape) {
        final ParticleScope scope = ParticleScope.of(this.plugin, player);
        switch (shape) {
            case final SpawnShape.Circle c -> this.drawCircle(player, scope, c);
            case final SpawnShape.Rectangle r -> this.drawRectangle(player, scope, r);
        }
    }

    private Particle.DustOptions gold() {
        return new Particle.DustOptions(GOLD, this.particleSize);
    }

    private Particle.DustOptions brightGold() {
        return this.enhanced ? new Particle.DustOptions(BRIGHT_GOLD, this.particleSize * 0.9f) : this.gold();
    }

    private void drawCircle(final Player player, final ParticleScope scope, final SpawnShape.Circle circle) {
        final double playerY = player.getLocation().getY();
        final double cx = circle.centerX();
        final double cz = circle.centerZ();
        final double radius = circle.radius();
        final Particle.DustOptions gold = this.gold();
        final Particle.DustOptions bright = this.brightGold();

        // Arc spacing in blocks instead of degrees, so big circles don't get denser.
        final double circumference = Math.max(1.0, 2 * Math.PI * radius);
        for (int yOffset = -this.verticalRange; yOffset <= this.verticalRange && !scope.exhausted(); yOffset += 2) {
            final double y = playerY + yOffset;
            final double spacing = Math.abs(yOffset) <= 5 ? 0.75 : (Math.abs(yOffset) <= 10 ? 1.25 : 2.0);
            final double angleStep = 360.0 * spacing / circumference;
            final Particle.DustOptions dust = Math.abs(yOffset) <= 8 ? bright : gold;
            for (double angle = 0; angle < 360 && !scope.exhausted(); angle += angleStep) {
                final double rad = Math.toRadians(angle);
                scope.dust(cx + radius * Math.cos(rad), y, cz + radius * Math.sin(rad), dust);
            }
        }

        final double[][] cardinals = {{cx + radius, cz}, {cx - radius, cz}, {cx, cz + radius}, {cx, cz - radius}};
        for (final double[] p : cardinals) {
            this.drawPillar(scope, p[0], p[1], playerY, this.pillarRange, 0.5);
        }
        this.drawCenter(scope, cx, cz, playerY);
    }

    private void drawRectangle(final Player player, final ParticleScope scope, final SpawnShape.Rectangle rect) {
        final double playerY = player.getLocation().getY();
        final Particle.DustOptions gold = this.gold();
        final Particle.DustOptions bright = this.brightGold();

        for (int yOffset = -this.verticalRange; yOffset <= this.verticalRange && !scope.exhausted(); yOffset += 3) {
            final double y = playerY + yOffset;
            final Particle.DustOptions dust = Math.abs(yOffset) <= 6 ? bright : gold;
            scope.lineX(rect.minX(), rect.maxX(), y, rect.minZ(), 0.75, dust);
            scope.lineX(rect.minX(), rect.maxX(), y, rect.maxZ(), 0.75, dust);
            scope.lineZ(rect.minZ(), rect.maxZ(), y, rect.minX(), 0.75, dust);
            scope.lineZ(rect.minZ(), rect.maxZ(), y, rect.maxX(), 0.75, dust);
        }

        final double[][] corners = {
                {rect.minX(), rect.minZ()}, {rect.minX(), rect.maxZ()}, {rect.maxX(), rect.minZ()}, {rect.maxX(), rect.maxZ()}};
        for (final double[] c : corners) {
            this.drawPillar(scope, c[0], c[1], playerY, this.pillarRange, 0.4);
        }
        this.drawCenter(scope, rect.centerX(), rect.centerZ(), playerY);
    }

    private void drawPillar(final ParticleScope scope, final double x, final double z,
                            final double playerY, final int range, final double step) {
        if (!scope.near(x, z)) {
            return;
        }
        for (double y = playerY - range; y <= playerY + range && !scope.exhausted(); y += step) {
            scope.spawn(Particle.END_ROD, x, y, z, 1, 0, 0, 0, 0, null);
        }
        if (this.enhanced) {
            scope.spawn(Particle.FIREWORK, x, playerY, z, 2, 0.1, 0.1, 0.1, 0.02, null);
        }
    }

    private void drawCenter(final ParticleScope scope, final double x, final double z, final double playerY) {
        if (!scope.near(x, z)) {
            return;
        }
        scope.spawn(Particle.END_ROD, x, playerY, z, 5, 0.2, 0.2, 0.2, 0, null);
        if (this.enhanced) {
            scope.spawn(Particle.FIREWORK, x, playerY, z, 3, 0.1, 0.1, 0.1, 0.05, null);
        }
        final int range = Math.max(10, this.verticalRange * 3 / 4);
        final Particle flame = this.enhanced ? Particle.SOUL_FIRE_FLAME : Particle.FLAME;
        for (double y = playerY - range; y <= playerY + range && !scope.exhausted(); y += 1.0) {
            scope.spawn(flame, x, y, z, 1, 0, 0, 0, 0, null);
        }
    }
}
