package dev.skycodex;

import baritone.api.pathing.goals.GoalNear;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.item.*;
import java.util.*;

/**
 * Hunts ordinary farm animals for food: adult, unnamed, unleashed, untamed animals of the requested types only, so
 * pets and named/leashed livestock are never attacked. No hostile-mob combat. Walks over the drops afterwards.
 */
final class HuntTask implements JobTask {
    private final Controller c;
    private final Minecraft mc;
    private final Set<EntityType<?>> types;
    private final int wanted, radius;
    private final BlockPos start;
    private final Map<Item, Integer> before;
    private final List<BlockPos> deaths = new ArrayList<>();
    private Animal target;
    private int kills, ticks, targetTicks, gaveUp;
    private DropSweep sweep;

    HuntTask(Controller c, Set<EntityType<?>> types, int count, int radius) {
        this.c = c; this.mc = c.mc; this.types = types; this.wanted = count; this.radius = radius;
        this.start = mc.player.blockPosition(); this.before = c.snapshot();
        if (candidates().isEmpty()) throw new IllegalStateException("No huntable adult, unnamed, untamed, unleashed animals of those types within " + radius + " blocks");
        equipWeapon();
    }

    static boolean huntable(Animal a) {
        if (!a.isAlive() || a.isBaby() || a.hasCustomName() || a.isLeashed()) return false;
        if (a instanceof TamableAnimal t && t.isTame()) return false;
        return !(a instanceof AbstractHorse h && h.isTamed());
    }

    private List<Animal> candidates() {
        return mc.level.getEntitiesOfClass(Animal.class, new net.minecraft.world.phys.AABB(start).inflate(radius, 8, radius),
                a -> types.contains(a.getType()) && huntable(a));
    }

    /** Best sword, otherwise best axe, from the whole inventory; bare hand when there is none. */
    private void equipWeapon() {
        ItemStack best = ItemStack.EMPTY; float bestScore = 0;
        for (ItemStack s : mc.player.getInventory().items) {
            float score = s.getItem() instanceof SwordItem sw ? 100 + sw.getTier().getAttackDamageBonus()
                    : s.getItem() instanceof AxeItem ax ? 50 + ax.getTier().getAttackDamageBonus() : 0;
            if (score > bestScore) { bestScore = score; best = s; }
        }
        if (!best.isEmpty()) c.equip(BuiltInRegistries.ITEM.getKey(best.getItem()).toString());
    }

    @Override public boolean tick() {
        if (sweep != null) return sweep.tick();
        ticks++;
        if (target != null && !target.isAlive()) { kills++; deaths.add(target.blockPosition()); target = null; }
        if (kills >= wanted) { sweep = new DropSweep(c, deaths, 200); return false; }
        if (target == null || targetTicks > 400) {
            if (target != null) gaveUp++; // could not reach this one; try another
            List<Animal> list = candidates();
            if (target != null) list.remove(target);
            if (list.isEmpty()) {
                if (kills == 0) throw new IllegalStateException("No huntable animal could be reached");
                sweep = new DropSweep(c, deaths, 200); return false;
            }
            target = Collections.min(list, Comparator.comparingDouble(e -> e.distanceToSqr(mc.player)));
            targetTicks = 0;
        }
        targetTicks++;
        double reach = mc.player.entityInteractionRange() - 0.3;
        if (mc.player.distanceTo(target) > reach || !mc.player.hasLineOfSight(target)) {
            if (targetTicks % 20 == 1) c.bot().getCustomGoalProcess().setGoalAndPath(new GoalNear(target.blockPosition(), 1));
            return false;
        }
        c.bot().getPathingBehavior().cancelEverything();
        c.lookAt(target.getEyePosition().add(0, -target.getBbHeight() * 0.3, 0));
        if (mc.player.getAttackStrengthScale(0.5f) >= 0.95f) {
            mc.gameMode.attack(mc.player, target);
            mc.player.swing(InteractionHand.MAIN_HAND);
        }
        return false;
    }

    @Override public void interrupted() { targetTicks = 0; }

    @Override public String message() {
        return "Hunted " + kills + " animal(s)" + (gaveUp > 0 ? " (" + gaveUp + " out of reach)" : "") + "; inventory " + c.gained(before);
    }
}
