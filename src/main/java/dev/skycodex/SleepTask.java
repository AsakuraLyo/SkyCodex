package dev.skycodex;

import baritone.api.pathing.goals.GoalNear;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.Vec3;

/**
 * Sleep through the night like a player: walk to the nearest free bed (within 32 blocks), lie down, and wait until
 * the night is skipped and the player wakes up. Refuses during the day. On a server the night only passes when
 * enough players sleep; the job then ends with a clear message at its timeout.
 */
final class SleepTask implements JobTask {
    private final Controller c;
    private final Minecraft mc;
    private final BlockPos bed;
    private int phase, ticks, tries;
    private String result = "";

    static boolean canSleepNow(Minecraft mc) {
        long t = mc.level.getDayTime() % 24000L;
        return t >= 12542 && t <= 23459 || mc.level.isThundering();
    }

    SleepTask(Controller c) {
        this.c = c; this.mc = c.mc;
        if (!canSleepNow(mc)) throw new IllegalStateException("It is day: beds only work at night or during thunderstorms");
        BlockPos me = mc.player.blockPosition(), best = null; double bestD = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.betweenClosed(me.offset(-32, -8, -32), me.offset(32, 8, 32))) {
            BlockState s = mc.level.getBlockState(p);
            if (!(s.getBlock() instanceof BedBlock) || s.getValue(BedBlock.PART) != BedPart.HEAD || s.getValue(BedBlock.OCCUPIED)) continue;
            double d = p.distSqr(me);
            if (d < bestD) { bestD = d; best = p.immutable(); }
        }
        if (best == null) throw new IllegalStateException("No free bed within 32 blocks; place a bed first");
        bed = best;
    }

    private boolean inReach() { return mc.player.getEyePosition().distanceTo(Vec3.atCenterOf(bed)) <= mc.player.blockInteractionRange() - 0.6; }

    @Override public boolean tick() {
        ticks++;
        switch (phase) {
            case 0 -> { // walk to the bed
                if (inReach()) { c.bot().getPathingBehavior().cancelEverything(); phase = 1; ticks = 0; }
                else if (ticks > 600) throw new IllegalStateException("Could not walk to the bed at " + bed.toShortString());
                else if (ticks == 1 || ticks % 100 == 0) c.bot().getCustomGoalProcess().setGoalAndPath(new GoalNear(bed, 1));
            }
            case 1 -> { c.useBlock(bed); phase = 2; ticks = 0; tries++; }
            case 2 -> { // the server answers with the sleeping pose, or a chat message ("monsters nearby", "too far")
                if (mc.player.isSleeping()) { phase = 3; ticks = 0; }
                else if (ticks > 40) {
                    if (tries >= 3) throw new IllegalStateException("The bed at " + bed.toShortString() + " did not let us sleep (monsters nearby? not night anymore?); see the chat with mc_screenshot");
                    phase = 1; ticks = 0;
                }
            }
            case 3 -> { // asleep: wait to be woken by the morning
                if (!mc.player.isSleeping()) { phase = 4; ticks = 0; }
            }
            case 4 -> { // the new time of day reaches the client a moment after waking up
                if (!canSleepNow(mc)) { result = "Slept until morning"; return true; }
                if (ticks > 40) { result = "Woke up but it is still night (other players were awake?)"; return true; }
            }
        }
        return false;
    }

    @Override public void interrupted() { if (phase == 0) ticks = 0; }

    /** Cancelled or timed out while in bed: get up like the "Leave Bed" button does. */
    @Override public void cleanup() {
        if (mc.player != null && mc.player.isSleeping() && mc.player.connection != null)
            mc.player.connection.send(new net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket(mc.player, net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket.Action.STOP_SLEEPING));
    }

    @Override public String message() { return result + " (bed " + bed.toShortString() + ")"; }
}
