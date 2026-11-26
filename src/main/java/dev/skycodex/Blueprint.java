package dev.skycodex;

import baritone.api.schematic.ISchematic;
import com.google.gson.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;
import java.util.*;

/** Sparse blueprint: unspecified cells are never cleared or modified. */
final class Blueprint implements ISchematic {
    /**
     * Properties the game recomputes from neighbours after placement (fence/pane/wall connections, stair corner shape,
     * leaf distance, ...). A blueprint cannot force them, so both verification and Baritone ignore them.
     */
    static final List<String> DERIVED_PROPERTIES = List.of(
            "north", "south", "east", "west", "up", "down", "shape", "waterlogged",
            "distance", "persistent", "snowy", "in_wall", "powered", "attached", "occupied",
            // change over time after placement (crops, cake)
            "age", "stage", "bites");
    /** Boolean properties a placement does control (lantern hanging from the clicked face). */
    private static final Set<String> CONTROLLABLE_BOOLEANS = Set.of("hanging");

    /**
     * True when the builder cannot choose this property: neighbour connections and other game-maintained state.
     * Besides the vanilla names above, every boolean property counts (modded connections such as Create girder
     * top/bottom/x/z, lit, open, powered, ...), except the few that the clicked face decides.
     */
    static boolean derived(Property<?> p) {
        if (DERIVED_PROPERTIES.contains(p.getName())) return true;
        return p instanceof net.minecraft.world.level.block.state.properties.BooleanProperty && !CONTROLLABLE_BOOLEANS.contains(p.getName());
    }

    /** Property names Baritone must ignore for this blueprint (Baritone matches ignored properties by name). */
    List<String> ignoredPropertyNames() {
        Set<String> out = new TreeSet<>(DERIVED_PROPERTIES);
        for (BlockState s : blocks.values()) for (Property<?> p : s.getProperties()) if (derived(p)) out.add(p.getName());
        return new ArrayList<>(out);
    }

    final Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
    final BlockPos origin;
    int width, height, length;

    /** Unsupported cells left out because skip_unsupported was set: "block id: reason" -> count. */
    final Map<String, Integer> skipped = new TreeMap<>();

    Blueprint(JsonObject args) {
        origin = Controller.pos(args.getAsJsonObject("origin"));
        boolean skip = args.has("skip_unsupported") && args.get("skip_unsupported").getAsBoolean();
        JsonArray cells = args.getAsJsonArray("blocks");
        if (cells == null || cells.isEmpty() || cells.size() > 20000) throw new IllegalArgumentException("Blueprint requires 1..20000 blocks");
        Map<String, Integer> problems = new TreeMap<>();
        for (JsonElement cell : cells) {
            JsonObject c = cell.getAsJsonObject();
            BlockPos p = Controller.pos(c);
            if (p.getX() < 0 || p.getY() < 0 || p.getZ() < 0 || p.getX() >= 64 || p.getY() >= 64 || p.getZ() >= 64) throw new IllegalArgumentException("Relative blueprint coordinates must be 0..63");
            String raw = Controller.str(c, "block");
            String problem;
            BlockState state = null;
            try { state = parse(raw, c); problem = unsupported(state); }
            catch (IllegalArgumentException e) { problem = e.getMessage(); }
            if (problem != null) { problems.merge(raw + ": " + problem, 1, Integer::sum); continue; }
            add(p, state);
        }
        if (!problems.isEmpty()) {
            if (!skip) {
                StringBuilder sb = new StringBuilder("Blueprint has unsupported cells (pass skip_unsupported=true to build the rest): ");
                int shown = 0;
                for (var e : problems.entrySet()) { if (shown++ == 25) { sb.append(" ... ").append(problems.size() - 25).append(" more kinds"); break; } sb.append(e.getKey()).append(" x").append(e.getValue()).append("; "); }
                throw new IllegalArgumentException(sb.toString());
            }
            skipped.putAll(problems);
        }
        if (blocks.isEmpty()) throw new IllegalArgumentException("Blueprint has no supported blocks left");
    }

    private static BlockState parse(String raw, JsonObject c) {
        ResourceLocation id = ResourceLocation.tryParse(raw);
        if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) throw new IllegalArgumentException("unknown block in this modpack");
        BlockState state = BuiltInRegistries.BLOCK.get(id).defaultBlockState();
        if (c.has("properties")) for (var entry : c.getAsJsonObject("properties").entrySet()) {
            Property<?> property = state.getBlock().getStateDefinition().getProperty(entry.getKey());
            if (property == null) throw new IllegalArgumentException("unknown property " + entry.getKey());
            state = set(state, property, entry.getValue().getAsString());
        }
        return state;
    }

    /** The block's default state with the given properties (facing/axis/half ...) applied; used by mc_place. */
    static BlockState withProperties(BlockState state, JsonObject props) {
        for (var entry : props.entrySet()) {
            Property<?> property = state.getBlock().getStateDefinition().getProperty(entry.getKey());
            if (property == null) throw new IllegalArgumentException("Block " + BuiltInRegistries.BLOCK.getKey(state.getBlock()) + " has no property " + entry.getKey() + "; it has " + state.getProperties().stream().map(Property::getName).toList());
            state = set(state, property, entry.getValue().getAsString());
        }
        return state;
    }

    /** All properties of a state as strings, for mc_scan. */
    static JsonObject propertiesOf(BlockState state) {
        JsonObject o = new JsonObject();
        for (Property<?> p : state.getProperties()) o.addProperty(p.getName(), valueName(state, p));
        return o;
    }
    private static <T extends Comparable<T>> String valueName(BlockState s, Property<T> p) { return p.getName(s.getValue(p)); }

    /** Why the beta builder cannot place this state, or null when it can. */
    private static String unsupported(BlockState state) {
        if (state.isAir()) return "air";
        if (state.hasBlockEntity()) return "block entity (machine/container; place separately with mc_place)";
        if (!state.getFluidState().isEmpty()) return "fluid or waterlogged";
        if (state.getBlock() instanceof DoorBlock) return "door";
        if (state.hasProperty(BlockStateProperties.SLAB_TYPE) && state.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.DOUBLE) return "double slab";
        if (state.getBlock().asItem() == Items.AIR) return "no item places this block";
        if (state.getDestroySpeed(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO) < 0) return "unbreakable/creative-only block";
        return null;
    }

    /** One-cell schematic used by mc_place; block entities (furnace, chest, table) are allowed here. */
    private Blueprint(BlockPos origin, BlockState state) { this.origin = origin; add(BlockPos.ZERO, state); }
    static Blueprint single(BlockPos at, BlockState state) { return new Blueprint(at, state); }

    private void add(BlockPos p, BlockState state) {
        if (blocks.putIfAbsent(p, state) != null) throw new IllegalArgumentException("Duplicate blueprint position " + p);
        width = Math.max(width, p.getX() + 1); height = Math.max(height, p.getY() + 1); length = Math.max(length, p.getZ() + 1);
    }

    /** Same block and same value for every property that a builder can actually control. */
    static boolean matches(BlockState current, BlockState wanted) {
        if (current.getBlock() != wanted.getBlock()) return false;
        for (Property<?> p : wanted.getProperties()) {
            if (derived(p)) continue;
            if (!Objects.equals(current.getValue(p), wanted.getValue(p))) return false;
        }
        return true;
    }

    int countMatched(Level level) {
        int n = 0;
        for (var e : blocks.entrySet()) if (matches(level.getBlockState(origin.offset(e.getKey())), e.getValue())) n++;
        return n;
    }

    Set<Item> materials() {
        Set<Item> out = new HashSet<>();
        for (BlockState s : blocks.values()) out.add(s.getBlock().asItem());
        return out;
    }

    private static <T extends Comparable<T>> BlockState set(BlockState state, Property<T> p, String v) {
        return state.setValue(p, p.getValue(v).orElseThrow(() -> new IllegalArgumentException("invalid value " + p.getName() + "=" + v)));
    }
    /** Relative cells hidden from Baritone because no placement can produce them right now (reported to the user). */
    final Set<BlockPos> excluded = new HashSet<>();
    @Override public boolean inSchematic(int x, int y, int z, BlockState current) { BlockPos p = new BlockPos(x,y,z); return blocks.containsKey(p) && !excluded.contains(p); }
    @Override public BlockState desiredState(int x, int y, int z, BlockState current, List<BlockState> available) { return blocks.getOrDefault(new BlockPos(x,y,z), current); }
    @Override public int widthX() { return width; }
    @Override public int heightY() { return height; }
    @Override public int lengthZ() { return length; }
}
