package dev.greencorps.anticlient;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Append-only UTF-8 JSONL file. Failures disable the sink instead of crashing the game. */
public final class FileSink implements LineSink {
	private BufferedWriter out;
	private final Path path;
	private boolean broken;

	public FileSink(Path path) throws IOException {
		this.path = path;
		Files.createDirectories(path.getParent());
		this.out = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
			StandardOpenOption.CREATE, StandardOpenOption.APPEND);
	}

	public Path path() {
		return path;
	}

	@Override
	public void write(String line) {
		if (broken) return;
		try {
			out.write(line);
			out.write('\n');
		} catch (IOException e) {
			broken = true;
		}
	}

	@Override
	public void flush() {
		if (broken) return;
		try {
			out.flush();
		} catch (IOException e) {
			broken = true;
		}
	}

	@Override
	public void close() {
		try {
			out.close();
		} catch (IOException ignored) {
			broken = true;
		}
	}
}
