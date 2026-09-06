package com.xaria.serverdiscovery.gui;

import com.mojang.blaze3d.platform.NativeImage;
import com.xaria.serverdiscovery.ServerDiscoveryMod;
import com.xaria.serverdiscovery.api.HttpUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one thumbnail every card without a better image (no Lunar background/
 * logo, no live favicon) falls back to. Fetched and uploaded once and
 * reused by every such card, rather than re-fetching the same picture per
 * server. The URL is a .jpg - see {@link ImageUtil} for why that needs
 * handling specially (NativeImage on its own only understands PNG).
 */
public final class DefaultThumbnail {

    private static final Logger LOGGER = LoggerFactory.getLogger("serverdiscovery/thumbnail");
    private static final String URL = "https://wallpapercave.com/wp/wp6143873.jpg";
    public static final Identifier ID = Identifier.fromNamespaceAndPath(ServerDiscoveryMod.MOD_ID, "default_thumbnail");

    private static volatile boolean requested = false;
    private static volatile boolean ready = false;
    private static volatile int width = 1;
    private static volatile int height = 1;

    private DefaultThumbnail() {
    }

    public static boolean isReady() {
        return ready;
    }

    public static int getWidth() {
        return width;
    }

    public static int getHeight() {
        return height;
    }

    /** Kicks off the one-time fetch+upload the first time any card needs it. */
    public static void ensureRequested() {
        if (requested) {
            return;
        }
        requested = true;
        HttpUtil.fetchBytes(URL).thenAccept(bytes -> Minecraft.getInstance().execute(() -> {
            try {
                NativeImage image = ImageUtil.decode(bytes);
                Minecraft.getInstance().getTextureManager().register(ID, new DynamicTexture(() -> "serverdiscovery default thumbnail", image));
                width = Math.max(1, image.getWidth());
                height = Math.max(1, image.getHeight());
                ready = true;
            } catch (Exception e) {
                LOGGER.warn("Could not decode the default thumbnail image", e);
            }
        })).exceptionally(e -> {
            LOGGER.warn("Could not fetch the default thumbnail image", e);
            return null;
        });
    }
}
