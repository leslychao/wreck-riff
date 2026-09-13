package game.wreckriff.tools;

import javax.imageio.ImageIO;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Offline importer helper. Never invoked by a build or the game. */
public final class PrepareDiffuseTextures {
    private PrepareDiffuseTextures() { }

    public static void main(String[] args) throws IOException {
        if (Runtime.version().feature() != 21 || !"Microsoft".equals(System.getProperty("java.vendor")))
            throw new IllegalStateException("Use the project's Microsoft JDK 21 to prepare diffuse textures");
        if (args.length != 2) throw new IllegalArgumentException("Expected source PNG and destination PNG");
        Path source = Path.of(args[0]).toRealPath();
        Path destination = Path.of(args[1]).toAbsolutePath().normalize();
        if (source.equals(destination) || (Files.exists(destination) && Files.isSameFile(source, destination)))
            throw new IllegalArgumentException("Preserve the original; source and destination must differ");
        ImageIO.setUseCache(false);
        BufferedImage input = ImageIO.read(source.toFile());
        if (input == null || input.getWidth() != 2048 || input.getHeight() != 2048
                || input.getColorModel().getColorSpace().getType() != ColorSpace.TYPE_RGB)
            throw new IOException("Expected a 2K RGB(A) diffuse PNG: " + source);
        BufferedImage output = new BufferedImage(input.getWidth(), input.getHeight(),
                input.getColorModel().hasAlpha() ? BufferedImage.TYPE_4BYTE_ABGR : BufferedImage.TYPE_3BYTE_BGR);
        // AWTLoader 3.8.1 uses BufferedImage.getRGB for RGB(A)16, then stores RGB(A)8.
        // Do that same conversion once offline, preserving alpha and avoiding Pillow's
        // different RGB16 truncation. No resizing, gamma operation, tint or dithering.
        int[] row = new int[input.getWidth()];
        for (int y = 0; y < input.getHeight(); y++) {
            input.getRGB(0, y, input.getWidth(), 1, row, 0, input.getWidth());
            output.setRGB(0, y, input.getWidth(), 1, row, 0, input.getWidth());
        }
        Files.createDirectories(destination.getParent());
        if (!ImageIO.write(output, "png", destination.toFile())) throw new IOException("PNG writer unavailable");
    }
}
