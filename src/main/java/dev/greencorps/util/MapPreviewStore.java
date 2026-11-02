package dev.greencorps.util;

import net.minecraft.block.MapColor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Instance-local mapart cache keyed by content hash of the 128x128 colors.
 * Path: {@code config/greencorps/map-previews/<server>/<contentHash>.bin}
 * Custom name is an index only (unique name may resolve; collisions never guess).
 */
public final class MapPreviewStore {
	private static final Logger LOG = LoggerFactory.getLogger("greencorps/map-preview");
	private static final int MAGIC = 0x47434D50; // GCMP
	private static final int VERSION = 2;
	private static final int COLORS = 128 * 128;
	/** Full map resolution; slot blit scales this down on the GPU. */
	private static final int TEX_PX = 128;

	private static final MapPreviewStore INSTANCE = new MapPreviewStore();

	/** contentHash -> colors */
	private final ConcurrentHashMap<String, byte[]> colorsByHash = new ConcurrentHashMap<>();
	/** contentHash -> GPU texture */
	private final ConcurrentHashMap<String, TexEntry> textures = new ConcurrentHashMap<>();
	/** this-session map id -> contentHash */
	private final ConcurrentHashMap<Integer, String> mapIdToHash = new ConcurrentHashMap<>();
	/** normalized name -> set of content hashes */
	private final ConcurrentHashMap<String, Set<String>> nameToHashes = new ConcurrentHashMap<>();

	private final AtomicReference<String> serverKey = new AtomicReference<>("");
	private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "greencorps-map-preview-io");
		t.setDaemon(true);
		return t;
	});

	private static final class TexEntry {
		final Identifier id;
		final NativeImageBackedTexture texture;
		final int hash;

		TexEntry(Identifier id, NativeImageBackedTexture texture, int hash) {
			this.id = id;
			this.texture = texture;
			this.hash = hash;
		}
	}

	private MapPreviewStore() {
	}

	public static MapPreviewStore get() {
		return INSTANCE;
	}

	public Path rootDir() {
		return net.fabricmc.loader.api.FabricLoader.getInstance()
			.getConfigDir().resolve("greencorps").resolve("map-previews");
	}

	public void onServerKey(String key) {
		String sanitized = sanitizeServer(key);
		String prev = serverKey.getAndSet(sanitized);
		if (sanitized.equals(prev)) return;
		colorsByHash.clear();
		nameToHashes.clear();
		mapIdToHash.clear();
		destroyTextures(textures);
		io.execute(() -> loadServerDir(sanitized));
	}

	public void clearSession() {
		mapIdToHash.clear();
	}

	public static String contentHash(byte[] colors) {
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			byte[] dig = md.digest(colors);
			return HexFormat.of().formatHex(dig, 0, 16);
		} catch (NoSuchAlgorithmException e) {
			return Integer.toHexString(Arrays.hashCode(colors));
		}
	}

	/**
	 * Persist non-blank colors under their content hash. Binds optional map id and name index.
	 * @return content hash, or null if blank/invalid
	 */
	public String put(byte[] colors, Integer mapId, String normalizedName) {
		if (colors == null || colors.length != COLORS || isBlank(colors)) return null;
		byte[] copy = Arrays.copyOf(colors, COLORS);
		String hash = contentHash(copy);
		byte[] prev = colorsByHash.put(hash, copy);
		boolean same = prev != null && Arrays.equals(prev, copy);
		ensureTexture(hash, copy);
		if (mapId != null) mapIdToHash.put(mapId, hash);
		if (normalizedName != null && !normalizedName.isEmpty()) {
			nameToHashes.computeIfAbsent(normalizedName, k -> ConcurrentHashMap.newKeySet()).add(hash);
		}
		if (!same) {
			String server = serverKey.get();
			if (server != null && !server.isEmpty()) {
				String name = normalizedName == null ? "" : normalizedName;
				io.execute(() -> writeFile(server, hash, name, copy));
			}
		}
		return hash;
	}

	public boolean hasMapId(int mapId) {
		return mapIdToHash.containsKey(mapId);
	}

	/** True if this name maps to exactly one known piece (safe disk lookup). */
	public boolean hasUniqueName(String normalizedName) {
		if (normalizedName == null) return false;
		Set<String> set = nameToHashes.get(normalizedName);
		return set != null && set.size() == 1;
	}

	/** True if we already have a durable or session bind for this stack identity. */
	public boolean hasPreviewFor(Integer mapId, String normalizedName) {
		if (mapId != null && mapIdToHash.containsKey(mapId)) return true;
		return hasUniqueName(normalizedName);
	}

	public Identifier textureForMapId(int mapId) {
		String hash = mapIdToHash.get(mapId);
		if (hash == null) return null;
		byte[] colors = colorsByHash.get(hash);
		if (colors == null) return null;
		return ensureTexture(hash, colors);
	}

	public Identifier textureForUniqueName(String normalizedName) {
		if (normalizedName == null) return null;
		Set<String> set = nameToHashes.get(normalizedName);
		if (set == null || set.size() != 1) return null;
		String hash = set.iterator().next();
		byte[] colors = colorsByHash.get(hash);
		if (colors == null) return null;
		return ensureTexture(hash, colors);
	}

	public byte[] colorsForMapId(int mapId) {
		String hash = mapIdToHash.get(mapId);
		return hash == null ? null : colorsByHash.get(hash);
	}

	public byte[] colorsForUniqueName(String normalizedName) {
		if (normalizedName == null) return null;
		Set<String> set = nameToHashes.get(normalizedName);
		if (set == null || set.size() != 1) return null;
		return colorsByHash.get(set.iterator().next());
	}

	private Identifier ensureTexture(String contentHash, byte[] colors) {
		int hash = Arrays.hashCode(colors);
		TexEntry existing = textures.get(contentHash);
		if (existing != null && existing.hash == hash) return existing.id;

		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc == null) return null;

		Identifier id = Identifier.of("greencorps", "map_slot/" + contentHash);
		NativeImage image = new NativeImage(NativeImage.Format.RGBA, TEX_PX, TEX_PX, false);
		for (int y = 0; y < TEX_PX; y++) {
			for (int x = 0; x < TEX_PX; x++) {
				byte colorByte = colors[x + y * 128];
				int argb = MapColor.getRenderColor(colorByte);
				image.setColorArgb(x, y, argb);
			}
		}
		NativeImageBackedTexture tex = new NativeImageBackedTexture(image);

		Runnable register = () -> {
			MinecraftClient c = MinecraftClient.getInstance();
			if (c == null) {
				tex.close();
				return;
			}
			TexEntry old = textures.get(contentHash);
			if (old != null) {
				c.getTextureManager().destroyTexture(old.id);
				old.texture.close();
			}
			c.getTextureManager().registerTexture(id, tex);
			textures.put(contentHash, new TexEntry(id, tex, hash));
		};

		if (mc.isOnThread()) register.run();
		else mc.execute(register);

		return id;
	}

	private void loadServerDir(String server) {
		Path dir = rootDir().resolve(server);
		if (!Files.isDirectory(dir)) return;
		try (var stream = Files.list(dir)) {
			stream.filter(p -> p.getFileName().toString().endsWith(".bin")).forEach(this::readFileQuiet);
		} catch (IOException e) {
			LOG.warn("map-preview load {}: {}", server, e.toString());
		}
	}

	private void readFileQuiet(Path file) {
		try (DataInputStream in = new DataInputStream(Files.newInputStream(file))) {
			if (in.readInt() != MAGIC) return;
			int ver = in.readInt();
			if (ver != VERSION && ver != 1) return;
			int nameLen = in.readUnsignedShort();
			if (nameLen > 512) return;
			String name = "";
			if (nameLen > 0) {
				byte[] nameBytes = in.readNBytes(nameLen);
				name = MapPreviewNames.normalize(new String(nameBytes, StandardCharsets.UTF_8));
			}
			byte[] colors = in.readNBytes(COLORS);
			if (colors.length != COLORS || isBlank(colors)) return;
			String hash = contentHash(colors);
			// Prefer filename hash when it matches content (v2); otherwise recompute.
			String fileStem = file.getFileName().toString().replace(".bin", "");
			if (fileStem.length() == 32 && fileStem.equals(hash)) {
				// ok
			}
			colorsByHash.put(hash, colors);
			if (!name.isEmpty()) {
				nameToHashes.computeIfAbsent(name, k -> ConcurrentHashMap.newKeySet()).add(hash);
			}
			MinecraftClient mc = MinecraftClient.getInstance();
			if (mc != null) {
				mc.execute(() -> ensureTexture(hash, colors));
			}
		} catch (IOException e) {
			LOG.warn("map-preview read {}: {}", file.getFileName(), e.toString());
		}
	}

	private void writeFile(String server, String contentHash, String normalizedName, byte[] colors) {
		Path dir = rootDir().resolve(server);
		try {
			Files.createDirectories(dir);
			Path file = dir.resolve(contentHash + ".bin");
			try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(file))) {
				out.writeInt(MAGIC);
				out.writeInt(VERSION);
				byte[] nameBytes = normalizedName.getBytes(StandardCharsets.UTF_8);
				int n = Math.min(nameBytes.length, 512);
				out.writeShort(n);
				if (n > 0) out.write(nameBytes, 0, n);
				out.write(colors);
			}
		} catch (IOException e) {
			LOG.warn("map-preview write: {}", e.toString());
		}
	}

	private static void destroyTextures(ConcurrentHashMap<String, TexEntry> map) {
		MinecraftClient mc = MinecraftClient.getInstance();
		Iterator<Map.Entry<String, TexEntry>> it = map.entrySet().iterator();
		while (it.hasNext()) {
			TexEntry e = it.next().getValue();
			if (mc != null) mc.getTextureManager().destroyTexture(e.id);
			e.texture.close();
			it.remove();
		}
	}

	public static boolean isBlank(byte[] colors) {
		if (colors == null) return true;
		for (byte b : colors) {
			if (b != 0) return false;
		}
		return true;
	}

	public static String sanitizeServer(String raw) {
		if (raw == null || raw.isBlank()) return "unknown";
		String s = raw.trim().toLowerCase();
		StringBuilder sb = new StringBuilder(s.length());
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '.' || c == '-' || c == '_') {
				sb.append(c);
			} else {
				sb.append('_');
			}
		}
		String out = sb.toString();
		return out.isEmpty() ? "unknown" : out;
	}
}
