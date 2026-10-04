package io.github.emanuelececcon.sidestairs;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Chunk;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/**
 * The sideways stairs of a chunk, kept in the chunk's own data so they are saved with the world. This is the source of
 * truth: the barrier at the position is the stair's collision and the display entity only draws it, so both can be
 * rebuilt from here.
 */
final class StairStore {

    /** One sideways stair: where it is, which side its bottom half went to and the upright stair it shows. */
    record Stair(int x, int y, int z, BlockFace side, String data) {

        static String key(int x, int y, int z) {
            return x + " " + y + " " + z;
        }

        String key() {
            return key(x, y, z);
        }

        String encode() {
            return x + " " + y + " " + z + " " + side.name() + " " + data;
        }

        static Stair decode(String line) {
            String[] parts = line.split(" ", 5);
            if (parts.length != 5) {
                return null;
            }
            try {
                BlockFace side = BlockFace.valueOf(parts[3]);
                if (!Orientation.isHorizontal(side)) {
                    return null;
                }
                return new Stair(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), side, parts[4]);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    private final NamespacedKey key;

    StairStore(NamespacedKey key) {
        this.key = key;
    }

    /** The stairs of a chunk by {@link Stair#key()}, in the order they were placed. */
    Map<String, Stair> read(Chunk chunk) {
        Map<String, Stair> stairs = new LinkedHashMap<>();
        List<String> lines = chunk.getPersistentDataContainer().get(key, PersistentDataType.LIST.strings());
        if (lines != null) {
            for (String line : lines) {
                Stair stair = Stair.decode(line);
                if (stair != null) {
                    stairs.put(stair.key(), stair);
                }
            }
        }
        return stairs;
    }

    boolean has(Chunk chunk) {
        return chunk.getPersistentDataContainer().has(key);
    }

    Stair get(Block block) {
        Chunk chunk = block.getChunk();
        if (!has(chunk)) {
            return null;
        }
        return read(chunk).get(Stair.key(block.getX(), block.getY(), block.getZ()));
    }

    void put(Chunk chunk, Stair stair) {
        Map<String, Stair> stairs = read(chunk);
        stairs.put(stair.key(), stair);
        write(chunk, stairs);
    }

    /** Forgets the stair at a block; false if there was none. */
    boolean remove(Block block) {
        Chunk chunk = block.getChunk();
        if (!has(chunk)) {
            return false;
        }
        Map<String, Stair> stairs = read(chunk);
        if (stairs.remove(Stair.key(block.getX(), block.getY(), block.getZ())) == null) {
            return false;
        }
        write(chunk, stairs);
        return true;
    }

    void write(Chunk chunk, Map<String, Stair> stairs) {
        PersistentDataContainer pdc = chunk.getPersistentDataContainer();
        if (stairs.isEmpty()) {
            pdc.remove(key);
            return;
        }
        List<String> lines = new ArrayList<>(stairs.size());
        for (Stair stair : stairs.values()) {
            lines.add(stair.encode());
        }
        pdc.set(key, PersistentDataType.LIST.strings(), lines);
    }
}
