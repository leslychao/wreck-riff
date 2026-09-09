package game.wreckriff.audio;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.security.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AudioAssetsTest {
    @Test void allFiveCampaignScoresHaveCompleteDistinctSeamlessNormalAndBossMixes() throws Exception {
        var registry=game.wreckriff.arena.ArenaRegistry.load();
        Set<String> hashes=new HashSet<>();int maps=0;
        for(var entry:registry.entries()) {
            if(!entry.campaign())continue;maps++;
            var arena=registry.definition(entry.id());long frames=-1;
            for(String path:List.of(arena.metadata().music(),arena.metadata().bossMusic())) {
                try(InputStream input=asset(path)) {
                    PcmWave.Header header=PcmWave.header(input);assertEquals(2,header.channels());
                    assertTrue(header.seconds()>=60&&header.seconds()<=180,path+" is not a complete loop");
                    if(frames>=0)assertEquals(frames,header.dataBytes()/4,"Normal/boss timeline must match exactly");
                    frames=header.dataBytes()/4;
                    byte[] pcm=input.readNBytes((int)header.dataBytes());assertEquals(header.dataBytes(),pcm.length);
                    assertTrue(hashes.add(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(pcm))),path+" repeats another score/mix");
                    Metrics signal=metrics(pcm);assertTrue(signal.peak>.65&&signal.peak<.9,path);assertTrue(signal.rms>.07,path);
                    assertTrue(Math.abs(signal.mean)<.002,path+" DC offset");
                    int stereoFrames=0;for(int i=0;i<pcm.length;i+=4)if(sample(pcm,i)!=sample(pcm,i+2))stereoFrames++;
                    assertTrue(stereoFrames>frames/2,path+" must contain a stereo arrangement");
                    for(int channel=0;channel<2;channel++)assertTrue(Math.abs(sample(pcm,channel*2)-sample(pcm,pcm.length-4+channel*2))<32768*.04,path+" loop seam");
                    int block=48000*4*8;
                    for(int offset=0;offset<pcm.length;offset+=block)
                        assertTrue(metrics(Arrays.copyOfRange(pcm,offset,Math.min(pcm.length,offset+block))).rms>.035,path+" unfinished section");
                }
            }
        }
        assertEquals(5,maps);assertEquals(10,hashes.size());
        try(InputStream input=asset("audio/campaign/provenance.json")) {
            var provenance=com.google.gson.JsonParser.parseReader(new InputStreamReader(input,java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            assertFalse(provenance.get("externalSamples").getAsBoolean());assertEquals(10,provenance.getAsJsonArray("assets").size());
            assertEquals("NEEDS_CREATIVE_REVIEW",provenance.get("artisticStatus").getAsString());
        }
    }

    @Test void a01AllRequiredEffectsAreDistinctMonoPcmWithoutClipping() throws Exception {
        AudioConfig config=AudioConfig.load();
        Set<String> hashes=new HashSet<>();
        Map<String,Integer> resultFrames=Map.of("victory",172_800,"defeat",124_800,"draw",96_000);
        assertEquals(92,config.effects().size());
        for(String effect:config.effects()) {
            try(InputStream input=asset("audio/"+effect+".wav")) {
                PcmWave.Header header=PcmWave.header(input);
                assertEquals(1,header.channels(),effect); assertEquals(16,header.bits()); assertEquals(48000,header.rate());
                if(resultFrames.containsKey(effect))
                    assertEquals(resultFrames.get(effect)/48_000.0,header.seconds(),1.0/48_000,effect+" result duration");
                else assertTrue(header.seconds()>=.1f && header.seconds()<=3,effect);
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
            assertEquals(2,header.channels()); assertEquals(179.2,header.seconds(),.001);
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
        for(String effect:List.of("engine-idle","engine-drive","turbo-loop","tyre-slip","hazard-active","napalm-fire")) {
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
            assertTrue(score.contains("Metalmania") && score.contains("CC BY 4.0") && score.contains("112 bars")); assertTrue(score.contains("NEEDS_CREATIVE_REVIEW"));
        }
        try(InputStream input=asset("audio/audio-metrics.csv")) {
            assertEquals(AudioConfig.load().effects().size()+2,new String(input.readAllBytes()).lines().count());
        }
    }
    @Test void licensedRecordingReplacesRejectedSongAndOverheatIsAbsent() throws Exception {
        assertEquals("audio/metalmania.wav",AudioConfig.load().musicAsset());
        assertFalse(AudioConfig.load().effects().contains("overheat"));
        assertNull(getClass().getResource("/audio/dead-air-circuit.wav"));
        assertNull(getClass().getResource("/audio/overheat.wav"));
        try(InputStream input=asset("audio/music-provenance.json")) {
            String evidence=new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(evidence.contains("Kevin MacLeod"));assertTrue(evidence.contains("CC-BY-4.0"));
            assertTrue(evidence.contains("sourceSha256"));assertTrue(evidence.contains("NEEDS_CREATIVE_REVIEW"));
        }
    }
    @Test void repeatedCombatCuesHaveThreeDistinctRecordedTakesAndRetiredCuesAreAbsent() throws Exception {
        AudioConfig config=AudioConfig.load();
        for(String cue:List.of("machine-gun","metal-hit","explosion","destroyed","homing-launch","power-launch",
                "power-explosion","mine-detonate","napalm-launch","napalm-explosion",
                "cannon-launch","cannon-ricochet","cannon-hit","ballistic-launch","ballistic-fall","ballistic-explosion","ram-hit")) {
            assertEquals(3,config.cueBanks().get(cue).size(),cue);
        }
        for(String retired:List.of("pulse","stun","freeze","shield","machine-gun","metal-hit","explosion")) {
            assertFalse(config.effects().contains(retired));
            assertNull(getClass().getResource("/audio/"+retired+".wav"));
        }
        try(InputStream input=asset("audio/sfx-provenance.json")) {
            String evidence=new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(evidence.contains("CC0-1.0") && evidence.contains("sourceEvidenceSha256"));
            assertTrue(evidence.contains("freeze-hit.wav") && evidence.contains("shield-end.wav"));
        }
    }
    @Test void weaponPickupsHaveEighteenDistinctShortTakesWithQuietEndsAndMatchedEnergy()throws Exception {
        AudioConfig config=AudioConfig.load();Set<String> hashes=new HashSet<>();
        for(String kind:List.of("homing","power","mine","napalm","ballistic","cannon")) {
            List<String> bank=config.cueBanks().get("pickup-"+kind);assertNotNull(bank);assertEquals(3,bank.size());
            for(String take:bank)try(InputStream input=asset("audio/"+take+".wav")) {
                PcmWave.Header header=PcmWave.header(input);
                assertTrue(header.seconds()>=.25&&header.seconds()<=.6,take);
                assertEquals(1,header.channels());assertEquals(48000,header.rate());assertEquals(16,header.bits());
                byte[] pcm=input.readNBytes((int)header.dataBytes());assertEquals(header.dataBytes(),pcm.length);
                assertTrue(hashes.add(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(pcm))),take);
                Metrics signal=metrics(pcm);assertTrue(signal.peak<=.82004,take+" peak ceiling");
                assertTrue(signal.rms>=.12&&signal.rms<=.16004,take+" RMS "+signal.rms);
                assertTrue(Math.abs(signal.mean)<.00004,take+" DC offset");
                assertEquals(0,sample(pcm,0),take+" onset");assertEquals(0,sample(pcm,pcm.length-2),take+" release");
                assertTrue(metrics(Arrays.copyOfRange(pcm,pcm.length-480,pcm.length)).rms<.025,take+" abrupt ending");
            }
        }
        assertEquals(18,hashes.size());
        assertFalse(config.cueBanks().containsKey("pickup-ammo"));
        assertNull(getClass().getResource("/audio/pickup-ammo.wav"));
        try(InputStream input=asset("audio/pickup-provenance.json")) {
            var evidence=com.google.gson.JsonParser.parseReader(new InputStreamReader(input,java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            assertFalse(evidence.get("externalSamples").getAsBoolean());
            assertEquals("ORIGINAL_PROJECT_CONTENT",evidence.get("origin").getAsString());
            assertEquals("NEEDS_CREATIVE_REVIEW",evidence.get("artisticStatus").getAsString());
            assertEquals(18,evidence.getAsJsonArray("assets").size());
            String source="src/tools/java/game/wreckriff/tools/GenerateAudio.java";
            assertEquals(source,evidence.get("generator").getAsString());
            assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(java.nio.file.Files.readAllBytes(java.nio.file.Path.of(source)))),
                    evidence.get("generatorSha256").getAsString());
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
