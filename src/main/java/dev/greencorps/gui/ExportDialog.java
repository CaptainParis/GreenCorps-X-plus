package dev.greencorps.gui;

import dev.greencorps.backup.Home;
import dev.greencorps.chat.HomeChat;
import dev.greencorps.modules.BaseBackup;
import meteordevelopment.meteorclient.gui.GuiThemes;
import meteordevelopment.meteorclient.gui.WindowScreen;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import net.minecraft.client.MinecraftClient;

import java.nio.file.Path;

public class ExportDialog extends WindowScreen {
	private final BaseBackup mod;
	private final Home home;
	private final String when;

	public ExportDialog(BaseBackup mod, Home home, String when) {
		super(GuiThemes.get(), "Export " + home.name);
		this.mod = mod;
		this.home = home;
		this.when = when == null ? "latest" : when;
		this.parent = new BaseBackupScreen(mod, home.id);
	}

	@Override
	public void initWidgets() {
		WTable t = add(theme.table()).expandX().widget();
		t.add(theme.label("When")).widget().tooltip = "latest, #12, -3d, or ISO timestamp";
		WTextBox whenBox = t.add(theme.textBox(when)).expandX().widget();
		t.row();

		t.add(theme.label("Destination"));
		String def = MinecraftClient.getInstance().runDirectory.toPath().resolve("saves")
			.resolve(dev.greencorps.export.PlayableSavePublisher.autoFolderName(home)).toString();
		WTextBox dest = t.add(theme.textBox(def)).expandX().widget();
		t.row();

		WButton go = t.add(theme.button("Export")).expandX().widget();
		go.action = () -> {
			try {
				Path path = mod.exportHome(home, whenBox.get(), Path.of(dest.get()));
				HomeChat.info("Exported to " + path);
				close();
			} catch (Exception e) {
				HomeChat.warn("Export failed: " + e);
			}
		};
	}
}
