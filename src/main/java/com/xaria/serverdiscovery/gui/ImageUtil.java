package com.xaria.serverdiscovery.gui;

import com.mojang.blaze3d.platform.NativeImage;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Decodes image bytes into a {@link NativeImage}, tolerating formats other
 * than PNG.
 *
 * <p>Confirmed by a real crash log from this project: {@code NativeImage.read}
 * only understands PNG - handed a JPEG (the default thumbnail is a .jpg
 * URL), it threw {@code IOException: Bad PNG Signature} rather than
 * decoding it. Since NativeImage itself has no JPEG support to fall back
 * on, this re-encodes anything NativeImage rejects as PNG first, using the
 * JDK's own ImageIO (which reads JPEG/GIF/BMP natively) - a plain JDK
 * facility, not a Minecraft internal, so it isn't at risk of shifting
 * between versions the way everything else in this project has.
 */
public final class ImageUtil {

    private ImageUtil() {
    }

    public static NativeImage decode(byte[] bytes) throws IOException {
        try {
            return NativeImage.read(bytes);
        } catch (IOException notPng) {
            BufferedImage buffered = ImageIO.read(new ByteArrayInputStream(bytes));
            if (buffered == null) {
                throw notPng;
            }
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            ImageIO.write(buffered, "png", png);
            return NativeImage.read(png.toByteArray());
        }
    }
}
