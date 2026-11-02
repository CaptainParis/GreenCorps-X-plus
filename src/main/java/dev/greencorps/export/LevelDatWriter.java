package dev.greencorps.export;

import dev.greencorps.backup.ChunkObject;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Writes a minimal 1.21.4 level.dat (void flat or noise overworld). */
public final class LevelDatWriter {
	private LevelDatWriter() {}

	public static void write(Path worldDir, String levelName, int spawnX, int spawnY, int spawnZ,
							 int dataVersion) throws IOException {
		writeVoid(worldDir, levelName, spawnX, spawnY, spawnZ, dataVersion, 0L);
	}

	public static void writeVoid(Path worldDir, String levelName, int spawnX, int spawnY, int spawnZ,
								 int dataVersion, long seed) throws IOException {
		write(worldDir, levelName, spawnX, spawnY, spawnZ, dataVersion, seed, false, false);
	}

	/** Embedded: noise overworld generator with chosen seed. */
	public static void writeNoise(Path worldDir, String levelName, int spawnX, int spawnY, int spawnZ,
								  int dataVersion, long seed) throws IOException {
		write(worldDir, levelName, spawnX, spawnY, spawnZ, dataVersion, seed, true, true);
	}

	private static void write(Path worldDir, String levelName, int spawnX, int spawnY, int spawnZ,
							  int dataVersion, long seed, boolean noise, boolean generateFeatures)
		throws IOException {
		Files.createDirectories(worldDir);
		NbtCompound root = new NbtCompound();
		NbtCompound data = new NbtCompound();

		data.putByte("GameType", (byte) 1); // creative
		data.putString("LevelName", levelName);
		data.putLong("RandomSeed", seed);
		data.putLong("Time", 0L);
		data.putLong("DayTime", 6000L);
		data.putInt("SpawnX", spawnX);
		data.putInt("SpawnY", spawnY);
		data.putInt("SpawnZ", spawnZ);
		data.putByte("hardcore", (byte) 0);
		data.putByte("Difficulty", (byte) 0);
		data.putByte("DifficultyLocked", (byte) 0);
		data.putByte("allowCommands", (byte) 1);
		data.putInt("version", 19133);
		data.putInt("DataVersion", dataVersion);
		data.putByte("initialized", (byte) 1);
		data.putFloat("SpawnAngle", 0f);

		NbtCompound version = new NbtCompound();
		version.putInt("Id", dataVersion);
		version.putString("Name", dataVersion == ChunkObject.MC_DATA_VERSION ? "1.21.4" : Integer.toString(dataVersion));
		version.putString("Series", "main");
		version.putByte("Snapshot", (byte) 0);
		data.put("Version", version);

		NbtCompound worldGen = new NbtCompound();
		worldGen.putByte("bonus_chest", (byte) 0);
		worldGen.putByte("generate_features", (byte) (generateFeatures ? 1 : 0));
		worldGen.putLong("seed", seed);

		NbtCompound dimensions = new NbtCompound();
		NbtCompound overworld = new NbtCompound();
		NbtCompound generator = new NbtCompound();
		if (noise) {
			generator.putString("type", "minecraft:noise");
			generator.putString("settings", "minecraft:overworld");
			NbtCompound biomeSource = new NbtCompound();
			biomeSource.putString("type", "minecraft:multi_noise");
			biomeSource.putString("preset", "minecraft:overworld");
			generator.put("biome_source", biomeSource);
		} else {
			generator.putString("type", "minecraft:flat");
			NbtCompound settings = new NbtCompound();
			settings.putString("biome", "minecraft:the_void");
			settings.putByte("lakes", (byte) 0);
			settings.putByte("features", (byte) 0);
			NbtList layers = new NbtList();
			NbtCompound airLayer = new NbtCompound();
			airLayer.putString("block", "minecraft:air");
			airLayer.putInt("height", 1);
			layers.add(airLayer);
			settings.put("layers", layers);
			NbtCompound structures = new NbtCompound();
			structures.put("structures", new NbtCompound());
			settings.put("structure_overrides", structures);
			generator.put("settings", settings);
		}
		overworld.put("generator", generator);
		overworld.putString("type", "minecraft:overworld");
		dimensions.put("minecraft:overworld", overworld);
		worldGen.put("dimensions", dimensions);
		data.put("WorldGenSettings", worldGen);

		NbtCompound dataPacks = new NbtCompound();
		NbtList enabled = new NbtList();
		enabled.add(NbtString.of("vanilla"));
		dataPacks.put("Enabled", enabled);
		dataPacks.put("Disabled", new NbtList());
		data.put("DataPacks", dataPacks);

		root.put("Data", data);

		Path levelDat = worldDir.resolve("level.dat");
		NbtIo.writeCompressed(root, levelDat);
	}
}
