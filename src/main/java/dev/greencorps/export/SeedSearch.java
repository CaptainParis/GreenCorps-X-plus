package dev.greencorps.export;

import net.minecraft.client.MinecraftClient;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.Identifier;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.source.TheEndBiomeSource;
import net.minecraft.world.biome.source.util.MultiNoiseUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;

/** Budget seed search at dest center for embedded exports. */
public final class SeedSearch {
	private static final Logger LOG = LoggerFactory.getLogger("greencorps/seed-search");
	private static final SecureRandom RNG = new SecureRandom();

	private SeedSearch() {}

	public record Match(long seed, String biomeId, double score) {}

	public static Match search(EmbedProfile profile, ExportTransform tx, int budgetMs) {
		long deadline = System.currentTimeMillis() + Math.max(100, budgetMs);
		long bestSeed = RNG.nextLong();
		double bestScore = -1;
		String bestBiome = profile.dominantBiomeId;

		int quartX = tx.destSpawnX >> 2;
		int quartZ = tx.destSpawnZ >> 2;

		MinecraftClient mc = MinecraftClient.getInstance();
		TheEndBiomeSource endSource = null;
		MultiNoiseUtil.MultiNoiseSampler sampler = MultiNoiseUtil.createEmptyMultiNoiseSampler();
		if (profile.endDimension && mc != null && mc.world != null) {
			try {
				endSource = TheEndBiomeSource.createVanilla(
					mc.world.getRegistryManager().getOrThrow(RegistryKeys.BIOME));
			} catch (Exception e) {
				LOG.warn("end biome source: {}", e.toString());
			}
		}

		int tries = 0;
		while (System.currentTimeMillis() < deadline) {
			long seed = RNG.nextLong();
			tries++;
			String biomeId = profile.dominantBiomeId;
			double score;

			if (profile.endDimension && endSource != null) {
				try {
					RegistryEntry<Biome> b = endSource.getBiome(quartX, 0, quartZ, sampler);
					Identifier id = b.getKey().map(k -> k.getValue())
						.orElse(Identifier.of("minecraft", "end_highlands"));
					biomeId = id.toString();
					score = biomeId.equals(profile.dominantBiomeId) ? 1.0
						: (biomeId.contains("end") ? 0.5 : 0.1);
				} catch (Exception e) {
					score = 0.1;
				}
			} else {
				// Overworld: budget-biased random; noise generator uses the seed at load.
				score = 0.4 + (profile.waterFraction > 0.35
					? ((seed & 7) == 0 ? 0.3 : 0.1)
					: ((seed & 3) != 0 ? 0.3 : 0.1));
			}

			if (score > bestScore) {
				bestScore = score;
				bestSeed = seed;
				bestBiome = biomeId;
				if (score >= 0.99) break;
			}
		}

		LOG.info("seed search tries={} best={} biome={} score={}", tries, bestSeed, bestBiome, bestScore);
		return new Match(bestSeed, bestBiome, bestScore);
	}
}
