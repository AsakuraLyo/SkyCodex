package dev.skycodex;

import com.google.gson.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import java.lang.reflect.Method;
import java.util.*;

/**
 * Finds machines (block entities) in loaded chunks, groups neighbouring ones into production lines and, for an
 * item, tells which recipe types make it and which nearby machines perform those recipe types. Read-only.
 */
final class MachineScanner {
    private final Minecraft mc = Minecraft.getInstance();

    /** Which machine blocks perform a recipe type. Create's are listed explicitly; others fall back to the namespace. */
    private static final Map<String, List<String>> MACHINES_FOR_TYPE = Map.ofEntries(
            Map.entry("minecraft:smelting", List.of("minecraft:furnace")),
            Map.entry("minecraft:blasting", List.of("minecraft:blast_furnace")),
            Map.entry("minecraft:smoking", List.of("minecraft:smoker", "minecraft:campfire")),
            Map.entry("minecraft:campfire_cooking", List.of("minecraft:campfire", "minecraft:soul_campfire")),
            Map.entry("minecraft:stonecutting", List.of("minecraft:stonecutter")),
            Map.entry("minecraft:smithing", List.of("minecraft:smithing_table")),
            Map.entry("minecraft:crafting", List.of("minecraft:crafting_table", "create:mechanical_crafter")),
            Map.entry("create:pressing", List.of("create:mechanical_press")),
            Map.entry("create:compacting", List.of("create:mechanical_press", "create:basin")),
            Map.entry("create:mixing", List.of("create:mechanical_mixer", "create:basin")),
            Map.entry("create:crushing", List.of("create:crushing_wheel")),
            Map.entry("create:milling", List.of("create:millstone")),
            Map.entry("create:cutting", List.of("create:mechanical_saw")),
            Map.entry("create:deploying", List.of("create:deployer")),
            Map.entry("create:item_application", List.of("create:deployer")),
            Map.entry("create:filling", List.of("create:spout")),
            Map.entry("create:emptying", List.of("create:item_drain")),
            Map.entry("create:splashing", List.of("create:encased_fan")),
            Map.entry("create:haunting", List.of("create:encased_fan")),
            Map.entry("create:sandpaper_polishing", List.of("create:deployer")),
            Map.entry("create:mechanical_crafting", List.of("create:mechanical_crafter")),
            Map.entry("create:sequenced_assembly", List.of("create:deployer", "create:spout", "create:mechanical_press", "create:mechanical_saw")));

    private record Machine(BlockPos pos, String block, BlockEntity be) {}

    private List<Machine> machines(int radius, String filter) {
        BlockPos c = mc.player.blockPosition();
        int cr = (radius >> 4) + 1;
        List<Machine> out = new ArrayList<>();
        for (int cx = (c.getX() >> 4) - cr; cx <= (c.getX() >> 4) + cr; cx++)
            for (int cz = (c.getZ() >> 4) - cr; cz <= (c.getZ() >> 4) + cr; cz++) {
                if (!mc.level.hasChunk(cx, cz)) continue;
                LevelChunk chunk = mc.level.getChunk(cx, cz);
                for (var e : chunk.getBlockEntities().entrySet()) {
                    BlockPos p = e.getKey();
                    if (Math.abs(p.getX() - c.getX()) > radius || Math.abs(p.getZ() - c.getZ()) > radius || Math.abs(p.getY() - c.getY()) > radius) continue;
                    String id = BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(p).getBlock()).toString();
                    if (id.contains("sign") || id.contains("banner") || id.contains("skull") || id.contains("head") || id.contains("bed")) continue; // decoration
                    if (!filter.isEmpty() && !id.contains(filter)) continue;
                    out.add(new Machine(p.immutable(), id, e.getValue()));
                }
            }
        return out;
    }

    /** Speed of a Create kinetic block entity (reflection, so Create stays optional). */
    private static Float speed(BlockEntity be) {
        try { Method m = be.getClass().getMethod("getSpeed"); Object v = m.invoke(be); return v instanceof Float f ? f : null; } catch (Exception e) { return null; }
    }
    private JsonArray contents(Machine m) {
        JsonArray items = new JsonArray();
        try {
            if (m.be() instanceof Container c) {
                for (int i = 0; i < c.getContainerSize() && items.size() < 6; i++) add(items, c.getItem(i));
            } else {
                IItemHandler h = mc.level.getCapability(Capabilities.ItemHandler.BLOCK, m.pos(), null);
                if (h != null) for (int i = 0; i < h.getSlots() && items.size() < 6; i++) add(items, h.getStackInSlot(i));
            }
        } catch (Exception ignored) {}
        return items;
    }
    private static void add(JsonArray items, ItemStack s) { if (!s.isEmpty()) items.add(s.getCount() + "x " + BuiltInRegistries.ITEM.getKey(s.getItem())); }

    JsonObject scan(int radius, String filter, String forItem) {
        List<Machine> all = machines(radius, filter == null ? "" : filter);
        JsonObject out = new JsonObject();
        if (forItem != null) out.add("for_item", forItem(forItem, all));
        // Group machines that touch or are 2 blocks apart (shafts/belts between them are usually block entities too).
        Map<BlockPos, Machine> byPos = new HashMap<>(); for (Machine m : all) byPos.put(m.pos(), m);
        Set<BlockPos> seen = new HashSet<>(); List<List<Machine>> groups = new ArrayList<>();
        for (Machine m : all) {
            if (!seen.add(m.pos())) continue;
            List<Machine> g = new ArrayList<>(); Deque<Machine> q = new ArrayDeque<>(List.of(m));
            while (!q.isEmpty()) {
                Machine cur = q.poll(); g.add(cur);
                for (BlockPos n : BlockPos.betweenClosed(cur.pos().offset(-2, -2, -2), cur.pos().offset(2, 2, 2))) {
                    Machine nm = byPos.get(n); if (nm != null && seen.add(nm.pos())) q.add(nm);
                }
            }
            groups.add(g);
        }
        BlockPos me = mc.player.blockPosition();
        groups.sort(Comparator.comparingInt((List<Machine> g) -> -g.size()));
        JsonArray lines = new JsonArray();
        for (List<Machine> g : groups) {
            if (lines.size() >= 12) break;
            JsonObject line = new JsonObject();
            int minX = Integer.MAX_VALUE, minY = minX, minZ = minX, maxX = Integer.MIN_VALUE, maxY = maxX, maxZ = maxX;
            Map<String, Integer> counts = new TreeMap<>(); JsonArray detail = new JsonArray();
            boolean turning = false, stalled = false;
            for (Machine m : g) {
                minX = Math.min(minX, m.pos().getX()); minY = Math.min(minY, m.pos().getY()); minZ = Math.min(minZ, m.pos().getZ());
                maxX = Math.max(maxX, m.pos().getX()); maxY = Math.max(maxY, m.pos().getY()); maxZ = Math.max(maxZ, m.pos().getZ());
                counts.merge(m.block(), 1, Integer::sum);
                Float sp = speed(m.be());
                if (sp != null) { if (sp != 0) turning = true; else stalled = true; }
                boolean interesting = !m.block().matches(".*(shaft|cogwheel|gearbox|belt|chain_drive|encased|pipe|glue|casing).*");
                if (interesting && detail.size() < 20) {
                    JsonObject d = Controller.coords(m.pos()); d.addProperty("block", m.block());
                    if (sp != null) d.addProperty("speed_rpm", sp);
                    JsonArray items = contents(m); if (!items.isEmpty()) d.add("contents", items);
                    detail.add(d);
                }
            }
            line.add("from", Controller.coords(new BlockPos(minX, minY, minZ)));
            line.add("to", Controller.coords(new BlockPos(maxX, maxY, maxZ)));
            line.addProperty("distance", (int) Math.sqrt(new BlockPos((minX + maxX) / 2, (minY + maxY) / 2, (minZ + maxZ) / 2).distSqr(me)));
            JsonObject cj = new JsonObject(); counts.forEach(cj::addProperty); line.add("blocks", cj);
            if (turning || stalled) line.addProperty("kinetics", turning ? (stalled ? "partly turning" : "turning") : "not turning (no power or overstressed)");
            line.add("machines", detail);
            lines.add(line);
        }
        out.add("production_lines", lines);
        out.addProperty("machines_found", all.size());
        out.addProperty("note", "Loaded chunks only. Contents are what the client can see (Create syncs most). Grouping is by distance, not by verified belts/shafts.");
        return out;
    }

    private JsonObject forItem(String itemId, List<Machine> all) {
        ResourceLocation id = ResourceLocation.parse(itemId);
        if (!BuiltInRegistries.ITEM.containsKey(id)) throw new IllegalArgumentException("Unknown item " + itemId);
        JsonObject o = new JsonObject();
        JsonArray recipes = new JsonArray(); Set<String> types = new LinkedHashSet<>();
        for (RecipeHolder<?> h : mc.level.getRecipeManager().getRecipes()) {
            ItemStack out = h.value().getResultItem(mc.level.registryAccess());
            if (out.isEmpty() || !BuiltInRegistries.ITEM.getKey(out.getItem()).equals(id)) continue;
            String type = String.valueOf(BuiltInRegistries.RECIPE_TYPE.getKey(h.value().getType()));
            types.add(type);
            if (recipes.size() < 12) {
                JsonObject r = new JsonObject(); r.addProperty("id", h.id().toString()); r.addProperty("type", type);
                JsonArray ins = new JsonArray();
                for (Ingredient in : h.value().getIngredients()) { ItemStack[] opts = in.getItems(); if (opts.length > 0) ins.add(BuiltInRegistries.ITEM.getKey(opts[0].getItem()) + (opts.length > 1 ? " (or " + (opts.length - 1) + " more)" : "")); }
                r.add("ingredients", ins); recipes.add(r);
            }
        }
        o.add("recipes", recipes);
        JsonArray nearby = new JsonArray();
        BlockPos me = mc.player.blockPosition();
        for (String type : types) {
            List<String> blocks = MACHINES_FOR_TYPE.getOrDefault(type, List.of());
            String ns = type.contains(":") ? type.substring(0, type.indexOf(':')) : "";
            JsonObject t = new JsonObject(); t.addProperty("recipe_type", type);
            JsonArray mach = new JsonArray();
            all.stream().filter(m -> blocks.contains(m.block()) || blocks.isEmpty() && !ns.equals("minecraft") && m.block().startsWith(ns + ":"))
                    .sorted(Comparator.comparingDouble(m -> m.pos().distSqr(me))).limit(5)
                    .forEach(m -> { JsonObject j = Controller.coords(m.pos()); j.addProperty("block", m.block()); Float sp = speed(m.be()); if (sp != null) j.addProperty("speed_rpm", sp); mach.add(j); });
            t.add("machines_nearby", mach);
            if (!blocks.isEmpty()) t.addProperty("needs", String.join(" + ", blocks));
            nearby.add(t);
        }
        o.add("by_recipe_type", nearby);
        if (types.isEmpty()) o.addProperty("note", "No recipe outputs this item (it may be a drop, loot or fluid product)");
        return o;
    }
}
