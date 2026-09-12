package game.wreckriff.audio;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BossTelegraphAudioAssetsTest {
    @Test void originalBossWarningHasExactDurationQuietEdgesAndVerifiableSourceAndOutputHashes()throws Exception {
        String path="audio/boss-telegraph.wav",generator="src/tools/java/game/wreckriff/tools/GenerateAudio.java";
        assertEquals(List.of("boss-telegraph"),AudioConfig.load().cueBanks().get("boss-telegraph"));
        byte[] wave=resource(path),pcm;
        try(InputStream input=new ByteArrayInputStream(wave)) {
            PcmWave.Header header=PcmWave.header(input);
            assertEquals(48000,header.rate());assertEquals(16,header.bits());assertEquals(1,header.channels());
            assertEquals(.85,header.seconds(),1.0/48000);
            pcm=input.readNBytes((int)header.dataBytes());assertEquals(40800*2,pcm.length);
        }
        double peak=0,squares=0,mean=0;
        for(int i=0;i<pcm.length;i+=2) {
            double sample=sample(pcm,i)/32768.0;peak=Math.max(peak,Math.abs(sample));squares+=sample*sample;mean+=sample;
        }
        double rms=Math.sqrt(squares/(pcm.length/2));
        assertTrue(peak>.2&&peak<=.82004);assertTrue(rms>=.12&&rms<=.16004);
        assertTrue(Math.abs(mean/(pcm.length/2))<.00004);assertEquals(0,sample(pcm,0));assertEquals(0,sample(pcm,pcm.length-2));
        var evidence=JsonParser.parseString(new String(resource("audio/boss-telegraph-provenance.json"),StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals(1,evidence.get("schemaVersion").getAsInt());assertEquals("ORIGINAL_PROJECT_CONTENT",evidence.get("origin").getAsString());
        assertFalse(evidence.get("externalSamples").getAsBoolean());assertFalse(evidence.get("looping").getAsBoolean());
        assertEquals("NEEDS_CREATIVE_REVIEW",evidence.get("artisticStatus").getAsString());
        assertEquals(generator,evidence.get("generator").getAsString());assertEquals(hash(Files.readAllBytes(Path.of(generator))),evidence.get("generatorSha256").getAsString());
        assertEquals(1,evidence.getAsJsonArray("assets").size());var item=evidence.getAsJsonArray("assets").get(0).getAsJsonObject();
        assertEquals(path,item.get("path").getAsString());assertEquals(40800,item.get("frames").getAsInt());assertEquals(hash(wave),item.get("sha256").getAsString());
        assertFalse(item.get("recipe").getAsString().isBlank());
    }
    private static byte[] resource(String path)throws IOException {
        try(InputStream input=Objects.requireNonNull(BossTelegraphAudioAssetsTest.class.getResourceAsStream("/"+path),path)) {return input.readAllBytes();}
    }
    private static short sample(byte[] pcm,int index) {return (short)((pcm[index]&255)|(pcm[index+1]<<8));}
    private static String hash(byte[] bytes)throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
}
