package io.github.emanuelececcon.sidestairs;

import io.papermc.paper.event.player.PlayerPickBlockEvent;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageAbortEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.util.Vector;

final class StairListener implements Listener {

    /** Where a player last right-clicked a block; the place event that follows doesn't say. */
    private record Click(UUID world, Vector point, int tick) {
    }

    private final SideStairsPlugin plugin;
    private final Map<UUID, Click> clicks = new HashMap<>();

    StairListener(SideStairsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND
                || event.getClickedBlock() == null || event.getClickedPosition() == null) {
            return;
        }
        Block clicked = event.getClickedBlock();
        Vector point = event.getClickedPosition().add(new Vector(clicked.getX(), clicked.getY(), clicked.getZ()));
        clicks.put(event.getPlayer().getUniqueId(), new Click(clicked.getWorld().getUID(), point, plugin.getServer().getCurrentTick()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Block block = event.getBlockPlaced();
        plugin.forgetStale(block);
        if (event.getHand() != EquipmentSlot.HAND || !(block.getBlockData() instanceof Stairs stairs)) {
            return;
        }
        Player player = event.getPlayer();
        if (!plugin.settings().isTrigger(player.getInventory().getItemInOffHand()) || !player.hasPermission("sidestairs.place")) {
            return;
        }
        BlockFace facing = stairs.getFacing();
        BlockFace side = Orientation.rightOf(facing);
        Click click = clicks.remove(player.getUniqueId());
        if (click != null && click.tick() == plugin.getServer().getCurrentTick() && click.world().equals(block.getWorld().getUID())) {
            side = Orientation.side(facing, click.point().getX() - (block.getX() + 0.5), click.point().getZ() - (block.getZ() + 0.5));
        }
        plugin.makeSideways(block, stairs, side);
    }

    /** Creative players break the barrier like any block; other plugins may break it for a player too. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (block.getType() != Material.BARRIER) {
            return;
        }
        Player player = event.getPlayer();
        StairStore.Stair stair = plugin.store().get(block);
        if (stair == null) {
            return;
        }
        BlockData data = plugin.parse(stair.data());
        boolean drop = event.isDropItems() && player.getGameMode() != GameMode.CREATIVE
                && data != null && data.isPreferredTool(player.getInventory().getItemInMainHand());
        plugin.breakStair(block, player, drop);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(BlockDamageEvent event) {
        Player player = event.getPlayer();
        plugin.miner().stop(player);
        if (player.getGameMode() == GameMode.SURVIVAL && plugin.stairAt(event.getBlock()) != null) {
            plugin.miner().start(player, event.getBlock(), event.getBlockFace());
        }
    }

    @EventHandler
    public void onDamageAbort(BlockDamageAbortEvent event) {
        plugin.miner().stop(event.getPlayer());
    }

    /** Middle click picks the stair, not the barrier. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPick(PlayerPickBlockEvent event) {
        StairStore.Stair stair = plugin.stairAt(event.getBlock());
        BlockData data = stair == null ? null : plugin.parse(stair.data());
        if (data == null) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        PlayerInventory inventory = player.getInventory();
        Material material = data.getMaterial();
        for (int slot = 0; slot < 9; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item != null && item.getType() == material) {
                inventory.setHeldItemSlot(slot);
                return;
            }
        }
        int held = inventory.getHeldItemSlot();
        for (int slot = 9; slot < 36; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item != null && item.getType() == material) {
                inventory.setItem(slot, inventory.getItem(held));
                inventory.setItem(held, item);
                return;
            }
        }
        if (player.getGameMode() == GameMode.CREATIVE) {
            inventory.setItem(held, new ItemStack(material));
        }
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        Chunk chunk = event.getChunk();
        if (!plugin.store().has(chunk) && event.getEntities().stream().noneMatch(e -> e instanceof BlockDisplay)) {
            return;
        }
        // Next tick, so displays are never added while the server is still adding the loaded ones.
        plugin.getServer().getScheduler().runTask(plugin, () -> plugin.repair(chunk));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        clicks.remove(event.getPlayer().getUniqueId());
        plugin.miner().stop(event.getPlayer());
    }
}
