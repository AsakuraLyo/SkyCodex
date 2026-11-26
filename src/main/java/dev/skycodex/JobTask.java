package dev.skycodex;

import baritone.api.pathing.goals.GoalNear;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;
import java.util.*;

/** A multi-tick job driven by Controller.tick(). Throw an exception to fail the job. */
interface JobTask {
    /** Advance one client tick; true when finished successfully. */
    boolean tick();
    /** Message reported on success. */
    String message();
    /** The job paused for something else (auto-eat cancelled pathing); re-issue goals on the next tick. */
    default void interrupted() {}
    /** Called once when the job ends for any reason. */
    default void cleanup() {}

    /** Walks over dropped items near the given positions so they are picked up, like a player would. */
    final class DropSweep {
        private final Controller c;
        private final List<BlockPos> around;
        private final int maxTicks;
        private int ticks;
        DropSweep(Controller c, Collection<BlockPos> around, int maxTicks) { this.c = c; this.around = new ArrayList<>(around); this.maxTicks = maxTicks; }
        boolean tick() {
            Minecraft mc = c.mc;
            if (++ticks > maxTicks || around.isEmpty()) { c.bot().getPathingBehavior().cancelEverything(); return true; }
            Set<ItemEntity> items = new LinkedHashSet<>();
            for (BlockPos p : around) items.addAll(mc.level.getEntitiesOfClass(ItemEntity.class, new AABB(p).inflate(4), ItemEntity::isAlive));
            if (items.isEmpty()) { if (ticks > 15) { c.bot().getPathingBehavior().cancelEverything(); return true; } return false; }
            if (ticks % 20 == 1) {
                ItemEntity closest = Collections.min(items, Comparator.comparingDouble(e -> e.distanceToSqr(mc.player)));
                // Standing next to an item is enough to pick it up; walking INTO a berry bush is not.
                c.bot().getCustomGoalProcess().setGoalAndPath(new GoalNear(closest.blockPosition(), 1));
            }
            return false;
        }
    }
}
