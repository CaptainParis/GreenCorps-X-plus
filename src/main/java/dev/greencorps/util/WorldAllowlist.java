package dev.greencorps.util;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * God's Eye is 2b2t-only. A join is allowed when the multiplayer address is
 * {@code 2b2t.org}/{@code 2b2t.com}, or an address the player listed as a
 * 2b2t proxy on this instance. Singleplayer and every other host stay local.
 *
 * Allowed sessions always upload as {@link #CANONICAL_WORLD}. The live Xaero
 * folder may still be {@code Multiplayer_<proxy-ip>}; that name is never sent
 * as {@code world=}.
 */
public final class WorldAllowlist {
    public static final String CANONICAL_WORLD = "Multiplayer_2b2t.org";
    public static final String CANONICAL_HOST = "2b2t.org";

    private static final Pattern IPV4 = Pattern.compile(
        "^(?:\\d{1,3}\\.){3}\\d{1,3}$");

    private static final List<Supplier<List<String>>> PROXY_SOURCES = new CopyOnWriteArrayList<>();

    private WorldAllowlist() {}

    /** Modules and other jars (God's Eye Companion) add their 2b2t proxy address settings here. */
    public static void registerProxySource(Supplier<List<String>> source) {
        if (source != null) PROXY_SOURCES.add(source);
    }

    /** Every registered 2b2t proxy address, normalized and de-duplicated. */
    public static List<String> proxyAddresses() {
        List<String> all = new ArrayList<>();
        for (Supplier<List<String>> source : PROXY_SOURCES) {
            try {
                List<String> list = source.get();
                if (list != null) all.addAll(list);
            } catch (RuntimeException ignored) {
                // A broken source must not take the gate down.
            }
        }
        return normalizeProxyList(all);
    }

    /** Strip port and brackets; lower-case. Empty when the string is unusable. */
    public static String normalizeHost(String address) {
        if (address == null) return "";
        String s = address.trim();
        if (s.isEmpty()) return "";
        // bracketed IPv6 with optional port
        if (s.startsWith("[")) {
            int end = s.indexOf(']');
            if (end > 1) return s.substring(1, end).toLowerCase(Locale.ROOT);
        }
        int colon = s.lastIndexOf(':');
        if (colon > 0 && s.indexOf(':') == colon) {
            // host:port (one colon) — drop port
            String maybePort = s.substring(colon + 1);
            if (!maybePort.isEmpty() && maybePort.chars().allMatch(Character::isDigit)) {
                s = s.substring(0, colon);
            }
        }
        return s.toLowerCase(Locale.ROOT);
    }

    public static boolean isBuiltin2b2t(String host) {
        String h = normalizeHost(host);
        return h.equals("2b2t.org") || h.equals("2b2t.com");
    }

    /**
     * Host the Minecraft client is joined to, or null for singleplayer /
     * no server entry.
     */
    public static String joinedHost(MinecraftClient mc) {
        if (mc == null) return null;
        if (mc.isInSingleplayer()) return null;
        ServerInfo info = mc.getCurrentServerEntry();
        if (info == null || info.address == null || info.address.isBlank()) return null;
        String host = normalizeHost(info.address);
        return host.isEmpty() ? null : host;
    }

    public static List<String> normalizeProxyList(List<String> raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) return out;
        for (String entry : raw) {
            if (entry == null) continue;
            String h = normalizeHost(entry);
            if (h.isEmpty() || isBuiltin2b2t(h)) continue;
            if (!out.contains(h)) out.add(h);
        }
        return out;
    }

    /** True when this join may send anything to God's Eye. */
    public static boolean allowsJoin(String joinedHost, List<String> proxyAllow) {
        if (joinedHost == null || joinedHost.isEmpty()) return false;
        if (isBuiltin2b2t(joinedHost)) return true;
        for (String p : normalizeProxyList(proxyAllow)) {
            if (p.equals(joinedHost)) return true;
        }
        return false;
    }

    /**
     * True when a local Xaero world folder may be queued for Upload existing.
     * Matches {@code Multiplayer_2b2t.org}, {@code Multiplayer_2b2t},
     * {@code Multiplayer_2b2t.com}, and {@code Multiplayer_<proxy>}.
     */
    public static boolean allowsFolder(String worldFolder, List<String> proxyAllow) {
        if (worldFolder == null || worldFolder.isEmpty()) return false;
        String id = worldFolder.trim();
        if (!id.startsWith("Multiplayer_")) return false;
        String rest = id.substring("Multiplayer_".length());
        String host = normalizeHost(rest);
        if (isBuiltin2b2t(host) || host.equals("2b2t")) return true;
        for (String p : normalizeProxyList(proxyAllow)) {
            if (p.equals(host)) return true;
        }
        return false;
    }

    /** Always the server-side archive id for an allowed session. */
    public static String uploadWorldId() {
        return CANONICAL_WORLD;
    }

    /** Xaero folder name for a joined host (best-effort match to disk). */
    public static String xaeroFolderForHost(String host) {
        String h = normalizeHost(host);
        if (h.isEmpty()) return CANONICAL_WORLD;
        if (h.equals("2b2t")) return "Multiplayer_2b2t";
        return "Multiplayer_" + h;
    }

    public static boolean looksLikeIpv4(String host) {
        return IPV4.matcher(normalizeHost(host)).matches();
    }
}
