package game.wreckriff.diagnostics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class VerifyAssetsTest {
    @TempDir Path directory;

    @Test void A02_readsActualPcmSamplesAndDistinguishesMonoFromStereo() throws Exception {
        Path mono = wav("mono.wav", 1, new short[]{16384, -16384, 8192, -8192});
        VerifyAssets.WaveMetrics metrics = VerifyAssets.inspectWave(mono.toUri().toURL());
        assertEquals(4, metrics.frames());
        assertEquals(1, metrics.channels());
        assertEquals(48_000, metrics.rate());
        assertEquals(16, metrics.bits());
        assertEquals(16384 / 32767.0, metrics.peak(), 1e-12);
        assertTrue(metrics.rms() > 0 && metrics.rms() < metrics.peak());
        assertEquals(64, metrics.sha256().length());
        Path stereo = wav("stereo.wav", 2, new short[]{16384, -16384, 8192, -8192});
        assertEquals(2, VerifyAssets.inspectWave(stereo.toUri().toURL()).frames());
        assertEquals(2, VerifyAssets.inspectWave(stereo.toUri().toURL()).channels());
    }

    @Test void A02_rejectsDigitalClippingSilenceAndTruncation() throws Exception {
        Path clipped = wav("clipped.wav", 1, new short[]{100, Short.MAX_VALUE});
        assertThrows(IOException.class, () -> VerifyAssets.inspectWave(clipped.toUri().toURL()));
        Path silence = wav("silence.wav", 1, new short[]{0, 0});
        assertThrows(IOException.class, () -> VerifyAssets.inspectWave(silence.toUri().toURL()));
        Path truncated = wav("truncated.wav", 1, new short[]{100, 200});
        byte[] bytes = Files.readAllBytes(truncated);
        Files.write(truncated, java.util.Arrays.copyOf(bytes, bytes.length - 2));
        assertThrows(IOException.class, () -> VerifyAssets.inspectWave(truncated.toUri().toURL()));
    }

    @Test void A02_rejectsAFalseRiffSizeEvenWhenDataChunkItselfFits() throws Exception {
        Path incorrect = wav("wrong-riff-size.wav", 1, new short[]{100, 200});
        byte[] bytes = Files.readAllBytes(incorrect);
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(4, 500);
        Files.write(incorrect, bytes);
        assertThrows(IOException.class, () -> VerifyAssets.inspectWave(incorrect.toUri().toURL()));
    }

    @Test void A01_generatedFontCoversAllVisibleAsciiWithActualPixels() throws Exception {
        byte[] fnt = bundled("fonts/wreck.fnt"), png = bundled("fonts/wreck.png");
        VerifyAssets.verifyFontBytes(fnt, png);
        String missing = new String(fnt, StandardCharsets.UTF_8).replaceAll("(?m)^char id=65 .*\\R", "");
        assertThrows(IOException.class, () -> VerifyAssets.verifyFontBytes(missing.getBytes(StandardCharsets.UTF_8), png));
    }

    @Test void A01_fontGlyphCannotPointOutsideAtlasOrUseMissingAtlas() throws Exception {
        byte[] fnt = bundled("fonts/wreck.fnt"), png = bundled("fonts/wreck.png");
        String invalid = new String(fnt, StandardCharsets.UTF_8).replaceFirst("char id=32 x=\\d+", "char id=32 x=9999");
        assertThrows(IOException.class, () -> VerifyAssets.verifyFontBytes(invalid.getBytes(StandardCharsets.UTF_8), png));
        assertThrows(IOException.class, () -> VerifyAssets.verifyFontBytes(fnt, new byte[]{0, 1, 2}));
    }
    @Test void fontRequiresActualCyrillicPixelsAndBothLicensedFaces() throws Exception {
        for(String face:List.of("wreck","wreck-bold")) {
            byte[] fnt=bundled("fonts/"+face+".fnt"),png=bundled("fonts/"+face+".png");
            VerifyAssets.verifyFontBytes(fnt,png);
            String missing=new String(fnt,StandardCharsets.UTF_8).replaceAll("(?m)^char id=1105 .*\\R", "");
            assertThrows(IOException.class,()->VerifyAssets.verifyFontBytes(missing.getBytes(StandardCharsets.UTF_8),png));
        }
    }
    @Test void materialValidationRejectsLowResolutionAndColorAsNormalData() throws Exception {
        VerifyAssets.verifyTextureBytes(bundled("textures/materials/asphalt_02/normal.png"),true);
        VerifyAssets.verifyTextureBytes(bundled("textures/materials/metal_plate_02/diffuse.png"),false);
        assertThrows(IOException.class,()->VerifyAssets.verifyTextureBytes(bundled("fonts/wreck.png"),false));
        assertThrows(IOException.class,()->VerifyAssets.verifyTextureBytes(bundled("textures/materials/asphalt_02/diffuse.png"),true));
    }
    @Test void fontSourceLicenseMustMatchTheActualFontRevision() throws Exception {
        for (String style : List.of("Regular", "Bold")) {
            byte[] ttf = Files.readAllBytes(Path.of("src/tools/assets/fonts/RobotoCondensed-" + style + ".ttf"));
            VerifyAssets.verifyFontSourceLicense(ttf);
            byte[] name = "SIL Open Font License, Version 1.1".getBytes(StandardCharsets.UTF_16BE);
            boolean replaced = false;
            for (int i = 0; i <= ttf.length - name.length; i++) {
                if (java.util.Arrays.equals(ttf, i, i + name.length, name, 0, name.length)) {
                    ttf[i + 1] = 'X'; replaced = true;
                }
            }
            assertTrue(replaced);
            assertThrows(IOException.class, () -> VerifyAssets.verifyFontSourceLicense(ttf));
        }
        assertThrows(IOException.class, () -> VerifyAssets.verifyFontSourceLicense(new byte[8]));
    }
    @Test void registrySeparatesLicensedRecordingFontAndTexturesFromOriginalProjectContent() {
        var music=new VerifyAssets.Asset("audio/metalmania.wav","licensed-music",100,"c".repeat(64),
                "https://incompetech.com/music/royalty-free/mp3-royaltyfree/Metalmania.mp3","LICENSED_DERIVED",2,10L,.5,.2);
        var texture=new VerifyAssets.Asset("textures/materials/cracked_concrete/diffuse.png","licensed-texture",100,"c".repeat(64),
                "https://polyhaven.com/a/cracked_concrete","LICENSED_BYTES_VERIFIED",null,null,null,null);
        var font=new VerifyAssets.Asset("fonts/wreck.fnt","bitmap-font",100,"c".repeat(64),"GenerateFont.java","LICENSED_DERIVED",null,null,null,null);
        var records=VerifyAssets.registerEntries(List.of(music,texture,font),"0.2.0");
        assertTrue(records.getFirst().author().contains("Kevin MacLeod"));
        assertTrue(records.getFirst().licensePermission().contains("CC-BY-4.0"));
        assertEquals("licenses/assets/CC-BY-4.0.txt",records.getFirst().licenseTextPath());
        assertTrue(records.get(1).author().contains("Dimitrios Savva"));
        assertTrue(records.get(1).licensePermission().contains("CC0-1.0"));
        assertTrue(records.getLast().licensePermission().contains("OFL-1.1"));
    }

    @Test void A01_registryDistinguishesActualPackagedBytesFromRuntimeGeometryRecipes() {
        String hash = "a".repeat(64);
        var mesh = new VerifyAssets.Asset("src/main/java/game/wreckriff/presentation/VehicleVisual.java", "procedural-source", 100, hash,
                "Original project source recipe", "SOURCE_PRESENT", null, null, null, null);
        var audio = new VerifyAssets.Asset("audio/music.wav", "music", 100, hash,
                "GenerateAudio.java; fixed score/seed", "ORIGINAL_GENERATED", 2, 10L, 0.5, 0.2);
        var records = VerifyAssets.registerEntries(List.of(mesh, audio), "0.1.0");
        var runtime = records.getFirst();
        assertEquals("runtime/VehicleVisual", runtime.assetId());
        assertEquals("", runtime.packagedPath(), "Runtime geometry has no packaged mesh file");
        assertEquals(mesh.path(), runtime.sourceOrigin());
        assertEquals(hash, runtime.sha256());
        assertTrue(runtime.hashScope().startsWith("SOURCE_GENERATOR_BYTES"));
        assertTrue(runtime.hashScope().contains("NOT_RUNTIME_MESH"));
        assertTrue(runtime.artifactForm().startsWith("RUNTIME_GEOMETRY_NOT_PACKAGED"));
        assertEquals("", runtime.licenseTextPath(), "No invented license is assigned to original project content");
        assertTrue(runtime.licensePermission().contains("no distribution license assigned"));
        assertEquals("OWNER_REVIEW_REQUIRED", runtime.attributionRequired());
        var resource = records.getLast();
        assertEquals("app/wreck-riff-0.1.0.jar!/audio/music.wav", resource.packagedPath());
        assertEquals("PACKAGED_RESOURCE_BYTES", resource.hashScope());
        assertEquals("PACKAGED_RESOURCE", resource.artifactForm());
    }

    @Test void A01_registryRetainsDependencyAuthorshipLicenseAndAttributionEvidence() {
        var material = new VerifyAssets.Asset("Common/MatDefs/Light/Lighting.j3md", "engine-material", 100, "b".repeat(64),
                "org.jmonkeyengine:jme3-core:3.8.1-stable", "DEPENDENCY_RESOURCE_PRESENT", null, null, null, null);
        var record = VerifyAssets.registerEntries(List.of(material), "0.1.0").getFirst();
        assertEquals("app/jme3-core-3.8.1-stable.jar!/Common/MatDefs/Light/Lighting.j3md", record.packagedPath());
        assertEquals("licenses/jme-BSD3.txt", record.licenseTextPath());
        assertTrue(record.author().contains("jMonkeyEngine"));
        assertTrue(record.licensePermission().contains("BSD-3-Clause"));
        assertEquals("RETAIN_COPYRIGHT_AND_LICENSE_NOTICE", record.attributionRequired());
        assertTrue(record.verificationStatus().contains("DISTRIBUTION_REVIEW_REQUIRED"));
    }

    private Path wav(String name, int channels, short[] samples) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(44 + samples.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + samples.length * 2).put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII));
        buffer.putInt(16).putShort((short) 1).putShort((short) channels).putInt(48_000).putInt(48_000 * channels * 2).putShort((short) (channels * 2)).putShort((short) 16);
        buffer.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(samples.length * 2);
        for (short sample : samples) buffer.putShort(sample);
        return Files.write(directory.resolve(name), buffer.array());
    }

    private static byte[] bundled(String path) throws IOException {
        try (InputStream input = VerifyAssetsTest.class.getClassLoader().getResourceAsStream(path)) {
            assertNotNull(input, "Generated resource must exist: " + path);
            return input.readAllBytes();
        }
    }
}
