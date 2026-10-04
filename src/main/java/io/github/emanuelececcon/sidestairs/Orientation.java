package io.github.emanuelececcon.sidestairs;

import org.bukkit.block.BlockFace;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * How a sideways stair is turned.
 *
 * <p>The display shows an upright stair facing the way the player looked (its tall back on the far side), turned 90
 * degrees around that direction so that its bottom half ends up on one side. The result is a block with three of its
 * four vertical quarters: the half on that side plus the far quarter on the other side. The missing quarter is the
 * one nearest the player, on the side opposite to {@code side}.
 */
final class Orientation {

    private Orientation() {
    }

    static boolean isHorizontal(BlockFace face) {
        return face == BlockFace.NORTH || face == BlockFace.EAST || face == BlockFace.SOUTH || face == BlockFace.WEST;
    }

    /** The direction to the right of someone looking towards {@code facing}. */
    static BlockFace rightOf(BlockFace facing) {
        return switch (facing) {
            case NORTH -> BlockFace.EAST;
            case EAST -> BlockFace.SOUTH;
            case SOUTH -> BlockFace.WEST;
            case WEST -> BlockFace.NORTH;
            default -> throw new IllegalArgumentException("not a horizontal direction: " + facing);
        };
    }

    /**
     * The side the stair's bottom half goes to: the side of the block the player clicked, seen from where they look.
     * {@code dx} and {@code dz} are the clicked point minus the centre of the new block. Clicking the side of a
     * neighbouring block therefore leans the stair against that block.
     */
    static BlockFace side(BlockFace facing, double dx, double dz) {
        BlockFace right = rightOf(facing);
        double along = dx * right.getModX() + dz * right.getModZ();
        return along >= 0 ? right : right.getOppositeFace();
    }

    /**
     * The display transformation for a stair whose bottom half goes to {@code side}, for a display entity standing at
     * the centre of the block. The rotation turns the stair around the block centre.
     */
    static Transformation transformation(BlockFace side) {
        Quaternionf rotation = new Quaternionf().rotationTo(
                new Vector3f(0, -1, 0), new Vector3f(side.getModX(), side.getModY(), side.getModZ()));
        Vector3f translation = rotation.transform(new Vector3f(0.5f, 0.5f, 0.5f)).negate();
        return new Transformation(translation, rotation, new Vector3f(1, 1, 1), new Quaternionf());
    }
}
