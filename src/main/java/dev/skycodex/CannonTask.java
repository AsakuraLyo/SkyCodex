package dev.skycodex;

import baritone.api.pathing.goals.GoalNear;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Create schematicannon support through reflection (Create stays an optional dependency).
 * status(): what the client sees of the cannon (state, progress, missing item, fuel).
 * As a job: open the cannon menu and press PLAY / PAUSE / STOP, the same packet the menu buttons send.
 * Loading the schematic (Schematic Table upload, deploying the schematic in the world, putting it into the cannon)
 * stays with the player; SkyCodex supplies materials (mc_deposit into the cannon's chests) and gunpowder.
 */
final class CannonTask implements JobTask {
    private static final String BE = "com.simibubi.create.content.schematics.cannon.SchematicannonBlockEntity";
    private static final String PACKET = "com.simibubi.create.content.schematics.cannon.ConfigureSchematicannonPacket";
    private final Controller c;
    private final Minecraft mc;
    private final BlockPos pos;
    private final String option;
    private int phase, ticks;
    private String before;

    CannonTask(Controller c, BlockPos pos, String action) {
        this.c = c; this.mc = c.mc; this.pos = pos;
        this.option = switch (action) { case "start" -> "PLAY"; case "pause" -> "PAUSE"; case "stop" -> "STOP"; default -> throw new IllegalArgumentException("action must be status, start, pause or stop"); };
        cannon(mc, pos); // validates
        before = String.valueOf(get(cannon(mc, pos), "state"));
    }

    static BlockEntity cannon(Minecraft mc, BlockPos pos) {
        BlockEntity be = mc.level.getBlockEntity(pos);
        if (be == null || !be.getClass().getName().equals(BE)) throw new IllegalArgumentException("No Create schematicannon at " + pos.toShortString());
        return be;
    }
    private static Object get(Object o, String field) {
        try { return o.getClass().getField(field).get(o); } catch (Exception e) { return null; }
    }

    static JsonObject status(Minecraft mc, BlockPos pos) {
        BlockEntity be = cannon(mc, pos);
        JsonObject o = Controller.coords(pos);
        o.addProperty("state", String.valueOf(get(be, "state")));
        Object msg = get(be, "statusMsg"); if (msg != null) o.addProperty("status", String.valueOf(msg));
        Object placed = get(be, "blocksPlaced"), total = get(be, "blocksToPlace");
        if (placed instanceof Integer p) o.addProperty("blocks_placed", p);
        if (total instanceof Integer t) o.addProperty("blocks_to_place", t);
        if (get(be, "schematicProgress") instanceof Float f) o.addProperty("schematic_progress", f);
        if (get(be, "missingItem") instanceof ItemStack s && !s.isEmpty()) o.addProperty("missing_item", BuiltInRegistries.ITEM.getKey(s.getItem()).toString());
        if (get(be, "remainingFuel") instanceof Integer f) o.addProperty("remaining_fuel_shots", f);
        if (get(be, "skipMissing") instanceof Boolean b) o.addProperty("skip_missing", b);
        o.addProperty("note", "Materials come from containers attached to the cannon; gunpowder goes into the cannon itself (mc_deposit). The schematic must be loaded into the cannon by the player.");
        return o;
    }

    private boolean inReach() { return mc.player.getEyePosition().distanceTo(Vec3.atCenterOf(pos)) <= mc.player.blockInteractionRange() - 0.6; }

    @Override public boolean tick() {
        ticks++;
        switch (phase) {
            case 0 -> {
                if (inReach()) { c.bot().getPathingBehavior().cancelEverything(); phase = 1; ticks = 0; }
                else if (ticks == 1 || ticks % 100 == 0) c.bot().getCustomGoalProcess().setGoalAndPath(new GoalNear(pos, 2));
                else if (ticks > 400) throw new IllegalStateException("Could not walk within reach of the schematicannon");
            }
            case 1 -> { c.useBlock(pos); phase = 2; ticks = 0; }
            case 2 -> {
                if (mc.player.containerMenu.getClass().getSimpleName().equals("SchematicannonMenu")) { send(); phase = 3; ticks = 0; }
                else if (ticks > 30) throw new IllegalStateException("Schematicannon menu did not open");
            }
            case 3 -> { if (ticks > 10) return true; }
        }
        return false;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void send() {
        try {
            Class<?> p = Class.forName(PACKET);
            Class<? extends Enum> opt = (Class<? extends Enum>) Class.forName(PACKET + "$Option");
            Object payload = p.getConstructor(opt, boolean.class).newInstance(Enum.valueOf(opt, option), true);
            // Create sends through Catnip, which owns the payload registration.
            Object network = Class.forName("net.createmod.catnip.platform.CatnipServices").getField("NETWORK").get(null);
            Class.forName("net.createmod.catnip.platform.services.NetworkHelper").getMethod("sendToServer", CustomPacketPayload.class).invoke(network, payload);
        } catch (Exception e) { throw new IllegalStateException("Could not control the schematicannon: " + e); }
    }

    @Override public void cleanup() { if (mc.player != null && mc.player.containerMenu.getClass().getSimpleName().equals("SchematicannonMenu")) mc.player.closeContainer(); }

    @Override public String message() {
        String now = String.valueOf(get(cannon(mc, pos), "state"));
        return "Pressed " + option + "; cannon state " + before + " -> " + now + (now.equals(before) && !option.equals("PAUSE") ? " (it may need a loaded, deployed schematic, gunpowder and materials; see mc_cannon status)" : "");
    }
}
