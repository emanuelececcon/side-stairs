package io.github.emanuelececcon.sidestairs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.bukkit.block.BlockFace;
import org.bukkit.util.Transformation;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class OrientationTest {

    private static final BlockFace[] HORIZONTAL = {BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST};

    /** The eighths of an upright bottom stair facing {@code facing}: the whole bottom half plus the top quarter at the back. */
    private static boolean uprightOccupied(BlockFace facing, int i, int j, int k) {
        if (j == 0) {
            return true;
        }
        return switch (facing) {
            case NORTH -> k == 0;
            case SOUTH -> k == 1;
            case EAST -> i == 1;
            case WEST -> i == 0;
            default -> throw new IllegalArgumentException();
        };
    }

    /** Which eighths of the block the display fills, by sending the centre of each model eighth through the transformation. */
    private static Set<String> rendered(BlockFace facing, BlockFace side) {
        Transformation t = Orientation.transformation(side);
        Set<String> cells = new HashSet<>();
        for (int i = 0; i < 2; i++) {
            for (int j = 0; j < 2; j++) {
                for (int k = 0; k < 2; k++) {
                    if (!uprightOccupied(facing, i, j, k)) {
                        continue;
                    }
                    Vector3f p = new Vector3f(0.25f + 0.5f * i, 0.25f + 0.5f * j, 0.25f + 0.5f * k);
                    p.mul(t.getScale());
                    t.getRightRotation().transform(p);
                    t.getLeftRotation().transform(p);
                    p.add(t.getTranslation());
                    // The display stands at the block centre.
                    p.add(0.5f, 0.5f, 0.5f);
                    for (float c : new float[] {p.x, p.y, p.z}) {
                        assertTrue(Math.abs(c - 0.25f) < 1e-4 || Math.abs(c - 0.75f) < 1e-4,
                                "eighth centre off the grid: " + p);
                    }
                    cells.add((p.x > 0.5f ? 1 : 0) + "," + (p.y > 0.5f ? 1 : 0) + "," + (p.z > 0.5f ? 1 : 0));
                }
            }
        }
        return cells;
    }

    @Test
    void missingColumnIsNearThePlayerOppositeTheClickedSide() {
        for (BlockFace facing : HORIZONTAL) {
            for (BlockFace side : new BlockFace[] {Orientation.rightOf(facing), Orientation.rightOf(facing).getOppositeFace()}) {
                Set<String> cells = rendered(facing, side);
                assertEquals(6, cells.size(), facing + " " + side);
                BlockFace near = facing.getOppositeFace();
                BlockFace other = side.getOppositeFace();
                for (int i = 0; i < 2; i++) {
                    for (int k = 0; k < 2; k++) {
                        boolean missing = onSide(near, i, k) && onSide(other, i, k);
                        for (int j = 0; j < 2; j++) {
                            assertEquals(!missing, cells.contains(i + "," + j + "," + k),
                                    facing + " " + side + " cell " + i + "," + j + "," + k);
                        }
                    }
                }
            }
        }
    }

    private static boolean onSide(BlockFace face, int i, int k) {
        return switch (face) {
            case NORTH -> k == 0;
            case SOUTH -> k == 1;
            case EAST -> i == 1;
            case WEST -> i == 0;
            default -> throw new IllegalArgumentException();
        };
    }

    @Test
    void sideFollowsTheClickedPoint() {
        // Looking north, east is to the right.
        assertEquals(BlockFace.EAST, Orientation.side(BlockFace.NORTH, 0.3, 0));
        assertEquals(BlockFace.WEST, Orientation.side(BlockFace.NORTH, -0.3, 0.5));
        // Clicking the west face of a block to the east (looking south): lean against it.
        assertEquals(BlockFace.EAST, Orientation.side(BlockFace.SOUTH, 0.5, 0.1));
        assertEquals(BlockFace.SOUTH, Orientation.side(BlockFace.EAST, 0, 0.2));
        assertEquals(BlockFace.NORTH, Orientation.side(BlockFace.WEST, 0.4, -0.01));
    }

    @Test
    void storedLinesRoundTrip() {
        StairStore.Stair stair = new StairStore.Stair(-12, -60, 300, BlockFace.WEST,
                "minecraft:oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]");
        assertEquals(stair, StairStore.Stair.decode(stair.encode()));
        assertEquals(null, StairStore.Stair.decode("1 2 3 UP minecraft:oak_stairs"));
        assertEquals(null, StairStore.Stair.decode("garbage"));
    }
}
