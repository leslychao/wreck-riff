package game.wreckriff.audio;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.security.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AudioAssetsTest {
    @Test void a01AllRequiredEffectsAreDistinctMonoPcmWithoutClipping() throws Exception {
        AudioConfig config=AudioConfig.load();
        Set<String> hashes=new HashSet<>();
        assertTrue(config.effects().size()>=24);
        for(String effect:config.effects()) {
            try(InputStream input=asset("audio/"+effect+".wav")) {
                PcmWave.Header header=PcmWave.header(input);
                assertEquals(1,header.channels(),effect); assertEquals(16,header.bits()); assertEquals(48000,header.rate());
                assertTrue(header.seconds()>=.1f && header.seconds()<=3,effect);
                byte[] pcm=input.readNBytes((int)header.dataBytes());
                assertEquals(header.dataBytes(),pcm.length,effect);
                assertTrue(hashes.add(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(pcm))),effect);
                Metrics metrics=metrics(pcm);
                assertTrue(metrics.peak>.2 && metrics.peak<.95,effect+" peak "+metrics.peak);
                assertTrue(metrics.rms>.025,effect+" unexpectedly silent");
                assertTrue(Math.abs(metrics.mean)<.002,effect+" DC offset");
            }
        }
    }

    @Test void a01MusicIsACompleteStereoScoreWithEnergyAndNonIdenticalChannels() throws Exception {
        try(InputStream input=asset(AudioConfig.load().musicAsset())) {
            PcmWave.Header header=PcmWave.header(input);
            assertEquals(2,header.channels()); assertEquals(180,header.seconds(),.001);
            byte[] pcm=input.readNBytes((int)header.dataBytes());
            assertEquals(header.dataBytes(),pcm.length);
            Metrics total=metrics(pcm);
            assertTrue(total.peak>.75 && total.peak<.9); assertTrue(total.rms>.08);
            int difference=0;
            for(int i=0;i<pcm.length;i+=4) if(sample(pcm,i)!=sample(pcm,i+2)) difference++;
            assertTrue(difference>pcm.length/8,"Music must contain an actual stereo mix");
            int sectionBytes=48000*4*15;
            for(int start=0;start<pcm.length;start+=sectionBytes) {
                Metrics section=metrics(Arrays.copyOfRange(pcm,start,Math.min(pcm.length,start+sectionBytes)));
                assertTrue(section.rms>.035,"Silent or unfinished score section at "+start/(48000*4));
            }
            for(int channel=0;channel<2;channel++) {
                double step=Math.abs(sample(pcm,channel*2)-sample(pcm,pcm.length-4+channel*2))/32768.0;
                assertTrue(step<.04,"Large sample jump at music loop seam: "+step);
            }
        }
    }

    @Test void continuousEffectsHaveQuietSampleBoundary() throws Exception {
        for(String effect:List.of("engine-idle","engine-drive","turbo-loop","tyre-slip","hazard-active")) {
            try(InputStream input=asset("audio/"+effect+".wav")) {
                PcmWave.Header header=PcmWave.header(input);
                byte[] pcm=input.readNBytes((int)header.dataBytes());
                assertTrue(Math.abs(sample(pcm,0)-sample(pcm,pcm.length-2))<=2,effect);
            }
        }
    }

    @Test void scoreAndSignalProvenanceAreBundled() throws Exception {
        try(InputStream input=asset("audio/score.txt")) {
            String score=new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(score.contains("96 bars")); assertTrue(score.contains("NEEDS_CREATIVE_REVIEW"));
        }
        try(InputStream input=asset("audio/audio-metrics.csv")) {
            assertEquals(26,new String(input.readAllBytes()).lines().count());
        }
    }
    private static InputStream asset(String path) { return Objects.requireNonNull(AudioAssetsTest.class.getResourceAsStream("/"+path),path); }
    private record Metrics(double peak,double rms,double mean) {}
    private static Metrics metrics(byte[] pcm) {
        double peak=0,sum=0,squares=0;
        for(int i=0;i<pcm.length;i+=2) { double s=sample(pcm,i)/32768.0;peak=Math.max(peak,Math.abs(s));sum+=s;squares+=s*s; }
        return new Metrics(peak,Math.sqrt(squares/(pcm.length/2)),sum/(pcm.length/2));
    }
    private static short sample(byte[] bytes,int offset) {return(short)((bytes[offset]&255)|(bytes[offset+1]<<8));}
}
