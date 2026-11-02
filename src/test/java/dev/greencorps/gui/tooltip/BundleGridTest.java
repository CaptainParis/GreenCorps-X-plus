package dev.greencorps.gui.tooltip;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BundleGridTest {
	@Test
	void eightPerRowNarrowerOnlyBelowEight() {
		assertEquals(1, BundleGrid.columns(1));
		assertEquals(5, BundleGrid.columns(5));
		assertEquals(1, BundleGrid.rows(5));
		assertEquals(8, BundleGrid.columns(8));
		assertEquals(8, BundleGrid.columns(10));
		assertEquals(2, BundleGrid.rows(10));
		assertEquals(8, BundleGrid.columns(64));
		assertEquals(8, BundleGrid.rows(64));
	}

	@Test
	void readingOrderFromTopLeft() {
		assertEquals(0, BundleGrid.col(0, 10));
		assertEquals(0, BundleGrid.row(0, 10));
		assertEquals(7, BundleGrid.col(7, 10));
		assertEquals(0, BundleGrid.col(8, 10));
		assertEquals(1, BundleGrid.row(9, 10));
	}

	@Test
	void fillLabel() {
		assertEquals("32/64 (50%) · 2 stacks", BundleGrid.fillLabel(0.5f, 2));
		assertEquals("Full · 64/64 · 1 stack", BundleGrid.fillLabel(1f, 1));
	}
}
