package game.wreckriff.audio;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class PcmWaveTest {
    @Test void rewindClosesEveryPreviousHandleAndRemainsAtMostOneOpen() throws Exception {
        AtomicInteger opened=new AtomicInteger(),closed=new AtomicInteger(),peak=new AtomicInteger();
        byte[] bytes=wave(2,16,48000,new byte[]{1,2,3,4,5,6,7,8});
        PcmWave.Header header=PcmWave.header(new ByteArrayInputStream(bytes));
        PcmWave.Opener opener=()->{
            peak.accumulateAndGet(opened.incrementAndGet()-closed.get(),Math::max);
            return new ByteArrayInputStream(bytes) {
                boolean done;
                @Override public void close() { if(!done){closed.incrementAndGet();done=true;} }
            };
        };
        try(PcmWave.ReopeningStream stream=new PcmWave.ReopeningStream(opener,header)) {
            for(int loop=0;loop<64;loop++) {
                assertArrayEquals(new byte[]{1,2,3,4,5,6,7,8},stream.readAllBytes());
                stream.setTime(0);
            }
            assertEquals(1,opened.get()-closed.get());
        }
        assertEquals(opened.get(),closed.get());
        assertEquals(1,peak.get(),"Looping must not leak file descriptors");
    }

    @Test void seekUsesCompleteFramesAndNeverIncludesTrailingMetadata() throws Exception {
        byte[] wave=wave(2,16,48000,new byte[]{1,2,3,4,5,6,7,8});
        byte[] withTrailer=java.util.Arrays.copyOf(wave,wave.length+24);
        java.util.Arrays.fill(withTrailer,wave.length,withTrailer.length,(byte)99);
        PcmWave.Header header=PcmWave.header(new ByteArrayInputStream(withTrailer));
        try(PcmWave.ReopeningStream stream=new PcmWave.ReopeningStream(()->new ByteArrayInputStream(withTrailer),header)) {
            stream.setTime(1.1f/48000);
            assertArrayEquals(new byte[]{5,6,7,8},stream.readAllBytes());
            assertEquals(-1,stream.read());
            assertThrows(IllegalArgumentException.class,()->stream.setTime(-1));
            assertThrows(IllegalArgumentException.class,()->stream.setTime(Float.NaN));
        }
    }

    @Test void invalidFormatAndTruncatedPcmFailClearly() throws Exception {
        assertThrows(IOException.class,()->PcmWave.header(new ByteArrayInputStream(wave(2,16,44100,new byte[4]))));
        assertThrows(IOException.class,()->PcmWave.header(new ByteArrayInputStream(wave(3,16,48000,new byte[6]))));
        byte[] full=wave(1,16,48000,new byte[8]);
        PcmWave.Header header=PcmWave.header(new ByteArrayInputStream(full));
        byte[] truncated=java.util.Arrays.copyOf(full,full.length-4);
        try(PcmWave.ReopeningStream stream=new PcmWave.ReopeningStream(()->new ByteArrayInputStream(truncated),header)) {
            assertThrows(EOFException.class,stream::readAllBytes);
        }
    }

    @Test void failedReopenAlsoClosesCandidate() throws Exception {
        AtomicInteger open=new AtomicInteger(),closed=new AtomicInteger();
        byte[] data=wave(2,16,48000,new byte[8]);
        PcmWave.Header header=PcmWave.header(new ByteArrayInputStream(data));
        try(PcmWave.ReopeningStream stream=new PcmWave.ReopeningStream(()->new ByteArrayInputStream(
                open.getAndIncrement()==0?data:new byte[1]) {
                    @Override public void close(){closed.incrementAndGet();}
                },header)) {
            assertThrows(UncheckedIOException.class,()->stream.setTime(0));
        }
        assertEquals(open.get(),closed.get());
    }

    static byte[] wave(int channels,int bits,int rate,byte[] pcm) {
        ByteBuffer buffer=ByteBuffer.allocate(44+pcm.length).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put("RIFF".getBytes()).putInt(36+pcm.length).put("WAVEfmt ".getBytes()).putInt(16);
        buffer.putShort((short)1).putShort((short)channels).putInt(rate).putInt(rate*channels*bits/8);
        buffer.putShort((short)(channels*bits/8)).putShort((short)bits).put("data".getBytes()).putInt(pcm.length).put(pcm);
        return buffer.array();
    }
}
