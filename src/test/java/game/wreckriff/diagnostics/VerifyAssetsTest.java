package game.wreckriff.diagnostics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

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
        String invalid = new String(fnt, StandardCharsets.UTF_8).replaceFirst("char id=32 x=\\d+", "char id=32 x=999");
        assertThrows(IOException.class, () -> VerifyAssets.verifyFontBytes(invalid.getBytes(StandardCharsets.UTF_8), png));
        assertThrows(IOException.class, () -> VerifyAssets.verifyFontBytes(fnt, new byte[]{0, 1, 2}));
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
