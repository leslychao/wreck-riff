package game.wreckriff.diagnostics;

import com.jme3.texture.Image;
import com.jme3.texture.plugins.AWTLoader;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Compare actual jME-decoded channels, not merely PNG hashes or a few sampled texels. */
class DiffuseTextureAssetsTest {
    private static final List<String> MATERIALS = List.of("asphalt_02", "cracked_concrete",
            "metal_plate_02", "blue_metal_plate", "rusty_metal_03", "leafy_grass", "brown_mud",
            "gravelly_sand", "red_brick_03", "wood_planks_grey", "concrete_wall_009", "asphalt_pit_lane", "dirt", "grass_ground");
    private static final List<String> ORIGINAL_SIXTEEN_BIT=List.of("cracked_concrete","metal_plate_02","blue_metal_plate","rusty_metal_03");

    @Test void preservedIntermediateUsesEightBitPngWithoutChangingResolutionOrAlpha() throws Exception {
        for (String material : MATERIALS) {
            var original = ImageIO.read(source(material).toFile());
            var derivative = ImageIO.read(runtime(material).toFile());
            assertEquals(2048, derivative.getWidth(), material);
            assertEquals(2048, derivative.getHeight(), material);
            assertEquals(original.getColorModel().hasAlpha(), derivative.getColorModel().hasAlpha(), material);
            for (int bits : derivative.getSampleModel().getSampleSize())
                assertEquals(8, bits, "Runtime diffuse must avoid AWTLoader's per-pixel 16-bit conversion: " + material);
        }
    }

    @Test void allDecodedChannelsMatchTheOriginalThroughTheActualJmeLoaderInBothOrientations() throws Exception {
        for (String material : MATERIALS) for (boolean flip : List.of(false, true)) {
            Image original = load(source(material), flip);
            Image derivative = load(runtime(material), flip);
            assertEquals(original.getWidth(), derivative.getWidth(), material);
            assertEquals(original.getHeight(), derivative.getHeight(), material);
            assertEquals(original.getColorSpace(), derivative.getColorSpace(), material);
            assertArrayEquals(channels(original), channels(derivative), material + " flip=" + flip);
        }
    }

    @Test void alreadyEightBitAsphaltRemainsByteIdenticalToItsPreservedSource() throws Exception {
        assertArrayEquals(Files.readAllBytes(source("asphalt_02")), Files.readAllBytes(runtime("asphalt_02")));
    }

    @Test void provenanceRetainsOriginalHashesAndIdentifiesTheExactOfflineConversion() throws Exception {
        var manifest = JsonParser.parseString(Files.readString(
                Path.of("src/main/resources/licenses/asset-provenance.json"))).getAsJsonObject();
        int found = 0;
        for (var element : manifest.getAsJsonArray("assets")) {
            var item = element.getAsJsonObject();
            String path = item.get("path").getAsString();
            if (!path.startsWith("textures/materials/") || !(path.endsWith("/diffuse.dds")||path.endsWith("/diffuse.png"))) continue;
            found++;
            byte[] original = Files.readAllBytes(Path.of(item.get("sourcePath").getAsString()));
            if(path.endsWith(".png")){
                assertEquals("textures/materials/leafy_grass/diffuse.png",path);
                assertEquals(hash(original),item.get("sourceSha256").getAsString());
                assertEquals("CC0-1.0",item.get("license").getAsString());
                byte[] retained=Files.readAllBytes(Path.of("src/main/resources").resolve(path));
                assertEquals(8,retained[24]);assertEquals(hash(retained),item.get("sha256").getAsString());
                assertArrayEquals(channels(load(Path.of(item.get("sourcePath").getAsString()),false)),
                        channels(load(Path.of("src/main/resources").resolve(path),false)),"Retained PNG must preserve the original jME-decoded texels");
                continue;
            }
            var compressed=JsonParser.parseString(Files.readString(Path.of("src/main/resources/textures/materials/diffuse-provenance.json"))).getAsJsonObject();
            var intermediate=java.util.stream.StreamSupport.stream(compressed.getAsJsonArray("textures").spliterator(),false)
                    .map(elementValue->elementValue.getAsJsonObject()).filter(value->value.get("runtime").getAsString().equals(path)).findFirst().orElseThrow();
            byte[] derivative = Files.readAllBytes(Path.of(intermediate.get("source").getAsString()));
            assertEquals(hash(original), item.get("sourceSha256").getAsString(), path);
            assertEquals(hash(derivative), intermediate.get("sourceSha256").getAsString(), path);
            assertEquals("CC0-1.0", item.get("license").getAsString(), path);
            String material=path.split("/")[2];
            if(ORIGINAL_SIXTEEN_BIT.contains(material))assertEquals(16,original[24],"Preserve existing 16-bit original: "+path);
            if (original[24]==8) {
                assertArrayEquals(original,derivative,"An 8-bit original needs no pixel conversion: "+path);
                assertTrue(item.get("transformation").getAsString().startsWith("Unmodified 2K PNG; sRGB color"));
            } else {
                assertEquals(16, original[24], "High-bit-depth original must be preserved: " + path);
                assertTrue(item.get("transformation").getAsString().contains("BufferedImage.getRGB"), path);
                assertTrue(item.get("transformation").getAsString().contains("PrepareDiffuseTextures.java"), path);
                assertTrue(derivative.length < original.length / 2, "Expected compact lossless-to-runtime derivative: " + path);
            }
        }
        assertEquals(MATERIALS.size(), found);
    }

    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static Path source(String material) {
        return Path.of("src/tools/assets/materials", material, "diff.png");
    }

    private static Path runtime(String material) {
        if(material.equals("leafy_grass"))return Path.of("src/main/resources/textures/materials/leafy_grass/diffuse.png");
        return Path.of("src/tools/assets/materials", material, "runtime-diffuse.png");
    }

    private static Image load(Path path, boolean flip) throws Exception {
        try (var input = Files.newInputStream(path)) { return new AWTLoader().load(input, flip); }
    }

    private static byte[] channels(Image image) {
        ByteBuffer data = image.getData(0).duplicate();
        data.clear();
        byte[] result = new byte[data.remaining()];
        data.get(result);
        switch (image.getFormat()) {
            case BGR8 -> {
                for (int i = 0; i < result.length; i += 3) {
                    byte blue = result[i]; result[i] = result[i + 2]; result[i + 2] = blue;
                }
            }
            case ABGR8 -> {
                for (int i = 0; i < result.length; i += 4) {
                    byte alpha = result[i], blue = result[i + 1];
                    result[i] = result[i + 3]; result[i + 1] = result[i + 2];
                    result[i + 2] = blue; result[i + 3] = alpha;
                }
            }
            case RGB8, RGBA8 -> { }
            default -> fail("Unexpected diffuse format " + image.getFormat());
        }
        return result;
    }
}
