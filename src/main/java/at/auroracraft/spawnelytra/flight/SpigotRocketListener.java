/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package at.auroracraft.spawnelytra.flight;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Fallback for servers without Paper's elytra boost event: a rocket used in
 * the air while gliding is the boost case.
 */
public final class SpigotRocketListener implements Listener {
    private final FlightController controller;

    public SpigotRocketListener(final FlightController controller) {
        this.controller = controller;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(final PlayerInteractEvent event) {
        if (Action.RIGHT_CLICK_AIR != event.getAction()) {
            return;
        }
        final ItemStack item = event.getItem();
        final Player player = event.getPlayer();
        if (item == null || Material.FIREWORK_ROCKET != item.getType() || !player.isGliding()) {
            return;
        }
        if (!this.controller.tryUseRocket(player)) {
            event.setUseItemInHand(Event.Result.DENY);
            event.setCancelled(true);
        }
    }
}
