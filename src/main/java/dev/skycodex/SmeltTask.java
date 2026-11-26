package dev.skycodex;

import baritone.api.pathing.goals.GoalNear;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/**
 * Operates a vanilla-style furnace, smoker or blast furnace with ordinary slot clicks: puts in the input and enough
 * fuel, waits with the menu open and takes the output until the requested amount is collected. With collect_only it
 * only takes finished output. Modded machines are not handled here.
 */
final class SmeltTask implements JobTask {
    private static final int SLOT_IN = 0, SLOT_FUEL = 1, SLOT_OUT = 2;
    private final Controller c;
    private final Minecraft mc;
    private final BlockPos pos;
    private final Item input, fuelWanted;
    private final int count;
    private final boolean collectOnly, waitForOutput;
    private final Map<Item, Integer> before;
    private AbstractFurnaceMenu menu;
    private Item output;
    private int phase, ticks, delay, collected, idleTicks;
    private final Deque<int[]> clicks = new ArrayDeque<>();
    private String note = "";

    SmeltTask(Controller c, BlockPos pos, String inputId, int count, String fuelId, boolean collectOnly, boolean waitForOutput) {
        this.c = c; this.mc = c.mc; this.pos = pos; this.count = count; this.collectOnly = collectOnly; this.waitForOutput = waitForOutput;
        this.input = inputId == null ? null : c.item(inputId);
        this.fuelWanted = fuelId == null ? null : c.item(fuelId);
        if (!collectOnly && input == null) throw new IllegalArgumentException("item (the thing to smelt) is required unless collect_only=true");
        if (!collectOnly && c.count(inputId) < count) throw new IllegalStateException("Only " + c.count(inputId) + " " + inputId + " in inventory");
        this.before = c.snapshot();
    }

    private boolean inReach() { return mc.player.getEyePosition().distanceTo(Vec3.atCenterOf(pos)) <= mc.player.blockInteractionRange() - 0.6; }

    @SuppressWarnings("unchecked")
    private Optional<ItemStack> result(ItemStack in) {
        RecipeType<? extends AbstractCookingRecipe> type = menu instanceof SmokerMenu ? RecipeType.SMOKING : menu instanceof BlastFurnaceMenu ? RecipeType.BLASTING : RecipeType.SMELTING;
        return mc.level.getRecipeManager().getRecipeFor((RecipeType<AbstractCookingRecipe>) type, new SingleRecipeInput(in), mc.level)
                .map(h -> h.value().getResultItem(mc.level.registryAccess()));
    }
    private RecipeType<?> recipeType() { return menu instanceof SmokerMenu ? RecipeType.SMOKING : menu instanceof BlastFurnaceMenu ? RecipeType.BLASTING : RecipeType.SMELTING; }

    /** Menu slot index of the player's inventory slot holding the most of this item, or -1. */
    private int playerSlotWith(Item item) {
        int best = -1, bestCount = 0;
        for (int i = 3; i < menu.slots.size(); i++) {
            Slot s = menu.getSlot(i);
            if (s.container == mc.player.getInventory() && s.getItem().is(item) && s.getItem().getCount() > bestCount) { best = i; bestCount = s.getItem().getCount(); }
        }
        return best;
    }
    /** Queue clicks moving exactly n of item into the target furnace slot (fewer if the inventory runs out). */
    private int queueMove(Item item, int n, int target) {
        int moved = 0;
        Map<Integer, Integer> left = new HashMap<>();
        for (int i = 3; i < menu.slots.size(); i++) { Slot s = menu.getSlot(i); if (s.container == mc.player.getInventory() && s.getItem().is(item)) left.put(i, s.getItem().getCount()); }
        for (var e : left.entrySet()) {
            if (moved >= n) break;
            int take = Math.min(e.getValue(), n - moved);
            clicks.add(new int[]{e.getKey(), 0});                     // pick up the stack
            if (take == e.getValue()) clicks.add(new int[]{target, 0}); // put all
            else { for (int k = 0; k < take; k++) clicks.add(new int[]{target, 1}); clicks.add(new int[]{e.getKey(), 0}); } // one by one, rest back
            moved += take;
        }
        return moved;
    }
    private void click(int slot, int button, ClickType type) { mc.gameMode.handleInventoryMouseClick(menu.containerId, slot, button, type, mc.player); delay = 4; }

    private Item chooseFuel() {
        if (fuelWanted != null) return fuelWanted;
        Item best = null; int bestScore = Integer.MAX_VALUE;
        for (ItemStack s : mc.player.getInventory().items) {
            if (s.isEmpty() || s.is(input) || s.getBurnTime(recipeType()) <= 0 || s.is(Items.LAVA_BUCKET)) continue;
            // Prefer coal/charcoal, then other fuel; never burn a container item.
            int score = s.is(Items.COAL) || s.is(Items.CHARCOAL) ? 0 : s.getItem().hasCraftingRemainingItem() ? 1000 : 100 - Math.min(99, s.getBurnTime(recipeType()) / 100);
            if (score < bestScore) { bestScore = score; best = s.getItem(); }
        }
        return best;
    }

    @Override public boolean tick() {
        if (delay > 0) { delay--; return false; }
        ticks++;
        switch (phase) {
            case 0 -> { // walk within reach
                if (inReach()) { c.bot().getPathingBehavior().cancelEverything(); phase = 1; ticks = 0; }
                else if (ticks == 1 || ticks % 100 == 0) c.bot().getCustomGoalProcess().setGoalAndPath(new GoalNear(pos, 2));
                else if (ticks > 400) throw new IllegalStateException("Could not walk within reach of the furnace");
            }
            case 1 -> { c.useBlock(pos); phase = 2; ticks = 0; }
            case 2 -> { // menu open?
                if (mc.player.containerMenu instanceof AbstractFurnaceMenu m) { menu = m; phase = 3; delay = 4; }
                else if (ticks > 30) throw new IllegalStateException("That block did not open a furnace/smoker/blast furnace menu");
            }
            case 3 -> { // plan
                checkMenu();
                if (!menu.getSlot(SLOT_OUT).getItem().isEmpty()) { click(SLOT_OUT, 0, ClickType.QUICK_MOVE); return false; } // old output first
                if (collectOnly) { phase = 5; return false; }
                ItemStack in = menu.getSlot(SLOT_IN).getItem();
                if (!in.isEmpty() && !in.is(input)) throw new IllegalStateException("Furnace input already holds " + BuiltInRegistries.ITEM.getKey(in.getItem()) + "; collect it first or use another furnace");
                output = result(new ItemStack(input)).map(ItemStack::getItem).orElseThrow(() -> new IllegalStateException(BuiltInRegistries.ITEM.getKey(input) + " has no recipe in this furnace type"));
                int room = input.getDefaultMaxStackSize() - in.getCount();
                int n = Math.min(count, room);
                if (n < count) note = " (furnace input slot only had room for " + n + ")";
                queueMove(input, n, SLOT_IN);
                // Fuel: every item needs 200 burn units in all three furnace types.
                int totalItems = in.getCount() + n;
                ItemStack fuelNow = menu.getSlot(SLOT_FUEL).getItem();
                Item fuel = fuelNow.isEmpty() ? chooseFuel() : fuelNow.getItem();
                if (fuel == null) throw new IllegalStateException("No fuel in inventory (coal, charcoal, planks, logs ...)");
                int burn = new ItemStack(fuel).getBurnTime(recipeType());
                if (burn <= 0) throw new IllegalStateException(BuiltInRegistries.ITEM.getKey(fuel) + " is not fuel");
                int need = (int)Math.ceil(totalItems * 200.0 / burn) - fuelNow.getCount() - (menu.isLit() ? 1 : 0);
                if (need > 0) {
                    int moved = queueMove(fuel, Math.min(need, fuel.getDefaultMaxStackSize() - fuelNow.getCount()), SLOT_FUEL);
                    if (moved < need) note += " (only " + moved + " of " + need + " fuel available; output may stop early)";
                }
                phase = 4;
            }
            case 4 -> { // execute clicks
                checkMenu();
                int[] k = clicks.poll();
                if (k != null) { click(k[0], k[1], ClickType.PICKUP); return false; }
                if (!menu.getCarried().isEmpty()) throw new IllegalStateException("Cursor still holds an item after filling the furnace; inspect mc_menu");
                if (!waitForOutput) return true;
                phase = 5; ticks = 0;
            }
            case 5 -> { // wait and collect
                checkMenu();
                ItemStack out = menu.getSlot(SLOT_OUT).getItem();
                if (!out.isEmpty()) {
                    if (output == null) output = out.getItem();
                    collected += out.getCount();
                    click(SLOT_OUT, 0, ClickType.QUICK_MOVE); idleTicks = 0; return false;
                }
                if (collectOnly) return true;
                if (collected >= count) return true;
                boolean working = menu.isLit() && !menu.getSlot(SLOT_IN).getItem().isEmpty();
                if (!working && ++idleTicks > 60) { note += " (furnace stopped: input or fuel ran out)"; return true; }
            }
        }
        return false;
    }

    private void checkMenu() { if (mc.player.containerMenu != menu) throw new IllegalStateException("Furnace menu was closed"); }

    @Override public void cleanup() { if (mc.player != null && mc.player.containerMenu instanceof AbstractFurnaceMenu) mc.player.closeContainer(); }

    @Override public String message() {
        String got = c.gained(before);
        if (!waitForOutput && !collectOnly) return "Furnace filled" + note + "; it keeps working with the menu closed. Come back later with collect_only=true";
        return "Collected " + collected + (output != null ? " " + BuiltInRegistries.ITEM.getKey(output) : "") + note + "; inventory " + got;
    }
}
