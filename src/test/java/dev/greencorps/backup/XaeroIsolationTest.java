package dev.greencorps.backup;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.fail;

/** Build-failing guard: no xaero.* imports outside gui/map and mixin. */
class XaeroIsolationTest {
	@Test
	void noXaeroImportsOutsideAllowedPackages() throws IOException {
		Path root = Path.of("src/main/java/dev/greencorps");
		if (!Files.isDirectory(root)) {
			// Running from IDE with different cwd
			root = Path.of("greencorps/src/main/java/dev/greencorps");
		}
		if (!Files.isDirectory(root)) return;

		try (Stream<Path> walk = Files.walk(root)) {
			for (Path file : walk.filter(p -> p.toString().endsWith(".java")).toList()) {
				String rel = root.relativize(file).toString().replace('\\', '/');
				if (rel.startsWith("gui/map/") || rel.startsWith("mixin/")) continue;
				if (rel.equals("compat/GcMixinPlugin.java") || rel.equals("compat/XaeroBridge.java")) continue;
				// util/XaeroWaypoints is pre-existing soft-dep for other modules
				if (rel.equals("util/XaeroWaypoints.java")) continue;
				String src = Files.readString(file);
				if (src.contains("import xaero.") || src.contains("import xaeroplus.")) {
					fail("xaero import outside allowed packages: " + rel);
				}
			}
		}
	}
}
