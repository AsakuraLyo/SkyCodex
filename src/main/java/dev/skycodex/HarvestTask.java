package dev.skycodex;

import baritone.api.pathing.goals.GoalNear;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/**
 * Harvests mature crops near the player like a farmer: breaks ripe crops (vanilla and modded CropBlock, nether wart,
 * cocoa) and replants them from the drops, or right-clicks berry bushes. Unripe plants are never touched.
 */
final class HarvestTask implements JobTask {
    enum Kind { BREAK_REPLANT, BREAK, RIGHT_CLICK }

    private final Controller c;
    private final Minecraft mc;
    private final boolean replant;
    private final Deque<BlockPos> targets = new ArrayDeque<>();
    private final List<BlockPos> done = new ArrayList<>();
    private final Map<Item, Integer> before;
    private BlockPos current;
    private Kind kind;
    private Block cropBlock;
    private int phase, ticks, harvested, replanted, skipped, picked;
    private boolean tryingRightClick;
    /** Per crop block: does right-click harvesting work in this pack (learned once per session)? */
    private static final Map<Block, Boolean> RIGHT_CLICK_WORKS = new HashMap<>();
    private DropSweep sweep;

    HarvestTask(Controller c, int radius, int limit, boolean replant, Set<Block> only) {
        this.c = c; this.mc = c.mc; this.replant = replant; this.before = c.snapshot();
        BlockPos center = mc.player.blockPosition();
        List<BlockPos> found = new ArrayList<>();
        for (BlockPos p : BlockPos.betweenClosed(center.offset(-radius, -4, -radius), center.offset(radius, 4, radius))) {
            if (!mc.level.hasChunkAt(p)) continue;
            BlockState s = mc.level.getBlockState(p);
            if ((only.isEmpty() || only.contains(s.getBlock())) && kind(s) != null) found.add(p.immutable());
        }
        found.sort(Comparator.comparingDouble(p -> p.distSqr(center)));
        if (found.isEmpty()) throw new IllegalStateException("No mature crops or ripe berry bushes within " + radius + " blocks");
        targets.addAll(found.subList(0, Math.min(limit, found.size())));
    }

    /** How a ripe plant is harvested, or null when it is not ripe / not supported. */
    static Kind kind(BlockState s) {
        Block b = s.getBlock();
        if (b instanceof SweetBerryBushBlock) return s.getValue(SweetBerryBushBlock.AGE) >= 2 ? Kind.RIGHT_CLICK : null;
        if (b instanceof CropBlock crop) return crop.isMaxAge(s) ? Kind.BREAK_REPLANT : null;
        if (b instanceof NetherWartBlock) return s.getValue(NetherWartBlock.AGE) >= 3 ? Kind.BREAK_REPLANT : null;
        if (b instanceof CocoaBlock) return s.getValue(CocoaBlock.AGE) >= 2 ? Kind.BREAK : null;
        return null;
    }

    private boolean inReach(BlockPos p) { return mc.player.getEyePosition().distanceTo(Vec3.atCenterOf(p)) <= mc.player.blockInteractionRange() - 0.6; }

    @Override public boolean tick() {
        if (sweep != null) return sweep.tick();
        ticks++;
        switch (phase) {
            case 0 -> { // next target
                current = targets.poll();
                if (current == null) { sweep = new DropSweep(c, done, 200); return false; }
                BlockState s = mc.level.getBlockState(current);
                kind = kind(s); cropBlock = s.getBlock();
                if (kind == null) { skipped++; return false; } // changed meanwhile
                ticks = 0;
                if (inReach(current)) phase = 2;
                else { c.bot().getCustomGoalProcess().setGoalAndPath(new GoalNear(current, 1)); phase = 1; }
            }
            case 1 -> { // walking
                if (inReach(current)) { c.bot().getPathingBehavior().cancelEverything(); phase = 2; }
                else if (ticks > 40 && !c.bot().getCustomGoalProcess().isActive() && !c.bot().getPathingBehavior().isPathing() || ticks > 400) { skipped++; phase = 0; }
            }
            case 2 -> { // act
                c.lookAt(Vec3.atCenterOf(current));
                // Many packs (this one included) add right-click harvesting: the crop resets to age 0 and drops its
                // produce without being broken. Try that first; fall back to break + replant if nothing happens.
                tryingRightClick = kind == Kind.BREAK_REPLANT && RIGHT_CLICK_WORKS.getOrDefault(cropBlock, true);
                if (kind == Kind.RIGHT_CLICK || tryingRightClick) {
                    c.selectHarmlessHotbarSlot(); // never right-click a crop with bone meal or seeds in hand
                    c.useBlock(current);
                } else mc.gameMode.startDestroyBlock(current, Direction.UP);
                mc.player.swing(InteractionHand.MAIN_HAND);
                phase = 3; ticks = 0;
            }
            case 3 -> { // verify on the server's answer
                BlockState s = mc.level.getBlockState(current);
                if (tryingRightClick) {
                    if (s.is(cropBlock) && kind(s) == null) { // picked, plant stays
                        RIGHT_CLICK_WORKS.put(cropBlock, true);
                        harvested++; picked++; done.add(current); phase = 0; ticks = 0;
                    } else if (!s.is(cropBlock)) { harvested++; done.add(current); phase = replant ? 4 : 0; ticks = 0; } // broke it after all
                    else if (ticks > 12) { RIGHT_CLICK_WORKS.put(cropBlock, false); phase = 2; } // no right-click harvest for this crop
                    return false;
                }
                boolean harvestedNow = kind == Kind.RIGHT_CLICK ? kind(s) == null : !s.is(cropBlock);
                if (harvestedNow) {
                    harvested++; done.add(current);
                    phase = kind == Kind.BREAK_REPLANT && replant ? 4 : 0; ticks = 0;
                } else if (ticks > 20) { mc.gameMode.stopDestroyBlock(); skipped++; phase = 0; }
            }
            case 4 -> { // replant from the drop (seeds come back once picked up)
                Item seed = cropBlock.asItem();
                if (seed == Items.AIR || !mc.level.getBlockState(current).isAir()) { phase = 0; return false; }
                if (c.count(BuiltInRegistries.ITEM.getKey(seed).toString()) < 1) { if (ticks > 30) phase = 0; return false; }
                c.equip(BuiltInRegistries.ITEM.getKey(seed).toString());
                phase = 5; ticks = 0;
            }
            case 5 -> {
                if (ticks < 3) return false; // hotbar switch reaches the server first
                BlockPos soil = current.below();
                Vec3 hit = Vec3.atCenterOf(soil).add(0, 0.5, 0);
                c.lookAt(hit);
                mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, new BlockHitResult(hit, Direction.UP, soil, false));
                mc.player.swing(InteractionHand.MAIN_HAND);
                phase = 6; ticks = 0;
            }
            case 6 -> {
                if (mc.level.getBlockState(current).is(cropBlock)) { replanted++; phase = 0; }
                else if (ticks > 20) phase = 0;
            }
        }
        return false;
    }

    @Override public void interrupted() {
        if (sweep != null) return; // the sweep re-issues its goal every second anyway
        if (phase == 1 && current != null) { targets.addFirst(current); phase = 0; }
    }

    @Override public String message() {
        return "Harvested " + harvested + (picked > 0 ? " (" + picked + " by right-click, plants kept)" : "") + (replanted > 0 ? ", replanted " + replanted : "") + (skipped > 0 ? ", skipped " + skipped + " (unreachable or changed)" : "")
                + "; inventory " + c.gained(before);
    }
}
