package dev.greencorps.compat;

import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/** Disables Xaero GuiMap mixins when xaeroworldmap is absent. */
public class GcMixinPlugin implements IMixinConfigPlugin {
	private static final boolean XAERO_WM = FabricLoader.getInstance().isModLoaded("xaeroworldmap");

	@Override
	public void onLoad(String mixinPackage) {}

	@Override
	public String getRefMapperConfig() {
		return null;
	}

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		if (mixinClassName.endsWith("MixinGuiMapRenderGc")
			|| mixinClassName.endsWith("MixinScreenRenderGc")
			|| mixinClassName.endsWith("MixinGuiMapRightClickGc")
			|| mixinClassName.endsWith("MixinWaypointReaderHome")) {
			return XAERO_WM;
		}
		return true;
	}

	@Override
	public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}

	@Override
	public List<String> getMixins() {
		return null;
	}

	@Override
	public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}

	@Override
	public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
