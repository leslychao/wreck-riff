package game.wreckriff.audio;

import com.jme3.asset.DesktopAssetManager;
import com.jme3.audio.*;
import com.jme3.math.*;
import com.jme3.scene.*;
import game.wreckriff.simulation.*;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Pure orchestration tests. A stub renderer cannot certify an audio device or audible output. */
class AudioDirectorTest {
    @Test void a02PauseResumesSameMusicObjectAndRetryDoesNotGrowSources() {
        Node scene=new Node();
        try(AudioDirector director=new AudioDirector(new DesktopAssetManager(true),renderer(),new Listener(),scene)) {
            AudioNode music=find(scene,"music-dead-air-circuit");
            for(int retry=0;retry<20;retry++) {
                director.startMatch();
                MatchSession session=new MatchSession(retry,180);
                director.update(session,world(new Vector3f(0,0,12)),1f/60);
                int original=director.voiceCount();
                assertTrue(original>1);
                director.pause(); assertEquals(AudioSource.Status.Paused,music.getStatus());
                director.ui(true);
                director.resume();
                assertSame(music,find(scene,"music-dead-air-circuit"));
                assertEquals(AudioSource.Status.Playing,music.getStatus());
                director.stopMatch(); assertEquals(0,director.voiceCount());
                assertEquals(1,((Node)scene.getChild("match-audio")).getQuantity());
            }
        }
        assertEquals(0,scene.getQuantity());
    }

    @Test void a03ThreatAndPlayerShotSurviveDecorativeSaturationAndMixRemainsBounded() {
        Node scene=new Node();
        try(AudioDirector director=new AudioDirector(new DesktopAssetManager(true),renderer(),new Listener(),scene)) {
            director.startMatch();
            for(int i=0;i<80;i++) director.accept(List.of(event(GameEvent.Type.DAMAGE,i,1,2,"metal",2)));
            assertTrue(director.voiceCount()<=32);
            director.accept(List.of(event(GameEvent.Type.SHOT,100,0,0,"power",0)));
            director.hazard(true,false,Vector3f.ZERO);
            for(int i=0;i<80;i++) director.accept(List.of(event(GameEvent.Type.DAMAGE,200+i,1,2,"metal",2)));
            assertNotNull(find(scene,"sound-power-launch")); assertNotNull(find(scene,"sound-hazard-warning"));
            assertEquals(32,director.voiceCount());
            double[] volume={0};
            scene.depthFirstTraversal(spatial->{if(spatial instanceof AudioNode audio)volume[0]+=audio.getVolume();});
            assertTrue(volume[0]<=1.000001,"Worst-case summed source gain cannot digitally clip");
        }
    }

    @Test void tyreSoundFollowsLateralMotionAndGroundContact() {
        Node scene=new Node();
        try(AudioDirector director=new AudioDirector(new DesktopAssetManager(true),renderer(),new Listener(),scene)) {
            director.startMatch();MatchSession session=new MatchSession(42,180);
            director.update(session,world(new Vector3f(0,0,20)),1f/60);
            assertNull(find(scene,"sound-tyre-slip"));
            director.update(session,world(new Vector3f(7,0,20)),1f/60);
            assertNotNull(find(scene,"sound-tyre-slip"));
            director.update(session,world(new Vector3f(0,0,20)),1f/60);
            assertNull(find(scene,"sound-tyre-slip"));
        }
    }

    @Test void disabledBackendHasZeroSourcesAndCanCloseTwice() {
        Node scene=new Node();
        AudioDirector director=new AudioDirector(null,null,null,scene);
        director.startMatch();director.pause();director.resume();director.accept(List.of());
        director.update(new MatchSession(1,180),world(Vector3f.ZERO),.02f);
        assertEquals(0,director.voiceCount());director.close();director.close();
        assertEquals(0,scene.getQuantity());
    }
    @Test void resultStingerPlaysAfterMatchCleanupWithoutRestartingMusic() {
        Node scene=new Node();
        try(AudioDirector director=new AudioDirector(new DesktopAssetManager(true),renderer(),new Listener(),scene)) {
            director.startMatch();director.stopMatch();
            director.accept(List.of(event(GameEvent.Type.MATCH_FINISHED,1,0,-1,"victory",0)));
            assertNotNull(find(scene,"sound-victory"));assertEquals(1,director.voiceCount());
            assertEquals(AudioSource.Status.Stopped,find(scene,"music-dead-air-circuit").getStatus());
        }
    }
    private static GameEvent event(GameEvent.Type type,long id,int subject,int source,String kind,float value) {
        return new GameEvent(type,id,subject,source,Vector3f.ZERO,kind,value);
    }
    private static AudioNode find(Node root,String name) {
        AudioNode[] found={null};root.depthFirstTraversal(s->{if(s instanceof AudioNode audio&&name.equals(audio.getName()))found[0]=audio;});
        return found[0];
    }
    private static AudioRenderer renderer() {
        return (AudioRenderer)Proxy.newProxyInstance(AudioRenderer.class.getClassLoader(),new Class<?>[]{AudioRenderer.class},
                (proxy,method,args)->{
                    if(args!=null && args.length>0 && args[0] instanceof AudioSource source) {
                        switch(method.getName()) {
                            case "playSource" -> source.setStatus(AudioSource.Status.Playing);
                            case "pauseSource" -> source.setStatus(AudioSource.Status.Paused);
                            case "stopSource" -> source.setStatus(AudioSource.Status.Stopped);
                        }
                    }
                    if(method.getReturnType()==float.class)return 0f;
                    if(method.getReturnType()==boolean.class)return false;
                    return null;
                });
    }
    private static WorldQuery world(Vector3f velocity) {
        return new WorldQuery() {
            public Vector3f position(int id){return new Vector3f(id*5,1,0);}
            public Vector3f velocity(int id){return velocity.clone();}
            public Quaternion rotation(int id){return new Quaternion();}
            public boolean grounded(int id){return true;}
            public float mass(int id){return 1100;}
            public Hit ray(Vector3f a,Vector3f b,int id){return null;}
            public Hit sweep(Vector3f a,Vector3f b,float r,int id){return null;}
            public boolean visible(Vector3f a,Vector3f b,int id){return true;}
            public float distanceToHull(int id,Vector3f p){return 0;}
            public void impulse(int id,Vector3f impulse){}
        };
    }
}
