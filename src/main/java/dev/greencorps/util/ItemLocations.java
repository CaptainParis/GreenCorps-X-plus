package dev.greencorps.util;

import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.block.entity.BeehiveBlockEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.BundleContentsComponent;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.component.type.LodestoneTrackerComponent;
import net.minecraft.component.type.MapDecorationsComponent;
import net.minecraft.component.type.MapIdComponent;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.GlobalPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Reads location-bearing components from an item and formats tooltip lines.
 * Bee {@code hive_pos} is kept when present, but vanilla strips it as a bee
 * enters a hive. {@code flower_pos} is the coordinate that survives.
 */
public final class ItemLocations {
	public static final int MAX_DEPTH = 3;

	public record Options(
		boolean nested,
		int maxLines,
		boolean showDistance,
		boolean showOrigin,
		int minHighlightDistance
	) {
		public static Options defaults() {
			return new Options(true, 10, true, true, 0);
		}
	}

	/** Player position used for distance. Null skips distance and the highlight range filter. */
	public record View(String dimension, double x, double y, double z) {}

	record Fact(String text, int[] pos, String dimension) {}

	private ItemLocations() {}

	/** Cheap gate before any NBT copy. Dirt and sticks never reach collect. */
	public static boolean mayCarry(ItemStack stack) {
		if (stack == null || stack.isEmpty()) return false;
		Item item = stack.getItem();
		if (item == Items.BEEHIVE || item == Items.BEE_NEST
			|| item == Items.COMPASS || item == Items.FILLED_MAP || item == Items.BUNDLE) {
			return true;
		}
		if (item instanceof BlockItem blockItem && blockItem.getBlock() instanceof ShulkerBoxBlock) {
			return true;
		}
		return stack.contains(DataComponentTypes.BEES)
			|| stack.contains(DataComponentTypes.LODESTONE_TRACKER)
			|| stack.contains(DataComponentTypes.MAP_ID)
			|| stack.contains(DataComponentTypes.MAP_DECORATIONS)
			|| stack.contains(DataComponentTypes.BLOCK_ENTITY_DATA)
			|| stack.contains(DataComponentTypes.CONTAINER)
			|| stack.contains(DataComponentTypes.BUNDLE_CONTENTS);
	}

	public static List<String> lines(ItemStack stack, MapCenterCache centers, Options options, View view) {
		if (stack == null || stack.isEmpty() || options == null || !mayCarry(stack)) return List.of();
		List<Fact> facts = new ArrayList<>();
		collect(stack, "", 0, facts, centers, options);
		return cap(render(facts, options, view), options.maxLines);
	}

	public static boolean hasHighlight(ItemStack stack, MapCenterCache centers, Options options, View view) {
		if (stack == null || stack.isEmpty() || options == null || !mayCarry(stack)) return false;
		List<Fact> facts = new ArrayList<>();
		collect(stack, "", 0, facts, centers, options);
		return anyHighlight(facts, view, options.minHighlightDistance);
	}

	private static void collect(
		ItemStack stack,
		String prefix,
		int depth,
		List<Fact> out,
		MapCenterCache centers,
		Options options
	) {
		if (stack == null || stack.isEmpty() || depth > MAX_DEPTH) return;

		LodestoneTrackerComponent lodestone = stack.get(DataComponentTypes.LODESTONE_TRACKER);
		if (lodestone != null) {
			Optional<GlobalPos> target = lodestone.target();
			if (target.isPresent()) {
				GlobalPos pos = target.get();
				BlockPos block = pos.pos();
				String dim = pos.dimension().getValue().toString();
				add(out, prefix, ItemLocationFacts.lodestone(
					dim, block.getX(), block.getY(), block.getZ(), lodestone.tracked()),
					new int[]{block.getX(), block.getY(), block.getZ()}, dim);
			}
			// Spinning / no-target compasses: no location line.
		}

		boolean wroteMapDest = appendMapDecorations(stack, prefix, out);
		MapIdComponent mapId = stack.get(DataComponentTypes.MAP_ID);
		if (mapId != null && !wroteMapDest) {
			MapCenterCache.Center center = centers != null ? centers.get(mapId.id()) : null;
			if (center != null) {
				// Learned on-canvas — show text; tint uses pos.
				add(out, prefix, ItemLocationFacts.mapLearnedView(
					mapId.id(), center.x, center.z, center.dimension),
					new int[]{center.x, 0, center.z}, center.dimension);
			}
			// Untagged mapart / canvas-only: no location line.
		}

		if (isHive(stack)) {
			appendHive(stack, prefix, out, options);
		}

		NbtComponent blockEntity = stack.get(DataComponentTypes.BLOCK_ENTITY_DATA);
		if (blockEntity != null && !blockEntity.isEmpty()) {
			appendBlockEntity(blockEntity.copyNbt(), prefix, out);
		}

		if (options.nested && depth < MAX_DEPTH) {
			walkNested(stack, child -> collect(child, nestedPrefix(child), depth + 1, out, centers, options));
		}
	}

	private static boolean appendMapDecorations(ItemStack stack, String prefix, List<Fact> out) {
		MapDecorationsComponent decorations = stack.get(DataComponentTypes.MAP_DECORATIONS);
		if (decorations == null || decorations.decorations().isEmpty()) return false;
		boolean wrote = false;
		for (MapDecorationsComponent.Decoration decoration : decorations.decorations().values()) {
			String path = decorationPath(decoration.type());
			if (ItemLocationFacts.isTransientMapMark(path)) continue;
			int x = (int) Math.round(decoration.x());
			int z = (int) Math.round(decoration.z());
			add(out, prefix, ItemLocationFacts.mapDecoration(path, x, z), new int[]{x, 0, z}, null);
			wrote = true;
		}
		return wrote;
	}

	private static void appendHive(ItemStack stack, String prefix, List<Fact> out, Options options) {
		List<BeehiveBlockEntity.BeeData> bees = stack.get(DataComponentTypes.BEES);
		if (bees == null || bees.isEmpty()) {
			return;
		}
		List<NbtCompound> tags = new ArrayList<>(bees.size());
		for (BeehiveBlockEntity.BeeData bee : bees) {
			tags.add(bee.entityData().copyNbt());
		}
		for (Fact fact : hiveFacts(tags, options.showOrigin)) {
			out.add(new Fact(ItemLocationFacts.prefixed(prefix, fact.text), fact.pos, fact.dimension));
		}
	}

	/** Only facts with real coordinates — empty / untagged bees produce nothing. */
	static List<Fact> hiveFacts(List<NbtCompound> tags, boolean showOrigin) {
		List<Fact> out = new ArrayList<>();
		if (tags == null || tags.isEmpty()) {
			return out;
		}
		for (int i = 0; i < tags.size(); i++) {
			NbtCompound nbt = tags.get(i);
			if (nbt == null) continue;
			int index = i + 1;
			int[] hive = pos(nbt, "hive_pos", "HivePos");
			int[] flower = pos(nbt, "flower_pos", "FlowerPos");
			int[] origin = showOrigin ? paperOrigin(nbt) : null;
			if (hive == null && flower == null && origin == null) continue;
			if (hive != null) out.add(new Fact(ItemLocationFacts.beeHive(index, hive[0], hive[1], hive[2]), hive, null));
			if (flower != null) out.add(new Fact(ItemLocationFacts.beeFlower(index, flower[0], flower[1], flower[2]), flower, null));
			if (origin != null) {
				String world = nbt.contains("Paper.OriginWorld", NbtElement.STRING_TYPE)
					? nbt.getString("Paper.OriginWorld") : "";
				out.add(new Fact(ItemLocationFacts.origin(world, origin[0], origin[1], origin[2]), origin, world));
			}
		}
		return out;
	}

	private static void appendBlockEntity(NbtCompound nbt, String prefix, List<Fact> out) {
		int[] exit = intArray(nbt, "exit_portal");
		if (exit != null) {
			add(out, prefix, ItemLocationFacts.exitPortal(exit[0], exit[1], exit[2]), exit, null);
		}
		if (!nbt.contains("bees")) {
			int[] flower = pos(nbt, "flower_pos", "FlowerPos");
			if (flower != null) {
				add(out, prefix, ItemLocationFacts.beeFlower(1, flower[0], flower[1], flower[2]), flower, null);
			}
		}
	}

	private static void walkNested(ItemStack stack, java.util.function.Consumer<ItemStack> visitor) {
		ContainerComponent container = stack.get(DataComponentTypes.CONTAINER);
		if (container != null) {
			for (ItemStack child : container.iterateNonEmpty()) visitor.accept(child);
		}
		BundleContentsComponent bundle = stack.get(DataComponentTypes.BUNDLE_CONTENTS);
		if (bundle != null) {
			for (ItemStack child : bundle.iterate()) {
				if (child != null && !child.isEmpty()) visitor.accept(child);
			}
		}
	}

	private static boolean isHive(ItemStack stack) {
		Item item = stack.getItem();
		return item == Items.BEEHIVE || item == Items.BEE_NEST || stack.contains(DataComponentTypes.BEES);
	}

	private static String nestedPrefix(ItemStack stack) {
		return stack.getName().getString();
	}

	private static String decorationPath(RegistryEntry<?> type) {
		if (type == null) return "";
		Optional<? extends RegistryKey<?>> key = type.getKey();
		if (key.isEmpty()) return "";
		return key.get().getValue().getPath();
	}

	static int[] pos(NbtCompound nbt, String arrayKey, String legacyKey) {
		int[] array = intArray(nbt, arrayKey);
		if (array != null) return array;
		return legacyCompound(nbt, legacyKey);
	}

	static int[] intArray(NbtCompound nbt, String key) {
		if (nbt == null || !nbt.contains(key, NbtElement.INT_ARRAY_TYPE)) return null;
		int[] raw = nbt.getIntArray(key);
		return raw.length >= 3 ? new int[]{raw[0], raw[1], raw[2]} : null;
	}

	static int[] legacyCompound(NbtCompound nbt, String key) {
		if (nbt == null || !nbt.contains(key, NbtElement.COMPOUND_TYPE)) return null;
		NbtCompound compound = nbt.getCompound(key);
		if (!compound.contains("X") || !compound.contains("Y") || !compound.contains("Z")) return null;
		return new int[]{compound.getInt("X"), compound.getInt("Y"), compound.getInt("Z")};
	}

	static int[] paperOrigin(NbtCompound nbt) {
		if (nbt == null || !nbt.contains("Paper.Origin")) return null;
		int[] array = intArray(nbt, "Paper.Origin");
		if (array != null) return array;
		if (!nbt.contains("Paper.Origin", NbtElement.LIST_TYPE)) return null;
		NbtList list = nbt.getList("Paper.Origin", NbtElement.DOUBLE_TYPE);
		if (list.size() < 3) list = nbt.getList("Paper.Origin", NbtElement.INT_TYPE);
		if (list.size() < 3) return null;
		return new int[]{
			(int) Math.floor(list.getDouble(0)),
			(int) Math.floor(list.getDouble(1)),
			(int) Math.floor(list.getDouble(2))
		};
	}

	private static void add(List<Fact> out, String prefix, String line, int[] pos, String dimension) {
		out.add(new Fact(ItemLocationFacts.prefixed(prefix, line), pos, dimension));
	}

	private static List<String> render(List<Fact> facts, Options options, View view) {
		List<String> lines = new ArrayList<>(facts.size());
		for (Fact fact : facts) {
			lines.add(renderOne(fact, options.showDistance, view));
		}
		return lines;
	}

	private static String renderOne(Fact fact, boolean showDistance, View view) {
		if (!showDistance || fact.pos == null || view == null) return fact.text;
		if (fact.dimension != null && !fact.dimension.isEmpty() && !sameDim(view.dimension, fact.dimension)) {
			return fact.text;
		}
		int blocks = (int) Math.round(horizontal(view, fact.pos[0], fact.pos[2]));
		return fact.text + " (" + ItemLocationFacts.distance(blocks) + ")";
	}

	static boolean anyHighlight(List<Fact> facts, View view, int minDistance) {
		for (Fact fact : facts) {
			if (fact.pos == null) continue;
			if (view == null || minDistance <= 0) return true;
			if (fact.dimension != null && !fact.dimension.isEmpty() && !sameDim(view.dimension, fact.dimension)) {
				return true;
			}
			if (horizontal(view, fact.pos[0], fact.pos[2]) >= minDistance) return true;
		}
		return false;
	}

	static boolean sameDim(String playerDim, String storedDim) {
		return path(playerDim).equals(path(storedDim));
	}

	private static String path(String id) {
		if (id == null) return "";
		int colon = id.indexOf(':');
		return colon >= 0 ? id.substring(colon + 1) : id;
	}

	private static double horizontal(View view, int x, int z) {
		double dx = view.x - x;
		double dz = view.z - z;
		return Math.sqrt(dx * dx + dz * dz);
	}

	static List<String> cap(List<String> lines, int maxLines) {
		if (maxLines < 1 || lines.size() <= maxLines) return lines;
		List<String> cut = new ArrayList<>(lines.subList(0, maxLines));
		cut.add(ItemLocationFacts.more(lines.size() - maxLines));
		return cut;
	}
}
