/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package at.auroracraft.spawnelytra.spawn;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpawnShapeTest {

    @Test
    void circleIsAColumnAndIgnoresHeight() {
        final SpawnShape.Circle c = new SpawnShape.Circle(0, 0, 100);
        assertTrue(c.contains(0, 0));
        assertTrue(c.contains(100, 0));
        assertTrue(c.contains(70, 70));
        assertFalse(c.contains(71, 71));
    }

    @Test
    void rectangleCoversBothCornerBlocks() {
        final SpawnShape.Rectangle r = SpawnShape.Rectangle.ofBlocks(10, 10, -5, 3);
        assertTrue(r.contains(-5, 3));
        assertTrue(r.contains(10.99, 10.99));
        assertFalse(r.contains(11, 5));
        assertFalse(r.contains(-5.01, 5));
        assertTrue(r.width() == 16 && r.depth() == 8);
    }

    @Test
    void circlesOverlapOnlyWhenTheyShareArea() {
        final SpawnShape.Circle a = new SpawnShape.Circle(0, 0, 50);
        assertTrue(a.overlaps(new SpawnShape.Circle(99, 0, 50)));
        assertFalse(a.overlaps(new SpawnShape.Circle(100, 0, 50)), "touching circles may sit next to each other");
        assertTrue(a.overlaps(new SpawnShape.Circle(0, 0, 5)), "a circle inside another overlaps");
    }

    @Test
    void rectanglesOverlap() {
        final SpawnShape.Rectangle a = SpawnShape.Rectangle.ofBlocks(0, 0, 9, 9);
        assertTrue(a.overlaps(SpawnShape.Rectangle.ofBlocks(9, 9, 20, 20)), "shared corner block");
        assertFalse(a.overlaps(SpawnShape.Rectangle.ofBlocks(10, 0, 20, 9)), "neighbouring blocks");
        assertTrue(a.overlaps(SpawnShape.Rectangle.ofBlocks(2, 2, 3, 3)), "rectangle inside another");
    }

    @Test
    void circleAndRectangleOverlapBothWays() {
        final SpawnShape.Rectangle r = SpawnShape.Rectangle.ofBlocks(0, 0, 9, 9);
        final SpawnShape.Circle inside = new SpawnShape.Circle(5, 5, 1);
        final SpawnShape.Circle nearCorner = new SpawnShape.Circle(13, 13, 4.3); // corner (10,10) is sqrt(18) ~ 4.24 away
        final SpawnShape.Circle farCorner = new SpawnShape.Circle(13, 13, 4.2);
        final SpawnShape.Circle aroundAll = new SpawnShape.Circle(5, 5, 500);
        assertTrue(r.overlaps(inside) && inside.overlaps(r));
        assertTrue(r.overlaps(nearCorner) && nearCorner.overlaps(r));
        assertFalse(r.overlaps(farCorner) || farCorner.overlaps(r));
        assertTrue(r.overlaps(aroundAll) && aroundAll.overlaps(r));
    }

    @Test
    void invalidShapesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new SpawnShape.Circle(0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new SpawnShape.Rectangle(5, 0, 5, 10));
    }
}
