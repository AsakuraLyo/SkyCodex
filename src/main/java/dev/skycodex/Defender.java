package dev.skycodex;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.*;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/**
 * Self-defence while control is enabled (the user asked not to stop on danger but to fight back):
 * whoever just hurt the player (mob or player) is attacked, and hostile mobs that come within melee reach are attacked.
 * The running job is paused meanwhile and resumed afterwards. It never chases: attacking happens only within reach,
 * because walking after a target with Baritone could dig through the user's buildings.
 */
final class Defender {
    // Whoever hurt us is engaged up to 12 blocks away (skeletons shoot from 10+); we walk up to it in a straight line.
    private static final double ENGAGE = 12.0, GIVE_UP_DISTANCE = 14.0;
    private static final int MAX_TICKS = 600, MAX_OUT_OF_REACH = 120, ATTACKER_MEMORY = 200;
    private final Controller c;
    private final Minecraft mc;
    private LivingEntity target, attacker;
    private int tickCount, attackerTick, ticks, outOfReach, savedSlot = -1, kills, engagements;
    private String last = "";

    Defender(Controller c) { this.c = c; this.mc = c.mc; }

    boolean active() { return target != null; }

    void reset() { if (target != null) end("control disabled"); attacker = null; }

    /** Call every tick while control is enabled. True while defending: the job must not run this tick. */
    boolean tick() {
        LocalPlayer p = mc.player;
        tickCount++;
        // Remember who hurt us (the client gets the damage source with the attacking entity).
        DamageSource src = p.getLastDamageSource();
        if (src != null && p.hurtTime > 0 && src.getEntity() instanceof LivingEntity le && le != p && !friendly(le)) { attacker = le; attackerTick = tickCount; }
        if (target != null) {
            String why = invalid(target);
            if (why == null) { fight(); return true; }
            end(why);
            return false; // let the caller resume the job; a new target is picked next tick
        }
        LivingEntity t = choose();
        if (t == null) return false;
        start(t);
        fight();
        return true;
    }

    private boolean friendly(LivingEntity e) {
        if (e instanceof TamableAnimal t && t.isTame()) return true;
        if (e instanceof OwnableEntity o && o.getOwnerUUID() != null) return true;
        return e instanceof Player pl && (pl.isCreative() || pl.isSpectator());
    }

    private LivingEntity choose() {
        LocalPlayer p = mc.player;
        if (attacker != null && tickCount - attackerTick < ATTACKER_MEMORY && attacker.isAlive() && !attacker.isRemoved()
                && attacker.distanceTo(p) <= ENGAGE) return attacker;
        double reach = p.entityInteractionRange();
        List<LivingEntity> near = mc.level.getEntitiesOfClass(LivingEntity.class, p.getBoundingBox().inflate(reach + 0.5, 2, reach + 0.5),
                e -> e instanceof Enemy && e.isAlive() && !e.isRemoved() && !e.hasCustomName() && !friendly(e) && !e.isInvisible()
                        && e.distanceTo(p) <= reach + 0.5 && p.hasLineOfSight(e));
        return near.isEmpty() ? null : Collections.min(near, Comparator.comparingDouble(e -> e.distanceToSqr(p)));
    }

    private String invalid(LivingEntity t) {
        if (!t.isAlive() || t.isRemoved()) { kills++; return "killed " + id(t); }
        if (t.distanceTo(mc.player) > GIVE_UP_DISTANCE) return id(t) + " moved away";
        if (ticks > MAX_TICKS) return "fought " + id(t) + " for 30 s";
        if (outOfReach > MAX_OUT_OF_REACH) return id(t) + " stayed out of reach";
        if (t instanceof Player pl && (pl.isCreative() || pl.isSpectator())) return "player is in creative/spectator";
        return null;
    }

    private void start(LivingEntity t) {
        target = t; ticks = 0; outOfReach = 0; engagements++;
        last = "fighting " + id(t);
        // Stop whatever the job was doing with the keys (walking, eating, mining).
        c.bot().getPathingBehavior().cancelEverything();
        c.bot().getInputOverrideHandler().clearAllKeys();
        mc.options.keyUse.setDown(false);
        if (mc.player.isUsingItem()) mc.gameMode.releaseUsingItem(mc.player);
        mc.gameMode.stopDestroyBlock();
        if (!(mc.player.containerMenu instanceof InventoryMenu)) mc.player.closeContainer();
        savedSlot = mc.player.getInventory().selected;
        equipWeapon();
    }

    private void fight() {
        LocalPlayer p = mc.player;
        ticks++;
        c.bot().getPathingBehavior().cancelEverything();
        c.bot().getInputOverrideHandler().clearAllKeys();
        // Reach is measured like the server does: from the eyes to the target's hit box (flying mobs such as fairies
        // are often above the player, where a feet-to-feet distance says "close" but the swing cannot connect).
        AABB box = target.getBoundingBox();
        Vec3 eye = p.getEyePosition();
        Vec3 aim = box.getCenter();
        c.lookAt(aim);
        double reach = p.entityInteractionRange() - 0.1;
        boolean visible = p.hasLineOfSight(target);
        boolean inReach = visible && box.distanceToSqr(eye) <= reach * reach
                && box.clip(eye, eye.add(aim.subtract(eye).normalize().scale(reach + 0.5))).isPresent();
        if (!inReach) {
            outOfReach++;
            // Close the gap like a player would (no Baritone, no digging), but never step towards a drop.
            if (visible && Math.sqrt(box.distanceToSqr(eye)) <= APPROACH && p.onGround()) stepTowards(aim);
            return;
        }
        outOfReach = 0;
        if (p.getAttackStrengthScale(0.5f) >= 0.9f) {
            mc.gameMode.attack(p, target);
            p.swing(InteractionHand.MAIN_HAND);
            hits++;
        }
    }

    private static final double APPROACH = 10;
    private int hits;

    /** One small step towards the target, only when there is solid ground under the next position (sky islands). */
    private void stepTowards(Vec3 aim) {
        LocalPlayer p = mc.player;
        Vec3 d = new Vec3(aim.x - p.getX(), 0, aim.z - p.getZ());
        if (d.lengthSqr() < 0.25) return;
        Vec3 dir = d.normalize();
        Vec3 next = p.position().add(dir.scale(0.8));
        net.minecraft.core.BlockPos below = net.minecraft.core.BlockPos.containing(next.x, p.getY() - 0.5, next.z);
        boolean ground = false;
        for (int i = 0; i < 3; i++) if (!mc.level.getBlockState(below.below(i)).getCollisionShape(mc.level, below.below(i)).isEmpty()) { ground = true; break; }
        if (!ground) return;
        Vec3 v = p.getDeltaMovement();
        p.setDeltaMovement(dir.x * 0.2, v.y, dir.z * 0.2);
    }

    private void end(String why) {
        last = why;
        target = null;
        if (savedSlot >= 0 && savedSlot < 9 && mc.player != null) mc.player.getInventory().selected = savedSlot;
        savedSlot = -1;
        if (why.startsWith("killed") && attacker != null && !attacker.isAlive()) attacker = null;
    }

    /** The item with the highest main-hand attack damage in the whole inventory (modded weapons included). */
    private void equipWeapon() {
        var inv = mc.player.getInventory();
        int bestSlot = -1; double bestDamage = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) continue;
            double d = attackDamage(s) + (i < 9 ? 0.001 : 0); // equal damage: prefer the hotbar (no swap needed)
            if (d > bestDamage) { bestDamage = d; bestSlot = i; }
        }
        if (bestSlot < 0) return;
        weapon = BuiltInRegistries.ITEM.getKey(inv.getItem(bestSlot).getItem()) + " (+" + Math.round(bestDamage * 10) / 10.0 + " damage)";
        if (bestSlot < 9) { inv.selected = bestSlot; return; }
        try { c.equip(BuiltInRegistries.ITEM.getKey(inv.getItem(bestSlot).getItem()).toString()); } catch (Exception ignored) {}
    }

    /** Sum of the item's additive main-hand attack-damage modifiers (what the tooltip shows minus the base 1). */
    static double attackDamage(ItemStack s) {
        double d = 0;
        var mods = s.getAttributeModifiers();
        for (var e : mods.modifiers()) {
            if (!e.attribute().is(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE)) continue;
            if (!e.slot().test(net.minecraft.world.entity.EquipmentSlot.MAINHAND)) continue;
            if (e.modifier().operation() == net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_VALUE) d += e.modifier().amount();
        }
        return d;
    }
    private String weapon = "";

    private static String id(LivingEntity e) {
        return e instanceof Player pl ? "player " + pl.getGameProfile().getName() : BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
    }

    JsonObject json() {
        JsonObject o = new JsonObject();
        o.addProperty("active", target != null);
        if (target != null) { o.addProperty("target", id(target)); o.addProperty("distance", Math.round(target.distanceTo(mc.player) * 10) / 10.0); }
        o.addProperty("kills", kills); o.addProperty("engagements", engagements); o.addProperty("hits", hits); o.addProperty("last", last);
        if (!weapon.isEmpty()) o.addProperty("weapon", weapon);
        return o;
    }
}
