package dev.greencorps.util;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtDouble;
import net.minecraft.nbt.NbtList;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemLocationFactsTest {
	@Test
	void emptyHiveYieldsNoFacts() {
		assertTrue(ItemLocations.hiveFacts(List.of(), true).isEmpty());
		assertTrue(ItemLocations.hiveFacts(null, true).isEmpty());
	}

	@Test
	void occupiedHiveWithoutPosYieldsNoFacts() {
		NbtCompound bee = new NbtCompound();
		bee.putString("id", "minecraft:bee");
		assertTrue(ItemLocations.hiveFacts(List.of(bee, bee.copy()), true).isEmpty());
	}

	@Test
	void flowerPosSurvivesAndHivePosIsOptional() {
		NbtCompound bee = new NbtCompound();
		bee.putIntArray("flower_pos", new int[]{5, 70, -8});
		assertEquals(
			List.of("Bee 1 flower: 5 70 -8"),
			texts(ItemLocations.hiveFacts(List.of(bee), true))
		);

		NbtCompound both = new NbtCompound();
		both.putIntArray("flower_pos", new int[]{1, 2, 3});
		both.putIntArray("hive_pos", new int[]{9, 8, 7});
		assertEquals(
			List.of("Bee 1 hive: 9 8 7", "Bee 1 flower: 1 2 3"),
			texts(ItemLocations.hiveFacts(List.of(both), false))
		);
	}

	@Test
	void legacyFlowerCompoundAndPaperOrigin() {
		NbtCompound legacy = new NbtCompound();
		NbtCompound flower = new NbtCompound();
		flower.putInt("X", 4);
		flower.putInt("Y", 64);
		flower.putInt("Z", -2);
		legacy.put("FlowerPos", flower);
		assertArrayEquals(new int[]{4, 64, -2}, ItemLocations.pos(legacy, "flower_pos", "FlowerPos"));

		NbtCompound paper = new NbtCompound();
		NbtList origin = new NbtList();
		origin.add(NbtDouble.of(10.9));
		origin.add(NbtDouble.of(64.2));
		origin.add(NbtDouble.of(-3.1));
		paper.put("Paper.Origin", origin);
		paper.putString("Paper.OriginWorld", "minecraft:the_nether");
		assertEquals(
			List.of("Origin: nether 10 64 -4"),
			texts(ItemLocations.hiveFacts(List.of(paper), true))
		);
		assertTrue(ItemLocations.hiveFacts(List.of(paper), false).isEmpty());
	}

	@Test
	void missingTagIsNull() {
		assertNull(ItemLocations.intArray(new NbtCompound(), "flower_pos"));
		assertNull(ItemLocations.paperOrigin(new NbtCompound()));
	}

	@Test
	void lodestoneAndMapLines() {
		assertEquals("Lodestone: overworld 100 64 -20", ItemLocationFacts.lodestone("minecraft:overworld", 100, 64, -20, true));
		assertEquals("Lodestone: nether 0 80 0 (untracked)", ItemLocationFacts.lodestone("minecraft:the_nether", 0, 80, 0, false));
		assertEquals("Lodestone: no target (spinning)", ItemLocationFacts.lodestoneSpinning());
		assertEquals(
			"Map: Trial Chambers 1840, -320 — stored on the item",
			ItemLocationFacts.mapDecoration("minecraft:trial_chambers", 1840, -320)
		);
		assertEquals(
			"Map: Buried Treasure 12, 44 — stored on the item",
			ItemLocationFacts.mapDecoration("red_x", 12, 44)
		);
		assertEquals(
			"Map #7: view (learned on-canvas) ~512, -1024 (end) — not stored on the item",
			ItemLocationFacts.mapLearnedView(7, 512, -1024, "minecraft:the_end")
		);
		assertEquals("Map #3: canvas only — no coords on the item", ItemLocationFacts.mapCanvasOnly(3));
		assertEquals("1.2k", ItemLocationFacts.distance(1200));
	}

	@Test
	void canvasMapFactsDoNotHighlightLearnedDoes() {
		List<ItemLocations.Fact> learned = List.of(
			new ItemLocations.Fact(ItemLocationFacts.mapLearnedView(1, 100, 200, "minecraft:overworld"),
				new int[]{100, 0, 200}, "minecraft:overworld")
		);
		List<ItemLocations.Fact> canvas = List.of(
			new ItemLocations.Fact(ItemLocationFacts.mapCanvasOnly(2), null, null)
		);
		List<ItemLocations.Fact> stored = List.of(
			new ItemLocations.Fact(ItemLocationFacts.mapDecoration("monument", 10, 20), new int[]{10, 0, 20}, null)
		);
		ItemLocations.View player = new ItemLocations.View("minecraft:overworld", 0, 64, 0);
		assertTrue(ItemLocations.anyHighlight(learned, player, 0));
		assertFalse(ItemLocations.anyHighlight(canvas, player, 0));
		assertTrue(ItemLocations.anyHighlight(stored, player, 0));
	}

	@Test
	void skipsPlayerAndFrameMarks() {
		assertTrue(ItemLocationFacts.isTransientMapMark("player"));
		assertTrue(ItemLocationFacts.isTransientMapMark("frame"));
		assertFalse(ItemLocationFacts.isTransientMapMark("trial_chambers"));
		assertFalse(ItemLocationFacts.isTransientMapMark("monument"));
	}

	@Test
	void mayCarryRejectsNull() {
		assertFalse(ItemLocations.mayCarry(null));
	}

	@Test
	void nestedPrefixCapAndHighlightRange() {
		assertEquals(
			"Bee Nest · Bees: none — no coordinates",
			ItemLocationFacts.prefixed("Bee Nest", ItemLocationFacts.beesNone())
		);
		List<String> many = new ArrayList<>();
		for (int i = 0; i < 5; i++) many.add("line " + i);
		List<String> cut = ItemLocations.cap(many, 3);
		assertEquals(4, cut.size());
		assertEquals("+2 more", cut.get(3));

		NbtCompound near = new NbtCompound();
		near.putIntArray("flower_pos", new int[]{10, 64, 10});
		var facts = ItemLocations.hiveFacts(List.of(near), false);
		ItemLocations.View player = new ItemLocations.View("minecraft:overworld", 0, 64, 0);
		assertFalse(ItemLocations.anyHighlight(facts, player, 1000));
		assertTrue(ItemLocations.anyHighlight(facts, player, 0));
	}

	private static List<String> texts(List<ItemLocations.Fact> facts) {
		return facts.stream().map(ItemLocations.Fact::text).toList();
	}
}
