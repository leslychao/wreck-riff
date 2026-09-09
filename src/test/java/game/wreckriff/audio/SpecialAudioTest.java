package game.wreckriff.audio;

import com.google.gson.JsonParser;
import com.jme3.asset.DesktopAssetManager;
import com.jme3.audio.*;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import com.jme3.scene.Node;
import game.wreckriff.combat.CombatRules;
import game.wreckriff.config.Configs;
import game.wreckriff.simulation.*;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Verifies authored PCM and managed source decisions; the renderer double makes no listening claim. */
class SpecialAudioTest {
    private static final UUID SESSION=new UUID(0,713);
    private static final Map<String,Integer> FRAMES=Map.of("special-pulse-charge",14400,"special-pulse-hit",14400,
            "special-grinder-start",28800,"special-grinder-loop",24000,"special-dash",10560,"special-bomb-warning",33600);

    @Test void everySpecialMapsOnceToItsOwnPositionalCueAndBombUsesExistingDetonation() {
        try(var fixture=new Fixture()) {
            List<GameEvent> events=List.of(event(GameEvent.Type.SPECIAL_STARTED,1,0,"rivet",.3f),
                    event(GameEvent.Type.SPECIAL_STARTED,2,2,"grinder",.6f),
                    event(GameEvent.Type.SPECIAL_STARTED,3,3,"spark",.35f),
                    event(GameEvent.Type.SPECIAL_HIT,4,1,"pulse",60),
                    event(GameEvent.Type.BOMB_PLACED,5,3,"special-bomb",4),
                    event(GameEvent.Type.EXPLOSION,5,3,"special-bomb",4));
            fixture.director.accept(events);fixture.director.accept(events);
            for(String cue:List.of("special-pulse-charge","special-grinder-start","special-dash","special-pulse-hit",
                    "special-bomb-warning","mine-detonate")) {
                assertEquals(1,fixture.playCount("sound-"+cue),cue+" duplicate playback");
                AudioNode node=fixture.find("sound-"+cue);assertNotNull(node);
                assertTrue(node.isPositional());assertFalse(node.isLooping());
                assertEquals(new Vector3f(8,1,4),node.getLocalTranslation());
            }
            assertEquals(7,fixture.director.voiceCount());
            fixture.director.accept(List.of(event(GameEvent.Type.DAMAGE,20,1,"grinder",.6667f),
                    event(GameEvent.Type.DAMAGE,21,1,"pulse",60)));
            assertEquals(0,fixture.playCount("sound-metal-hit"),"Existing special cues must not double with generic hits");
        }
    }

    @Test void grinderLoopFollowsLivePhaseAndIsStoppedByEndDeathResultAndExit() {
        try(var fixture=new Fixture()) {
            var truck=fixture.session.vehicle(2);
            truck.specialPhase=VehicleState.SpecialPhase.GRINDER_WINDUP;fixture.update();
            assertNull(fixture.find("sound-special-grinder-loop"));
            truck.specialPhase=VehicleState.SpecialPhase.GRINDER_SEARCH;fixture.update();
            AudioNode loop=fixture.find("sound-special-grinder-loop");assertNotNull(loop);assertTrue(loop.isLooping());
            assertEquals(new Vector3f(10,1,0),loop.getLocalTranslation());
            truck.specialPhase=VehicleState.SpecialPhase.GRINDER_CONTACT;
            for(int tick=0;tick<12;tick++)fixture.update();
            assertSame(loop,fixture.find("sound-special-grinder-loop"));assertEquals(1,fixture.playCount("sound-special-grinder-loop"));
            truck.clearSpecial();fixture.update();assertNull(fixture.find("sound-special-grinder-loop"));
            truck.specialPhase=VehicleState.SpecialPhase.GRINDER_SEARCH;fixture.update();
            fixture.director.accept(List.of(event(GameEvent.Type.SPECIAL_ENDED,40,2,"grinder",0)));
            assertNull(fixture.find("sound-special-grinder-loop"));
            truck.hp=0;fixture.update();assertNull(fixture.find("sound-special-grinder-loop"));
            truck.hp=truck.maximumHp;fixture.update();assertNotNull(fixture.find("sound-special-grinder-loop"));
            fixture.session.outcome=MatchSession.Outcome.VICTORY;fixture.update();assertNull(fixture.find("sound-special-grinder-loop"));
            fixture.director.stopMatch();fixture.update();assertEquals(0,fixture.director.voiceCount());
        }
    }

    @Test void pausedOrStaleSpecialEventsCannotQueueOrBindASession() {
        try(var fixture=new Fixture()) {
            GameEvent warning=event(GameEvent.Type.BOMB_PLACED,50,3,"special-bomb",4);
            fixture.director.pause();fixture.director.accept(List.of(warning));fixture.update();
            fixture.director.resume();fixture.update();
            assertNull(fixture.find("sound-special-bomb-warning"));
            var stale=new GameEvent(GameEvent.Type.BOMB_PLACED,50,3,3,new Vector3f(8,1,4),"special-bomb",4)
                    .inSession(new UUID(0,999));
            fixture.director.accept(List.of(stale));
            assertNull(fixture.find("sound-special-bomb-warning"));
            fixture.director.accept(List.of(warning));assertEquals(1,fixture.playCount("sound-special-bomb-warning"));
            fixture.director.stopMatch();fixture.director.accept(List.of(event(GameEvent.Type.BOMB_PLACED,51,3,"special-bomb",4)));
            assertEquals(0,fixture.director.voiceCount());
            fixture.director.startMatch(SESSION,"audio/metalmania.wav","audio/metalmania.wav");
            fixture.director.accept(List.of(warning));assertEquals(2,fixture.playCount("sound-special-bomb-warning"),"Retry clears dedupe");
        }
    }

    @Test void warningOutranksEnginesButCannotEvictCriticalHazardsAndRespectsThirtyTwoVoices() {
        try(var fixture=new Fixture()) {
            fixture.update();fixture.director.hazard(true,true,Vector3f.ZERO);
            AudioNode warning=fixture.find("sound-hazard-warning"),active=fixture.find("sound-hazard-active");
            List<GameEvent> flood=new ArrayList<>();
            for(int index=0;index<100;index++)flood.add(event(GameEvent.Type.SPECIAL_STARTED,100+index,3,"spark",.35f));
            fixture.director.accept(flood);assertEquals(32,fixture.director.voiceCount());
            fixture.director.accept(List.of(event(GameEvent.Type.BOMB_PLACED,300,3,"special-bomb",4)));
            assertNotNull(fixture.find("sound-special-bomb-warning"));
            assertSame(warning,fixture.find("sound-hazard-warning"));assertSame(active,fixture.find("sound-hazard-active"));
            assertEquals(32,fixture.director.voiceCount());
            double[] volume={0};fixture.scene.depthFirstTraversal(node->{if(node instanceof AudioNode audio)volume[0]+=audio.getVolume();});
            assertTrue(volume[0]<=1.000001,"Specials share the existing mix headroom");
            fixture.director.stopMatch();assertEquals(0,fixture.director.voiceCount());
        }
    }

    @Test void specialWindupRemainsAudibleDuringPickupFloodWithoutEvictingHazards() {
        try(var fixture=new Fixture()) {
            fixture.director.hazard(true,true,Vector3f.ZERO);
            AudioNode danger=fixture.find("sound-hazard-active");
            List<GameEvent> pickups=new ArrayList<>();
            for(int index=0;index<100;index++)pickups.add(event(GameEvent.Type.PICKUP,400+index,0,"homing-ammo",1));
            fixture.director.accept(pickups);assertEquals(32,fixture.director.voiceCount());
            fixture.director.accept(List.of(event(GameEvent.Type.SPECIAL_STARTED,550,2,"grinder",.6f)));
            assertNotNull(fixture.find("sound-special-grinder-start"),"Enemy windup must outrank repeated pickup confirmations");
            assertSame(danger,fixture.find("sound-hazard-active"));assertEquals(32,fixture.director.voiceCount());
        }
    }

    @Test void allSixOriginalCuesHaveApprovedTimingPcmAndEvidenceAndLoopIsContinuous()throws Exception {
        Set<String> cues=new HashSet<>(FRAMES.keySet());
        try(InputStream input=asset("audio/special-provenance.json")) {
            var evidence=JsonParser.parseReader(new InputStreamReader(input,StandardCharsets.UTF_8)).getAsJsonObject();
            assertEquals("ORIGINAL_PROJECT_CONTENT",evidence.get("origin").getAsString());
            assertFalse(evidence.get("externalSamples").getAsBoolean());
            assertEquals("NEEDS_CREATIVE_REVIEW",evidence.get("artisticStatus").getAsString());
            assertEquals(6,evidence.getAsJsonArray("assets").size());
            for(var entry:evidence.getAsJsonArray("assets")) {
                var item=entry.getAsJsonObject();String path=item.get("path").getAsString();
                String cue=path.substring(6,path.length()-4);assertTrue(cues.remove(cue));
                try(InputStream wave=asset(path)) {
                    var header=PcmWave.header(wave);
                    assertEquals(48000,header.rate());assertEquals(16,header.bits());assertEquals(1,header.channels());
                    assertEquals(FRAMES.get(cue).longValue(),header.dataBytes()/2);
                    byte[] pcm=wave.readNBytes((int)header.dataBytes());double sum=0,squares=0,peak=0;
                    for(int index=0;index<pcm.length;index+=2) {
                        double sample=sample(pcm,index)/32768.0;sum+=sample;squares+=sample*sample;peak=Math.max(peak,Math.abs(sample));
                    }
                    assertTrue(peak>.2&&peak<.83, cue+" peak");assertTrue(Math.sqrt(squares/(pcm.length/2))>.04,cue+" RMS");
                    assertTrue(Math.abs(sum/(pcm.length/2))<.002,cue+" DC offset");
                    if(cue.equals("special-grinder-loop"))
                        assertTrue(Math.abs(sample(pcm,0)-sample(pcm,pcm.length-2))<32768*.04,"No discontinuous loop boundary");
                }
            }
        }
        assertTrue(cues.isEmpty());
    }

    private static short sample(byte[] pcm,int index) { return (short)((pcm[index]&255)|(pcm[index+1]<<8)); }
    private static InputStream asset(String path) { return Objects.requireNonNull(SpecialAudioTest.class.getClassLoader().getResourceAsStream(path),path); }
    private static GameEvent event(GameEvent.Type type,long id,int subject,String kind,float value) {
        return new GameEvent(type,id,subject,subject,new Vector3f(8,1,4),kind,value).inSession(SESSION);
    }
    private static final class Fixture implements AutoCloseable {
        final Node scene=new Node();final List<AudioSource> started=new ArrayList<>();
        final MatchSession session=new MatchSession(42,180,Configs.load("combat",CombatRules.class),SESSION);
        final AudioDirector director;
        Fixture() {
            AudioRenderer renderer=(AudioRenderer)Proxy.newProxyInstance(AudioRenderer.class.getClassLoader(),new Class<?>[]{AudioRenderer.class},
                    (proxy,method,args)->{
                        if(args!=null&&args.length>0&&args[0] instanceof AudioSource source) {
                            if(method.getName().equals("playSource")) {started.add(source);source.setStatus(AudioSource.Status.Playing);}
                            if(method.getName().equals("stopSource"))source.setStatus(AudioSource.Status.Stopped);
                        }
                        if(method.getReturnType()==float.class)return 0f;
                        if(method.getReturnType()==boolean.class)return false;
                        return null;
                    });
            director=new AudioDirector(new DesktopAssetManager(true),renderer,new Listener(),scene);
            director.startMatch(SESSION,"audio/metalmania.wav","audio/metalmania.wav");
        }
        long playCount(String name) {return started.stream().filter(source->source instanceof AudioNode node&&node.getName().equals(name)).count();}
        AudioNode find(String name) {
            AudioNode[] result={null};scene.depthFirstTraversal(node->{if(node instanceof AudioNode audio&&name.equals(audio.getName()))result[0]=audio;});
            return result[0];
        }
        void update() {director.update(session,WORLD,.05f);}
        @Override public void close() {director.close();assertEquals(0,scene.getQuantity());}
    }
    private static final WorldQuery WORLD=new WorldQuery() {
        public Vector3f position(int id){return new Vector3f(id*5,1,0);}
        public Vector3f velocity(int id){return new Vector3f();}
        public Quaternion rotation(int id){return new Quaternion();}
        public boolean grounded(int id){return true;}
        public float mass(int id){return 1100;}
        public Hit ray(Vector3f a,Vector3f b,int id){return null;}
        public Hit sweep(Vector3f a,Vector3f b,float radius,int id,float start,float end){return null;}
        public Hit staticSweep(Vector3f a,Vector3f b,float radius){return null;}
        public boolean visible(Vector3f a,Vector3f b,int id){return true;}
        public float distanceToHull(int id,Vector3f point){return 0;}
        public void impulse(int id,Vector3f linear,Vector3f angular,float cap){}
        public Vector3f closestHullPoint(int id,Vector3f from){return position(id);}
    };
}
