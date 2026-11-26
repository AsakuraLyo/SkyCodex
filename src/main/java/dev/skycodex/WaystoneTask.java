package dev.skycodex;

import baritone.api.pathing.goals.GoalNear;
import com.google.gson.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.phys.Vec3;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;

/**
 * Waystones (Blay09, 21.1.x) support through reflection: walk to the nearest waystone, open it like a player and either
 * read every known destination (name, position, dimension) or teleport to one by name with the same packet the menu
 * button sends ({@code Balm.getNetworking().sendToServer(new SelectWaystoneMessage(uid))}). Teleport costs (XP,
 * cooldown) are the server's rules; a refused teleport is reported, never retried blindly.
 */
final class WaystoneTask implements JobTask {
    static final String MENU = "net.blay09.mods.waystones.menu.WaystoneSelectionMenu";
    private final Controller c;
    private final Minecraft mc;
    private final String action, wanted;
    private BlockPos stone;
    private Vec3 before;
    private String beforeDim, chosen;
    private int phase, ticks, xpBefore;
    private final JsonObject detail;

    private final BlockPos wantedPos;

    WaystoneTask(Controller c, String action, String name, BlockPos wantedPos, JsonObject detail) {
        this.c = c; this.mc = c.mc; this.action = action; this.wanted = name == null ? null : name.trim(); this.wantedPos = wantedPos; this.detail = detail;
        if (!action.equals("list") && !action.equals("teleport")) throw new IllegalArgumentException("action must be list or teleport");
        if (action.equals("teleport") && (wanted == null || wanted.isEmpty()) && wantedPos == null) throw new IllegalArgumentException("teleport needs name or the destination's x,y,z (see action list)");
        if (isMenu(mc)) phase = 3; // the player already opened a waystone / warp stone menu
        else {
            BlockPos me = mc.player.blockPosition(); double best = Double.MAX_VALUE;
            for (BlockPos p : BlockPos.betweenClosed(me.offset(-24, -8, -24), me.offset(24, 8, 24))) {
                String id = BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(p).getBlock()).toString();
                if (id.startsWith("waystones:") && id.matches(".*(waystone|sharestone|portstone).*") && p.distSqr(me) < best) { best = p.distSqr(me); stone = p.immutable(); }
            }
            if (stone == null) throw new IllegalStateException("No waystone within 24 blocks; walk near one first (mc_memory recall category waystone)");
            detail.add("waystone", Controller.coords(stone));
        }
    }

    static boolean isMenu(Minecraft mc) { return mc.player != null && mc.player.containerMenu.getClass().getName().equals(MENU); }
    private boolean inReach() { return mc.player.getEyePosition().distanceTo(Vec3.atCenterOf(stone)) <= mc.player.blockInteractionRange() - 0.6; }

    /**
     * Calls a public no-argument method. Implementations of the Waystones API are often non-public classes, whose
     * methods cannot be invoked reflectively, so the method is looked up on public classes/interfaces only.
     */
    private static Object call(Object o, String name) {
        if (o == null) return null;
        Deque<Class<?>> todo = new ArrayDeque<>(List.of(o.getClass())); Set<Class<?>> seen = new HashSet<>();
        while (!todo.isEmpty()) {
            Class<?> k = todo.poll();
            if (!seen.add(k)) continue;
            if (Modifier.isPublic(k.getModifiers())) {
                try { Method m = k.getMethod(name); if (m.getParameterCount() == 0) return m.invoke(o); }
                catch (NoSuchMethodException | IllegalAccessException ignored) {}
                catch (Exception e) { return null; }
            }
            if (k.getSuperclass() != null) todo.add(k.getSuperclass());
            todo.addAll(Arrays.asList(k.getInterfaces()));
        }
        return null;
    }
    private static String text(Object comp) { return comp instanceof Component cc ? cc.getString() : comp == null ? "" : comp.toString(); }
    private static String dim(Object key) { return key instanceof ResourceKey<?> k ? k.location().toString() : key == null ? "" : key.toString(); }

    private record Dest(Object waystone, UUID uid, String name, boolean current) {}

    /** Destinations in the open menu, including the waystone the player stands at (current = true). */
    private List<Dest> destinations() {
        List<Dest> out = new ArrayList<>();
        Object list = call(mc.player.containerMenu, "getWaystones");
        if (!(list instanceof Collection<?> col)) throw new IllegalStateException("Waystones API changed: menu has no waystone list");
        Object from = call(mc.player.containerMenu, "getWaystoneFrom");
        UUID fromUid = call(from, "getWaystoneUid") instanceof UUID u ? u : null;
        for (Object w : col) {
            String name = text(call(w, "getEffectiveName"));
            if (name.isBlank()) name = text(call(w, "getName"));
            UUID uid = call(w, "getWaystoneUid") instanceof UUID u ? u : null;
            out.add(new Dest(w, uid, name, uid != null && uid.equals(fromUid)));
        }
        return out;
    }

    @Override public boolean tick() {
        ticks++;
        switch (phase) {
            case 0 -> {
                if (inReach()) { c.bot().getPathingBehavior().cancelEverything(); phase = 1; ticks = 0; }
                else if (ticks > 400) throw new IllegalStateException("Could not walk to the waystone at " + stone.toShortString());
                else if (ticks == 1 || ticks % 100 == 0) c.bot().getCustomGoalProcess().setGoalAndPath(new GoalNear(stone, 2));
            }
            case 1 -> { c.useBlock(stone); phase = 2; ticks = 0; }
            case 2 -> {
                if (isMenu(mc)) { phase = 3; ticks = 0; }
                else if (ticks > 40) throw new IllegalStateException("Waystone menu did not open (not activated yet? right-click it once in game)");
            }
            case 3 -> {
                if (ticks < 3) return false; // let the menu data settle
                List<Dest> dests = destinations();
                String here = mc.level.dimension().location().toString();
                JsonArray arr = new JsonArray();
                for (Dest d : dests) {
                    JsonObject j = new JsonObject(); j.addProperty("name", d.name);
                    if (d.current) j.addProperty("current", true);
                    String dm = dim(call(d.waystone, "getDimension"));
                    if (call(d.waystone, "getPos") instanceof BlockPos p) {
                        j.add("pos", Controller.coords(p));
                        if (dm.equals(here)) j.addProperty("distance", (int) Math.round(Math.sqrt(p.distSqr(mc.player.blockPosition()))));
                    }
                    if (!dm.isEmpty()) j.addProperty("dimension", dm);
                    Object type = call(d.waystone, "getWaystoneType"); if (type != null) j.addProperty("type", type.toString());
                    arr.add(j);
                }
                detail.add("waystones", arr);
                if (action.equals("list")) return true;
                Dest pick = null;
                if (wantedPos != null) {
                    // A waystone is two blocks tall: accept the lower or the upper block position.
                    for (Dest d : dests) if (call(d.waystone, "getPos") instanceof BlockPos p && (p.equals(wantedPos) || p.above().equals(wantedPos))) pick = d;
                    if (pick == null) throw new IllegalStateException("No known waystone at " + wantedPos.toShortString() + "; see job.detail.waystones");
                }
                else for (Dest d : dests) if (d.name.equals(wanted)) pick = d;
                if (pick == null) {
                    List<Dest> partial = dests.stream().filter(d -> d.name.contains(wanted)).toList();
                    if (partial.size() > 1) throw new IllegalStateException("Name '" + wanted + "' matches " + partial.stream().map(Dest::name).toList() + "; use the full name");
                    if (partial.size() == 1) pick = partial.get(0);
                }
                if (pick == null) throw new IllegalStateException("No known waystone named '" + wanted + "'; see job.detail.waystones");
                if (pick.current) throw new IllegalStateException("'" + pick.name + "' is the waystone you are standing at");
                chosen = pick.name.isEmpty() && call(pick.waystone, "getPos") instanceof BlockPos bp ? bp.toShortString() : pick.name;
                if (pick.uid == null) throw new IllegalStateException("Waystones API changed: no waystone UUID");
                before = mc.player.position(); beforeDim = here; xpBefore = mc.player.experienceLevel;
                send(pick.uid);
                phase = 4; ticks = 0;
            }
            case 4 -> {
                boolean moved = !mc.level.dimension().location().toString().equals(beforeDim) || mc.player.position().distanceTo(before) > 8;
                if (moved) { detail.addProperty("xp_levels_spent", xpBefore - mc.player.experienceLevel); return true; }
                if (ticks > 100) throw new IllegalStateException("Teleport to '" + chosen + "' did not happen (not enough XP, cooldown, or blocked destination?); check chat with mc_screenshot");
            }
        }
        return false;
    }

    private void send(UUID uid) {
        try {
            Object payload = Class.forName("net.blay09.mods.waystones.network.message.SelectWaystoneMessage").getConstructor(UUID.class).newInstance(uid);
            // Waystones registers its payloads through Balm, so it must also be sent through Balm (like the menu button).
            Object net = Class.forName("net.blay09.mods.balm.api.Balm").getMethod("getNetworking").invoke(null);
            Class.forName("net.blay09.mods.balm.api.network.BalmNetworking").getMethod("sendToServer", CustomPacketPayload.class).invoke(net, payload);
        } catch (Exception e) { throw new IllegalStateException("Could not send the waystone teleport: " + e); }
    }

    @Override public void interrupted() { if (phase == 0) ticks = 0; }

    @Override public void cleanup() { if (isMenu(mc)) mc.player.closeContainer(); }

    @Override public String message() {
        int n = detail.has("waystones") ? detail.getAsJsonArray("waystones").size() : 0;
        if (action.equals("list")) return "Read " + n + " known waystones (job.detail.waystones; current = the one you stand at)";
        return "Teleported to '" + chosen + "', now at " + mc.player.blockPosition().toShortString() + " in " + mc.level.dimension().location();
    }
}
