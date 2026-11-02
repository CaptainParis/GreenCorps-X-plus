package dev.greencorps.util;

import dev.greencorps.GreenCorps;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableTextContent;
import net.minecraft.util.ActionResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Ender chest contents per player and server, learned while the ender chest screen is open and kept on disk.
 * Meteor's {@code EChestMemory} lives in memory and is wiped on every server hop / config phase, which made
 * the tooltip read empty until the chest was reopened.
 */
public final class EnderChestCache {
	public static final int SIZE = 27;

	private static final Path FILE = FabricLoader.getInstance().getConfigDir()
		.resolve("greencorps").resolve("ender-chest.nbt");
	/** A 3-row container opened this soon after right-clicking an ender chest block counts as the ender chest. */
	private static final int BLOCK_CLICK_WINDOW_TICKS = 40;
	private static final int SAVE_DELAY_TICKS = 40;

	private static NbtCompound root;
	private static String loadedKey;
	private static ItemStack[] items;
	private static boolean dirty;
	private static int saveIn = -1;
	private static int ticks;
	private static int enderBlockClickTick = Integer.MIN_VALUE / 2;

	private EnderChestCache() {}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(EnderChestCache::tick);
		UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
			if (world.isClient && world.getBlockState(hit.getBlockPos()).isOf(Blocks.ENDER_CHEST)) {
				enderBlockClickTick = ticks;
			}
			return ActionResult.PASS;
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> flush());
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> flush());
	}

	/** True once this player's ender chest has been seen on this server. */
	public static boolean isKnown() {
		return current() != null;
	}

	/** The last seen 27 stacks (do not modify), or null while unknown. */
	public static ItemStack[] items() {
		return current();
	}

	private static ItemStack[] current() {
		MinecraftClient mc = MinecraftClient.getInstance();
		String key = key(mc);
		if (key == null) return null;
		if (!key.equals(loadedKey)) {
			loadedKey = key;
			items = decode(mc, key);
		}
		return items;
	}

	private static void tick(MinecraftClient mc) {
		ticks++;
		if (mc.player != null && mc.currentScreen instanceof GenericContainerScreen screen
			&& screen.getScreenHandler() instanceof GenericContainerScreenHandler handler
			&& handler.getRows() == 3 && isEnderChestScreen(screen.getTitle())) {
			learn(mc, handler);
		}
		if (saveIn > 0 && --saveIn == 0) flush();
	}

	/** Ender chest screen: its vanilla title, or a 3-row container opened right after clicking an ender chest. */
	public static boolean isEnderChestScreen(Text title) {
		if (title.getContent() instanceof TranslatableTextContent t && "container.enderchest".equals(t.getKey())) return true;
		return ticks - enderBlockClickTick <= BLOCK_CLICK_WINDOW_TICKS;
	}

	private static void learn(MinecraftClient mc, GenericContainerScreenHandler handler) {
		String key = key(mc);
		if (key == null) return;
		ItemStack[] known = current();
		ItemStack[] seen = new ItemStack[SIZE];
		boolean changed = known == null;
		for (int i = 0; i < SIZE; i++) {
			seen[i] = handler.getInventory().getStack(i).copy();
			if (!changed && !ItemStack.areEqual(seen[i], known[i])) changed = true;
		}
		if (!changed) return;
		items = seen;
		loadedKey = key;
		RegistryWrapper.WrapperLookup registries = mc.world.getRegistryManager();
		NbtList list = new NbtList();
		for (ItemStack stack : seen) list.add(stack.toNbtAllowEmpty(registries));
		NbtCompound entry = new NbtCompound();
		entry.put("items", list);
		entry.putLong("seen", System.currentTimeMillis());
		root().put(key, entry);
		dirty = true;
		if (saveIn <= 0) saveIn = SAVE_DELAY_TICKS;
	}

	private static ItemStack[] decode(MinecraftClient mc, String key) {
		NbtCompound entry = root().getCompound(key);
		if (!entry.contains("items", NbtElement.LIST_TYPE)) return null;
		NbtList list = entry.getList("items", NbtElement.COMPOUND_TYPE);
		RegistryWrapper.WrapperLookup registries = mc.world.getRegistryManager();
		ItemStack[] out = new ItemStack[SIZE];
		for (int i = 0; i < SIZE; i++) {
			out[i] = i < list.size() ? ItemStack.fromNbtOrEmpty(registries, list.getCompound(i)) : ItemStack.EMPTY;
		}
		return out;
	}

	/** Player UUID + server; 2b2t proxies count as 2b2t, so a Zenith hop keeps the same chest. */
	private static String key(MinecraftClient mc) {
		if (mc.player == null || mc.world == null) return null;
		String server;
		if (mc.isInSingleplayer() && mc.getServer() != null) {
			server = "singleplayer/" + mc.getServer().getSaveProperties().getLevelName();
		} else {
			String host = WorldAllowlist.joinedHost(mc);
			if (host == null) return null;
			server = WorldAllowlist.allowsJoin(host, WorldAllowlist.proxyAddresses()) ? WorldAllowlist.CANONICAL_HOST : host;
		}
		return mc.player.getUuid() + "@" + server;
	}

	private static NbtCompound root() {
		if (root != null) return root;
		root = new NbtCompound();
		if (Files.isRegularFile(FILE)) {
			try {
				root = NbtIo.readCompressed(FILE, NbtSizeTracker.ofUnlimitedBytes());
			} catch (IOException e) {
				GreenCorps.LOG.warn("GreenCorps: could not read {}: {}", FILE, e.toString());
			}
		}
		return root;
	}

	private static void flush() {
		saveIn = -1;
		if (!dirty || root == null) return;
		dirty = false;
		try {
			Files.createDirectories(FILE.getParent());
			Path tmp = FILE.resolveSibling(FILE.getFileName() + ".tmp");
			NbtIo.writeCompressed(root, tmp);
			Files.move(tmp, FILE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			GreenCorps.LOG.warn("GreenCorps: could not save {}: {}", FILE, e.toString());
		}
	}
}
