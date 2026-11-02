package dev.greencorps.export;

import dev.greencorps.backup.ChunkObject;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtDouble;
import net.minecraft.nbt.NbtFloat;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.RegistryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Writes the local client's inventory + ender chest into an exported SP world
 * ({@code playerdata/<uuid>.dat} and {@code level.dat} Data.Player).
 */
public final class PlayerExport {
	private static final Logger LOG = LoggerFactory.getLogger("greencorps/player-export");

	private PlayerExport() {}

	public static void writeIntoWorld(Path destWorld, int spawnX, int spawnY, int spawnZ) {
		writeIntoWorld(destWorld, spawnX, spawnY, spawnZ, 0f);
	}

	public static void writeIntoWorld(Path destWorld, int spawnX, int spawnY, int spawnZ, float facingYaw) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc == null || mc.player == null || destWorld == null) return;
		try {
			ClientPlayerEntity player = mc.player;
			RegistryWrapper.WrapperLookup regs = mc.world != null
				? mc.world.getRegistryManager() : null;
			NbtCompound playerNbt = buildPlayerNbt(player, regs, spawnX, spawnY, spawnZ, facingYaw);
			writePlayerData(destWorld, player.getUuid(), playerNbt);
			patchLevelDatPlayer(destWorld, playerNbt);
		} catch (Exception e) {
			LOG.warn("player export skipped: {}", e.toString());
		}
	}

	static NbtCompound buildPlayerNbt(ClientPlayerEntity player, RegistryWrapper.WrapperLookup regs,
									  int spawnX, int spawnY, int spawnZ, float facingYaw) {
		NbtCompound nbt = new NbtCompound();
		nbt.putInt("DataVersion", ChunkObject.MC_DATA_VERSION);

		NbtList pos = new NbtList();
		pos.add(NbtDouble.of(spawnX + 0.5));
		pos.add(NbtDouble.of(spawnY));
		pos.add(NbtDouble.of(spawnZ + 0.5));
		nbt.put("Pos", pos);

		NbtList motion = new NbtList();
		motion.add(NbtDouble.of(0));
		motion.add(NbtDouble.of(0));
		motion.add(NbtDouble.of(0));
		nbt.put("Motion", motion);

		// Dest facing only — never copy live 2b2t look into export.
		NbtList rotation = new NbtList();
		rotation.add(NbtFloat.of(facingYaw));
		rotation.add(NbtFloat.of(0f));
		nbt.put("Rotation", rotation);

		nbt.putInt("SpawnX", spawnX);
		nbt.putInt("SpawnY", spawnY);
		nbt.putInt("SpawnZ", spawnZ);
		nbt.putByte("SpawnForced", (byte) 1);
		nbt.putString("Dimension", "minecraft:overworld");

		UUID id = player.getUuid();
		nbt.putLong("UUIDMost", id.getMostSignificantBits());
		nbt.putLong("UUIDLeast", id.getLeastSignificantBits());
		nbt.putIntArray("UUID", new int[] {
			(int) (id.getMostSignificantBits() >> 32),
			(int) id.getMostSignificantBits(),
			(int) (id.getLeastSignificantBits() >> 32),
			(int) id.getLeastSignificantBits()
		});

		NbtList inv = new NbtList();
		player.getInventory().writeNbt(inv);
		nbt.put("Inventory", inv);

		if (regs != null) {
			NbtList ender = player.getEnderChestInventory().toNbtList(regs);
			nbt.put("EnderItems", ender);
		}

		nbt.putInt("playerGameType", 1); // creative in exported worlds
		nbt.putFloat("Health", player.getHealth());
		nbt.putInt("foodLevel", player.getHungerManager().getFoodLevel());
		return nbt;
	}

	static void writePlayerData(Path destWorld, UUID uuid, NbtCompound playerNbt) throws IOException {
		Path dir = destWorld.resolve("playerdata");
		Files.createDirectories(dir);
		Path file = dir.resolve(uuid.toString() + ".dat");
		NbtIo.writeCompressed(playerNbt, file);
	}

	static void patchLevelDatPlayer(Path destWorld, NbtCompound playerNbt) throws IOException {
		Path levelDat = destWorld.resolve("level.dat");
		if (!Files.isRegularFile(levelDat)) return;
		NbtCompound root = NbtIo.readCompressed(levelDat, net.minecraft.nbt.NbtSizeTracker.ofUnlimitedBytes());
		if (root == null) return;
		NbtCompound data = root.getCompound("Data");
		data.put("Player", playerNbt.copy());
		root.put("Data", data);
		NbtIo.writeCompressed(root, levelDat);
	}
}
