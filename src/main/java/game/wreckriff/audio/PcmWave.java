package game.wreckriff.audio;

import com.jme3.asset.AssetInfo;
import com.jme3.audio.AudioStream;
import com.jme3.audio.SeekableStream;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Narrow PCM WAV contract used for our generated assets and configurable replacement music. */
public final class PcmWave {
    public record Header(int channels, int rate, int bits, long dataOffset, long dataBytes) {
        public int frameBytes() { return channels * bits / 8; }
        public float seconds() { return dataBytes / (float) (frameBytes() * rate); }
    }
    @FunctionalInterface public interface Opener { InputStream open() throws IOException; }
    private PcmWave() {}

    public static Header header(InputStream input) throws IOException {
        DataInputStream in = new DataInputStream(input);
        if (!four(in).equals("RIFF")) throw new IOException("Expected RIFF WAV");
        long riffBytes = unsignedInt(in);
        if (!four(in).equals("WAVE")) throw new IOException("Expected WAVE");
        long offset = 12;
        int channels = 0, rate = 0, bits = 0;
        while (offset + 8 <= riffBytes + 8) {
            String chunk = four(in);
            long bytes = unsignedInt(in);
            offset += 8;
            if (bytes > riffBytes + 8 - offset) throw new IOException("WAV chunk exceeds RIFF bounds");
            if (chunk.equals("fmt ")) {
                if (bytes < 16 || bytes > 4096) throw new IOException("Invalid WAV format chunk");
                int encoding = unsignedShort(in);
                channels = unsignedShort(in); rate = (int) unsignedInt(in);
                long bytesPerSecond = unsignedInt(in);
                int frameBytes = unsignedShort(in); bits = unsignedShort(in);
                if (encoding != 1 || channels < 1 || channels > 2 || rate != 48000 || bits != 16
                        || frameBytes != channels * 2 || bytesPerSecond != (long) rate * frameBytes)
                    throw new IOException("Audio requires 48 kHz 16-bit mono/stereo PCM");
                in.skipNBytes(bytes - 16);
            } else if (chunk.equals("data")) {
                if (channels == 0 || bytes == 0 || bytes % (channels * 2) != 0)
                    throw new IOException("Invalid WAV data chunk");
                return new Header(channels, rate, bits, offset, bytes);
            } else in.skipNBytes(bytes);
            if ((bytes & 1) != 0) in.skipNBytes(1);
            offset += bytes + (bytes & 1);
        }
        throw new IOException("WAV data chunk missing");
    }

    public static AudioStream stream(AssetInfo asset) throws IOException {
        if (asset == null) throw new FileNotFoundException("Music asset missing");
        Header format;
        try (InputStream input = asset.openStream()) { format = header(input); }
        if (format.channels != 2) throw new IOException("Music must be stereo");
        AudioStream stream = new AudioStream();
        stream.setupFormat(format.channels, format.bits, format.rate);
        stream.updateData(new ReopeningStream(asset::openStream, format), format.seconds());
        return stream;
    }

    /** At most one owned file handle, including every automatic OpenAL loop rewind. */
    public static final class ReopeningStream extends InputStream implements SeekableStream {
        private final Opener opener;
        private final Header format;
        private InputStream current;
        private long remaining;
        private boolean closed;
        public ReopeningStream(Opener opener, Header format) throws IOException {
            this.opener = opener; this.format = format;
            reopen(0);
        }
        private void reopen(long byteOffset) throws IOException {
            if (current != null) { current.close(); current = null; }
            InputStream candidate = new BufferedInputStream(opener.open());
            try {
                candidate.skipNBytes(format.dataOffset + byteOffset);
                current = candidate;
                remaining = format.dataBytes - byteOffset;
            } catch (IOException | RuntimeException e) { candidate.close(); throw e; }
        }
        @Override public synchronized void setTime(float seconds) {
            if (closed) throw new IllegalStateException("Music stream is closed");
            if (!Float.isFinite(seconds) || seconds < 0 || seconds > format.seconds())
                throw new IllegalArgumentException("Music position outside track");
            long frame = Math.min(format.dataBytes / format.frameBytes(), (long) (seconds * format.rate));
            try { reopen(frame * format.frameBytes()); }
            catch (IOException e) { throw new UncheckedIOException("Cannot rewind music", e); }
        }
        @Override public synchronized int read() throws IOException {
            if (closed || remaining == 0) return -1;
            int value = current.read();
            if (value >= 0) remaining--; else throw new EOFException("Truncated music PCM");
            return value;
        }
        @Override public synchronized int read(byte[] target, int offset, int count) throws IOException {
            if (count == 0) return 0;
            if (closed || remaining == 0) return -1;
            int read = current.read(target, offset, (int) Math.min(count, remaining));
            if (read > 0) remaining -= read; else if (read < 0) throw new EOFException("Truncated music PCM");
            return read;
        }
        @Override public synchronized void close() throws IOException {
            closed = true;
            if (current != null) { current.close(); current = null; }
        }
    }
    private static String four(DataInputStream in) throws IOException {
        byte[] bytes = in.readNBytes(4);
        if (bytes.length != 4) throw new EOFException("Truncated WAV header");
        return new String(bytes, StandardCharsets.US_ASCII);
    }
    private static long unsignedInt(DataInputStream in) throws IOException { return Integer.toUnsignedLong(Integer.reverseBytes(in.readInt())); }
    private static int unsignedShort(DataInputStream in) throws IOException { return Short.toUnsignedInt(Short.reverseBytes(in.readShort())); }
}
