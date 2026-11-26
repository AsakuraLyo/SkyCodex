package dev.skycodex;

import baritone.api.*;
import baritone.api.pathing.goals.*;
import com.google.gson.*;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.serialization.JsonOps;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.*;
import net.minecraft.util.Mth;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.*;
import java.util.*;

final class Controller {
    static final String VERSION = "0.1.0-beta.2";
    final Minecraft mc = Minecraft.getInstance();
    /** API requests from LocalServer, executed on the client thread at the start of each client tick. */
    final java.util.concurrent.ConcurrentLinkedQueue<Runnable> pending = new java.util.concurrent.ConcurrentLinkedQueue<>();
    void runPending() { for (Runnable r; (r = pending.poll()) != null; ) r.run(); }
    private boolean enabled;
    private long heartbeat = System.nanoTime();
    private String dimension = "";
    private Job job;
    /** A batch of job steps run back to back on the client, each verified before the next starts. */
    private static final class Seq {
        final String id = UUID.randomUUID().toString();
        final List<JsonObject> steps; final boolean stopOnFailure; final long started = System.nanoTime();
        int index = -1; String state = "running", message = ""; final JsonArray log = new JsonArray();
        Seq(List<JsonObject> steps, boolean stopOnFailure) { this.steps = steps; this.stopOnFailure = stopOnFailure; }
    }
    private Seq seq;
    private boolean startingSequenceStep;
    private final QuestBridge quests = new QuestBridge();
    private final MachineScanner machines = new MachineScanner();
    /** Fights back while control is enabled; pauses and then resumes the running job. */
    private final Defender defender = new Defender(this);
    private final Map<Settings.Setting<?>, Object> previousSettings = new LinkedHashMap<>();
    private String lastStop = "Press F8 in game to enable control";
    /** Blocks Baritone may use as temporary scaffolding, minus anything the current job needs to keep. */
    private static final List<Item> SCAFFOLD = List.of(Items.DIRT, Items.COBBLESTONE, Items.NETHERRACK, Items.COBBLED_DEEPSLATE, Items.STONE);
    /**
     * Builds scaffold only with these, and may break only these (Baritone allowBreakAnyway), so it can take its own
     * scaffolding back out of blueprint cells without being able to break anything else.
     */
    private static final List<Block> BUILD_SCAFFOLD = List.of(Blocks.DIRT, Blocks.NETHERRACK);
    /** Blueprint cells that our own build jobs filled with scaffolding this session; not conflicts on resume. */
    private final Set<BlockPos> ownScaffold = new HashSet<>();
    private static List<Block> partialObstacles;
    /**
     * Every block the modpack adds (namespace other than minecraft) that has a collision box in ANY of its states:
     * Baritone may never path THROUGH them (blocksToAvoid only affects walking through a cell, not standing on top,
     * so floors made of modded blocks stay walkable). Vanilla's "pathfindable" check treats non-full blocks as air,
     * which made Baritone route straight into Create shafts, cogwheels, pipes, depots and other mods' blocks.
     * Kept out: blocks a player stands in or walks through on purpose (carpets, snow layers, ladders, scaffolding,
     * doors, fence gates, trapdoors).
     */
    static List<Block> partialObstacles() {
        if (partialObstacles != null) return partialObstacles;
        List<Block> out = new ArrayList<>();
        for (Block b : BuiltInRegistries.BLOCK) {
            try {
                if (BuiltInRegistries.BLOCK.getKey(b).getNamespace().equals("minecraft")) continue;
                if (b instanceof CarpetBlock || b instanceof SnowLayerBlock || b instanceof LadderBlock || b instanceof ScaffoldingBlock
                        || b instanceof DoorBlock || b instanceof FenceGateBlock || b instanceof TrapDoorBlock) continue;
                boolean collides = false; int checked = 0;
                for (BlockState st : b.getStateDefinition().getPossibleStates()) {
                    if (++checked > 256) break; // huge state spaces (connection flags): the first ones are enough
                    try { if (!st.getCollisionShape(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty()) { collides = true; break; } }
                    catch (Throwable ignored) {} // some modded shapes need a real level
                }
                if (collides) out.add(b);
            } catch (Throwable ignored) {}
        }
        return partialObstacles = out;
    }
    private static boolean isBuildScaffold(BlockState s) { for (Block b : BUILD_SCAFFOLD) if (s.is(b)) return true; return false; }
    private static final List<String> JOB_KINDS = List.of("move", "collect", "craft", "build", "use_block", "eat", "place", "break", "harvest", "hunt", "smelt", "deposit", "withdraw", "cannon", "waystone", "sleep", "fly");
    /** Long jobs eat on their own (pause, eat, resume) once food drops to this level, well before the stop at 6. */
    private static final int AUTO_EAT_FOOD = 14;
    private static final Set<String> AUTO_EAT_KINDS = Set.of("move", "collect", "build", "harvest", "hunt");
    /** Jobs that open a GUI themselves. */
    private static final Set<String> GUI_KINDS = Set.of("craft", "use_block", "smelt", "deposit", "withdraw", "cannon", "waystone", "sleep");

    static String str(JsonObject a, String k) {
        if (!a.has(k) || !a.get(k).isJsonPrimitive()) throw new IllegalArgumentException("Missing " + k);
        return a.get(k).getAsString();
    }
    static int integer(JsonObject a, String k, int fallback, int min, int max) {
        if (!a.has(k)) return fallback;
        double d = a.get(k).getAsDouble();
        if (!Double.isFinite(d) || d != Math.rint(d) || d < min || d > max) throw new IllegalArgumentException(k + " outside " + min + ".." + max);
        return (int)d;
    }
    static BlockPos pos(JsonObject a) {
        if (a == null || !a.has("x") || !a.has("y") || !a.has("z")) throw new IllegalArgumentException("x, y, z required");
        return new BlockPos(integer(a,"x",0,-29999984,29999984),integer(a,"y",0,-2048,2048),integer(a,"z",0,-29999984,29999984));
    }
    static JsonObject coords(BlockPos p) { JsonObject o = new JsonObject();o.addProperty("x",p.getX());o.addProperty("y",p.getY());o.addProperty("z",p.getZ());return o; }
    private static List<String> idList(String raw) {
        List<String> out = new ArrayList<>();
        for (String s : raw.split(",")) if (!s.isBlank()) out.add(s.trim());
        if (out.isEmpty() || out.size() > 16) throw new IllegalArgumentException("Give 1..16 comma separated IDs");
        return out;
    }
    IBaritone bot() { return BaritoneAPI.getProvider().getPrimaryBaritone(); }
    void lookAt(Vec3 target) {
        Vec3 d = target.subtract(mc.player.getEyePosition());
        mc.player.setYRot((float)(Mth.atan2(d.z, d.x) * 180 / Math.PI) - 90);
        mc.player.setXRot((float)(-Mth.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)) * 180 / Math.PI));
    }
    Map<Item,Integer> snapshot() {
        Map<Item,Integer> m = new HashMap<>();
        for (ItemStack s : mc.player.getInventory().items) if (!s.isEmpty()) m.merge(s.getItem(), s.getCount(), Integer::sum);
        return m;
    }
    /** "+3 minecraft:wheat, +1 minecraft:wheat_seeds" relative to a snapshot. */
    String gained(Map<Item,Integer> before) {
        Map<Item,Integer> now = snapshot(); List<String> out = new ArrayList<>();
        for (var e : now.entrySet()) { int d = e.getValue() - before.getOrDefault(e.getKey(), 0); if (d > 0) out.add("+" + d + " " + BuiltInRegistries.ITEM.getKey(e.getKey())); }
        return out.isEmpty() ? "unchanged" : String.join(", ", out);
    }
    /** Select a hotbar slot whose item does nothing on right-click (empty hand preferred). */
    void selectHarmlessHotbarSlot() {
        var inv = mc.player.getInventory();
        for (int i = 0; i < 9; i++) if (inv.getItem(i).isEmpty()) { inv.selected = i; return; }
        for (int i = 0; i < 9; i++) { Item it = inv.getItem(i).getItem(); if (!(it instanceof BlockItem) && !(it instanceof BoneMealItem) && inv.getItem(i).get(DataComponents.FOOD) == null && !(it instanceof BucketItem)) { inv.selected = i; return; } }
    }
    /** Most nourishing safe food in the inventory, or null. */
    String bestFood() {
        var inv = mc.player.getInventory(); String best = null; int bestScore = -1;
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty() || !safeFood(s)) continue;
            FoodProperties f = s.get(DataComponents.FOOD);
            int score = f.nutrition() * 10 + Math.round(f.saturation()) + (i < 9 ? 1 : 0);
            if (score > bestScore) { bestScore = score; best = BuiltInRegistries.ITEM.getKey(s.getItem()).toString(); }
        }
        return best;
    }
    private Settings cfg() { return BaritoneAPI.getSettings(); }
    private boolean running() { return job != null && job.state.equals("running"); }
    private void requireWorld() { if (mc.player == null || mc.level == null || mc.gameMode == null) throw new IllegalStateException("Join the server/world first"); }
    private void requireControl() {
        requireWorld();
        if (!enabled) throw new IllegalStateException("Control disabled. Press F8 in game.");
        if (!mc.player.isAlive()) throw new IllegalStateException("Player is dead");
        if (running()) throw new IllegalStateException("Another job is running; inspect status or stop first");
        if (!startingSequenceStep && seq != null && seq.state.equals("running")) throw new IllegalStateException("A sequence is running; wait for it or mc_stop");
    }

    // ---- sequences ----------------------------------------------------------------------------------------------
    private void startSequence(JsonObject a) {
        requireControl();
        JsonArray raw = a.getAsJsonArray("steps");
        if (raw == null || raw.isEmpty() || raw.size() > 20) throw new IllegalArgumentException("A sequence needs 1..20 steps");
        List<JsonObject> steps = new ArrayList<>();
        for (JsonElement e : raw) {
            JsonObject s = e.getAsJsonObject();
            String op = str(s, "op");
            if (!JOB_KINDS.contains(op)) throw new IllegalArgumentException("Step operation not allowed in a sequence: " + op);
            if (s.has("expect")) { JsonObject ex = s.getAsJsonObject("expect"); item(str(ex, "item")); integer(ex, "at_least", 1, 0, 100000); }
            steps.add(s);
        }
        seq = new Seq(steps, !a.has("stop_on_failure") || a.get("stop_on_failure").getAsBoolean());
        nextStep();
    }
    private void nextStep() {
        seq.index++;
        if (seq.index >= seq.steps.size()) { seq.state = "succeeded"; seq.message = "All " + seq.steps.size() + " steps done and verified"; return; }
        JsonObject s = seq.steps.get(seq.index);
        String op = str(s, "op");
        JsonObject args = s.has("args") ? s.getAsJsonObject("args") : new JsonObject();
        startingSequenceStep = true;
        try {
            begin(op, args);
            try { startJob(args); } catch (Exception e) { finish("failed", e.getMessage() == null ? e.toString() : e.getMessage()); }
        } catch (Exception e) {
            logStep(op, "refused", e.getMessage() == null ? e.toString() : e.getMessage());
            stepFailed(op, "refused: " + e.getMessage());
        } finally { startingSequenceStep = false; }
    }
    private void logStep(String op, String state, String message) {
        JsonObject l = new JsonObject(); l.addProperty("step", seq.index + 1); l.addProperty("op", op);
        l.addProperty("state", state); l.addProperty("message", message); seq.log.add(l);
    }
    private void stepFailed(String op, String why) {
        String m = "Step " + (seq.index + 1) + "/" + seq.steps.size() + " (" + op + ") " + why;
        if (seq.stopOnFailure) { seq.state = "failed"; seq.message = m + "; later steps were not run"; }
        else nextStep();
    }
    /** Called on the first tick after a step's job ended: verify, then start the next step or stop. */
    private void afterStep() {
        JsonObject s = seq.steps.get(seq.index);
        String op = str(s, "op");
        if (!job.state.equals("succeeded")) { logStep(op, job.state, job.message); stepFailed(op, job.state + ": " + job.message); return; }
        if (s.has("expect")) {
            JsonObject ex = s.getAsJsonObject("expect");
            String id = str(ex, "item"); int need = integer(ex, "at_least", 1, 0, 100000), have = count(id);
            if (have < need) { logStep(op, "failed", job.message + "; expected at least " + need + " " + id + ", have " + have); stepFailed(op, "check failed: expected at least " + need + " " + id + ", have " + have); return; }
        }
        logStep(op, "succeeded", job.message);
        nextStep();
    }
    private JsonObject seqJson() {
        JsonObject o = new JsonObject();
        o.addProperty("id", seq.id); o.addProperty("state", seq.state); o.addProperty("message", seq.message);
        o.addProperty("step", Math.min(seq.index + 1, seq.steps.size())); o.addProperty("steps", seq.steps.size());
        o.addProperty("elapsed_seconds", (System.nanoTime() - seq.started) / 1_000_000_000L);
        o.add("log", seq.log);
        return o;
    }
    private boolean lowVitals() { return mc.player.getHealth() <= 8 || mc.player.getFoodData().getFoodLevel() <= 6; }
    void toggleEnabled() {
        if (enabled) { disarm("Disabled with F8"); return; }
        if (mc.player == null) return;
        enabled = true; heartbeat = System.nanoTime(); dimension = mc.level.dimension().location().toString();
        mc.player.displayClientMessage(Component.literal("SkyCodex 已启用；F9 立即停止并交还控制"), false);
    }
    void disarm(String reason) {
        enabled = false; lastStop = reason;
        defender.reset();
        if (running()) finish("cancelled", reason);
        if (seq != null && seq.state.equals("running")) { seq.state = "cancelled"; seq.message = "Cancelled at step " + (seq.index + 1) + ": " + reason; }
        if (mc.player != null) mc.player.displayClientMessage(Component.literal("SkyCodex 已停止：" + reason), false);
    }
    private <T> void setting(Settings.Setting<T> s, T value) { previousSettings.putIfAbsent(s, s.value); s.value = value; }
    private void scaffoldExcept(Collection<Item> keep) {
        List<Item> allowed = new ArrayList<>(SCAFFOLD);
        allowed.removeAll(keep);
        setting(cfg().acceptableThrowawayItems, allowed);
    }
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void release() {
        try { bot().getPathingBehavior().cancelEverything(); bot().getPathingBehavior().forceCancel(); bot().getInputOverrideHandler().clearAllKeys(); } catch (Exception ignored) {}
        if (mc.gameMode != null) mc.gameMode.stopDestroyBlock();
        mc.options.keyUse.setDown(false);
        mc.options.keyShift.setDown(false);
        if (mc.player != null && mc.gameMode != null && mc.player.isUsingItem()) mc.gameMode.releaseUsingItem(mc.player);
        previousSettings.forEach((k,v) -> ((Settings.Setting)k).value = v); previousSettings.clear();
    }
    private void finish(String state, String message) {
        if (job != null) {
            job.state = state; job.message = message;
            if (job.task == null && job.autoEats > 0) job.message += "; auto-ate " + job.autoEats + "x";
            if (job.autoEatNote != null) job.message += "; " + job.autoEatNote;
        }
        // A crafting table this job opened itself is closed on success AND failure, otherwise every later job is
        // refused with "Close the current GUI first".
        if (job != null && job.craft != null && job.craft.openedTable() && mc.player != null
                && mc.player.containerMenu instanceof CraftingMenu) mc.player.closeContainer();
        if (job != null && job.task != null) { try { job.task.cleanup(); } catch (Exception ignored) {} }
        release();
    }
    private void begin(String kind, JsonObject args) {
        requireControl();
        if (mc.screen != null && !kind.equals("craft") && !(kind.equals("waystone") && WaystoneTask.isMenu(mc))) throw new IllegalStateException("Close the current GUI first (mc_close_menu)");
        if (!mc.player.containerMenu.getCarried().isEmpty()) throw new IllegalStateException("Put down the item held by the mouse first");
        // Walking away, eating and going to bed must stay possible when hurt: that is how a player survives.
        if (!kind.equals("eat") && !kind.equals("move") && !kind.equals("sleep") && !kind.equals("fly") && lowVitals()) throw new IllegalStateException("Health <= 8 or food <= 6. Use mc_eat first, or mc_move to safety, before other jobs.");
        job = new Job(kind, integer(args, "timeout_seconds", 180, 10, 600));
        bot().getPathingBehavior().cancelEverything();
        bot().getPathingBehavior().forceCancel();
        Settings s = cfg();
        setting(s.allowBreak, false); setting(s.allowPlace, false); setting(s.allowInventory, true);
        setting(s.allowParkour, false); setting(s.allowParkourPlace, false); setting(s.maxFallHeightNoWater, 2);
        setting(s.allowWaterBucketFall, false); setting(s.allowDownward, false); setting(s.allowSprint, true); // sprint like a player on long walks
        setting(s.autoTool, true); setting(s.itemSaver, true); setting(s.enterPortal, false);
        setting(s.disconnectOnArrival, false); setting(s.freeLook, false);
        setting(s.avoidance, true);
        // Modded partial blocks (Create shafts, cogwheels, pipes, depots, ...) count as obstacles: vanilla marks every
        // non-full block as pathfindable, so Baritone would route straight through them and walk into them forever.
        List<Block> avoid = new ArrayList<>(s.blocksToAvoid.value);
        for (Block b : partialObstacles()) if (!avoid.contains(b)) avoid.add(b);
        for (Block b : learnedObstacles) if (!avoid.contains(b)) avoid.add(b);
        setting(s.blocksToAvoid, avoid);
        // Never mine for ores the player cannot see and never start branch-mining tunnels on its own.
        setting(s.legitMine, false); setting(s.exploreForBlocks, false);
        setting(s.allowOnlyExposedOres, true); setting(s.allowOnlyExposedOresDistance, 1);
        scaffoldExcept(List.of());
    }
    JsonObject request(String op, JsonObject args) throws Exception {
        heartbeat = System.nanoTime();
        if (op.equals("sequence")) { startSequence(args); return status(); }
        if (JOB_KINDS.contains(op)) {
            begin(op,args);
            seq = null; // a single job replaces a finished sequence in status
            try { startJob(args); } catch (Exception e) { finish("failed",e.getMessage()==null?e.toString():e.getMessage()); }
            return status();
        }
        return switch (op) {
            case "heartbeat" -> { JsonObject o=new JsonObject();o.addProperty("ok",true);yield o; }
            case "status" -> status();
            case "stop" -> { disarm("Stopped from Codex"); yield status(); }
            case "cancel" -> {
                // Cancel the running job/sequence but keep control enabled (unlike stop/F9).
                requireWorld();
                if (seq != null && seq.state.equals("running")) { seq.state = "cancelled"; seq.message = "Cancelled at step " + (seq.index + 1) + " by Codex"; }
                if (running()) finish("cancelled", "Cancelled by Codex; control stays enabled");
                yield status();
            }
            case "scan" -> scan(args);
            case "items" -> items(args);
            case "recipes" -> recipes(args);
            case "screenshot" -> screenshot(args);
            case "menu" -> menu();
            case "entities" -> { requireWorld(); yield entities(integer(args,"radius",32,2,96), args.has("type") ? str(args,"type") : null); }
            case "use_entity" -> { requireControl(); yield useEntity(str(args,"type")); }
            case "drop" -> { requireControl(); yield dropItem(str(args,"item"), integer(args,"count",64,1,64), args.has("x") ? pos(args) : null); }
            case "blueprint_check" -> { requireWorld(); yield blueprintCheck(new Blueprint(args)); }
            case "quests" -> { requireWorld(); yield quests.read(args.has("mode") ? str(args,"mode") : "available", args.has("query") ? str(args,"query") : null, integer(args,"limit",20,1,60)); }
            case "quest_submit" -> { requireControl(); yield quests.submit(str(args,"id")); }
            case "quest_claim" -> { requireControl(); yield quests.claim(str(args,"id")); }
            case "cannon_status" -> { requireWorld(); yield CannonTask.status(mc, pos(args)); }
            case "machines" -> { requireWorld(); yield machines.scan(integer(args,"radius",32,4,96), args.has("block") ? str(args,"block") : null, args.has("for_item") ? str(args,"for_item") : null); }
            case "click_slot" -> { requireControl(); clickSlot(args); yield menu(); }
            case "close_menu" -> { requireControl();mc.player.closeContainer();yield status(); }
            case "equip" -> { requireControl(); equip(str(args,"item"));yield status(); }
            case "dev_command" -> {
                if (!DevHarness.ENABLED) throw new IllegalArgumentException("Unknown operation " + op);
                requireWorld(); mc.player.connection.sendCommand(str(args,"command"));
                JsonObject o=new JsonObject();o.addProperty("ok",true);yield o;
            }
            default -> throw new IllegalArgumentException("Unknown operation " + op);
        };
    }
    private void startJob(JsonObject a) {
        Settings s = cfg();
        switch (job.kind) {
            case "move" -> {
                job.target = pos(a); job.radius = integer(a,"radius",1,0,4);
                if (mc.player.blockPosition().distSqr(job.target)>1024*1024) throw new IllegalArgumentException("Move target must be within 1024 blocks (use mc_fly for longer trips)");
                job.restart = () -> bot().getCustomGoalProcess().setGoalAndPath(new GoalNear(job.target,job.radius));
                job.restart.run();
            }
            case "harvest" -> {
                Set<Block> only = new HashSet<>();
                if (a.has("crops")) for (String raw : idList(str(a,"crops"))) {
                    ResourceLocation id = ResourceLocation.parse(raw);
                    if (!BuiltInRegistries.BLOCK.containsKey(id)) throw new IllegalArgumentException("Unknown crop block " + raw);
                    only.add(BuiltInRegistries.BLOCK.get(id));
                }
                job.task = new HarvestTask(this, integer(a,"radius",12,2,32), integer(a,"limit",64,1,256), !a.has("replant") || a.get("replant").getAsBoolean(), only);
            }
            case "hunt" -> {
                Set<net.minecraft.world.entity.EntityType<?>> types = new HashSet<>();
                for (String raw : idList(a.has("animals") ? str(a,"animals") : "minecraft:cow,minecraft:pig,minecraft:sheep,minecraft:chicken,minecraft:rabbit")) {
                    ResourceLocation id = ResourceLocation.parse(raw);
                    if (!BuiltInRegistries.ENTITY_TYPE.containsKey(id)) throw new IllegalArgumentException("Unknown entity type " + raw);
                    types.add(BuiltInRegistries.ENTITY_TYPE.get(id));
                }
                setting(s.allowBreak,false); setting(s.allowPlace,false);
                job.task = new HuntTask(this, types, integer(a,"count",1,1,16), integer(a,"radius",16,4,32));
            }
            case "deposit", "withdraw" -> {
                job.target = pos(a); checkNearby(job.target, 64);
                List<Item> its = new ArrayList<>();
                boolean all = a.has("all_items") && a.get("all_items").getAsBoolean();
                if (a.has("items")) for (String id : idList(str(a,"items"))) its.add(item(id));
                else if (!all) throw new IllegalArgumentException("Give items (comma separated ids) or all_items=true");
                job.task = new ContainerTask(this, job.target, job.kind.equals("deposit"), its, integer(a,"count",Integer.MAX_VALUE,1,Integer.MAX_VALUE));
            }
            case "cannon" -> {
                job.target = pos(a); checkNearby(job.target, 64);
                job.task = new CannonTask(this, job.target, str(a,"action"));
            }
            case "sleep" -> job.task = new SleepTask(this);
            case "fly" -> {
                job.target = pos(a);
                job.task = new FlyTask(this, job.target, integer(a,"radius",8,2,64), integer(a,"cruise_height",40,10,120),
                        !a.has("land") || a.get("land").getAsBoolean());
            }
            case "waystone" -> {
                job.detail = new JsonObject();
                job.task = new WaystoneTask(this, a.has("action") ? str(a,"action") : "list", a.has("name") ? str(a,"name") : null, a.has("x") ? pos(a) : null, job.detail);
            }
            case "smelt" -> {
                job.target = pos(a); checkNearby(job.target, 64);
                boolean collectOnly = a.has("collect_only") && a.get("collect_only").getAsBoolean();
                boolean wait = !a.has("wait") || a.get("wait").getAsBoolean();
                job.task = new SmeltTask(this, job.target, a.has("item") ? str(a,"item") : null, integer(a,"count",1,1,64), a.has("fuel") ? str(a,"fuel") : null, collectOnly, wait);
            }
            case "collect" -> {
                List<Block> blocks = new ArrayList<>();
                for (String raw : idList(str(a,"block"))) {
                    ResourceLocation blockId = ResourceLocation.parse(raw);
                    if (!BuiltInRegistries.BLOCK.containsKey(blockId)) throw new IllegalArgumentException("Unknown block " + blockId);
                    Block block = BuiltInRegistries.BLOCK.get(blockId);
                    if (block.defaultBlockState().isAir() || block.defaultBlockState().hasBlockEntity()) throw new IllegalArgumentException("Cannot collect air or machine/container blocks");
                    blocks.add(block);
                }
                job.items = idList(str(a,"drop_item")); for (String id : job.items) item(id);
                job.initialCount = count(job.items); job.goalCount = job.initialCount + integer(a,"count",1,1,512);
                boolean hidden = "any".equals(a.has("mode") ? str(a,"mode") : "exposed");
                setting(s.allowBreak,true); setting(s.allowPlace,true); setting(s.allowDownward,true);
                setting(s.allowOnlyExposedOres,!hidden);
                List<Item> keep = new ArrayList<>(); for (String id : job.items) keep.add(item(id));
                for (Block b : blocks) keep.add(b.asItem());
                scaffoldExcept(keep);
                // Inventory delta is verified independently; Baritone's drop estimation is not authoritative.
                job.restart = () -> bot().getMineProcess().mine(0, blocks.toArray(new Block[0]));
                job.restart.run();
            }
            case "build" -> {
                job.blueprint = new Blueprint(a);
                JsonObject check=blueprintCheck(job.blueprint);job.detail=check;
                if (check.getAsJsonArray("conflicts").size()>0) throw new IllegalStateException("Build location contains conflicting blocks; see blueprint_check");
                if (check.getAsJsonArray("missing").size()>0) throw new IllegalStateException("Missing materials; see blueprint_check");
                job.matchedAtCycleStart=check.get("already_correct").getAsInt();
                configureBuilder(job.blueprint, false);
                job.restart = () -> { if (job.manual != null) job.phase = 10; else bot().getBuilderProcess().build("SkyCodex",job.blueprint,job.blueprint.origin); };
                bot().getBuilderProcess().build("SkyCodex",job.blueprint,job.blueprint.origin);
            }
            case "craft" -> {
                var holder=mc.level.getRecipeManager().byKey(ResourceLocation.parse(str(a,"recipe"))).orElseThrow(()->new IllegalArgumentException("Recipe ID not found"));
                if (!(holder.value() instanceof ShapedRecipe) && !(holder.value() instanceof ShapelessRecipe)) throw new IllegalArgumentException("Automatic craft supports standard shaped/shapeless recipes only. Machine/special recipes need manual tool steps.");
                job.recipe=(CraftingRecipe)holder.value();job.remaining=integer(a,"times",1,1,64);
                job.items=List.of(BuiltInRegistries.ITEM.getKey(job.recipe.getResultItem(mc.level.registryAccess()).getItem()).toString());
                job.initialCount=count(job.items);
                job.craft=new CraftTask(this,job.recipe,job.remaining);
            }
            case "use_block" -> {
                job.target=pos(a);
                if (a.has("face")) useBlockFace(job.target, Direction.byName(str(a,"face")));
                else useBlock(job.target);
                job.phaseTicks=8;
            }
            case "eat" -> startEating(a.has("item") ? str(a,"item") : null);
            case "place" -> {
                job.target = pos(a);
                Item it = item(str(a,"item"));
                if (!(it instanceof BlockItem bi)) throw new IllegalArgumentException(str(a,"item") + " is not a placeable block item");
                if (count(List.of(str(a,"item"))) < 1) throw new IllegalStateException("Item not in inventory: " + str(a,"item"));
                checkNearby(job.target, 64);
                BlockState current = mc.level.getBlockState(job.target);
                if (current.is(bi.getBlock())) throw new IllegalStateException("That block is already there");
                if (!current.canBeReplaced()) throw new IllegalStateException("Target position is occupied by " + BuiltInRegistries.BLOCK.getKey(current.getBlock()) + "; choose an empty spot or mc_break it first");
                job.placeBlock = bi.getBlock();
                // Any "properties" object (even {}) selects our own placement, which also handles machines whose only
                // support is another machine (sneak-click); Baritone's builder cannot do that.
                if (a.has("properties") && a.get("properties").isJsonObject()) {
                    // Oriented placement: choose the face to click like a player would so the block gets exactly these
                    // properties (facing/axis/half ...), then verify the server's state. Machines are allowed here.
                    BlockState wanted = Blueprint.withProperties(bi.getBlock().defaultBlockState(), a.getAsJsonObject("properties"));
                    job.blueprint = Blueprint.single(job.target, wanted);
                    job.detail = new JsonObject(); job.detail.addProperty("wanted", wanted.toString());
                    job.restart = () -> { if (job.manual != null) job.phase = 10; };
                    startManualPlace();
                } else {
                    Blueprint single = Blueprint.single(job.target, bi.getBlock().defaultBlockState());
                    configureBuilder(single, true);
                    bot().getBuilderProcess().build("SkyCodex place", single, job.target);
                }
            }
            case "break" -> {
                job.target = pos(a);
                checkNearby(job.target, 64);
                BlockState current = mc.level.getBlockState(job.target);
                if (current.isAir()) throw new IllegalStateException("Nothing to break there");
                if (current.getDestroySpeed(mc.level, job.target) < 0) throw new IllegalStateException("Block is unbreakable");
                var be = mc.level.getBlockEntity(job.target);
                boolean allowContainer = a.has("allow_container") && a.get("allow_container").getAsBoolean();
                if (be instanceof Container && !allowContainer) throw new IllegalStateException("This is a container; empty it first, then pass allow_container=true");
                job.item = BuiltInRegistries.BLOCK.getKey(current.getBlock()).toString();
                Item dropItem = current.getBlock().asItem();
                job.items = dropItem == Items.AIR ? List.of() : List.of(BuiltInRegistries.ITEM.getKey(dropItem).toString());
                job.initialCount = count(job.items);
                setting(s.allowBreak,true); setting(s.allowPlace,true);
                scaffoldExcept(List.of(current.getBlock().asItem()));
                bot().getBuilderProcess().clearArea(job.target, job.target);
            }
            default -> throw new IllegalArgumentException("Unsupported job");
        }
    }
    private void configureBuilder(Blueprint b, boolean ignoreDirection) {
        Settings s = cfg();
        setting(s.allowPlace,true);
        setting(s.buildInLayers,true);
        setting(s.layerOrder,false);
        setting(s.buildIgnoreExisting,false);
        setting(s.buildIgnoreDirection,ignoreDirection);
        setting(s.buildIgnoreProperties,b.ignoredPropertyNames());
        setting(s.buildIgnoreBlocks,new ArrayList<>());
        setting(s.buildSkipBlocks,new ArrayList<>());
        setting(s.buildOnlySelection,false);
        setting(s.startAtLayer,0);
        setting(s.layerHeight,1);
        // One impossible cell (e.g. a wall button whose wall comes later) must not stop every layer above it.
        setting(s.skipFailedLayers,true);
        setting(s.buildValidSubstitutes,new HashMap<>());
        setting(s.buildSubstitutes,new HashMap<>());
        setting(s.okIfAir,new ArrayList<>());
        setting(s.buildRepeat,new Vec3i(0,0,0));
        setting(s.schematicOrientationX,false);
        setting(s.schematicOrientationY,false);
        setting(s.schematicOrientationZ,false);
        setting(s.buildSchematicRotation,Rotation.NONE);
        setting(s.buildSchematicMirror,Mirror.NONE);
        // Never burn blueprint materials (e.g. cobblestone walls) as temporary scaffolding.
        scaffoldExcept(b.materials());
        if (!ignoreDirection) { // real blueprint build (not the one-block mc_place)
            List<Item> throwaway = new ArrayList<>(); List<Block> breakable = new ArrayList<>();
            for (Block blk : BUILD_SCAFFOLD) if (!b.materials().contains(blk.asItem())) { throwaway.add(blk.asItem()); breakable.add(blk); }
            setting(s.acceptableThrowawayItems, throwaway);
            setting(s.allowBreakAnyway, breakable);
        }
    }
    private void checkNearby(BlockPos p, int range) {
        if (mc.player.blockPosition().distSqr(p) > (long) range * range) throw new IllegalArgumentException("Target must be within " + range + " blocks; mc_move closer first");
        if (!mc.level.hasChunkAt(p) || mc.level.isOutsideBuildHeight(p)) throw new IllegalArgumentException("Target is outside loaded chunks or build height");
    }

    // ---- eating -------------------------------------------------------------------------------------------------
    private static boolean safeFood(ItemStack s) {
        FoodProperties f = s.get(DataComponents.FOOD);
        if (f == null) return false;
        for (FoodProperties.PossibleEffect e : f.effects()) if (e.effect().getEffect().value().getCategory() == MobEffectCategory.HARMFUL) return false;
        return true;
    }
    private void startEating(String wanted) {
        if (mc.player.containerMenu != mc.player.inventoryMenu) throw new IllegalStateException("Close the container first");
        var inv = mc.player.getInventory();
        String best = null; int bestScore = -1;
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) continue;
            String id = BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
            if (wanted != null) { if (id.equals(wanted)) { best = id; break; } continue; }
            if (!safeFood(s)) continue;
            FoodProperties f = s.get(DataComponents.FOOD);
            int score = f.nutrition() * 10 + Math.round(f.saturation()) + (i < 9 ? 1 : 0);
            if (score > bestScore) { bestScore = score; best = id; }
        }
        if (best == null) throw new IllegalStateException(wanted == null ? "No safe food in inventory; collect or craft food first" : "Item not in inventory: " + wanted);
        ItemStack sample = new ItemStack(item(best));
        FoodProperties f = sample.get(DataComponents.FOOD);
        if (f == null) throw new IllegalArgumentException(best + " is not edible");
        if (!f.canAlwaysEat() && !mc.player.getFoodData().needsFood()) throw new IllegalStateException("Player is not hungry");
        job.item = best;
        job.items = List.of(best);
        job.initialCount = count(job.items);
        job.initialFood = mc.player.getFoodData().getFoodLevel();
        equip(best);
        job.phaseTicks = 3; // let the hotbar switch reach the server before using the item
    }
    private void tickEating() {
        if (job.phase == 0) {
            if (--job.phaseTicks > 0) return;
            if (!mc.player.getMainHandItem().is(item(job.item))) throw new IllegalStateException("Food is not in hand");
            // Use the item directly so a block under the crosshair (table, chest) is not right-clicked instead.
            mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
            mc.options.keyUse.setDown(true); // vanilla releases item use as soon as the use key is up
            job.phase = 1; job.phaseTicks = 0;
            return;
        }
        job.phaseTicks++;
        if (mc.player.isUsingItem()) { if (job.phaseTicks > 100) throw new IllegalStateException("Eating did not finish"); return; }
        mc.options.keyUse.setDown(false);
        if (job.phaseTicks < 3) throw new IllegalStateException("Server rejected eating; the item may not be edible right now");
        if (count(job.items) < job.initialCount || mc.player.getFoodData().getFoodLevel() > job.initialFood) {
            job.progress = mc.player.getFoodData().getFoodLevel();
            finish("succeeded", "Ate " + job.item + "; food is now " + mc.player.getFoodData().getFoodLevel());
        } else throw new IllegalStateException("Eating was interrupted");
    }

    /** Runs at the start of the client tick, after server packets were handled but before vanilla key handling. */
    void preTick() {
        // Eating finished (server confirmed): release the use key now, otherwise vanilla immediately eats another item.
        if (running() && mc.player != null && !mc.player.isUsingItem()
                && (job.kind.equals("eat") && job.phase == 1 || job.autoEatPhase == 2)) mc.options.keyUse.setDown(false);
    }

    // ---- auto-eat during long jobs ------------------------------------------------------------------------------
    private boolean wantsAutoEat() {
        if (!AUTO_EAT_KINDS.contains(job.kind) || job.autoEatDisabled || mc.screen != null) return false;
        if (mc.player.getFoodData().getFoodLevel() > AUTO_EAT_FOOD) return false;
        return bestFood() != null;
    }
    private void startAutoEat() {
        bot().getPathingBehavior().cancelEverything();
        // Baritone forces key states while it walks; drop them or it overrides the held use key and eating stops.
        bot().getInputOverrideHandler().clearAllKeys();
        mc.gameMode.stopDestroyBlock();
        job.autoEatItem = bestFood(); job.autoEatFood = mc.player.getFoodData().getFoodLevel();
        job.autoEatCount = count(job.autoEatItem); job.autoEatSlot = mc.player.getInventory().selected;
        equip(job.autoEatItem);
        job.autoEatPhase = 1; job.autoEatTicks = 0;
    }
    /** Eat one item, then resume the job where it was. Failure only disables auto-eat; the job continues. */
    private void tickAutoEat() {
        job.autoEatTicks++;
        if (job.autoEatPhase == 1) {
            bot().getInputOverrideHandler().clearAllKeys();
            if (job.autoEatTicks < 6) return; // let the hotbar switch and the stop reach the server
            if (!mc.player.getMainHandItem().is(item(job.autoEatItem))) { endAutoEat(false, "food not in hand"); return; }
            mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
            mc.options.keyUse.setDown(true);
            job.autoEatPhase = 2; job.autoEatTicks = 0;
            return;
        }
        bot().getInputOverrideHandler().clearAllKeys();
        if (mc.player.isUsingItem()) { mc.options.keyUse.setDown(true); if (job.autoEatTicks > 100) endAutoEat(false, "eating did not finish"); return; }
        mc.options.keyUse.setDown(false);
        boolean ate = count(job.autoEatItem) < job.autoEatCount || mc.player.getFoodData().getFoodLevel() > job.autoEatFood;
        endAutoEat(ate, ate ? null : "eating stopped after " + job.autoEatTicks + " ticks");
    }
    private void endAutoEat(boolean ate, String why) {
        mc.options.keyUse.setDown(false);
        if (mc.player.isUsingItem()) mc.gameMode.releaseUsingItem(mc.player);
        if (ate) job.autoEats++;
        if (!ate) { job.autoEatDisabled = true; job.autoEatNote = "auto-eat failed (" + why + "); eat manually with mc_eat"; }
        if (job.autoEatSlot >= 0 && job.autoEatSlot < 9) mc.player.getInventory().selected = job.autoEatSlot;
        job.autoEatPhase = 0;
        if (job.restart != null) job.restart.run();
        if (job.task != null) job.task.interrupted();
        job.lastProgressTick = job.ticks; // eating is not a stall
    }
    void tick() {
        if (!enabled) return;
        if (mc.player==null || mc.level==null) { disarm("Left world");return; }
        if (!dimension.equals(mc.level.dimension().location().toString())) {
            // A waystone teleport may legitimately change dimension; anything else returns control to the player.
            if (running() && job.kind.equals("waystone")) dimension = mc.level.dimension().location().toString();
            else { disarm("Dimension changed");return; }
        }
        if (!mc.player.isAlive()) { disarm("Player died");return; }
        if (System.nanoTime()-heartbeat>60_000_000_000L) { disarm("Connection heartbeat expired");return; }
        // Self-defence first: the user wants the character to fight back instead of stopping.
        try {
            boolean wasDefending = defender.active();
            if (defender.tick()) {
                if (running()) {
                    job.lastProgressTick = job.ticks; // fighting is not a stall
                    if (job.autoEatPhase > 0) { job.autoEatPhase = 0; mc.options.keyUse.setDown(false); }
                }
                return;
            }
            if (wasDefending && running()) {
                if (job.restart != null) job.restart.run();
                if (job.task != null) job.task.interrupted();
                job.lastProgressTick = job.ticks;
            }
        } catch (Exception e) { defender.reset(); }
        if (!running() && seq != null && seq.state.equals("running") && job != null) { afterStep(); return; }
        if (!running()) return;
        if (System.nanoTime()>job.deadline) { finish("failed","Task timed out; inspect the world before retrying");return; }
        if (mc.screen!=null && !GUI_KINDS.contains(job.kind)) { disarm("GUI opened; control returned");return; }
        try {
            if (job.autoEatPhase > 0) { tickAutoEat(); return; }
            if (wantsAutoEat()) { startAutoEat(); return; }
        } catch (Exception e) { job.autoEatDisabled = true; job.autoEatPhase = 0; }
        // Stop the job (but keep control) so Codex can react with mc_eat instead of the player re-pressing F8.
        // Never drop a flight in mid-air: an uncontrolled glide is worse than finishing the landing.
        if (!job.kind.equals("eat") && !job.kind.equals("move") && !job.kind.equals("sleep") && !job.kind.equals("fly") && lowVitals()) { finish("failed","Health <= 8 or food <= 6; job stopped. Use mc_eat or mc_move to safety, then retry");return; }
        job.ticks++;
        try { unstick(); } catch (Exception ignored) {}
        // While stepping out of an obstacle cell, and for 2 s after re-planning, the job's own "Baritone stopped" checks
        // must not count as failure: the path is being recalculated on purpose.
        if (escapeTarget != null || job.ticks < job.graceUntil) return;
        try {
            if (job.task != null) {
                if (job.task.tick()) {
                    String m = job.task.message();
                    finish("succeeded", job.autoEats > 0 ? m + "; auto-ate " + job.autoEats + "x" : m);
                }
                return;
            }
            switch (job.kind) {
                case "move" -> {
                    if (new GoalNear(job.target,job.radius).isInGoal(mc.player.blockPosition())) finish("succeeded","Reached target");
                    else if (job.ticks>40 && !bot().getCustomGoalProcess().isActive() && !bot().getPathingBehavior().isPathing())
                        finish("failed","No walkable path found (move never digs or bridges). Pick a closer or different target");
                }
                case "collect" -> {
                    job.progress=count(job.items)-job.initialCount;
                    if (count(job.items)>=job.goalCount) finish("succeeded","Requested items are in inventory");
                    else if (job.ticks>60 && !bot().getMineProcess().isActive()) finish("failed","Mining stopped before enough items were collected: no more exposed target blocks in loaded terrain, or tool/inventory problem. Move to another area, or use mode=any");
                }
                case "build" -> {
                    if (job.manual != null) tickManualPlace();
                    else if (job.ticks%20==0) {
                        int matched=job.blueprint.countMatched(mc.level);
                        noteOwnScaffold();
                        if (matched>job.progress || job.ticks<=20) job.lastProgressTick=job.ticks;
                        job.progress=matched;
                        if (matched==job.blueprint.blocks.size()) finish("succeeded","All blueprint block states verified");
                        // Baritone only tries rotations from where it stands and reports oriented blocks (stairs facing
                        // a given way) as "missing materials"; it can also loop forever, e.g. when the player stands in
                        // the target cell. Either way, place the remainder ourselves, like a player would.
                        else if (job.ticks>80 && (!bot().getBuilderProcess().isActive() || bot().getBuilderProcess().isPaused())) startManualPlace();
                        else if (job.ticks-job.lastProgressTick>600) startManualPlace();
                    }
                }
                case "craft" -> {
                    if (job.craft.tick()) {
                        job.progress=count(job.items)-job.initialCount;
                        finish("succeeded","Crafting output verified and collected");
                    }
                }
                case "use_block" -> { if (--job.phaseTicks<=0) finish("succeeded","Interaction sent; inspect menu/state to verify its effect"); }
                case "eat" -> tickEating();
                case "place" -> {
                    if (job.manual != null) {
                        tickManualPlace();
                        if (job.state.equals("succeeded")) job.message = "Block placed and verified: " + mc.level.getBlockState(job.target);
                        else if (job.state.equals("failed") && job.unplaced != null && !job.unplaced.isEmpty())
                            job.message = "Could not place " + job.detail.get("wanted").getAsString() + ": " + job.unplaced.get(0).getAsJsonObject().get("reason").getAsString() + " (stand closer, or give a support block on the side you want it attached to)";
                    }
                    else if (mc.level.getBlockState(job.target).is(job.placeBlock)) finish("succeeded","Block placed and verified");
                    else if (job.ticks>60 && !bot().getBuilderProcess().isActive()) finish("failed","Could not place the block; check reach, support and inventory");
                }
                case "break" -> {
                    BlockState now = mc.level.getBlockState(job.target);
                    if (job.phase == 0) {
                        if (now.isAir() || now.canBeReplaced() && !now.is(BuiltInRegistries.BLOCK.get(ResourceLocation.parse(job.item)))) {
                            bot().getPathingBehavior().cancelEverything();
                            job.phase = 1; job.phaseTicks = 0;
                        } else if (job.ticks>60 && !bot().getBuilderProcess().isActive()) finish("failed","Could not break the block; check reach and tool");
                    } else {
                        // Walk over the drop so it is picked up, like a player would, and verify it actually arrived.
                        job.phaseTicks++;
                        boolean gained = count(job.items) > job.initialCount;
                        List<ItemEntity> drops = mc.level.getEntitiesOfClass(ItemEntity.class, new AABB(job.target).inflate(4), ItemEntity::isAlive);
                        if (gained && (drops.isEmpty() || job.phaseTicks > 20)) finish("succeeded","Block broken and drop picked up");
                        else if (drops.isEmpty() && job.phaseTicks > 40) finish("succeeded","Block broken; no drop left on the ground (its drop may be a different item; check mc_status)");
                        else if (job.phaseTicks > 200) finish("failed","Block broken but the drop was not picked up; it may have fallen out of reach");
                        else if (!drops.isEmpty() && job.phaseTicks % 20 == 1) {
                            ItemEntity closest = Collections.min(drops, Comparator.comparingDouble(e -> e.distanceToSqr(mc.player)));
                            bot().getCustomGoalProcess().setGoalAndPath(new GoalBlock(closest.blockPosition()));
                        }
                    }
                }
            }
        } catch (Exception e) { finish("failed",e.getMessage()==null?e.toString():e.getMessage()); }
    }
    // ---- oriented placement fallback for blueprints -------------------------------------------------------------
    /** sneak: the support is a container/machine, so the click must be a sneak-click or it would open/use it. */
    private record Placement(BlockHitResult hit, float yaw, float pitch, Item item, boolean sneak) {}

    private void startManualPlace() {
        bot().getPathingBehavior().cancelEverything();
        // Every remaining cell is tried; ones that hold another block or lack items are skipped and reported.
        List<BlockPos> left = new ArrayList<>();
        for (var e : job.blueprint.blocks.entrySet()) {
            BlockPos p = job.blueprint.origin.offset(e.getKey());
            if (!Blueprint.matches(mc.level.getBlockState(p), e.getValue())) left.add(p);
        }
        // Bottom-up, so lower blocks can support the ones above.
        left.sort(Comparator.<BlockPos>comparingInt(Vec3i::getY).thenComparingDouble(p -> p.distSqr(mc.player.blockPosition())));
        job.manual = left; job.phase = 10; job.phaseTicks = 0; job.attempts = 0; job.deferred = 0; job.unplaced = new JsonArray();
    }

    /** Any scaffold block now in a blueprint cell was put there by this job (conflicts are refused at start). */
    private void noteOwnScaffold() {
        for (var e : job.blueprint.blocks.entrySet()) {
            BlockPos p = job.blueprint.origin.offset(e.getKey());
            BlockState now = mc.level.getBlockState(p);
            if (isBuildScaffold(now) && !Blueprint.matches(now, e.getValue())) ownScaffold.add(p.immutable());
        }
    }

    private BlockState wantedAt(BlockPos world) { return job.blueprint.blocks.get(world.subtract(job.blueprint.origin)); }

    private void tickManualPlace() {
        if (job.manual.isEmpty()) {
            int matched = job.blueprint.countMatched(mc.level); job.progress = matched;
            if (matched == job.blueprint.blocks.size()) { finish("succeeded","All blueprint block states verified"); return; }
            // Cells out of reach from the ground go back to Baritone, which can scaffold up to them. Repeat while
            // a Baritone + manual cycle still makes progress.
            if (job.deferred > 0 && matched > job.matchedAtCycleStart && job.cycles < 12) {
                job.cycles++; job.matchedAtCycleStart = matched; job.manual = null; job.lastProgressTick = job.ticks; job.ticks = Math.max(job.ticks, 81);
                bot().getPathingBehavior().cancelEverything();
                configureBuilder(job.blueprint, false);
                bot().getBuilderProcess().build("SkyCodex", job.blueprint, job.blueprint.origin);
                return;
            }
            if (job.unplaced == null) job.unplaced = new JsonArray();
            job.detail.add("unplaced", job.unplaced);
            job.detail.addProperty("out_of_reach", job.deferred);
            finish("failed", (job.blueprint.blocks.size() - matched) + " blocks not placed (" + job.unplaced.size() + "+ listed in job.detail.unplaced, "
                    + job.deferred + " out of reach even with scaffolding); everything else is verified. Fix access or the design, then run the same blueprint again");
            return;
        }
        BlockPos cell = job.manual.get(0);
        BlockState wanted = wantedAt(cell);
        switch (job.phase) {
            case 10 -> { // choose how to place from here, or where to walk to
                if (Blueprint.matches(mc.level.getBlockState(cell), wanted)) { job.manual.remove(0); job.attempts = 0; job.badStands.clear(); job.stand = null; return; }
                if (!mc.level.getBlockState(cell).canBeReplaced()) {
                    noteOwnScaffold();
                    if (ownScaffold.contains(cell)) { job.deferred++; job.manual.remove(0); job.attempts = 0; job.badStands.clear(); job.stand = null; return; } // Baritone clears its scaffold
                    giveUp(cell, wanted, "a different block is there now"); return;
                }
                if (++job.attempts > 4) { giveUp(cell, wanted, "placement did not produce the wanted state"); return; }
                if (count(BuiltInRegistries.ITEM.getKey(wanted.getBlock().asItem()).toString()) < 1) { giveUp(cell, wanted, "no item left in inventory"); return; }
                Placement pl = findPlacement(cell, wanted, mc.player.getEyePosition());
                if (pl != null) { job.placement = pl; equip(BuiltInRegistries.ITEM.getKey(pl.item()).toString()); job.phase = 20; job.phaseTicks = 0; return; }
                // Standing on the chosen spot did not work after all (not exactly centred): never pick it again.
                if (job.stand != null && mc.player.blockPosition().equals(job.stand)) job.badStands.add(job.stand);
                List<BlockPos> stands = standCandidates(cell);
                stands.removeIf(job.badStands::contains);
                if (stands.isEmpty() && job.badStands.isEmpty()) { job.deferred++; job.manual.remove(0); job.attempts = 0; job.badStands.clear(); job.stand = null; return; } // high up: Baritone scaffolds
                BlockPos stand = null;
                for (BlockPos c : stands) if (placeableAround(cell, wanted, c)) { stand = c; break; }
                if (stand == null) { giveUp(cell, wanted, "no support face gives this state yet (it may need a neighbour that is built later)"); return; }
                job.stand = stand; job.phase = 30; job.phaseTicks = 0;
                bot().getCustomGoalProcess().setGoalAndPath(new GoalBlock(stand));
            }
            case 20 -> { // look at the face, let the rotation reach the server, then right-click
                mc.player.setYRot(job.placement.yaw()); mc.player.setXRot(job.placement.pitch());
                if (++job.phaseTicks < 3) return;
                Placement fresh = findPlacement(cell, wanted, mc.player.getEyePosition());
                if (fresh == null || !mc.player.getMainHandItem().is(fresh.item())) { job.phase = 10; return; }
                mc.player.setYRot(fresh.yaw()); mc.player.setXRot(fresh.pitch());
                var conn = mc.player.connection;
                // The server decides look-dependent states (Create shaft axis, ...) from ITS copy of our rotation:
                // send exactly the simulated rotation right before the click.
                conn.send(new net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.Rot(fresh.yaw(), fresh.pitch(), mc.player.onGround()));
                // Sneak exactly for this click with explicit packets: the keyboard sneak state does not get through
                // while Baritone owns the movement input. The server handles the packets in order.
                if (fresh.sneak()) conn.send(new net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket(mc.player, net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket.Action.PRESS_SHIFT_KEY));
                boolean oldInputShift = mc.player.input.shiftKeyDown;
                mc.player.input.shiftKeyDown = fresh.sneak(); // the client-side prediction uses the same sneak state
                try { mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, fresh.hit()); }
                finally { mc.player.input.shiftKeyDown = oldInputShift; }
                if (fresh.sneak()) conn.send(new net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket(mc.player, net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket.Action.RELEASE_SHIFT_KEY));
                mc.player.swing(InteractionHand.MAIN_HAND);
                job.phase = 21; job.phaseTicks = 0;
            }
            case 21 -> { // verify with the SERVER's answer, not the client's own placement prediction
                job.phaseTicks++;
                BlockState now = mc.level.getBlockState(cell);
                if (job.phaseTicks < 8) return;
                if (Blueprint.matches(now, wanted)) { mc.options.keyShift.setDown(false); job.manual.remove(0); job.attempts = 0; job.badStands.clear(); job.stand = null; job.progress = job.blueprint.countMatched(mc.level); job.phase = 10; }
                else if (now.is(wanted.getBlock())) { mc.options.keyShift.setDown(false); giveUp(cell, wanted, "the server placed it as " + now + " instead"); }
                else if (job.phaseTicks > 30) { mc.options.keyShift.setDown(false); job.phase = 10; }
            }
            case 30 -> { // walking to a standing position
                job.phaseTicks++;
                if (mc.player.blockPosition().equals(job.stand) && mc.player.onGround()) { bot().getPathingBehavior().cancelEverything(); job.phase = 10; }
                else if (job.phaseTicks > 40 && !bot().getCustomGoalProcess().isActive() && !bot().getPathingBehavior().isPathing()) { bot().getPathingBehavior().cancelEverything(); job.phase = 10; }
                else if (job.phaseTicks > 600) { bot().getPathingBehavior().cancelEverything(); job.phase = 10; }
            }
            default -> job.phase = 10;
        }
    }

    private Vec3 stuckPos;
    private int stuckTicks;
    /**
     * While Baritone walks: if the player has not moved for a while it is pressed against something (a block the
     * path planner thought passable). Jump twice like a player would; if that does not help, plan again (and for a
     * manual placement, give up on that stand position and pick another one).
     */
    /** Block types the player actually got stuck on this session (any mod): kept out of every later path. */
    private static final Set<Block> learnedObstacles = new LinkedHashSet<>();

    private Vec3 escapeTarget;
    private int escapeTicks;

    private void unstick() {
        if (job.kind.equals("fly")) return; // elytra flight steers itself; no walking-stuck logic in the air
        Vec3 p = mc.player.position();
        if (escapeTarget != null) { // stepping out of a cell that holds an obstacle, then plan again from there
            bot().getPathingBehavior().cancelEverything();
            Vec3 d = new Vec3(escapeTarget.x - p.x, 0, escapeTarget.z - p.z);
            if (d.length() < 0.15 || --escapeTicks <= 0) {
                escapeTarget = null; stuckPos = null; stuckTicks = 0; job.graceUntil = job.ticks + 40;
                if (job.manual != null && job.phase == 30 && job.stand != null) { job.badStands.add(job.stand); job.phase = 10; }
                else if (job.restart != null) job.restart.run();
                else if (job.task != null) job.task.interrupted();
                return;
            }
            Vec3 step = d.normalize().scale(Math.min(0.2, d.length()));
            mc.player.setDeltaMovement(step.x, mc.player.getDeltaMovement().y, step.z);
            lookAt(new Vec3(escapeTarget.x, mc.player.getEyeY(), escapeTarget.z));
            return;
        }
        boolean walking = bot().getPathingBehavior().isPathing();
        if (!walking) { stuckTicks = 0; stuckPos = p; return; }
        // Horizontal progress only: jumping in place is not progress.
        double dx = stuckPos == null ? 1 : p.x - stuckPos.x, dz = stuckPos == null ? 1 : p.z - stuckPos.z;
        if (dx * dx + dz * dz > 0.04) { stuckPos = p; stuckTicks = 0; return; }
        stuckTicks++;
        if (stuckTicks == 12 && mc.player.onGround() && lowObstacleAhead()) {
            Vec3 v = mc.player.getDeltaMovement();
            mc.player.setDeltaMovement(v.x, 0.42 + (mc.player.hasEffect(net.minecraft.world.effect.MobEffects.JUMP) ? 0.1 * (mc.player.getEffect(net.minecraft.world.effect.MobEffects.JUMP).getAmplifier() + 1) : 0), v.z);
            mc.player.hasImpulse = true;
            job.jumps++;
        }
        if (stuckTicks >= 40) {
            stuckTicks = 0; stuckPos = null; job.replans++;
            learnBlockingBlocks();
            bot().getPathingBehavior().cancelEverything();
            // Squeezed into a cell that holds a thin obstacle (shaft, pipe ...)? The planner thinks we stand in that
            // cell and keeps planning the same first step through it: step back to a free cell first.
            BlockPos here = mc.player.blockPosition();
            boolean inBlockedCell = false;
            for (BlockPos q : blockingCells()) if (q.getX() == here.getX() && q.getZ() == here.getZ() && q.getY() >= here.getY()) inBlockedCell = true;
            // Also when pressed against an obstacle corner from outside (Baritone cuts diagonal corners past thin
            // blocks): step to the free neighbour farthest from what we hit, so the next path does not cut it again.
            List<BlockPos> hitCells = blockingCells();
            hitCells.removeIf(q -> q.getY() < here.getY());
            BlockPos free = (inBlockedCell || !hitCells.isEmpty()) ? nearestFreeCell(here, hitCells) : null;
            if (free != null) { escapeTarget = Vec3.atBottomCenterOf(free); escapeTicks = 30; job.escapes++; return; }
            job.graceUntil = job.ticks + 40;
            if (job.manual != null && job.phase == 30 && job.stand != null) { job.badStands.add(job.stand); job.phase = 10; }
            else if (job.restart != null) job.restart.run();
            else if (job.task != null) job.task.interrupted();
        }
    }

    /**
     * A neighbouring cell a player can stand in (solid floor, feet and head cells without any collision), as far as
     * possible from the cells we got stuck on; among equally far ones the closest to the player.
     */
    private BlockPos nearestFreeCell(BlockPos here, List<BlockPos> hit) {
        BlockPos best = null; double bestClear = -1, bestD = Double.MAX_VALUE;
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            if (dx == 0 && dz == 0) continue;
            BlockPos c = here.offset(dx, 0, dz);
            if (!mc.level.getBlockState(c).getCollisionShape(mc.level, c).isEmpty()) continue;
            if (!mc.level.getBlockState(c.above()).getCollisionShape(mc.level, c.above()).isEmpty()) continue;
            if (!mc.level.getBlockState(c.below()).isFaceSturdy(mc.level, c.below(), Direction.UP)) continue;
            // Diagonal neighbours need a free corner too, or we would clip the same obstacle on the way there.
            if (dx != 0 && dz != 0) {
                BlockPos a = here.offset(dx, 0, 0), b = here.offset(0, 0, dz);
                if (!mc.level.getBlockState(a).getCollisionShape(mc.level, a).isEmpty() && !mc.level.getBlockState(b).getCollisionShape(mc.level, b).isEmpty()) continue;
            }
            double clear = Double.MAX_VALUE;
            for (BlockPos h : hit) clear = Math.min(clear, (c.getX() - h.getX()) * (c.getX() - h.getX()) + (c.getZ() - h.getZ()) * (c.getZ() - h.getZ()));
            if (hit.isEmpty()) clear = 0;
            double d = Vec3.atBottomCenterOf(c).distanceToSqr(mc.player.position());
            if (clear > bestClear + 1e-6 || (Math.abs(clear - bestClear) < 1e-6 && d < bestD)) { bestClear = clear; bestD = d; best = c; }
        }
        return best;
    }

    /** Blocks touching the player's body (slightly widened) at feet and head height. */
    private List<BlockPos> blockingCells() {
        List<BlockPos> out = new ArrayList<>();
        AABB body = mc.player.getBoundingBox().inflate(0.2, 0, 0.2).move(0, 0.05, 0);
        for (BlockPos q : BlockPos.betweenClosed(BlockPos.containing(body.minX, body.minY, body.minZ), BlockPos.containing(body.maxX, body.maxY, body.maxZ))) {
            BlockState st = mc.level.getBlockState(q);
            if (st.isAir()) continue;
            var shape = st.getCollisionShape(mc.level, q);
            if (shape.isEmpty()) continue;
            for (AABB part : shape.toAabbs()) if (part.move(q).intersects(body)) { out.add(q.immutable()); break; }
        }
        return out;
    }

    /** Jump only for something a jump can clear: nothing solid at head height in the way. */
    private boolean lowObstacleAhead() {
        int headY = mc.player.blockPosition().getY() + 1;
        for (BlockPos q : blockingCells()) if (q.getY() >= headY) return false;
        return true;
    }

    /**
     * The path planner walked us into these blocks, so it misjudges them (modded partial blocks, odd collision
     * shapes): avoid that block type from now on and record what we hit.
     */
    private void learnBlockingBlocks() {
        JsonArray hit = new JsonArray();
        for (BlockPos q : blockingCells()) {
            BlockState st = mc.level.getBlockState(q);
            if (q.getY() < mc.player.blockPosition().getY()) continue; // the floor we stand on
            Block b = st.getBlock();
            JsonObject h = coords(q); h.addProperty("block", BuiltInRegistries.BLOCK.getKey(b).toString()); hit.add(h);
            if (Block.isShapeFullBlock(st.getCollisionShape(mc.level, q))) continue; // Baritone knows full cubes
            if (learnedObstacles.add(b)) {
                List<Block> avoid = new ArrayList<>(cfg().blocksToAvoid.value);
                if (!avoid.contains(b)) { avoid.add(b); setting(cfg().blocksToAvoid, avoid); cfg().blocksToAvoid.value = avoid; }
            }
        }
        if (job.detail == null) job.detail = new JsonObject();
        job.detail.add("stuck_on", hit);
    }

    /** Placeable from the stand's centre AND from a quarter block off in every direction (players never stop exactly centred). */
    private boolean placeableAround(BlockPos cell, BlockState wanted, BlockPos stand) {
        double eyeY = stand.getY() + mc.player.getEyeHeight();
        AABB cellBox = new AABB(cell);
        for (double[] o : new double[][]{{0, 0}, {0.25, 0}, {-0.25, 0}, {0, 0.25}, {0, -0.25}}) {
            Vec3 feet = new Vec3(stand.getX() + 0.5 + o[0], stand.getY(), stand.getZ() + 0.5 + o[1]);
            // An offset that would put the player inside the target cell is not a place it can stand anyway.
            if (o[0] != 0 || o[1] != 0) { if (mc.player.getDimensions(mc.player.getPose()).makeBoundingBox(feet).intersects(cellBox)) continue; }
            if (findPlacement(cell, wanted, new Vec3(feet.x, eyeY, feet.z)) == null) return false;
        }
        return true;
    }

    /** Skip one cell, remember why, and carry on with the rest of the blueprint. */
    private void giveUp(BlockPos cell, BlockState wanted, String reason) {
        if (job.unplaced == null) job.unplaced = new JsonArray();
        if (job.unplaced.size() < 40) {
            JsonObject u = coords(cell); u.addProperty("block", BuiltInRegistries.BLOCK.getKey(wanted.getBlock()).toString());
            u.addProperty("wanted", wanted.toString()); u.addProperty("reason", reason); job.unplaced.add(u);
        }
        job.blueprint.excluded.add(cell.subtract(job.blueprint.origin)); // keep Baritone from looping on it
        job.manual.remove(0); job.attempts = 0; job.badStands.clear(); job.stand = null; job.phase = 10;
    }

    /** Places where a player could stand (solid floor, two free cells, outside the blueprint) and place the block. */
    private List<BlockPos> standCandidates(BlockPos cell) {
        List<BlockPos> candidates = new ArrayList<>();
        for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) for (int dy = -3; dy <= 1; dy++) {
            if (dx == 0 && dz == 0) continue;
            BlockPos stand = cell.offset(dx, dy, dz);
            if (wantedAt(stand) != null || wantedAt(stand.above()) != null) continue;
            if (!mc.level.getBlockState(stand.below()).isFaceSturdy(mc.level, stand.below(), Direction.UP)) continue;
            if (!mc.level.getBlockState(stand).getCollisionShape(mc.level, stand).isEmpty() || !mc.level.getBlockState(stand.above()).getCollisionShape(mc.level, stand.above()).isEmpty()) continue;
            candidates.add(stand);
        }
        candidates.sort(Comparator.comparingDouble(p -> p.distSqr(mc.player.blockPosition())));
        return candidates;
    }

    /**
     * Simulates vanilla placement (getStateForPlacement) for every visible face of every solid neighbour, looking from
     * {@code eye} straight at the hit point, and returns one whose resulting state matches the blueprint.
     */
    private Placement findPlacement(BlockPos cell, BlockState wanted, Vec3 eye) {
        Item it = wanted.getBlock().asItem();
        if (!(it instanceof BlockItem)) return null;
        ItemStack stack = new ItemStack(it);
        double reach = mc.player.blockInteractionRange() - 0.25;
        AABB cellBox = new AABB(cell);
        float oldYaw = mc.player.getYRot(), oldPitch = mc.player.getXRot();
        boolean oldShift = mc.player.isShiftKeyDown(), oldInputShift = mc.player.input.shiftKeyDown;
        try {
          // Pass 0: plain blocks only. Pass 1: containers/machines too, clicked while sneaking so they are not opened.
          for (int pass = 0; pass < 2; pass++) {
            boolean sneak = pass == 1;
            // LocalPlayer.isShiftKeyDown() reads the keyboard input, not the entity flag: set both so the simulated
            // getStateForPlacement sees the same sneak state the server will (Create blocks place differently).
            mc.player.setShiftKeyDown(sneak); mc.player.input.shiftKeyDown = sneak;
            for (Direction d : Direction.values()) {
                BlockPos support = cell.relative(d);
                BlockState ss = mc.level.getBlockState(support);
                if (ss.isAir() || ss.canBeReplaced() || ss.getCollisionShape(mc.level, support).isEmpty()) continue;
                boolean interactive = ss.hasBlockEntity() || ss.getBlock() instanceof CraftingTableBlock || ss.getBlock() instanceof DoorBlock
                        || ss.getBlock() instanceof TrapDoorBlock || ss.getBlock() instanceof FenceGateBlock || ss.getBlock() instanceof LeverBlock || ss.getBlock() instanceof ButtonBlock;
                if (interactive != sneak) continue;
                Direction face = d.getOpposite();
                Vec3 normal = Vec3.atLowerCornerOf(face.getNormal());
                Vec3 center = Vec3.atCenterOf(support).add(normal.scale(0.5));
                // A 3x3 grid on the face, not just its centre: some blocks (Create shafts, cogwheels, ...) take their axis
                // from the player's look direction, and the centre can sit exactly on the boundary between two axes.
                List<Vec3> hits = new ArrayList<>(List.of(center));
                for (double u : new double[]{-0.35, 0, 0.35}) for (double v : new double[]{-0.35, 0, 0.35}) {
                    if (u == 0 && v == 0) continue;
                    hits.add(switch (face.getAxis()) {
                        case Y -> center.add(u, 0, v);
                        case X -> center.add(0, u, v);
                        case Z -> center.add(u, v, 0);
                    });
                }
                for (Vec3 aim : hits) {
                    Vec3 aimDelta = aim.subtract(eye);
                    if (aimDelta.length() > reach + 0.5 || aimDelta.dot(normal) >= 0) continue; // out of reach or back face
                    // Aim into the block, not at its full-cube face: chests, basins, funnels ... are smaller than a
                    // cube, so the click lands on their real outline (what a player's crosshair would hit).
                    // Extend along the line of sight (not along the face normal, which would bend the ray into a
                    // neighbouring block at shallow angles).
                    BlockHitResult ray = mc.level.clip(new ClipContext(eye, aim.add(aimDelta.normalize().scale(0.6)), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
                    if (ray.getType() != HitResult.Type.BLOCK || !ray.getBlockPos().equals(support) || ray.getDirection() != face) continue; // not visible
                    Vec3 hitVec = ray.getLocation();
                    Vec3 delta = hitVec.subtract(eye);
                    if (delta.length() > reach) continue;
                    float yaw = (float)(Mth.atan2(delta.z, delta.x) * 180 / Math.PI) - 90;
                    float pitch = (float)(-Mth.atan2(delta.y, Math.sqrt(delta.x * delta.x + delta.z * delta.z)) * 180 / Math.PI);
                    mc.player.setYRot(yaw); mc.player.setXRot(pitch);
                    BlockHitResult hit = new BlockHitResult(hitVec, face, support, false);
                    BlockPlaceContext ctx = new BlockPlaceContext(mc.level, mc.player, InteractionHand.MAIN_HAND, stack, hit);
                    if (!ctx.getClickedPos().equals(cell) || !ctx.canPlace()) continue;
                    Vec3 feet = eye.subtract(0, mc.player.getEyeHeight(), 0);
                    if (mc.player.getDimensions(mc.player.getPose()).makeBoundingBox(feet).intersects(cellBox)) continue;
                    BlockState result = wanted.getBlock().getStateForPlacement(ctx);
                    if (result == null || !Blueprint.matches(result, wanted)) continue;
                    // The server refuses a placement whose collision box (which can reach outside the cell, e.g. the
                    // cat engine) overlaps the player: test the real shape against where the player would stand.
                    AABB body = mc.player.getDimensions(mc.player.getPose()).makeBoundingBox(feet);
                    boolean blocked = false;
                    for (AABB part : result.getCollisionShape(mc.level, cell).toAabbs()) if (part.move(cell).intersects(body)) { blocked = true; break; }
                    if (blocked) continue;
                    // Robust choice only: the same state must also come out when the look direction is a few degrees
                    // off (standing slightly off-centre, rotation rounding on the server).
                    boolean robust = true;
                    for (float[] off : new float[][]{{3, 0}, {-3, 0}, {0, 3}, {0, -3}}) {
                        mc.player.setYRot(yaw + off[0]); mc.player.setXRot(Mth.clamp(pitch + off[1], -90, 90));
                        BlockState r2 = wanted.getBlock().getStateForPlacement(new BlockPlaceContext(mc.level, mc.player, InteractionHand.MAIN_HAND, stack, hit));
                        if (r2 == null || !Blueprint.matches(r2, wanted)) { robust = false; break; }
                    }
                    if (robust) return new Placement(hit, yaw, pitch, it, sneak);
                }
            }
          }
        } finally { mc.player.setYRot(oldYaw); mc.player.setXRot(oldPitch); mc.player.setShiftKeyDown(oldShift); mc.player.input.shiftKeyDown = oldInputShift; }
        return null;
    }

    private JsonObject status() {
        JsonObject o=new JsonObject();o.addProperty("version",VERSION);o.addProperty("enabled",enabled);o.addProperty("last_stop",lastStop);
        o.addProperty("in_world",mc.player!=null && mc.level!=null);
        if (mc.player!=null && mc.level!=null) {
            o.add("position",coords(mc.player.blockPosition()));o.addProperty("dimension",mc.level.dimension().location().toString());
            // Which server/world this is, so bridge-side memory is kept per server.
            if (mc.getCurrentServer()!=null) o.addProperty("world", mc.getCurrentServer().ip);
            else if (mc.getSingleplayerServer()!=null) o.addProperty("world", "singleplayer_" + mc.getSingleplayerServer().getWorldData().getLevelName());
            o.addProperty("facing",mc.player.getDirection().getName());
            o.addProperty("fall_flying",mc.player.isFallFlying());
            o.addProperty("health",mc.player.getHealth());o.addProperty("food",mc.player.getFoodData().getFoodLevel());
            long time=mc.level.getDayTime()%24000;o.addProperty("time_of_day",time);o.addProperty("is_night",time>=13000&&time<23000);
            o.addProperty("screen",mc.screen==null?"none":mc.screen.getClass().getSimpleName());
            o.addProperty("selected_hotbar",mc.player.getInventory().selected);
            JsonArray inv=new JsonArray();
            for (int i=0;i<mc.player.getInventory().getContainerSize();i++) { ItemStack stack=mc.player.getInventory().getItem(i); if (!stack.isEmpty()) { JsonObject entry=stack(stack);entry.addProperty("inventory_index",i);inv.add(entry); } }
            o.add("inventory",inv);
            JsonArray hostiles=new JsonArray();
            List<LivingEntity> near=mc.level.getEntitiesOfClass(LivingEntity.class,mc.player.getBoundingBox().inflate(16),e->e instanceof Enemy && e.isAlive());
            near.sort(Comparator.comparingDouble(e->e.distanceToSqr(mc.player)));
            for (LivingEntity e:near.subList(0,Math.min(8,near.size()))) {JsonObject h=coords(e.blockPosition());h.addProperty("type",BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString());h.addProperty("distance",Math.round(e.distanceTo(mc.player)));hostiles.add(h);}
            o.add("hostiles_nearby",hostiles);
            o.add("defense",defender.json());
            JsonObject pathing=new JsonObject();pathing.addProperty("partial_obstacles",partialObstacles().size());
            pathing.addProperty("shaft_is_obstacle",partialObstacles().stream().anyMatch(b->BuiltInRegistries.BLOCK.getKey(b).toString().equals("create:shaft")));
            JsonArray learned=new JsonArray();for(Block b:learnedObstacles)learned.add(BuiltInRegistries.BLOCK.getKey(b).toString());pathing.add("learned_obstacles",learned);
            o.add("pathing",pathing);
            o.addProperty("max_health",mc.player.getMaxHealth());
            o.addProperty("xp_level",mc.player.experienceLevel);
        }
        if (job!=null) {
            JsonObject j=new JsonObject();j.addProperty("id",job.id);j.addProperty("kind",job.kind);j.addProperty("state",job.state);j.addProperty("message",job.message);j.addProperty("progress",job.progress);
            j.addProperty("elapsed_seconds",(System.nanoTime()-job.started)/1_000_000_000L);
            if(job.jumps>0)j.addProperty("auto_jumps",job.jumps);if(job.replans>0)j.addProperty("replans",job.replans);if(job.escapes>0)j.addProperty("escapes",job.escapes);
            if(job.detail!=null)j.add("detail",job.detail);o.add("job",j);
        }
        if (seq!=null) o.add("sequence",seqJson());
        return o;
    }
    static JsonObject stack(ItemStack s) {
        JsonObject o=new JsonObject();o.addProperty("item",BuiltInRegistries.ITEM.getKey(s.getItem()).toString());o.addProperty("count",s.getCount());o.addProperty("name",s.getHoverName().getString());
        if(s.isDamageableItem()){o.addProperty("damage",s.getDamageValue());o.addProperty("max_damage",s.getMaxDamage());}return o;
    }
    Item item(String id) { ResourceLocation r=ResourceLocation.parse(id);if(!BuiltInRegistries.ITEM.containsKey(r))throw new IllegalArgumentException("Unknown item "+id);return BuiltInRegistries.ITEM.get(r); }
    int count(String id) { return count(List.of(id)); }
    int count(List<String> ids) {
        Set<Item> wanted=new HashSet<>();for(String id:ids)wanted.add(item(id));
        int n=0;for(ItemStack s:mc.player.getInventory().items)if(wanted.contains(s.getItem()))n+=s.getCount();return n;
    }
    private JsonObject scan(JsonObject a) {
        requireWorld();int radius=integer(a,"radius",8,0,16),limit=integer(a,"limit",32,1,128);
        String filter=a.has("block")?str(a,"block"):"";
        // Optional centre (any loaded position within 64 blocks of the player), e.g. to read one block's state.
        BlockPos origin=a.has("x")?pos(a):mc.player.blockPosition();
        if(origin.distSqr(mc.player.blockPosition())>64*64)throw new IllegalArgumentException("Scan centre must be within 64 blocks of the player");
        List<BlockPos> found=new ArrayList<>();
        for(BlockPos p:BlockPos.betweenClosed(origin.offset(-radius,-radius,-radius),origin.offset(radius,radius,radius))) {
            if(!mc.level.hasChunkAt(p))continue;BlockState s=mc.level.getBlockState(p);if(s.isAir())continue;
            String id=BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString();
            if(!filter.isEmpty() && !id.contains(filter))continue;found.add(p.immutable());
        }
        found.sort(Comparator.comparingDouble(p->p.distSqr(origin)));
        JsonArray result=new JsonArray();for(BlockPos p:found.subList(0,Math.min(limit,found.size()))) {BlockState st=mc.level.getBlockState(p);JsonObject e=coords(p);e.addProperty("block",BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString());if(!st.getProperties().isEmpty())e.add("state",Blueprint.propertiesOf(st));result.add(e);}
        JsonObject o=new JsonObject();o.add("blocks",result);o.addProperty("matches",found.size());o.addProperty("note","Loaded nearby blocks only; this is not a visibility or reachability guarantee");return o;
    }
    private JsonObject items(JsonObject a) {
        requireWorld();String q=str(a,"query").toLowerCase(Locale.ROOT);JsonArray list=new JsonArray();
        for(Item it:BuiltInRegistries.ITEM) {String id=BuiltInRegistries.ITEM.getKey(it).toString(),name=it.getDescription().getString();
            if(id.toLowerCase(Locale.ROOT).contains(q)||name.toLowerCase(Locale.ROOT).contains(q)){JsonObject o=new JsonObject();o.addProperty("id",id);o.addProperty("name",name);o.addProperty("is_block",it instanceof BlockItem);list.add(o);if(list.size()>=60)break;}}
        JsonObject o=new JsonObject();o.add("items",list);return o;
    }
    private JsonObject recipes(JsonObject a) {
        requireWorld();String query=str(a,"query");JsonArray list=new JsonArray();int limit=integer(a,"limit",12,1,30);
        for(RecipeHolder<?> holder:mc.level.getRecipeManager().getRecipes()) {
            Recipe<?> r=holder.value();ItemStack out=r.getResultItem(mc.level.registryAccess());
            if(!holder.id().toString().contains(query) && (out.isEmpty() || !BuiltInRegistries.ITEM.getKey(out.getItem()).toString().contains(query)))continue;
            JsonObject e=new JsonObject();e.addProperty("id",holder.id().toString());e.addProperty("type",BuiltInRegistries.RECIPE_TYPE.getKey(r.getType()).toString());e.add("output",stack(out));
            e.addProperty("automatic_crafting",r instanceof ShapedRecipe || r instanceof ShapelessRecipe);
            JsonArray ingredients=new JsonArray();for(Ingredient in:r.getIngredients()){JsonArray opts=new JsonArray();for(ItemStack option:in.getItems())opts.add(stack(option));ingredients.add(opts);}e.add("ingredients",ingredients);
            try {Recipe.CODEC.encodeStart(RegistryOps.create(JsonOps.INSTANCE,mc.level.registryAccess()),r).result().ifPresent(raw->e.add("raw",raw));}catch(Exception ignored){}
            list.add(e);if(list.size()>=limit)break;
        }
        JsonObject o=new JsonObject();o.add("recipes",list);o.addProperty("note","Search matches recipe ID or primary item output. Fluid-only/multiple-output recipes may require searching by recipe ID. Machine recipes are information only, not automatic craft.");return o;
    }
    private JsonObject screenshot(JsonObject a) throws Exception {
        requireWorld();int maxWidth=integer(a,"max_width",1280,320,3840);
        try(NativeImage image=Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
            JsonObject o=new JsonObject();o.addProperty("mimeType","image/png");
            int w=image.getWidth(),h=image.getHeight();
            if (w>maxWidth) {
                // Full-resolution Retina/4K PNGs are several MB and waste model context; downscale like a thumbnail.
                int nh=Math.max(1,Math.round(h*(maxWidth/(float)w)));
                try(NativeImage small=new NativeImage(maxWidth,nh,false)) {image.resizeSubRectTo(0,0,w,h,small);o.addProperty("data",Base64.getEncoder().encodeToString(small.asByteArray()));}
            } else o.addProperty("data",Base64.getEncoder().encodeToString(image.asByteArray()));
            return o;
        }
    }
    JsonObject menu() {
        requireWorld();AbstractContainerMenu menu=mc.player.containerMenu;JsonObject o=new JsonObject();o.addProperty("container_id",menu.containerId);o.addProperty("type",menu.getClass().getSimpleName());o.add("carried",stack(menu.getCarried()));JsonArray slots=new JsonArray();
        for(int i=0;i<menu.slots.size();i++){Slot s=menu.getSlot(i);JsonObject e=stack(s.getItem());e.addProperty("slot",i);e.addProperty("player_inventory",s.container==mc.player.getInventory());slots.add(e);}o.add("slots",slots);return o;
    }
    private void clickSlot(JsonObject a) {
        AbstractContainerMenu menu=mc.player.containerMenu;
        int id=integer(a,"container_id",-1,0,Integer.MAX_VALUE);if(id!=menu.containerId)throw new IllegalStateException("Container changed; read menu again");
        int slot=integer(a,"slot",-1,0,menu.slots.size()-1);if(slot<0)throw new IllegalArgumentException("slot required");
        String mode=str(a,"mode");ClickType type=switch(mode){case "pickup"->ClickType.PICKUP;case "quick_move"->ClickType.QUICK_MOVE;default->throw new IllegalArgumentException("Only pickup and quick_move are supported");};
        int button=integer(a,"button",0,0,1);mc.gameMode.handleInventoryMouseClick(id,slot,button,type,mc.player);
    }
    void equip(String id) {
        var inv=mc.player.getInventory();
        if (id.equals("empty") || id.equals("minecraft:air")) { // an empty hand: some blocks (depot, saw) hand items out only then
            for(int i=0;i<9;i++)if(inv.getItem(i).isEmpty()){inv.selected=i;return;}
            if(mc.player.containerMenu!=mc.player.inventoryMenu)throw new IllegalStateException("Close container before equipping");
            for(int i=9;i<36;i++)if(inv.getItem(i).isEmpty()){mc.gameMode.handleInventoryMouseClick(mc.player.inventoryMenu.containerId,i,inv.selected,ClickType.SWAP,mc.player);return;}
            throw new IllegalStateException("No free inventory slot for an empty hand");
        }
        Item it=item(id);
        for(int i=0;i<9;i++)if(inv.getItem(i).is(it)){inv.selected=i;return;}
        if(mc.player.containerMenu!=mc.player.inventoryMenu)throw new IllegalStateException("Close container before equipping");
        for(int i=9;i<36;i++)if(inv.getItem(i).is(it)){mc.gameMode.handleInventoryMouseClick(mc.player.inventoryMenu.containerId,i,inv.selected,ClickType.SWAP,mc.player);return;}
        throw new IllegalStateException("Item not in inventory: "+id);
    }
    /** Nearby living entities (not players' own body), nearest first: id, type, name, position, distance. Read-only. */
    private JsonObject entities(int radius, String type) {
        var list = mc.level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class, mc.player.getBoundingBox().inflate(radius),
                e -> e != mc.player && e.isAlive() && (type == null || BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString().contains(type)));
        list.sort(Comparator.comparingDouble(e -> e.distanceToSqr(mc.player)));
        JsonArray arr = new JsonArray();
        for (var e : list.subList(0, Math.min(64, list.size()))) {
            JsonObject o = new JsonObject();
            o.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString());
            o.addProperty("name", e.getName().getString());
            o.add("pos", coords(e.blockPosition()));
            o.addProperty("distance", Math.round(e.distanceTo(mc.player) * 10) / 10.0);
            if (e.isPassenger() && e.getVehicle() != null) o.addProperty("riding", BuiltInRegistries.ENTITY_TYPE.getKey(e.getVehicle().getType()).toString());
            arr.add(o);
        }
        JsonObject r = new JsonObject(); r.add("entities", arr); r.addProperty("count", list.size());
        // Items lying on the ground nearby, nearest first (thrown items that missed, drops to pick up).
        var drops = mc.level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, mc.player.getBoundingBox().inflate(Math.min(radius, 24)), net.minecraft.world.entity.Entity::isAlive);
        drops.sort(Comparator.comparingDouble(e -> e.distanceToSqr(mc.player)));
        JsonArray items = new JsonArray();
        for (var e : drops.subList(0, Math.min(32, drops.size()))) {
            JsonObject o = new JsonObject();
            o.addProperty("item", BuiltInRegistries.ITEM.getKey(e.getItem().getItem()).toString());
            o.addProperty("count", e.getItem().getCount());
            o.addProperty("x", Math.round(e.getX() * 10) / 10.0); o.addProperty("y", Math.round(e.getY() * 10) / 10.0); o.addProperty("z", Math.round(e.getZ() * 10) / 10.0);
            items.add(o);
        }
        r.add("ground_items", items);
        return r;
    }

    /** Throw `count` of an item like the Q key, optionally aimed at the top of a block (depot, saw, hopper...). */
    private JsonObject dropItem(String id, int count, BlockPos at) {
        if (mc.screen != null) throw new IllegalStateException("Close the current GUI first (mc_close_menu)");
        equip(id);
        if (!mc.player.getMainHandItem().is(item(id))) throw new IllegalStateException("Could not hold " + id);
        if (at != null) {
            Vec3 eye = mc.player.getEyePosition(), aim = Vec3.atCenterOf(at).add(0, 0.5, 0), d = aim.subtract(eye);
            if (d.length() > 4) throw new IllegalStateException("Stand within 4 blocks of " + at.toShortString() + " before dropping onto it");
            mc.player.setYRot((float) (Mth.atan2(d.z, d.x) * 180 / Math.PI) - 90);
            mc.player.setXRot((float) (-Mth.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)) * 180 / Math.PI));
            mc.player.connection.send(new net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.Rot(mc.player.getYRot(), mc.player.getXRot(), mc.player.onGround()));
        }
        int dropped = 0;
        for (int i = 0; i < count; i++) { if (mc.player.getMainHandItem().isEmpty()) break; mc.player.drop(false); dropped++; }
        mc.player.swing(InteractionHand.MAIN_HAND);
        JsonObject o = status(); o.addProperty("dropped", dropped); return o;
    }

    /** Right-click the nearest entity of a type with the held item (capture box, name tag, lead, ...), like a player. */
    private JsonObject useEntity(String type) {
        if (mc.screen != null) throw new IllegalStateException("Close the current GUI first (mc_close_menu)");
        double reach = mc.player.entityInteractionRange();
        var list = mc.level.getEntitiesOfClass(net.minecraft.world.entity.Entity.class, mc.player.getBoundingBox().inflate(reach + 1),
                e -> e != mc.player && e.isAlive() && BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString().contains(type));
        Vec3 eye = mc.player.getEyePosition();
        net.minecraft.world.entity.Entity target = null; double best = Double.MAX_VALUE;
        for (var e : list) { double d = e.getBoundingBox().distanceToSqr(eye); if (d <= reach * reach && d < best) { best = d; target = e; } }
        if (target == null) throw new IllegalStateException("No " + type + " within reach (" + Math.round(reach) + " blocks); walk closer (mc_entities shows where)");
        Vec3 aim = target.getBoundingBox().getCenter(), d = aim.subtract(eye);
        mc.player.setYRot((float) (Mth.atan2(d.z, d.x) * 180 / Math.PI) - 90);
        mc.player.setXRot((float) (-Mth.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)) * 180 / Math.PI));
        var hit = new net.minecraft.world.phys.EntityHitResult(target, target.getBoundingBox().clip(eye, aim.add(d.normalize())).orElse(aim));
        var res = mc.gameMode.interactAt(mc.player, target, hit, InteractionHand.MAIN_HAND);
        if (!res.consumesAction()) res = mc.gameMode.interact(mc.player, target, InteractionHand.MAIN_HAND);
        mc.player.swing(InteractionHand.MAIN_HAND);
        JsonObject o = status();
        o.addProperty("used_on", BuiltInRegistries.ENTITY_TYPE.getKey(target.getType()).toString() + " at " + target.blockPosition().toShortString());
        o.addProperty("result", res.toString());
        return o;
    }

    /** Right-click one specific face of a block (e.g. the top of a depot or an upward saw), like aiming the crosshair there. */
    void useBlockFace(BlockPos p, Direction face) {
        if (face == null) throw new IllegalArgumentException("face must be down/up/north/south/west/east");
        if(!mc.level.hasChunkAt(p))throw new IllegalStateException("Chunk not loaded");
        Vec3 eye=mc.player.getEyePosition();
        BlockHitResult chosen=null;
        for (double u : new double[]{0, -0.3, 0.3}) for (double v : new double[]{0, -0.3, 0.3}) {
            Vec3 c=Vec3.atCenterOf(p).add(Vec3.atLowerCornerOf(face.getNormal()).scale(0.45));
            Vec3 target=switch(face.getAxis()){case Y->c.add(u,0,v);case X->c.add(0,u,v);case Z->c.add(u,v,0);};
            if(eye.distanceTo(target)>mc.player.blockInteractionRange())continue;
            Vec3 d=target.subtract(eye);
            BlockHitResult hit=mc.level.clip(new ClipContext(eye,target.add(d.normalize().scale(0.6)),ClipContext.Block.OUTLINE,ClipContext.Fluid.NONE,mc.player));
            if(hit.getType()==HitResult.Type.BLOCK && hit.getBlockPos().equals(p) && hit.getDirection()==face){chosen=hit;break;}
        }
        if(chosen==null)throw new IllegalStateException("The "+face.getName()+" face of "+p.toShortString()+" is not visible from here; move so you can see it");
        Vec3 delta=chosen.getLocation().subtract(eye);mc.player.setYRot((float)(Mth.atan2(delta.z,delta.x)*180/Math.PI)-90);mc.player.setXRot((float)(-Mth.atan2(delta.y,Math.sqrt(delta.x*delta.x+delta.z*delta.z))*180/Math.PI));
        mc.player.connection.send(new net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.Rot(mc.player.getYRot(), mc.player.getXRot(), mc.player.onGround()));
        mc.gameMode.useItemOn(mc.player,InteractionHand.MAIN_HAND,chosen);mc.player.swing(InteractionHand.MAIN_HAND);
    }

    void useBlock(BlockPos p) {
        if(!mc.level.hasChunkAt(p))throw new IllegalStateException("Chunk not loaded");
        Vec3 eye=mc.player.getEyePosition();BlockHitResult chosen=null;
        for(Direction d:Direction.values()) {
            Vec3 target=Vec3.atCenterOf(p).add(Vec3.atLowerCornerOf(d.getNormal()).scale(0.499));
            if(eye.distanceTo(target)>mc.player.blockInteractionRange())continue;
            BlockHitResult hit=mc.level.clip(new ClipContext(eye,target,ClipContext.Block.OUTLINE,ClipContext.Fluid.NONE,mc.player));
            if(hit.getType()==HitResult.Type.BLOCK && hit.getBlockPos().equals(p)){chosen=hit;break;}
        }
        if(chosen==null)throw new IllegalStateException("Block not reachable or obstructed; move closer");
        Vec3 delta=chosen.getLocation().subtract(eye);mc.player.setYRot((float)(Mth.atan2(delta.z,delta.x)*180/Math.PI)-90);mc.player.setXRot((float)(-Mth.atan2(delta.y,Math.sqrt(delta.x*delta.x+delta.z*delta.z))*180/Math.PI));
        mc.gameMode.useItemOn(mc.player,InteractionHand.MAIN_HAND,chosen);mc.player.swing(InteractionHand.MAIN_HAND);
    }
    private JsonObject blueprintCheck(Blueprint b) {
        if(mc.player.blockPosition().distSqr(b.origin)>128*128)throw new IllegalArgumentException("Blueprint origin must be within 128 blocks");
        JsonArray conflicts=new JsonArray(),missing=new JsonArray();Map<String,Integer> required=new TreeMap<>();int matched=0,ownLeft=0;
        for(var e:b.blocks.entrySet()) {
            BlockPos p=b.origin.offset(e.getKey());if(mc.level.isOutsideBuildHeight(p)||!mc.level.hasChunkAt(p))throw new IllegalArgumentException("Blueprint outside build height or loaded chunks");
            BlockState current=mc.level.getBlockState(p),wanted=e.getValue();
            if(Blueprint.matches(current,wanted)){matched++;continue;}
            if(!current.canBeReplaced() && ownScaffold.contains(p) && isBuildScaffold(current)){ownLeft++;}
            else if(!current.canBeReplaced()){JsonObject c=coords(p);c.addProperty("block",BuiltInRegistries.BLOCK.getKey(current.getBlock()).toString());c.addProperty("wanted",BuiltInRegistries.BLOCK.getKey(wanted.getBlock()).toString());conflicts.add(c);}
            Item it=wanted.getBlock().asItem();if(it==Items.AIR)throw new IllegalArgumentException("No placeable item for "+wanted);
            required.merge(BuiltInRegistries.ITEM.getKey(it).toString(),1,Integer::sum);
        }
        JsonArray materials=new JsonArray();required.forEach((id,n)->{JsonObject row=new JsonObject();row.addProperty("item",id);row.addProperty("required",n);row.addProperty("available",count(id));materials.add(row);if(count(id)<n){JsonObject miss=row.deepCopy();miss.addProperty("shortfall",n-count(id));missing.add(miss);}});
        JsonObject o=new JsonObject();o.addProperty("total",b.blocks.size());o.addProperty("already_correct",matched);o.add("materials",materials);o.add("missing",missing);
        // Cap the list so a badly placed blueprint does not flood the model context.
        JsonArray shown=new JsonArray();for(int i=0;i<Math.min(40,conflicts.size());i++)shown.add(conflicts.get(i));
        o.add("conflicts",shown);o.addProperty("conflict_count",conflicts.size());
        if(ownLeft>0)o.addProperty("own_scaffold_to_clear",ownLeft);
        if (!b.skipped.isEmpty()) {
            JsonArray sk = new JsonArray(); int total = 0;
            for (var e : b.skipped.entrySet()) { total += e.getValue(); if (sk.size() < 40) { JsonObject row = new JsonObject(); row.addProperty("block", e.getKey()); row.addProperty("count", e.getValue()); sk.add(row); } }
            o.add("skipped_unsupported", sk); o.addProperty("skipped_count", total);
        }
        o.addProperty("scaffold_note","Scaffolding uses dirt/cobblestone/netherrack/cobbled_deepslate/stone that are NOT blueprint materials. Carry ~32 extra of one for tall builds.");
        return o;
    }
    private static final class Job {
        final String id=UUID.randomUUID().toString(),kind;
        String state="running",message="",item="";final long started=System.nanoTime(),deadline;
        List<String> items=List.of();
        int ticks,progress,initialCount,goalCount,radius,remaining,phaseTicks,phase,initialFood,attempts,lastProgressTick,deferred,cycles,matchedAtCycleStart;
        BlockPos target,stand;Blueprint blueprint;CraftingRecipe recipe;CraftTask craft;JsonObject detail;Block placeBlock;
        List<BlockPos> manual;Placement placement;JsonArray unplaced;
        JobTask task;Runnable restart;
        /** Stand positions from which a manual placement turned out impossible (not retried). */
        final Set<BlockPos> badStands=new HashSet<>();
        /** Unstick statistics: automatic jumps and re-planned paths. */
        int jumps,replans,escapes,graceUntil;
        int autoEatPhase,autoEatTicks,autoEatFood,autoEatCount,autoEatSlot=-1,autoEats;String autoEatItem,autoEatNote;boolean autoEatDisabled;
        Job(String k,int timeout){kind=k;deadline=started+timeout*1_000_000_000L;}
    }
}
