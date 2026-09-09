package game.wreckriff.audio;

import com.google.gson.*;
import com.jme3.math.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class AudioCaptureTest {
    @TempDir Path directory;
    @Test void reconstructsAllLocalCampaignMusicPathsAndKeepsTheirExactAssetIdentity()throws Exception {
        double[] time={0};java.util.Set<String> expected=new java.util.HashSet<>();
        try(AudioCapture capture=new AudioCapture(directory,()->time[0],.12)) {
            for(String arena:java.util.List.of("construction_17","neon_zero","euphoria_park","ash_necropolis","doomsday_arena"))
                for(String mix:java.util.List.of("normal","boss")) {
                    String asset="audio/campaign/"+arena+"-"+mix+".wav";expected.add(asset);
                    capture.observe(new Object(),asset,true,false,.02f,1,Vector3f.ZERO,Vector3f.ZERO,Vector3f.UNIT_X,8,120,.5);
                }
            time[0]=.12;
        }
        java.util.Set<String> actual=new java.util.HashSet<>();
        for(var voice:journal().getAsJsonArray("voices"))actual.add(voice.getAsJsonObject().get("asset").getAsString());
        assertEquals(expected,actual);byte[] pcm=pcm(directory.resolve("audio.wav"));
        boolean sound=false,stereo=false;
        for(int frame=0;frame<pcm.length/4;frame++) {
            sound|=sample(pcm,frame,0)!=0;stereo|=sample(pcm,frame,0)!=sample(pcm,frame,1);
        }
        assertTrue(sound);assertTrue(stereo);assertTrue(Files.readString(directory.resolve("AUDIO_README.txt")).contains("not an exact native OpenAL"));
    }

    @Test void campaignSupportCannotAdmitTraversalNestedFoldersOrUnrecognisedMusicFilenameShapes()throws Exception {
        try(AudioCapture capture=new AudioCapture(directory,()->0,.01)) {
            for(String path:java.util.List.of("audio/campaign/../metalmania.wav","audio/campaign/../../escape-normal.wav",
                    "audio/campaign/sub/arena-normal.wav","audio/campaign/arena.wav","audio/campaign/arena-other.wav",
                    "audio/campaign/arena-normal.wav/extra","audio/campaign\\arena-normal.wav","audio//arena.wav",
                    "https://example.test/audio/campaign/arena-normal.wav"))
                assertThrows(IllegalArgumentException.class,()->observe(capture,new Object(),path,.1f),path);
            assertThrows(IllegalArgumentException.class,()->observe(capture,new Object(),null,.1f));
        }
        assertTrue(journal().getAsJsonArray("voices").isEmpty());
    }

    @Test void reconstructsTheResolvedSampleAtActualStartWithPitchGainAndPan() throws Exception {
        double[] time={0};Object identity=new Object();
        try(AudioCapture capture=new AudioCapture(directory,()->time[0],.5)) {
            time[0]=.1;
            capture.observe(identity,"audio/machine-gun-2.wav",false,true,.5f,2,new Vector3f(10,0,0),Vector3f.ZERO,
                    Vector3f.UNIT_X,10,100,0);
            time[0]=.25;capture.stop(identity);time[0]=.5;
        }
        byte[] rendered=pcm(directory.resolve("audio.wav"));
        assertEquals(48_000*.5*4,rendered.length);
        for(int frame=0;frame<4800;frame++)assertEquals(0,sample(rendered,frame,1));
        try(InputStream source=AudioCaptureTest.class.getClassLoader().getResourceAsStream("audio/machine-gun-2.wav")) {
            PcmWave.header(source);byte[] original=source.readAllBytes();
            for(int i=0;i<4000;i++) {
                assertEquals(0,sample(rendered,4800+i,0),"A source directly on the right must pan right");
                int expected=(short)((original[i*4]&255)|(original[i*4+1]<<8));
                assertEquals(expected*.5,sample(rendered,4800+i,1),1.5,"Pitch 2 must traverse the same recording twice as fast");
            }
        }
        for(int frame=12_000;frame<24_000;frame++)assertEquals(0,sample(rendered,frame,1));
        JsonObject voice=journal().getAsJsonArray("voices").get(0).getAsJsonObject();
        assertEquals("audio/machine-gun-2.wav",voice.get("asset").getAsString());
        assertEquals(.1,voice.getAsJsonArray("states").get(0).getAsJsonObject().get("seconds").getAsDouble());
        assertEquals(.25,voice.get("endSeconds").getAsDouble());
    }
    @Test void includesLoopingEngineAndStereoMusicAcrossItsLoopSeamWithVolumeUpdates() throws Exception {
        double[] time={0};Object engine=new Object(),music=new Object();
        try(AudioCapture capture=new AudioCapture(directory,()->time[0],1)) {
            capture.observe(engine,"audio/engine-idle.wav",true,true,.1f,1,Vector3f.ZERO,Vector3f.ZERO,Vector3f.UNIT_X,10,100,0);
            capture.observe(music,AudioConfig.load().musicAsset(),true,false,.2f,1,Vector3f.ZERO,Vector3f.ZERO,Vector3f.UNIT_X,10,100,179.1);
            time[0]=.6;
            capture.observe(music,AudioConfig.load().musicAsset(),true,false,0,1,Vector3f.ZERO,Vector3f.ZERO,Vector3f.UNIT_X,10,100,.5);
            time[0]=.8;capture.stop(engine);time[0]=1;
        }
        byte[] rendered=pcm(directory.resolve("audio.wav"));
        boolean stereo=false;
        for(int i=5000;i<28_000;i++)if(sample(rendered,i,0)!=sample(rendered,i,1))stereo=true;
        assertTrue(stereo,"Music keeps both original channels through its seam");
        long engineEnergy=0;
        for(int i=30_000;i<38_000;i++) {assertEquals(sample(rendered,i,0),sample(rendered,i,1));engineEnergy+=Math.abs(sample(rendered,i,0));}
        assertTrue(engineEnergy>10_000,"Looping engine remains audible after music is muted");
        for(int i=38_400;i<48_000;i++)assertEquals(0,sample(rendered,i,0));
        assertEquals(2,journal().getAsJsonArray("voices").size());
        assertTrue(Files.readString(directory.resolve("AUDIO_README.txt")).contains("not an exact native OpenAL"));
    }
    @Test void rejectsUnboundedInputsAndClockRewindsAndCoalescesSameTimeUpdates() throws Exception {
        assertThrows(IllegalArgumentException.class,()->new AudioCapture(directory,()->0,41));
        assertThrows(IllegalArgumentException.class,()->new AudioCapture(directory,()->0,Double.NaN));
        double[] time={0};Object voice=new Object();
        try(AudioCapture capture=new AudioCapture(directory,()->time[0],.1)) {
            assertThrows(IllegalArgumentException.class,()->observe(capture,voice,"../escape.wav",1));
            observe(capture,voice,"audio/machine-gun-1.wav",.1f);
            observe(capture,voice,"audio/machine-gun-1.wav",.2f);
            for(int i=0;i<31;i++)observe(capture,new Object(),"audio/engine-idle.wav",.001f);
            assertThrows(IllegalStateException.class,()->observe(capture,new Object(),"audio/engine-idle.wav",.001f));
            time[0]=.01;capture.stop(voice);time[0]=0;
            assertThrows(IllegalStateException.class,()->capture.stopAll());
            time[0]=.1;
        }
        JsonArray states=journal().getAsJsonArray("voices").get(0).getAsJsonObject().getAsJsonArray("states");
        assertEquals(1,states.size());assertEquals(.2,states.get(0).getAsJsonObject().get("volume").getAsDouble(),1e-6);
    }
    private static void observe(AudioCapture capture,Object identity,String asset,float volume) {
        capture.observe(identity,asset,false,false,volume,1,Vector3f.ZERO,Vector3f.ZERO,Vector3f.UNIT_X,10,100,0);
    }
    private JsonObject journal()throws IOException {return JsonParser.parseString(Files.readString(directory.resolve("audio-events.json"))).getAsJsonObject();}
    private static byte[] pcm(Path file)throws IOException {
        try(InputStream in=Files.newInputStream(file)) {
            PcmWave.Header header=PcmWave.header(in);assertEquals(2,header.channels());assertEquals(48_000,header.rate());return in.readAllBytes();
        }
    }
    private static short sample(byte[] pcm,int frame,int channel) {int at=frame*4+channel*2;return(short)((pcm[at]&255)|(pcm[at+1]<<8));}
}
