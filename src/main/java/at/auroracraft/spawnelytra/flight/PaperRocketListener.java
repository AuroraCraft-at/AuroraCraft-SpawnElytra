/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package at.auroracraft.spawnelytra.flight;

import com.destroystokyo.paper.event.player.PlayerElytraBoostEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Applies the per-flight rocket limit on Paper. The event fires exactly when a
 * firework rocket would boost a gliding player, so only real boosts count.
 * Registered only when the Paper event class exists.
 */
public final class PaperRocketListener implements Listener {
    private final FlightController controller;

    public PaperRocketListener(final FlightController controller) {
        this.controller = controller;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onElytraBoost(final PlayerElytraBoostEvent event) {
        if (!this.controller.tryUseRocket(event.getPlayer())) {
            event.setCancelled(true);
        }
    }
}
