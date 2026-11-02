package dev.greencorps.backup;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Content-address hashes: SHA-256 truncated to 128 bits (32 hex chars). */
public final class HashUtil {
	private static final HexFormat HEX = HexFormat.of();

	private HashUtil() {}

	public static String hash128(byte[] data) {
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			byte[] full = md.digest(data);
			return HEX.formatHex(full, 0, 16);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 missing", e);
		}
	}

	public static String prefix2(String hash) {
		return hash.substring(0, 2);
	}
}
