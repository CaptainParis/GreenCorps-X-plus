package dev.greencorps.backup;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guards the named-compound NbtIo.writeCompound/readCompound pair used by ChunkCapture.
 * A prior bug used NbtIo.read (unnamed) against write (named) and returned empty compounds.
 */
class ChunkCaptureNbtRoundTripTest {
	@Test
	void roundTripPreservesChunkKeys() throws Exception {
		NbtCompound original = new NbtCompound();
		original.putString("Status", "minecraft:full");
		original.putInt("xPos", 12);
		original.putInt("zPos", -34);
		original.putInt("DataVersion", ChunkObject.MC_DATA_VERSION);
		NbtList sections = new NbtList();
		NbtCompound section = new NbtCompound();
		section.putByte("Y", (byte) 0);
		sections.add(section);
		original.put("sections", sections);

		byte[] bytes = ChunkCapture.writeNbt(original);
		assertTrue(bytes.length > 4, "named compound should include type+name header");
		assertEquals(0x0A, bytes[0] & 0xFF, "root tag type COMPOUND");

		NbtCompound back = ChunkCapture.readNbt(bytes);
		assertEquals("minecraft:full", back.getString("Status"));
		assertEquals(12, back.getInt("xPos"));
		assertEquals(-34, back.getInt("zPos"));
		assertTrue(back.contains("sections"), "sections must survive round-trip");
		assertEquals(1, back.getList("sections", 10).size());
	}
}
