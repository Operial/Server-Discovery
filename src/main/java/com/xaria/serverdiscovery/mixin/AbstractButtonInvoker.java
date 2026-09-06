package com.xaria.serverdiscovery.mixin;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Bridges to the protected {@code extractWidgetRenderState} on AbstractButton
 * (confirmed by the compiler error itself: right name, right signature,
 * just protected) so DiscoveryServerList can render its manually-positioned
 * per-row Join/Add buttons - normal buttons render automatically via
 * addRenderableWidget, but these move every frame with list scrolling and
 * need to be driven directly. This is exactly what Mixin's @Invoker is for:
 * it generates a public bridge method to a protected/private target.
 */
@Mixin(AbstractButton.class)
public interface AbstractButtonInvoker {
    @Invoker("extractWidgetRenderState")
    void serverdiscovery$extractWidgetRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick);
}
