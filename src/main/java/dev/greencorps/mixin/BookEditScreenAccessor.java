package dev.greencorps.mixin;

import net.minecraft.client.gui.screen.ingame.BookEditScreen;
import net.minecraft.util.Hand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;

@Mixin(BookEditScreen.class)
public interface BookEditScreenAccessor {
	@Accessor("pages")
	List<String> greencorps$getPages();

	@Accessor("currentPage")
	void greencorps$setCurrentPage(int page);

	@Accessor("currentPage")
	int greencorps$getCurrentPage();

	@Accessor("dirty")
	boolean greencorps$isDirty();

	@Accessor("title")
	String greencorps$getTitle();

	@Accessor("title")
	void greencorps$setTitle(String title);

	@Accessor("hand")
	Hand greencorps$getHand();

	@Accessor("dirty")
	void greencorps$setDirty(boolean dirty);

	@Invoker("invalidatePageContent")
	void greencorps$invalidatePageContent();

	@Invoker("updateButtons")
	void greencorps$updateButtons();

	@Invoker("finalizeBook")
	void greencorps$finalizeBook(boolean signBook);
}
