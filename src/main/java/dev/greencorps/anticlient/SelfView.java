package dev.greencorps.anticlient;

/** Own position and the round-trip lead the model should assume. */
public record SelfView(double x, double y, double z, int rttTicks) {}
