package game.wreckriff.audio;

import com.jme3.asset.DesktopAssetManager;
import com.jme3.audio.*;
import com.jme3.math.Vector3f;
import com.jme3.scene.Node;
import game.wreckriff.simulation.GameEvent;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Pure source orchestration checks; audible device output still requires the graphical review. */
class BossTelegraphAudioTest {
    private static final UUID SESSION=new UUID(0,321),OTHER=new UUID(0,654);
    private static final String MUSIC="audio/metalmania.wav";

    @Test void startsOnePositionalShotAndIgnoresDuplicateOrStaleStartsEvenAfterTheShotFinishes() {
        Node scene=new Node();Vector3f position=new Vector3f(4,8,12);
        try(AudioDirector director=director(scene)) {
            director.startMatch(SESSION,MUSIC,MUSIC);
            director.bossTelegraph(SESSION,9,100,position,true);
            AudioNode first=warning(scene);assertNotNull(first);
            assertTrue(first.isPositional());assertFalse(first.isLooping());assertEquals(position,first.getLocalTranslation());
            director.bossTelegraph(SESSION,9,100,Vector3f.ZERO,true);
            director.bossTelegraph(SESSION,9,99,Vector3f.ZERO,true);
            assertSame(first,warning(scene));assertEquals(2,director.voiceCount());
            first.setStatus(AudioSource.Status.Stopped);director.updateTail(.1f);
            director.bossTelegraph(SESSION,9,100,position,true);
            assertNull(warning(scene));
            director.bossTelegraph(SESSION,9,101,position,true);
            assertNotNull(warning(scene));assertNotSame(first,warning(scene));
        }
    }

    @Test void cancellationMatchesBothBossAndBeganTickAndDoesNotStopArenaOrOtherBossWarnings() {
        Node scene=new Node();
        try(AudioDirector director=director(scene)) {
            director.startMatch(SESSION,MUSIC,MUSIC);
            director.accept(List.of(hazard(1,"crane",GameEvent.Type.ARENA_HAZARD_WARNING)));
            AudioNode hazard=find(scene,"sound-hazard-crane-warning");
            director.bossTelegraph(SESSION,9,100,Vector3f.ZERO,true);AudioNode first=warning(scene);
            director.bossTelegraph(SESSION,10,100,Vector3f.UNIT_X,true);AudioNode other=warning(scene);
            director.bossTelegraph(SESSION,9,101,Vector3f.UNIT_Y,true);AudioNode current=warning(scene);
            assertEquals(AudioSource.Status.Stopped,first.getStatus());assertNull(first.getParent());
            director.bossTelegraph(SESSION,9,100,null,false);
            director.bossTelegraph(OTHER,9,101,null,false);
            director.bossTelegraph(SESSION,11,101,null,false);
            assertEquals(AudioSource.Status.Playing,current.getStatus());
            director.bossTelegraph(SESSION,9,101,null,false);
            assertEquals(AudioSource.Status.Stopped,current.getStatus());assertNull(current.getParent());
            assertEquals(AudioSource.Status.Playing,other.getStatus());assertEquals(AudioSource.Status.Playing,hazard.getStatus());
            director.bossTelegraph(SESSION,9,101,Vector3f.ZERO,true);
            assertSame(other,warning(scene));assertEquals(3,director.voiceCount());
        }
    }

    @Test void malformedAndForeignCallsCannotPoisonDedupeAndCleanupBindsTheNextSession() {
        Node scene=new Node();
        try(AudioDirector director=director(scene)) {
            director.bossTelegraph(SESSION,9,500,Vector3f.ZERO,true);assertNull(warning(scene));
            director.startMatch(SESSION,MUSIC,MUSIC);
            director.bossTelegraph(null,9,500,Vector3f.ZERO,true);
            director.bossTelegraph(OTHER,9,500,Vector3f.ZERO,true);
            director.bossTelegraph(SESSION,-1,500,Vector3f.ZERO,true);
            director.bossTelegraph(SESSION,0,500,Vector3f.ZERO,true);
            director.bossTelegraph(SESSION,9,-1,Vector3f.ZERO,true);
            director.bossTelegraph(SESSION,9,500,null,true);
            director.bossTelegraph(SESSION,9,500,new Vector3f(Float.NaN,0,0),true);
            director.bossTelegraph(SESSION,9,500,new Vector3f(0,Float.POSITIVE_INFINITY,0),true);
            assertEquals(1,director.voiceCount());
            director.bossTelegraph(SESSION,9,100,Vector3f.ZERO,true);assertNotNull(warning(scene));
            director.stopMatch();assertNull(warning(scene));assertEquals(0,director.voiceCount());
            director.bossTelegraph(SESSION,9,101,Vector3f.ZERO,true);assertNull(warning(scene));
            director.startMatch(OTHER,MUSIC,MUSIC);
            director.bossTelegraph(SESSION,9,500,Vector3f.ZERO,true);assertNull(warning(scene));
            director.bossTelegraph(OTHER,9,0,Vector3f.ZERO,true);assertNotNull(warning(scene));
            director.close();director.bossTelegraph(OTHER,9,1,Vector3f.ZERO,true);assertEquals(0,director.voiceCount());
        }
    }

    @Test void pauseAndRangeCullingNeverQueueAWarning() {
        Node scene=new Node();
        try(AudioDirector director=director(scene)) {
            director.startMatch(SESSION,MUSIC,MUSIC);director.pause();
            director.bossTelegraph(SESSION,9,100,Vector3f.ZERO,true);assertNull(warning(scene));
            director.resume();director.updateTail(.1f);assertNull(warning(scene));
            director.bossTelegraph(SESSION,9,101,new Vector3f(121,0,0),true);assertNull(warning(scene));
            director.bossTelegraph(SESSION,9,101,Vector3f.ZERO,true);assertNull(warning(scene));
            director.bossTelegraph(SESSION,9,102,Vector3f.ZERO,true);assertNotNull(warning(scene));
            director.pause();director.bossTelegraph(SESSION,9,102,null,false);assertNull(warning(scene));
        }
        try(AudioDirector director=new AudioDirector(new DesktopAssetManager(true),null,new Listener(),new Node())) {
            director.startMatch(SESSION,MUSIC,MUSIC);director.bossTelegraph(SESSION,9,0,Vector3f.ZERO,true);
            assertEquals(0,director.voiceCount());
        }
    }

    @Test void bossPriorityIsBetweenArenaWarningAndActivationWithinTheCrossfadeBudget() {
        Node scene=new Node();
        try(AudioDirector director=director(scene)) {
            director.startMatch(SESSION,"audio/campaign/construction_17-normal.wav","audio/campaign/construction_17-boss.wav");
            director.bossMusic(true);assertEquals(2,director.musicSourceCount());
            for(int i=1;i<=29;i++)director.accept(List.of(hazard(i,"crane",GameEvent.Type.ARENA_HAZARD_WARNING)));
            director.accept(List.of(hazard(30,"traffic",GameEvent.Type.ARENA_HAZARD_ACTIVE)));
            AudioNode impact=find(scene,"sound-hazard-traffic-active");assertEquals(32,director.voiceCount());
            director.bossTelegraph(SESSION,9,100,Vector3f.ZERO,true);AudioNode boss=warning(scene);assertNotNull(boss);
            assertEquals(AudioSource.Status.Stopped,impact.getStatus());assertEquals(32,director.voiceCount());
            for(int i=100;i<180;i++)director.accept(List.of(new GameEvent(GameEvent.Type.PICKUP,i,0,0,Vector3f.ZERO,"power-ammo",1).inSession(SESSION)));
            assertSame(boss,warning(scene));assertEquals(32,director.voiceCount());
            double[] gain={0};scene.depthFirstTraversal(node->{if(node instanceof AudioNode audio)gain[0]+=audio.getVolume();});
            assertTrue(gain[0]<=1.000001);
            director.accept(List.of(hazard(31,"crane",GameEvent.Type.ARENA_HAZARD_WARNING)));
            assertEquals(AudioSource.Status.Stopped,boss.getStatus());assertNull(warning(scene));
            director.bossTelegraph(SESSION,9,100,Vector3f.ZERO,true);assertNull(warning(scene));
            director.bossTelegraph(SESSION,10,100,Vector3f.ZERO,true);assertNull(warning(scene));
            director.accept(List.of(hazard(1,"crane",GameEvent.Type.ARENA_HAZARD_CANCELLED)));
            director.updateTail(.1f);director.bossTelegraph(SESSION,10,100,Vector3f.ZERO,true);
            assertNull(warning(scene));assertEquals(0,director.pendingImpactCount());
        }
    }

    @Test void participantHistoryIsBoundedAndOnlyRetryResetsItsCapacity() {
        Node scene=new Node();
        try(AudioDirector director=director(scene)) {
            director.startMatch(SESSION,MUSIC,MUSIC);
            for(int id=1;id<=32;id++) {
                director.bossTelegraph(SESSION,id,1,Vector3f.ZERO,true);
                director.bossTelegraph(SESSION,id,1,null,false);
            }
            for(int id=33;id<1000;id++)director.bossTelegraph(SESSION,id,1,Vector3f.ZERO,true);
            assertNull(warning(scene));assertEquals(1,director.voiceCount());
            director.bossTelegraph(SESSION,1,1,Vector3f.ZERO,true);assertNull(warning(scene));
            director.bossTelegraph(SESSION,1,2,Vector3f.ZERO,true);assertNotNull(warning(scene));
            director.startMatch(SESSION,MUSIC,MUSIC);
            director.bossTelegraph(SESSION,33,1,Vector3f.ZERO,true);assertNotNull(warning(scene));
        }
    }

    private static GameEvent hazard(long id,String kind,GameEvent.Type type) {
        return new GameEvent(type,1000+id,-1,-1,Vector3f.ZERO,kind,1,Vector3f.ZERO,Vector3f.ZERO,SESSION,"hazard-"+id);
    }
    private static AudioNode warning(Node scene) {return find(scene,"sound-boss-telegraph");}
    private static AudioNode find(Node root,String name) {
        AudioNode[] result={null};root.depthFirstTraversal(node->{if(node instanceof AudioNode audio&&name.equals(audio.getName()))result[0]=audio;});return result[0];
    }
    private static AudioDirector director(Node scene) {
        AudioRenderer renderer=(AudioRenderer)Proxy.newProxyInstance(AudioRenderer.class.getClassLoader(),new Class<?>[]{AudioRenderer.class},
                (proxy,method,args)->{
                    if(args!=null&&args.length>0&&args[0] instanceof AudioSource source)switch(method.getName()) {
                        case "playSource" -> source.setStatus(AudioSource.Status.Playing);
                        case "stopSource" -> source.setStatus(AudioSource.Status.Stopped);
                    }
                    if(method.getReturnType()==float.class)return 0f;if(method.getReturnType()==boolean.class)return false;return null;
                });
        return new AudioDirector(new DesktopAssetManager(true),renderer,new Listener(),scene);
    }
}
