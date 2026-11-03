package dev.greencorps.anticlient;

public interface LineSink {
	void write(String line);

	void flush();

	void close();
}
