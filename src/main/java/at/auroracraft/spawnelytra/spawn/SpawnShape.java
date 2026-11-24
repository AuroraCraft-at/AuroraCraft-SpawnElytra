/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package at.auroracraft.spawnelytra.spawn;

/**
 * The horizontal outline of a spawn. Every shape is a column: it covers the
 * whole build height of its world, so only X and Z matter.
 */
public sealed interface SpawnShape permits SpawnShape.Circle, SpawnShape.Rectangle {

    boolean contains(double x, double z);

    /** True when both shapes share an area. Shapes that only touch at the edge do not overlap. */
    boolean overlaps(SpawnShape other);

    double centerX();

    double centerZ();

    /** Config name of the shape: {@code circle} or {@code rectangle}. */
    String typeName();

    record Circle(double centerX, double centerZ, double radius) implements SpawnShape {
        public Circle {
            if (radius <= 0) {
                throw new IllegalArgumentException("radius must be positive");
            }
        }

        @Override
        public boolean contains(final double x, final double z) {
            final double dx = x - this.centerX;
            final double dz = z - this.centerZ;
            return dx * dx + dz * dz <= this.radius * this.radius;
        }

        @Override
        public boolean overlaps(final SpawnShape other) {
            return switch (other) {
                case final Circle c -> {
                    final double dx = c.centerX - this.centerX;
                    final double dz = c.centerZ - this.centerZ;
                    final double reach = c.radius + this.radius;
                    yield dx * dx + dz * dz < reach * reach;
                }
                case final Rectangle r -> SpawnShape.circleOverlapsRectangle(this, r);
            };
        }

        @Override
        public String typeName() {
            return "circle";
        }
    }

    /**
     * An axis-aligned rectangle. {@code min} is inclusive and {@code max} exclusive,
     * so a rectangle built from block corners covers both corner blocks completely.
     */
    record Rectangle(double minX, double minZ, double maxX, double maxZ) implements SpawnShape {
        public Rectangle {
            if (maxX <= minX || maxZ <= minZ) {
                throw new IllegalArgumentException("rectangle must have a positive area");
            }
        }

        /** Builds a rectangle that covers every block between two corner blocks (inclusive). */
        public static Rectangle ofBlocks(final int x1, final int z1, final int x2, final int z2) {
            return new Rectangle(Math.min(x1, x2), Math.min(z1, z2), Math.max(x1, x2) + 1, Math.max(z1, z2) + 1);
        }

        @Override
        public boolean contains(final double x, final double z) {
            return x >= this.minX && x < this.maxX && z >= this.minZ && z < this.maxZ;
        }

        @Override
        public boolean overlaps(final SpawnShape other) {
            return switch (other) {
                case final Rectangle r -> this.minX < r.maxX && r.minX < this.maxX
                        && this.minZ < r.maxZ && r.minZ < this.maxZ;
                case final Circle c -> SpawnShape.circleOverlapsRectangle(c, this);
            };
        }

        @Override
        public double centerX() {
            return (this.minX + this.maxX) / 2.0;
        }

        @Override
        public double centerZ() {
            return (this.minZ + this.maxZ) / 2.0;
        }

        public double width() {
            return this.maxX - this.minX;
        }

        public double depth() {
            return this.maxZ - this.minZ;
        }

        @Override
        public String typeName() {
            return "rectangle";
        }
    }

    private static boolean circleOverlapsRectangle(final Circle c, final Rectangle r) {
        final double closestX = Math.max(r.minX(), Math.min(c.centerX(), r.maxX()));
        final double closestZ = Math.max(r.minZ(), Math.min(c.centerZ(), r.maxZ()));
        final double dx = c.centerX() - closestX;
        final double dz = c.centerZ() - closestZ;
        return dx * dx + dz * dz < c.radius() * c.radius();
    }
}
