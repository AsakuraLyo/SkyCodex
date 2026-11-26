package dev.skycodex;

import baritone.api.pathing.goals.GoalNear;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/**
 * Moves items between the player and a container (chest, barrel, schematicannon, ...) with ordinary clicks:
 * deposit = player -> container, withdraw = container -> player. `count` limits each item type (default: all).
 * Exact counts use pick-up + right-click; whole stacks use shift-click so the container's own slot rules apply.
 */
final class ContainerTask implements JobTask {
    private final Controller c;
    private final Minecraft mc;
    private final BlockPos pos;
    private final boolean deposit;
    private final List<Item> items; // empty = every item (deposit only with all_items)
    private final int limit;
    private final Map<Item, Integer> before;
    private final Map<Item, Integer> moved = new LinkedHashMap<>();
    private AbstractContainerMenu menu;
    private int phase, ticks, delay, itemIndex, stuck;
    private final Deque<int[]> clicks = new ArrayDeque<>(); // {slot, button, quickMove?1:0}
    private String note = "";

    ContainerTask(Controller c, BlockPos pos, boolean deposit, List<Item> items, int limit) {
        this.c = c; this.mc = c.mc; this.pos = pos; this.deposit = deposit; this.items = items; this.limit = limit;
        this.before = c.snapshot();
    }

    private boolean inReach() { return mc.player.getEyePosition().distanceTo(Vec3.atCenterOf(pos)) <= mc.player.blockInteractionRange() - 0.6; }
    private boolean isPlayerSlot(Slot s) { return s.container == mc.player.getInventory(); }
    private boolean isSource(Slot s) { return deposit ? isPlayerSlot(s) && s.getContainerSlot() < 36 : !isPlayerSlot(s); }
    private boolean isDest(Slot s) { return deposit ? !isPlayerSlot(s) : isPlayerSlot(s) && s.getContainerSlot() < 36; }
    private void click(int slot, int button, ClickType type) { mc.gameMode.handleInventoryMouseClick(menu.containerId, slot, button, type, mc.player); delay = 3; }

    /** Plan clicks that move up to `want` of item from source slots into destination slots. */
    private void plan(Item item, int want) {
        int planned = 0;
        for (int i = 0; i < menu.slots.size() && planned < want; i++) {
            Slot src = menu.getSlot(i);
            if (!isSource(src) || !src.getItem().is(item) || !src.mayPickup(mc.player)) continue;
            int n = src.getItem().getCount();
            if (planned + n <= want) { clicks.add(new int[]{i, 0, 1}); planned += n; continue; }
            // Partial stack: pick it up, right-click single items into a destination slot that accepts them, put the rest back.
            int need = want - planned;
            Slot dest = null;
            for (int j = 0; j < menu.slots.size(); j++) {
                Slot d = menu.getSlot(j);
                if (!isDest(d) || !d.mayPlace(src.getItem())) continue;
                ItemStack ds = d.getItem();
                if (ds.isEmpty() || ItemStack.isSameItemSameComponents(ds, src.getItem()) && ds.getCount() + need <= Math.min(ds.getMaxStackSize(), d.getMaxStackSize())) { dest = d; break; }
            }
            if (dest == null) { note += " (no room for a partial stack of " + BuiltInRegistries.ITEM.getKey(item) + ")"; break; }
            clicks.add(new int[]{i, 0, 0});
            for (int k = 0; k < need; k++) clicks.add(new int[]{dest.index, 1, 0});
            clicks.add(new int[]{i, 0, 0});
            planned = want;
        }
    }

    private List<Item> wantedItems() {
        if (!items.isEmpty()) return items;
        Set<Item> all = new LinkedHashSet<>();
        for (Slot s : menu.slots) if (isSource(s) && !s.getItem().isEmpty()) all.add(s.getItem().getItem());
        return new ArrayList<>(all);
    }

    @Override public boolean tick() {
        if (delay > 0) { delay--; return false; }
        ticks++;
        switch (phase) {
            case 0 -> {
                if (inReach()) { c.bot().getPathingBehavior().cancelEverything(); phase = 1; ticks = 0; }
                else if (ticks == 1 || ticks % 100 == 0) c.bot().getCustomGoalProcess().setGoalAndPath(new GoalNear(pos, 2));
                else if (ticks > 400) throw new IllegalStateException("Could not walk within reach of the container");
            }
            case 1 -> { c.useBlock(pos); phase = 2; ticks = 0; }
            case 2 -> {
                AbstractContainerMenu m = mc.player.containerMenu;
                if (m != mc.player.inventoryMenu && m.slots.stream().anyMatch(s -> !isPlayerSlot(s))) { menu = m; phase = 3; delay = 4; }
                else if (ticks > 30) throw new IllegalStateException("That block did not open a container menu (Create item vaults and some machines have none; use a chest or funnel next to them)");
            }
            case 3 -> { // next item type
                if (mc.player.containerMenu != menu) throw new IllegalStateException("Container closed");
                List<Item> list = wantedItems();
                if (itemIndex >= list.size()) return true;
                Item item = list.get(itemIndex++);
                int have = 0; for (Slot s : menu.slots) if (isSource(s) && s.getItem().is(item)) have += s.getItem().getCount();
                if (have == 0) { note += " (" + BuiltInRegistries.ITEM.getKey(item) + ": none " + (deposit ? "in inventory" : "in container") + ")"; return false; }
                plan(item, Math.min(have, limit));
                phase = 4; stuck = 0;
            }
            case 4 -> {
                if (mc.player.containerMenu != menu) throw new IllegalStateException("Container closed");
                int[] k = clicks.poll();
                if (k != null) { click(k[0], k[1], k[2] == 1 ? ClickType.QUICK_MOVE : ClickType.PICKUP); return false; }
                if (!menu.getCarried().isEmpty()) {
                    // Destination filled up mid-way: put the cursor stack back into any source slot.
                    for (int i = 0; i < menu.slots.size(); i++) { Slot s = menu.getSlot(i); if (isSource(s) && (s.getItem().isEmpty() || ItemStack.isSameItemSameComponents(s.getItem(), menu.getCarried()))) { click(i, 0, ClickType.PICKUP); break; } }
                    if (++stuck > 5) throw new IllegalStateException("Cursor still holds an item; inspect mc_menu");
                    return false;
                }
                phase = 3;
            }
        }
        return false;
    }

    @Override public void cleanup() { if (mc.player != null && menu != null && mc.player.containerMenu == menu) mc.player.closeContainer(); }

    @Override public String message() {
        Map<Item, Integer> now = c.snapshot(); List<String> out = new ArrayList<>();
        Set<Item> keys = new LinkedHashSet<>(before.keySet()); keys.addAll(now.keySet());
        for (Item it : keys) { int d = now.getOrDefault(it, 0) - before.getOrDefault(it, 0); if (deposit ? d < 0 : d > 0) out.add(Math.abs(d) + " " + BuiltInRegistries.ITEM.getKey(it)); }
        return (deposit ? "Deposited " : "Withdrew ") + (out.isEmpty() ? "nothing" : String.join(", ", out)) + note;
    }
}
