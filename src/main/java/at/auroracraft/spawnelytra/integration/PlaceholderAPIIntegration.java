/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package at.auroracraft.spawnelytra.integration;

import at.auroracraft.spawnelytra.Main;
import at.auroracraft.spawnelytra.data.PlayerDataManager;
import at.auroracraft.spawnelytra.flight.FlightController;
import at.auroracraft.spawnelytra.spawn.Spawn;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public class PlaceholderAPIIntegration extends PlaceholderExpansion {
    private final Main plugin;
    private final PlayerDataManager playerDataManager;

    public PlaceholderAPIIntegration(final Main plugin, final PlayerDataManager playerDataManager) {
        this.plugin = plugin;
        this.playerDataManager = playerDataManager;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "spawnelytra";
    }

    @Override
    public @NotNull String getAuthor() {
        return "AuroraCraft";
    }

    @Override
    public @NotNull String getVersion() {
        return this.plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public boolean canRegister() {
        return true;
    }

    @Override
    public String onPlaceholderRequest(final Player player, @NotNull final String identifier) {
        if (player == null) {
            return "";
        }

        if ("fly_count".equals(identifier)) {
            return String.valueOf(this.playerDataManager.getPlayerData(player.getUniqueId()).getFlyCount());
        }

        if ("boost_count".equals(identifier)) {
            return String.valueOf(this.playerDataManager.getPlayerData(player.getUniqueId()).getBoostCount());
        }

        if ("total_count".equals(identifier)) {
            final PlayerDataManager.PlayerData data = this.playerDataManager.getPlayerData(player.getUniqueId());
            return String.valueOf(data.getFlyCount() + data.getBoostCount());
        }

        final FlightController flight = this.plugin.getFlightController();
        switch (identifier) {
            case "flying" -> {
                return String.valueOf(flight.isFlying(player));
            }
            case "in_area" -> {
                return String.valueOf(this.plugin.getSpawnRegistry().findAt(player.getLocation()) != null);
            }
            case "spawn" -> {
                final Spawn flightSpawn = flight.getFlightSpawn(player);
                final Spawn spawn = flightSpawn != null ? flightSpawn : this.plugin.getSpawnRegistry().findAt(player.getLocation());
                return spawn == null ? "" : spawn.name();
            }
            case "boosts_remaining" -> {
                return String.valueOf(flight.getBoostsRemaining(player));
            }
            case "rockets_remaining" -> {
                final int rockets = flight.getRocketsRemaining(player);
                return rockets < 0 ? "∞" : String.valueOf(rockets);
            }
            default -> {
            }
        }

        return null;
    }
}
