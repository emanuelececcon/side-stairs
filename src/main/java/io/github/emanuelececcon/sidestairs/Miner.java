package io.github.emanuelececcon.sidestairs;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.Tool;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.SoundGroup;
import org.bukkit.Statistic;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.RayTraceResult;

/**
 * Survival breaking. The barrier under a sideways stair can't be broken, so the client only says when the player
 * starts and stops hitting it; in between this counts the time the stair itself would take to break, with the same
 * formula as vanilla (tool, enchantments, Haste, Mining Fatigue, water, standing on the ground), and breaks it when
 * the time is up. The player sees the stair's hit sounds and crumbs while they hold the button.
 */
final class Miner implements Runnable {

    private static final class Session {
        final Block block;
        final BlockFace face;
        final BlockData data;
        float progress;
        int ticks;

        Session(Block block, BlockFace face, BlockData data) {
            this.block = block;
            this.face = face;
            this.data = data;
        }
    }

    private final SideStairsPlugin plugin;
    private final Map<UUID, Session> sessions = new HashMap<>();

    Miner(SideStairsPlugin plugin) {
        this.plugin = plugin;
    }

    void start(Player player, Block block, BlockFace face) {
        StairStore.Stair stair = plugin.stairAt(block);
        BlockData data = stair == null ? null : plugin.parse(stair.data());
        if (data == null) {
            return;
        }
        Session session = new Session(block, face == null ? BlockFace.UP : face, data);
        if (progressPerTick(player, data) >= 1.0f) {
            breakNow(player, session);
            return;
        }
        sessions.put(player.getUniqueId(), session);
    }

    void stop(Player player) {
        sessions.remove(player.getUniqueId());
    }

    void clear() {
        sessions.clear();
    }

    int active() {
        return sessions.size();
    }

    @Override
    public void run() {
        if (sessions.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<UUID, Session>> it = sessions.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Session> entry = it.next();
            Session session = entry.getValue();
            Player player = plugin.getServer().getPlayer(entry.getKey());
            if (player == null || player.getGameMode() != GameMode.SURVIVAL || !player.getWorld().equals(session.block.getWorld())
                    || session.block.getType() != Material.BARRIER || !looksAt(player, session.block)) {
                it.remove();
                continue;
            }
            session.progress += progressPerTick(player, session.data);
            session.ticks++;
            if (session.progress >= 1.0f) {
                it.remove();
                breakNow(player, session);
                continue;
            }
            effects(session);
        }
    }

    private boolean looksAt(Player player, Block block) {
        AttributeInstance range = player.getAttribute(Attribute.BLOCK_INTERACTION_RANGE);
        RayTraceResult hit = player.rayTraceBlocks((range == null ? 4.5 : range.getValue()) + 1.0);
        return hit != null && block.equals(hit.getHitBlock());
    }

    private void effects(Session session) {
        Location face = session.block.getLocation().add(0.5, 0.5, 0.5)
                .add(session.face.getModX() * 0.55, session.face.getModY() * 0.55, session.face.getModZ() * 0.55);
        double spreadX = session.face.getModX() == 0 ? 0.25 : 0.02;
        double spreadY = session.face.getModY() == 0 ? 0.25 : 0.02;
        double spreadZ = session.face.getModZ() == 0 ? 0.25 : 0.02;
        session.block.getWorld().spawnParticle(Particle.BLOCK, face, 1, spreadX, spreadY, spreadZ, 0, session.data);
        if (session.ticks % 4 == 0) {
            SoundGroup sound = session.data.getSoundGroup();
            session.block.getWorld().playSound(session.block.getLocation().add(0.5, 0.5, 0.5), sound.getHitSound(),
                    SoundCategory.BLOCKS, (sound.getVolume() + 1.0f) / 8.0f, sound.getPitch() * 0.5f);
        }
    }

    /** Vanilla's {@code BlockBehaviour#getDestroyProgress} for the stair this barrier stands for. */
    static float progressPerTick(Player player, BlockData data) {
        float hardness = data.getMaterial().asBlockType().getHardness();
        if (hardness < 0) {
            return 0;
        }
        if (hardness == 0) {
            return 1;
        }
        ItemStack tool = player.getInventory().getItemInMainHand();
        float speed = data.getDestroySpeed(tool, false);
        if (speed > 1.0f) {
            speed += (float) attribute(player, Attribute.MINING_EFFICIENCY, 0);
        }
        int haste = Math.max(amplifier(player, PotionEffectType.HASTE), amplifier(player, PotionEffectType.CONDUIT_POWER));
        if (haste >= 0) {
            speed *= 1.0f + (haste + 1) * 0.2f;
        }
        int fatigue = amplifier(player, PotionEffectType.MINING_FATIGUE);
        if (fatigue >= 0) {
            speed *= (float) Math.pow(0.3, fatigue + 1);
        }
        speed *= (float) attribute(player, Attribute.BLOCK_BREAK_SPEED, 1);
        if (player.isUnderWater()) {
            speed *= (float) attribute(player, Attribute.SUBMERGED_MINING_SPEED, 0.2);
        }
        @SuppressWarnings("deprecation") // the client's word, as vanilla uses it here
        boolean onGround = player.isOnGround();
        if (!onGround) {
            speed /= 5.0f;
        }
        return speed / hardness / (data.isPreferredTool(tool) ? 30 : 100);
    }

    private static double attribute(Player player, Attribute attribute, double fallback) {
        AttributeInstance instance = player.getAttribute(attribute);
        return instance == null ? fallback : instance.getValue();
    }

    private static int amplifier(Player player, PotionEffectType type) {
        PotionEffect effect = player.getPotionEffect(type);
        return effect == null ? -1 : effect.getAmplifier();
    }

    /** Breaks the sideways stair as the player, the way vanilla breaks a block: event, drops, tool damage, stats. */
    private void breakNow(Player player, Session session) {
        Block block = session.block;
        if (plugin.stairAt(block) == null) {
            return;
        }
        BlockBreakEvent event = new BlockBreakEvent(block, player);
        event.setExpToDrop(0);
        // StairListener#onBreak forgets the stair, removes its display and drops the item.
        if (!event.callEvent()) {
            return;
        }
        if (plugin.store().get(block) != null) {
            // Should not happen: the listener runs for every break that isn't cancelled.
            plugin.breakStair(block, player, event.isDropItems() && session.data.isPreferredTool(player.getInventory().getItemInMainHand()));
        }
        boolean water = block.getBlockData() instanceof Waterlogged waterlogged && waterlogged.isWaterlogged();
        block.setType(water ? Material.WATER : Material.AIR);

        ItemStack tool = player.getInventory().getItemInMainHand();
        Tool toolData = tool.isEmpty() ? null : tool.getData(DataComponentTypes.TOOL);
        if (toolData != null && toolData.damagePerBlock() > 0) {
            player.damageItemStack(EquipmentSlot.HAND, toolData.damagePerBlock());
        }
        player.incrementStatistic(Statistic.MINE_BLOCK, session.data.getMaterial());
        player.setExhaustion(player.getExhaustion() + 0.005f);
    }
}
