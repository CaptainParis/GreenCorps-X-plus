package dev.greencorps.modules;

import dev.greencorps.modules.ItemFrameControl.Action;
import dev.greencorps.modules.ItemFrameControl.Content;
import dev.greencorps.modules.ItemFrameControl.EmptyStyle;
import dev.greencorps.modules.ItemFrameControl.FrameLook;
import dev.greencorps.modules.ItemFrameControl.HideMode;
import dev.greencorps.modules.ItemFrameControl.Rules;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemFrameControlTest {
	private static final Rules DEFAULTS = new Rules(FrameLook.HIDE_FRAME, false, false, HideMode.WHOLE_FRAME,
		EmptyStyle.ESP, true);

	private static Rules rules(FrameLook look, boolean maps, boolean items, HideMode mode, EmptyStyle empty) {
		return new Rules(look, maps, items, mode, empty, true);
	}

	private static Action decide(Content content, Rules rules) {
		return ItemFrameControl.decide(content, true, false, rules);
	}

	@Test
	void defaultsDropTheBorderKeepMapartAndMarkEmptyFrames() {
		assertEquals(Action.HIDE_FRAME, decide(Content.MAP, DEFAULTS));
		assertEquals(Action.HIDE_FRAME, decide(Content.ITEM, DEFAULTS));
		assertEquals(Action.ESP, decide(Content.EMPTY, DEFAULTS));
		assertEquals(Action.VANILLA, decide(Content.EMPTY, rules(FrameLook.HIDE_FRAME, false, false, HideMode.WHOLE_FRAME, EmptyStyle.VANILLA)));
		assertTrue(Action.HIDE_FRAME.hidesFrame());
		assertFalse(Action.HIDE_FRAME.hidesContents());
		assertFalse(Action.HIDE_FRAME.skipsVanilla());
		assertFalse(Action.HIDE_FRAME.drawsEsp());
	}

	@Test
	void vanillaLookLeavesFilledFramesAlone() {
		Rules vanilla = rules(FrameLook.VANILLA, false, false, HideMode.WHOLE_FRAME, EmptyStyle.VANILLA);
		assertEquals(Action.VANILLA, decide(Content.MAP, vanilla));
		assertEquals(Action.VANILLA, decide(Content.ITEM, vanilla));
	}

	@Test
	void noRenderIsOptInPerContent() {
		Rules maps = rules(FrameLook.HIDE_FRAME, true, false, HideMode.WHOLE_FRAME, EmptyStyle.VANILLA);
		assertEquals(Action.HIDE, decide(Content.MAP, maps));
		assertEquals(Action.HIDE_FRAME, decide(Content.ITEM, maps));

		Rules items = rules(FrameLook.VANILLA, false, true, HideMode.WHOLE_FRAME, EmptyStyle.VANILLA);
		assertEquals(Action.VANILLA, decide(Content.MAP, items));
		assertEquals(Action.HIDE, decide(Content.ITEM, items));
	}

	@Test
	void contentsOnlyKeepsTheFrame() {
		Rules contents = rules(FrameLook.HIDE_FRAME, true, true, HideMode.CONTENTS_ONLY, EmptyStyle.VANILLA);
		assertEquals(Action.HIDE_CONTENTS, decide(Content.MAP, contents));
		assertEquals(Action.HIDE_CONTENTS, decide(Content.ITEM, contents));
		assertFalse(Action.HIDE_CONTENTS.skipsVanilla());
		assertTrue(Action.HIDE_CONTENTS.hidesContents());
	}

	@Test
	void emptyStylesPickEspOrBoth() {
		Rules esp = rules(FrameLook.HIDE_FRAME, false, false, HideMode.WHOLE_FRAME, EmptyStyle.ESP);
		assertEquals(Action.ESP, decide(Content.EMPTY, esp));
		assertTrue(Action.ESP.skipsVanilla());
		assertTrue(Action.ESP.drawsEsp());
		assertFalse(Action.ESP.hidesContents());

		Rules both = rules(FrameLook.HIDE_FRAME, false, false, HideMode.WHOLE_FRAME, EmptyStyle.ESP_AND_VANILLA);
		assertEquals(Action.ESP_AND_VANILLA, decide(Content.EMPTY, both));
		assertFalse(Action.ESP_AND_VANILLA.skipsVanilla());
		assertTrue(Action.ESP_AND_VANILLA.drawsEsp());

		assertEquals(Action.HIDE_FRAME, decide(Content.MAP, esp));
	}

	@Test
	void disabledFrameTypeStaysVanilla() {
		Rules esp = rules(FrameLook.HIDE_FRAME, true, true, HideMode.WHOLE_FRAME, EmptyStyle.ESP);
		assertEquals(Action.VANILLA, ItemFrameControl.decide(Content.MAP, false, false, esp));
		assertEquals(Action.VANILLA, ItemFrameControl.decide(Content.EMPTY, false, false, esp));
	}

	@Test
	void hiddenFramesFollowClickThroughAndSneakEdits() {
		assertTrue(ItemFrameControl.passesThrough(Action.HIDE_FRAME, true, false, false));
		assertTrue(ItemFrameControl.passesThrough(Action.HIDE, true, false, false));
		assertTrue(ItemFrameControl.passesThrough(Action.HIDE_CONTENTS, true, false, false));
		assertFalse(ItemFrameControl.passesThrough(Action.HIDE_FRAME, true, false, true));
		assertFalse(ItemFrameControl.passesThrough(Action.HIDE, true, false, true));
		assertFalse(ItemFrameControl.passesThrough(Action.HIDE_FRAME, false, false, false));
		assertFalse(ItemFrameControl.passesThrough(Action.VANILLA, true, false, false));
		assertFalse(ItemFrameControl.passesThrough(Action.ESP, true, false, false));
	}

	@Test
	void storageFramesPassThroughUnlessEditing() {
		assertTrue(ItemFrameControl.passesThrough(Action.VANILLA, true, true, false));
		assertTrue(ItemFrameControl.passesThrough(Action.VANILLA, false, true, false));
		assertFalse(ItemFrameControl.passesThrough(Action.VANILLA, true, true, true));
		assertTrue(ItemFrameControl.passesThrough(Action.HIDE_FRAME, false, true, false));
		assertFalse(ItemFrameControl.passesThrough(Action.HIDE_FRAME, false, true, true));
	}

	@Test
	void invisibleEmptyFramesFollowIncludeInvisible() {
		Rules with = new Rules(FrameLook.HIDE_FRAME, false, false, HideMode.WHOLE_FRAME, EmptyStyle.ESP, true);
		Rules without = new Rules(FrameLook.HIDE_FRAME, false, false, HideMode.WHOLE_FRAME, EmptyStyle.ESP, false);
		assertEquals(Action.ESP, ItemFrameControl.decide(Content.EMPTY, true, true, with));
		assertEquals(Action.VANILLA, ItemFrameControl.decide(Content.EMPTY, true, true, without));
		assertEquals(Action.HIDE_FRAME, ItemFrameControl.decide(Content.MAP, true, true, without));
	}
}
