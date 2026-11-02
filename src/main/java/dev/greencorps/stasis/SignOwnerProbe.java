package dev.greencorps.stasis;

import net.minecraft.block.AbstractBannerBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.SkullBlock;
import net.minecraft.block.WallSkullBlock;
import net.minecraft.block.entity.BannerBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.block.entity.SignText;
import net.minecraft.block.entity.SkullBlockEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Reads owner IGN from signs / hanging signs or stealth markers (banner / skull)
 * around a chamber seat. Text is untrusted — scrub invisible Unicode; exact IGN only.
 */
public final class SignOwnerProbe {
	private static final Pattern IGN = Pattern.compile("^[A-Za-z0-9_]{1,16}$");
	private static final long CACHE_MS = 3000L;

	public enum MarkerMode {
		SIGNS,
		BANNER,
		SKULL
	}

	/** Exact IGN hit with optional 0xRRGGBB colour from the sign line. */
	public record Hit(String ign, Integer rgb) {
		public Hit {
			if (ign == null || ign.isBlank()) throw new IllegalArgumentException("blank ign");
		}
	}

	private static final int CACHE_CAP = 256;

	/** Per seat+mode probe result (null hit cached too); one slot thrashed with N chambers. */
	private record Entry(Hit hit, long untilMs) {}

	private static final java.util.HashMap<String, Entry> CACHE = new java.util.HashMap<>();

	private SignOwnerProbe() {}

	public static Optional<String> ignAtSeat(ClientWorld world, int sx, int sy, int sz) {
		return hitAtSeat(world, sx, sy, sz, MarkerMode.SIGNS).map(Hit::ign);
	}

	public static Optional<Hit> hitAtSeat(ClientWorld world, int sx, int sy, int sz) {
		return hitAtSeat(world, sx, sy, sz, MarkerMode.SIGNS);
	}

	public static Optional<Hit> hitAtSeat(ClientWorld world, int sx, int sy, int sz, MarkerMode mode) {
		if (world == null) return Optional.empty();
		MarkerMode m = mode == null ? MarkerMode.SIGNS : mode;
		String key = m.name() + "|" + sx + "|" + sy + "|" + sz;
		long now = System.currentTimeMillis();
		synchronized (CACHE) {
			Entry e = CACHE.get(key);
			if (e != null && now < e.untilMs()) return Optional.ofNullable(e.hit());
		}
		long t0 = StasisPerf.start();
		Hit found = probe(world, sx, sy, sz, m);
		StasisPerf.stop(StasisPerf.Bucket.SIGN_PROBE, t0);
		synchronized (CACHE) {
			if (CACHE.size() >= CACHE_CAP) CACHE.values().removeIf(x -> now >= x.untilMs());
			if (CACHE.size() >= CACHE_CAP) CACHE.clear();
			CACHE.put(key, new Entry(found, now + CACHE_MS));
		}
		return Optional.ofNullable(found);
	}

	public static void clearCache() {
		synchronized (CACHE) {
			CACHE.clear();
		}
	}

	/**
	 * Same XZ 1-4 up; cardinals at dy 0-4 (covers 2-3 above, 1 in front / behind the column).
	 */
	private static Hit probe(ClientWorld world, int sx, int sy, int sz, MarkerMode mode) {
		for (int dy = 1; dy <= 4; dy++) {
			Hit hit = hitAt(world, sx, sy + dy, sz, mode);
			if (hit != null) return hit;
		}
		int[][] offs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
		for (int dy = 0; dy <= 4; dy++) {
			for (int[] o : offs) {
				Hit hit = hitAt(world, sx + o[0], sy + dy, sz + o[1], mode);
				if (hit != null) return hit;
			}
		}
		return null;
	}

	private static Hit hitAt(ClientWorld world, int x, int y, int z, MarkerMode mode) {
		return switch (mode) {
			case SIGNS -> hitFromSign(world, x, y, z);
			case BANNER -> hitFromBanner(world, x, y, z);
			case SKULL -> hitFromSkull(world, x, y, z);
		};
	}

	private static Hit hitFromSign(ClientWorld world, int x, int y, int z) {
		BlockEntity be = world.getBlockEntity(new BlockPos(x, y, z));
		if (!(be instanceof SignBlockEntity sign)) return null;
		Hit fromFront = extractHit(sign.getFrontText());
		if (fromFront != null) return fromFront;
		return extractHit(sign.getBackText());
	}

	private static Hit hitFromBanner(ClientWorld world, int x, int y, int z) {
		BlockPos pos = new BlockPos(x, y, z);
		BlockState state = world.getBlockState(pos);
		Block block = state.getBlock();
		if (!(block instanceof AbstractBannerBlock)) return null;
		BlockEntity be = world.getBlockEntity(pos);
		if (!(be instanceof BannerBlockEntity banner)) return null;
		Text custom = banner.getCustomName();
		if (custom == null) custom = banner.getComponents().get(DataComponentTypes.CUSTOM_NAME);
		return hitFromCustomName(custom);
	}

	private static Hit hitFromSkull(ClientWorld world, int x, int y, int z) {
		BlockPos pos = new BlockPos(x, y, z);
		BlockState state = world.getBlockState(pos);
		Block block = state.getBlock();
		if (!(block instanceof SkullBlock) && !(block instanceof WallSkullBlock)) return null;
		SkullBlock.SkullType type = null;
		if (block instanceof SkullBlock skull) type = skull.getSkullType();
		else if (block instanceof WallSkullBlock wall) type = wall.getSkullType();
		if (type == SkullBlock.Type.PLAYER) return null; // head ESP common
		BlockEntity be = world.getBlockEntity(pos);
		if (!(be instanceof SkullBlockEntity skullBe)) return null;
		return hitFromCustomName(skullBe.getComponents().get(DataComponentTypes.CUSTOM_NAME));
	}

	private static Hit hitFromCustomName(Text name) {
		if (name == null) return null;
		String raw = name.getString();
		String scrubbed = scrub(raw);
		if (scrubbed.isEmpty()) return null;
		if (isInjection(scrubbed)) return null;
		if (!IGN.matcher(scrubbed).matches()) return null;
		return new Hit(scrubbed, colourOf(name, raw));
	}

	private static Hit extractHit(SignText text) {
		if (text == null) return null;
		Text[] messages = text.getMessages(false);
		if (messages == null) return null;
		for (Text line : messages) {
			if (line == null) continue;
			String raw = line.getString();
			String scrubbed = scrub(raw);
			if (scrubbed.isEmpty()) continue;
			if (isInjection(scrubbed)) return null;
			if (IGN.matcher(scrubbed).matches()) {
				return new Hit(scrubbed, colourOf(line, raw));
			}
			return null;
		}
		return null;
	}

	/** Style colour first, else first section colour code on the raw line. */
	private static Integer colourOf(Text line, String raw) {
		TextColor style = line.getStyle().getColor();
		if (style != null) return style.getRgb() & 0xFFFFFF;
		if (raw == null || raw.isEmpty()) return null;
		for (int i = 0; i < raw.length() - 1; i++) {
			if (raw.charAt(i) != '\u00A7') continue;
			char code = Character.toLowerCase(raw.charAt(i + 1));
			Formatting fmt = Formatting.byCode(code);
			if (fmt != null && fmt.isColor() && fmt.getColorValue() != null) {
				return fmt.getColorValue() & 0xFFFFFF;
			}
		}
		return null;
	}

	/** Strip invisible / bidi / section-sign formatting. */
	public static String scrub(String raw) {
		if (raw == null || raw.isEmpty()) return "";
		StringBuilder sb = new StringBuilder(raw.length());
		for (int i = 0; i < raw.length(); i++) {
			char c = raw.charAt(i);
			if (c == '\u00A7') {
				i++;
				continue;
			}
			int cp = Character.codePointAt(raw, i);
			if (cp >= 0x200B && cp <= 0x200D) continue;
			if (cp >= 0x202A && cp <= 0x202E) continue;
			if (cp == 0xFEFF) continue;
			if (Character.isWhitespace(cp)) {
				if (sb.length() == 0) continue;
				sb.append(' ');
				continue;
			}
			sb.appendCodePoint(cp);
			if (Character.isSupplementaryCodePoint(cp)) i++;
		}
		return sb.toString().trim();
	}

	private static boolean isInjection(String s) {
		String u = s.toUpperCase();
		return u.contains("[SYSTEM") || u.contains("IGNORE PREVIOUS") || u.contains("IGNORE ALL");
	}
}
