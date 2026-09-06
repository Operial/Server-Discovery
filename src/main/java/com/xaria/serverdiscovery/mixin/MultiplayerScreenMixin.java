package com.xaria.serverdiscovery.mixin;

import com.xaria.serverdiscovery.gui.DiscoveryScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds a "Discovery" button to the top-right corner of the vanilla
 * Multiplayer screen, next to (but not overlapping) the usual Direct
 * Connect / Add Server / Edit / Delete / Refresh / Cancel row at the bottom.
 *
 * <p>Confirmed via javap on the actual 26.2 jar: the screen you get from the
 * title screen's "Multiplayer" button is now called
 * {@code net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen}
 * (same package as before, just renamed - presumably to disambiguate from
 * the separate {@code MultiplayerOptionsScreen}). The server list itself
 * lives in a sibling class, {@code ServerSelectionList}, which this mixin
 * doesn't need to touch.
 *
 * <p>This mixin extends {@link Screen} purely so it can call the protected
 * {@code addRenderableWidget}/{@code width}/{@code minecraft} members it
 * inherits from it - it does not change how JoinMultiplayerScreen itself is
 * constructed.
 */
@Mixin(JoinMultiplayerScreen.class)
public abstract class MultiplayerScreenMixin extends Screen {

    protected MultiplayerScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void serverdiscovery$addDiscoveryButton(CallbackInfo ci) {
        this.addRenderableWidget(Button.builder(Component.translatable("serverdiscovery.button.discovery"),
                button -> this.minecraft.gui.setScreen(new DiscoveryScreen((JoinMultiplayerScreen) (Object) this))
        ).bounds(this.width - 105, 5, 100, 20).build());
    }
}
