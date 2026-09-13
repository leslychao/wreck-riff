package game.wreckriff.audio;

import com.jme3.asset.DesktopAssetManager;
import com.jme3.audio.*;
import com.jme3.math.Vector3f;
import com.jme3.scene.*;
import game.wreckriff.arena.*;
import game.wreckriff.combat.CombatRules;
import game.wreckriff.config.Configs;
import game.wreckriff.diagnostics.AmbientAssetsVerifier;
import game.wreckriff.simulation.*;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Orchestration/PCM proof only. The test renderer does not certify a real audio device. */
class ArenaAmbienceTest {
    private static final String MUSIC="audio/music/dead-air-yard.wav";
    private static final WorldQuery UNUSED_WORLD=(WorldQuery)Proxy.newProxyInstance(WorldQuery.class.getClassLoader(),
            new Class<?>[]{WorldQuery.class},(proxy,method,args)->{throw new AssertionError("No live vehicle in this sound fixture");});

    @Test void realMachineryAnchorsCoverTheThreeWorldsAndStayAttachedToTheirCasings() {
        var registry=ArenaRegistry.load();
        for(String id:registry.campaignIds()) {
            var arena=registry.definition(id);var emitters=new ArenaAmbience(arena).emitters();
            assertTrue(emitters.size()>=2&&emitters.size()<=48,id);
            for(var emitter:emitters)assertEquals(arena.boxes().stream().filter(box->box.id().equals(emitter.anchor())).findFirst().orElseThrow().center(),emitter.position());
            if(id.equals("construction_17"))assertTrue(emitters.stream().anyMatch(e->e.anchor().endsWith("electric-pump")));
            if(id.equals("neon_zero")) {
                assertTrue(emitters.stream().anyMatch(e->e.anchor().contains("tunnel-vent")));
                assertTrue(emitters.stream().anyMatch(e->e.anchor().contains("parking-")));
            }
            if(id.equals("euphoria_park"))assertTrue(emitters.stream().anyMatch(e->e.anchor().contains("ferris-drive")));
        }
        assertTrue(new ArenaAmbience(registry.definition("dead-air-yard")).emitters().isEmpty());
    }

    @Test void threeRecordedLoopsHaveVerifiedLicensesHonestPreviewEvidenceAndContinuousUnclippedPcm()throws Exception {
        assertEquals(3,AmbientAssetsVerifier.verify(AudioConfig.load(),new ArrayList<>()).size());
    }

    @Test void voiceCapHoldsDuringSelectionAndOutOfRangeSourcesAreImmediatelyReleased() {
        Node scene=new Node();Listener listener=new Listener();int[] maximum={0},starts={0};
        var renderer=TestAudioRenderer.create(source->{maximum[0]=Math.max(maximum[0],ambient(scene).size());starts[0]++;assertTrue(ambient(scene).size()<=3);});
        List<ArenaAmbience.Emitter> emitters=new ArrayList<>();
        for(int i=0;i<8;i++)emitters.add(new ArenaAmbience.Emitter("motor-"+i,"ambient-motor",new ArenaDefinition.Vec3(i*5,0,0),60,.4f,1));
        var plan=new ArenaAmbience(emitters);
        try(var audio=new AudioDirector(new DesktopAssetManager(true),renderer,listener,scene)) {
            audio.startMatch(UUID.randomUUID(),MUSIC,MUSIC);
            for(int frame=0;frame<120;frame++)plan.update(audio,listener.getLocation(),1/60f);
            assertEquals(3,ambient(scene).size());int before=starts[0];
            for(int frame=0;frame<120;frame++)plan.update(audio,listener.getLocation(),1/60f);
            assertEquals(before,starts[0],"Stable nearby machinery reuses the same voices");
            listener.setLocation(new Vector3f(38,0,0));
            for(int frame=0;frame<30;frame++)plan.update(audio,listener.getLocation(),1/60f);
            assertEquals(3,maximum[0]);assertEquals(3,ambient(scene).size());
            var retired=ambient(scene);listener.setLocation(new Vector3f(500,0,0));plan.update(audio,listener.getLocation(),1/60f);
            assertTrue(ambient(scene).isEmpty());
            for(var node:retired){assertNull(node.getParent());assertEquals(AudioSource.Status.Stopped,node.getStatus());}
        }
    }

    @Test void loadingPauseRetryResultAndMapChangeOwnTheSameAmbientLifecycle() {
        var registry=ArenaRegistry.load();var construction=registry.definition("construction_17");
        var source=new ArenaAmbience(construction).emitters().getFirst();
        Node scene=new Node();Listener listener=new Listener();listener.setLocation(source.position().vector());
        try(var audio=new AudioDirector(new DesktopAssetManager(true),TestAudioRenderer.create(),listener,scene)) {
            var session=session();audio.prepareMatch(session.sessionId,MUSIC,MUSIC);
            assertThrows(IllegalStateException.class,()->audio.prepareAmbience(UUID.randomUUID(),construction));
            audio.prepareAmbience(session.sessionId,construction);audio.update(session,UNUSED_WORLD,.1f);assertTrue(ambient(scene).isEmpty());
            audio.startPreparedMatch();for(int i=0;i<8;i++)audio.update(session,UNUSED_WORLD,.1f);
            var original=ambient(scene);assertFalse(original.isEmpty());
            for(var node:original){assertTrue(node.isLooping());assertTrue(node.isPositional());assertTrue(node.getVolume()>0&&node.getVolume()<.06);}
            audio.pause();for(int i=0;i<10;i++)audio.update(session,UNUSED_WORLD,.1f);
            for(var node:original)assertEquals(AudioSource.Status.Paused,node.getStatus());
            audio.resume();audio.update(session,UNUSED_WORLD,.1f);assertEquals(original,ambient(scene));
            var retry=session();audio.prepareMatch(retry.sessionId,MUSIC,MUSIC);assertTrue(ambient(scene).isEmpty());
            audio.prepareAmbience(retry.sessionId,construction);audio.startPreparedMatch();
            audio.update(session,UNUSED_WORLD,.1f);assertTrue(ambient(scene).isEmpty(),"Old session cannot recreate a loop");
            for(int i=0;i<8;i++)audio.update(retry,UNUSED_WORLD,.1f);assertFalse(ambient(scene).isEmpty());
            retry.outcome=MatchSession.Outcome.VICTORY;audio.update(retry,UNUSED_WORLD,.1f);assertTrue(ambient(scene).isEmpty());
            var classic=session();audio.prepareMatch(classic.sessionId,MUSIC,MUSIC);
            audio.prepareAmbience(classic.sessionId,registry.definition("dead-air-yard"));audio.startPreparedMatch();
            audio.update(classic,UNUSED_WORLD,.1f);assertTrue(ambient(scene).isEmpty());
            audio.stopMatch();assertEquals(0,audio.voiceCount());
        }
        assertEquals(0,scene.getQuantity());
    }

    @Test void combatWarningsEvictAmbientInsteadOfLosingTheirSharedSourceBudget() {
        Node scene=new Node();Listener listener=new Listener();var session=session();
        var plan=new ArenaAmbience(List.of(new ArenaAmbience.Emitter("motor","ambient-motor",new ArenaDefinition.Vec3(0,0,0),60,.4f,1)));
        try(var audio=new AudioDirector(new DesktopAssetManager(true),TestAudioRenderer.create(),listener,scene)) {
            audio.startMatch(session.sessionId,MUSIC,MUSIC);plan.update(audio,Vector3f.ZERO,.1f);assertEquals(1,ambient(scene).size());
            for(int i=0;i<80;i++)audio.accept(List.of(new GameEvent(GameEvent.Type.PICKUP,i,0,0,Vector3f.ZERO,"homing-ammo",1).inSession(session.sessionId)));
            assertTrue(ambient(scene).isEmpty());int before=audio.voiceCount();
            for(int i=0;i<60;i++)plan.update(audio,Vector3f.ZERO,1/60f);
            assertEquals(before,audio.voiceCount());assertTrue(ambient(scene).isEmpty());
            assertTrue(audio.voiceCount()<=AudioConfig.load().sourceLimit());
        }
    }
    private static MatchSession session(){
        var session=new MatchSession(42,180,Configs.load("combat",CombatRules.class),UUID.randomUUID());
        // Isolate fixed machinery from vehicle-engine loops. Outcome still belongs to the match owner.
        session.vehicles.forEach(vehicle->vehicle.hp=0);return session;
    }
    private static List<AudioNode> ambient(Node root) {
        List<AudioNode> nodes=new ArrayList<>();root.depthFirstTraversal(spatial->{if(spatial instanceof AudioNode audio&&audio.getName().startsWith("sound-ambient-"))nodes.add(audio);});return nodes;
    }
}
