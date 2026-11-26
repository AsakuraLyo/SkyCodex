package dev.skycodex;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Fly to a far target with an elytra, steering with the camera like a player: put the elytra on, jump, open it while
 * falling, look towards the target, boost with firework rockets when slow or too low, avoid terrain ahead and dive
 * to land within `radius` blocks of the target. If it touches the ground early it takes off again (rockets needed).
 */
final class FlyTask implements JobTask {
    private final Controller c;
    private final Minecraft mc;
    private final BlockPos target;
    private final int radius, cruise;
    private final boolean land;
    private int phase, ticks, rocketCooldown, rocketsUsed, takeoffs, groundTicks, thrustTicks, boosts;
    private String result = "";
    private ItemStack previousChest = ItemStack.EMPTY;

    private final net.minecraft.client.KeyMapping thruster;

    FlyTask(Controller c, BlockPos target, int radius, int cruise, boolean land) {
        this.c = c; this.mc = c.mc; this.target = target; this.radius = radius; this.cruise = cruise; this.land = land;
        // Powered elytras of this pack (Rainbow Compound "动力鞘翅") accelerate while their thruster key is held:
        // no fireworks needed. Found by name so a re-bound key still works.
        net.minecraft.client.KeyMapping found = null;
        for (var k : mc.options.keyMappings) if (k.getName().contains("dynamicelytra_fly")) { found = k; break; }
        thruster = found;
    }

    /** Elytra-like gear: vanilla elytra, Rainbow Compound powered elytras, Cataclysm elytra chestplate, ... */
    private boolean glides(ItemStack s) { return !s.isEmpty() && (s.is(Items.ELYTRA) || s.canElytraFly(mc.player)); }

    /** Swap elytra-like gear into the chest armour slot (inventory menu slot 6) with ordinary clicks. Gear already
     *  worn in a Curios elytra slot also works: then nothing is swapped and take-off simply tries. */
    private void wearElytra() {
        if (glides(mc.player.getItemBySlot(EquipmentSlot.CHEST))) return;
        var inv = mc.player.getInventory();
        int from = -1;
        for (int i = 0; i < 36; i++) if (glides(inv.getItem(i))) { from = i; break; }
        if (from < 0) return;
        int menuSlot = from < 9 ? 36 + from : from; // InventoryMenu: 9..35 main, 36..44 hotbar
        int id = mc.player.inventoryMenu.containerId;
        previousChest = mc.player.getItemBySlot(EquipmentSlot.CHEST).copy();
        mc.gameMode.handleInventoryMouseClick(id, menuSlot, 0, ClickType.PICKUP, mc.player);
        mc.gameMode.handleInventoryMouseClick(id, 6, 0, ClickType.PICKUP, mc.player);
        mc.gameMode.handleInventoryMouseClick(id, menuSlot, 0, ClickType.PICKUP, mc.player); // old chestplate back
    }

    private int rockets() {
        int n = 0; var inv = mc.player.getInventory();
        for (int i = 0; i < 36; i++) if (inv.getItem(i).is(Items.FIREWORK_ROCKET)) n += inv.getItem(i).getCount();
        return n;
    }

    /** Speed up: hold the powered-elytra thruster for a moment if the pack has one, otherwise fire a rocket. */
    private boolean boost() {
        if (thruster != null) { thruster.setDown(true); thrustTicks = 10; boosts++; return true; }
        return useRocket();
    }

    private boolean useRocket() {
        if (rocketCooldown > 0 || rockets() == 0) return false;
        c.equip("minecraft:firework_rocket");
        if (!mc.player.getMainHandItem().is(Items.FIREWORK_ROCKET)) return false;
        mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
        rocketCooldown = 30; rocketsUsed++;
        return true;
    }

    private double horizontalDist() {
        Vec3 p = mc.player.position();
        double dx = target.getX() + 0.5 - p.x, dz = target.getZ() + 0.5 - p.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private float yawToTarget() {
        Vec3 p = mc.player.position();
        return (float) (Mth.atan2(target.getZ() + 0.5 - p.z, target.getX() + 0.5 - p.x) * 180 / Math.PI) - 90;
    }

    /** Ground height below a column if loaded, else the target height. */
    private int groundAt(double x, double z) {
        BlockPos col = BlockPos.containing(x, 0, z);
        if (!mc.level.hasChunkAt(col)) return target.getY();
        return mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, col.getX(), col.getZ());
    }

    /** Something solid along the current flight line within the next ~2.5 s? */
    private boolean obstacleAhead() {
        Vec3 eye = mc.player.getEyePosition(), v = mc.player.getDeltaMovement();
        Vec3 dir = v.lengthSqr() < 0.01 ? mc.player.getLookAngle() : v.normalize();
        double look = Math.max(12, v.length() * 50);
        var hit = mc.level.clip(new ClipContext(eye, eye.add(dir.scale(look)), ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, mc.player));
        return hit.getType() == HitResult.Type.BLOCK;
    }

    @Override public boolean tick() {
        ticks++;
        if (rocketCooldown > 0) rocketCooldown--;
        if (thrustTicks > 0 && --thrustTicks == 0 && thruster != null) thruster.setDown(false);
        var p = mc.player;
        double dist = horizontalDist();
        switch (phase) {
            case 0 -> { // gear up
                c.bot().getPathingBehavior().cancelEverything();
                wearElytra();
                phase = 1; ticks = 0;
            }
            case 1 -> { // take off: jump, then open the elytra while falling
                if (ticks < 5) return false; // let the armour swap reach the server
                if (dist <= radius && p.onGround()) { result = "Already at the target"; return true; }
                p.setYRot(yawToTarget()); p.setXRot(-10);
                if (p.isFallFlying()) { phase = 2; ticks = 0; return false; } // already gliding (next leg)
                if (p.onGround()) { // need room to jump and spread the wings (a canopy higher up is fine)
                    BlockPos head = p.blockPosition().above(2);
                    for (int i = 0; i < 3; i++) if (!mc.level.getBlockState(head.above(i)).getCollisionShape(mc.level, head.above(i)).isEmpty())
                        throw new IllegalStateException("No open sky above " + p.blockPosition().toShortString() + " (block " + (i + 2) + " above the feet): walk to a clearing first");
                }
                if (p.isInWater()) { // never sit under water: swim up, take-off is impossible there
                    mc.options.keyJump.setDown(true);
                    if (ticks > 200) throw new IllegalStateException("In water at " + p.blockPosition().toShortString() + ": cannot take off");
                    return false;
                }
                if (p.onGround()) { mc.options.keyJump.setDown(false); if (ticks % 10 == 1) p.jumpFromGround(); }
                else if (p.getDeltaMovement().y < 0.1 && !p.isFallFlying()) {
                    // Open the wings exactly like the player does: press jump again in mid-air (a fresh key press).
                    // The game's own jump handling then checks the chest slot AND the Curios back slot (Rainbow
                    // Compound "彩虹之翼") and tells the server.
                    mc.options.keyJump.setDown(ticks % 4 < 2);
                }
                if (p.isFallFlying()) { mc.options.keyJump.setDown(false); phase = 2; ticks = 0; takeoffs++; p.setXRot(-35); boost(); }
                if (ticks > 200) throw new IllegalStateException("Could not open an elytra: wear one (chest or Curios elytra slot) or carry one, and start from open space");
            }
            case 2 -> { // cruise towards the target with the camera
                if (!p.isFallFlying()) {
                    if (p.onGround() || p.isInWater()) {
                        if (dist <= radius + 8) { phase = 4; ticks = 0; return false; }
                        if (++groundTicks > 10) {
                            if (thruster == null && rockets() == 0 && p.getY() < target.getY() + 3)
                                throw new IllegalStateException("Landed " + (int) dist + " blocks short at " + p.blockPosition().toShortString() + " with no thruster and no rockets to take off again");
                            if (takeoffs > 40) throw new IllegalStateException("Too many forced landings; stopped at " + p.blockPosition().toShortString());
                            phase = 1; ticks = 0; groundTicks = 0;
                        }
                    }
                    return false;
                }
                groundTicks = 0;
                float yaw = yawToTarget();
                double speed = p.getDeltaMovement().horizontalDistance();
                int ground = groundAt(p.getX(), p.getZ());
                // Sky-island worlds: a column without blocks is VOID, not ground. Never descend into it.
                int voidLevel = mc.level.getMinBuildHeight() + 1;
                boolean overVoid = ground <= voidLevel;
                double above = overVoid ? 999 : p.getY() - ground;
                // Highest real ground on the next stretch towards the target, so we climb before an island.
                Vec3 fwd = new Vec3(target.getX() + 0.5 - p.getX(), 0, target.getZ() + 0.5 - p.getZ()).normalize();
                int ahead = overVoid ? Integer.MIN_VALUE : ground;
                for (int s = 16; s <= 96; s += 16) {
                    int g = groundAt(p.getX() + fwd.x * s, p.getZ() + fwd.z * s);
                    if (g > voidLevel) ahead = Math.max(ahead, g);
                }
                if (!land && dist <= radius) {
                    result = "Passed waypoint " + target.toShortString() + " in flight at " + p.blockPosition().toShortString() + " (" + boosts + " boosts)";
                    return true;
                }
                int targetGround = groundAt(target.getX() + 0.5, target.getZ() + 0.5);
                boolean targetLoaded = mc.level.hasChunkAt(target);
                if (land && targetLoaded && targetGround <= voidLevel && dist <= radius + 20)
                    throw new IllegalStateException("No ground at the target column (void); still flying at " + p.blockPosition().toShortString() + ", pick a target on land");
                float pitch;
                double h = p.getY() - targetGround, vy = p.getDeltaMovement().y;
                if (land && targetLoaded && targetGround > voidLevel && dist < Math.max(48, h * 0.4)) {
                    // Approach without a power-dive: vanilla elytra resets the fall distance only while the sink
                    // rate is slower than 0.5 blocks/tick, so a steep dive builds up a deadly fall even on landing.
                    // Spiral down around the target keeping the sink rate under ~0.4, then flare just above ground.
                    if (thruster != null) { thruster.setDown(false); thrustTicks = 0; }
                    if (h > 24) {
                        // close in, then orbit: aim at the target while far, sideways (90 deg) when close
                        yaw = dist > 24 ? yaw : yaw + (float) Mth.clamp(90 - dist * 2, 30, 90);
                        pitch = vy < -0.42 ? -8 : 22;
                    } else {
                        pitch = vy < -0.3 ? -14 : (dist > 4 ? 4 : 10);
                    }
                    if (ground > voidLevel && p.getY() - ground < 3 && speed > 0.6) pitch = -20; // flare before touching down
                } else {
                    int want = Math.max(ahead, target.getY()) + cruise;
                    pitch = p.getY() < want - 6 ? -20 : p.getY() > want + 20 ? 12 : 3;
                    // Far below the cruise height (just took off inside a structure or valley): climb steeply with the
                    // thruster first instead of a long shallow climb that runs into walls.
                    if (thruster != null && p.getY() < want - 20) { pitch = -55; boost(); }
                    if (above < 12 || obstacleAhead()) { pitch = -30; boost(); }
                    // Powered wings: hold the thruster (space) the whole cruise, like holding the key in game.
                    else if (thruster != null) { if (thrustTicks == 0) boost(); else thrustTicks = 10; }
                    else if (speed < 0.9 && p.getY() < want + 10 && thrustTicks == 0) boost();
                }
                // Under a canopy/roof: glide level towards the target without climbing into the ceiling; turn away
                // from anything solid ahead. Normal cruise resumes as soon as the sky is open above.
                if (!mc.level.canSeeSky(p.blockPosition().above())) {
                    if (thruster != null) { thruster.setDown(false); thrustTicks = 0; }
                    pitch = vy < -0.4 ? -6 : 2;
                    if (obstacleAhead()) yaw += 60;
                }
                p.setYRot(yaw); p.setXRot(pitch);
                if (ticks > 20 * 60 * 9) throw new IllegalStateException("Flight took too long; stopped at " + p.blockPosition().toShortString());
            }
            case 4 -> { // landed near the target
                result = "Landed " + (int) dist + " blocks from the target at " + p.blockPosition().toShortString()
                        + " (" + takeoffs + " take-offs, " + (thruster != null ? boosts + " thruster boosts" : rocketsUsed + " rockets") + ")";
                return true;
            }
        }
        return false;
    }

    @Override public void interrupted() { if (phase == 1) ticks = 0; }

    @Override public void cleanup() { mc.options.keyJump.setDown(false); if (thruster != null) thruster.setDown(false); }

    @Override public String message() { return result; }
}
