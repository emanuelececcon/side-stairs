package io.github.emanuelececcon.sidestairs;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Effect;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/** SideStairs: hold a stick in the off-hand while placing a stair and it goes on its side. */
public final class SideStairsPlugin extends JavaPlugin {

    private Settings settings;
    private StairStore store;
    private NamespacedKey displayKey;
    private Miner miner;

    long placed;
    long broken;
    long repaired;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        settings = Settings.load(getConfig(), getLogger());
        store = new StairStore(new NamespacedKey(this, "stairs"));
        displayKey = new NamespacedKey(this, "stair");
        miner = new Miner(this);

        getServer().getPluginManager().registerEvents(new StairListener(this), this);
        PluginCommand command = getCommand("sidestairs");
        if (command != null) {
            SideStairsCommand executor = new SideStairsCommand(this);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }
        getServer().getScheduler().runTaskTimer(this, miner, 1L, 1L);
        // Chunks that were already loaded (plugin loaded while the server runs) get their displays checked too.
        int fixed = repairLoadedChunks();
        getLogger().info("Trigger items in the off-hand: " + settings.triggerNames()
                + (fixed > 0 ? ", repaired " + fixed + " sideways stairs" : ""));
    }

    @Override
    public void onDisable() {
        if (miner != null) {
            miner.clear();
        }
    }

    void reloadSettings() {
        reloadConfig();
        settings = Settings.load(getConfig(), getLogger());
    }

    /** Turns a stair the player just placed into a sideways one. */
    void makeSideways(Block block, Stairs placedStair, BlockFace side) {
        Stairs upright = (Stairs) placedStair.getMaterial().createBlockData();
        upright.setFacing(placedStair.getFacing());
        upright.setHalf(org.bukkit.block.data.Bisected.Half.BOTTOM);
        upright.setShape(Stairs.Shape.STRAIGHT);
        upright.setWaterlogged(false);

        BlockData barrier = Material.BARRIER.createBlockData();
        if (barrier instanceof Waterlogged waterlogged) {
            waterlogged.setWaterlogged(placedStair.isWaterlogged());
        }
        // No physics here: the server runs them for the placed position right after the place event.
        block.setBlockData(barrier, false);

        StairStore.Stair stair = new StairStore.Stair(block.getX(), block.getY(), block.getZ(), side, upright.getAsString());
        store.put(block.getChunk(), stair);
        spawnDisplay(block.getWorld(), stair, upright);
        placed++;
    }

    /**
     * Forgets the sideways stair at a block that is being broken and removes its display. Drops the stair item when
     * {@code drop} is true. Returns the stair, or null if there was none.
     */
    StairStore.Stair breakStair(Block block, Player player, boolean drop) {
        StairStore.Stair stair = store.get(block);
        if (stair == null || !store.remove(block)) {
            return null;
        }
        removeDisplays(block);
        broken++;
        BlockData data = parse(stair.data());
        if (data == null) {
            return stair;
        }
        Location center = block.getLocation().add(0.5, 0.5, 0.5);
        block.getWorld().playEffect(center, Effect.STEP_SOUND, data);
        if (drop && (player == null || player.getGameMode() != GameMode.CREATIVE)) {
            block.getWorld().dropItemNaturally(center, new ItemStack(data.getMaterial()));
        }
        return stair;
    }

    /** The barrier at this block is a sideways stair. */
    StairStore.Stair stairAt(Block block) {
        if (block.getType() != Material.BARRIER) {
            return null;
        }
        return store.get(block);
    }

    BlockData parse(String data) {
        try {
            BlockData parsed = Bukkit.createBlockData(data);
            return parsed instanceof Stairs ? parsed : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private BlockDisplay spawnDisplay(World world, StairStore.Stair stair, BlockData data) {
        Location at = new Location(world, stair.x() + 0.5, stair.y() + 0.5, stair.z() + 0.5);
        return world.spawn(at, BlockDisplay.class, display -> {
            display.setBlock(data);
            display.setTransformation(Orientation.transformation(stair.side()));
            display.setViewRange(settings.viewRange());
            display.setPersistent(true);
            display.getPersistentDataContainer().set(displayKey, PersistentDataType.STRING, stair.key());
        });
    }

    private String displayPos(Entity entity) {
        if (!(entity instanceof BlockDisplay)) {
            return null;
        }
        return entity.getPersistentDataContainer().get(displayKey, PersistentDataType.STRING);
    }

    private void removeDisplays(Block block) {
        String key = StairStore.Stair.key(block.getX(), block.getY(), block.getZ());
        Location center = block.getLocation().add(0.5, 0.5, 0.5);
        for (BlockDisplay display : block.getWorld().getNearbyEntitiesByType(BlockDisplay.class, center, 0.5)) {
            if (key.equals(displayPos(display))) {
                display.remove();
            }
        }
    }

    /** Called when a block replaces whatever stood at this position: drops a record left behind by an editing tool. */
    void forgetStale(Block block) {
        if (store.has(block.getChunk()) && store.remove(block)) {
            removeDisplays(block);
        }
    }

    /**
     * Makes a chunk's displays match its records: forgets records whose barrier is gone (replaced by a command or an
     * editing tool), removes displays without a record or duplicated, and draws missing ones (killed by a command).
     * Only runs once the chunk's entities are loaded. Returns how many things it fixed.
     */
    int repair(Chunk chunk) {
        if (!chunk.isLoaded() || !chunk.isEntitiesLoaded()) {
            return 0;
        }
        int fixed = 0;
        World world = chunk.getWorld();
        Map<String, StairStore.Stair> stairs = store.has(chunk) ? store.read(chunk) : new HashMap<>();
        boolean changed = false;
        for (StairStore.Stair stair : new ArrayList<>(stairs.values())) {
            if (world.getBlockAt(stair.x(), stair.y(), stair.z()).getType() != Material.BARRIER || parse(stair.data()) == null) {
                stairs.remove(stair.key());
                changed = true;
                fixed++;
            }
        }
        if (changed) {
            store.write(chunk, stairs);
        }

        Map<String, List<BlockDisplay>> displays = new HashMap<>();
        for (Entity entity : chunk.getEntities()) {
            String key = displayPos(entity);
            if (key != null && entity.isValid()) {
                displays.computeIfAbsent(key, k -> new ArrayList<>()).add((BlockDisplay) entity);
            }
        }
        for (Map.Entry<String, List<BlockDisplay>> entry : displays.entrySet()) {
            StairStore.Stair stair = stairs.get(entry.getKey());
            List<BlockDisplay> list = entry.getValue();
            int keep = stair == null ? 0 : 1;
            for (int i = keep; i < list.size(); i++) {
                list.get(i).remove();
                fixed++;
            }
            if (stair != null) {
                BlockDisplay display = list.getFirst();
                BlockData data = parse(stair.data());
                if (!display.getBlock().equals(data)) {
                    display.setBlock(data);
                }
                display.setTransformation(Orientation.transformation(stair.side()));
                display.setViewRange(settings.viewRange());
            }
        }
        for (StairStore.Stair stair : stairs.values()) {
            if (!displays.containsKey(stair.key())) {
                spawnDisplay(world, stair, parse(stair.data()));
                fixed++;
            }
        }
        repaired += fixed;
        return fixed;
    }

    int repairLoadedChunks() {
        int fixed = 0;
        for (World world : getServer().getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                try {
                    fixed += repair(chunk);
                } catch (RuntimeException e) {
                    getLogger().log(Level.WARNING, "Could not check the sideways stairs of chunk " + chunk.getX() + ", "
                            + chunk.getZ() + " in " + world.getName(), e);
                }
            }
        }
        return fixed;
    }

    /** Sideways stairs in loaded chunks. */
    int loadedCount() {
        int count = 0;
        for (World world : getServer().getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                if (store.has(chunk)) {
                    count += store.read(chunk).size();
                }
            }
        }
        return count;
    }

    Settings settings() {
        return settings;
    }

    StairStore store() {
        return store;
    }

    Miner miner() {
        return miner;
    }
}
