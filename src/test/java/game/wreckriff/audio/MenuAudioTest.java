package game.wreckriff.audio;

import com.jme3.asset.DesktopAssetManager;
import com.jme3.audio.*;
import com.jme3.math.Vector3f;
import com.jme3.scene.Node;
import game.wreckriff.simulation.GameEvent;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MenuAudioTest {
    private static final UUID SESSION=new UUID(0,301);
    private static final String NORMAL="audio/music/construction_17-normal.wav",BOSS="audio/music/construction_17-boss.wav";

    @Test void menuNavigationAndLoadingKeepTheSameThemeUntilTheVisibleBattleCrossfade() {
        Node scene=new Node();List<AudioSource> started=new ArrayList<>();
        try(AudioDirector audio=new AudioDirector(new DesktopAssetManager(true),TestAudioRenderer.create(started::add),new Listener(),scene)) {
            audio.startMenu();AudioNode menu=find(scene,"music-menu"),ambience=find(scene,"sound-menu-ambience");
            assertEquals(2,audio.menuSourceCount());assertEquals(0,audio.gameplayVoiceCount());
            for(int page=0;page<10;page++){audio.startMenu();audio.updatePresentation(.1f);}
            assertEquals(1,Collections.frequency(started,menu));assertEquals(1,Collections.frequency(started,ambience));
            audio.stopMatch();audio.prepareMatch(SESSION,NORMAL,BOSS);
            assertSame(menu,find(scene,"music-menu"));assertEquals(1,audio.musicSourceCount());
            assertEquals(AudioSource.Status.Stopped,find(scene,"music-normal").getStatus());
            audio.startPreparedMatch();assertEquals(2,audio.musicSourceCount());
            AudioNode normal=find(scene,"music-normal");assertEquals(0,normal.getVolume());
            audio.updatePresentation(.1f);assertTrue(menu.getVolume()>0);assertTrue(normal.getVolume()>0);
            for(int frame=0;frame<6;frame++)audio.updatePresentation(.1f);
            assertEquals(0,audio.menuSourceCount());assertNull(find(scene,"music-menu"));assertNull(find(scene,"sound-menu-ambience"));
            assertEquals(1,audio.musicSourceCount());assertEquals(AudioSource.Status.Stopped,menu.getStatus());
            audio.startMenu();assertEquals(2,audio.menuSourceCount());assertEquals(0,audio.gameplayVoiceCount());
        }
        assertEquals(0,scene.getQuantity());
    }

    @Test void bossCheckpointStartsThePreparedBossAtItsBeginningWithoutAThirdMusicSource() {
        Node scene=new Node();
        try(AudioDirector audio=new AudioDirector(new DesktopAssetManager(true),TestAudioRenderer.create(),new Listener(),scene)) {
            audio.startMenu();audio.prepareMatch(SESSION,NORMAL,BOSS);audio.bossMusic(true);audio.startPreparedMatch();
            assertEquals(2,audio.musicSourceCount());assertEquals(AudioSource.Status.Stopped,find(scene,"music-normal").getStatus());
            AudioNode boss=find(scene,"music-boss");assertEquals(0,boss.getTimeOffset());
            for(int i=0;i<25;i++){audio.bossMusic(true);audio.updatePresentation(.1f);assertTrue(audio.musicSourceCount()<=2);}
            assertSame(boss,find(scene,"music-boss"));assertEquals(1,audio.musicSourceCount());
        }
    }

    @Test void twoMenuSlotsRemainAvailableAtGameplaySaturationAndNeverEvictSuspendedSources() {
        Node scene=new Node();
        try(AudioDirector audio=new AudioDirector(new DesktopAssetManager(true),TestAudioRenderer.create(),new Listener(),scene)) {
            audio.startMatch(SESSION,NORMAL,BOSS);audio.bossMusic(true);
            for(int i=0;i<60;i++)audio.accept(List.of(new GameEvent(GameEvent.Type.DAMAGE,i,1,2,Vector3f.ZERO,"metal",2).inSession(SESSION)));
            assertEquals(30,audio.gameplayVoiceCount());
            List<AudioNode> gameplay=new ArrayList<>();scene.depthFirstTraversal(node->{if(node instanceof AudioNode source&&source.getStatus()==AudioSource.Status.Playing)gameplay.add(source);});
            audio.pause();
            for(UiCue cue:UiCue.values()){audio.ui(cue);assertTrue(audio.voiceCount()<=32);}
            assertEquals(32,audio.voiceCount());assertEquals(30,audio.gameplayVoiceCount());
            for(AudioNode source:gameplay)assertEquals(AudioSource.Status.Paused,source.getStatus());
            assertEquals(AudioSource.Status.Playing,find(scene,"sound-ui-back").getStatus());
            assertEquals(AudioSource.Status.Playing,find(scene,"sound-ui-vehicle").getStatus());
            audio.resume();for(AudioNode source:gameplay)assertEquals(AudioSource.Status.Playing,source.getStatus());
        }
    }

    @Test void sliderFeedbackIsThrottledButDifferentActionsAndPausedMenuCleanupRemainImmediate() {
        Node scene=new Node();List<AudioSource> started=new ArrayList<>();
        try(AudioDirector audio=new AudioDirector(new DesktopAssetManager(true),TestAudioRenderer.create(started::add),new Listener(),scene)) {
            audio.startMatch(SESSION,NORMAL,BOSS);audio.pause();
            audio.ui(UiCue.CHANGE);AudioNode first=find(scene,"sound-ui-change");
            for(int i=0;i<10;i++)audio.ui(UiCue.CHANGE);
            assertEquals(1,started.stream().filter(node->node instanceof AudioNode sound&&sound.getName().equals("sound-ui-change")).count());
            audio.updatePresentation(.079f);audio.ui(UiCue.CHANGE);assertSame(first,find(scene,"sound-ui-change"));
            audio.updatePresentation(.002f);audio.ui(UiCue.CHANGE);assertNotSame(first,find(scene,"sound-ui-change"));
            audio.ui(UiCue.CONFIRM);AudioNode confirm=find(scene,"sound-ui-confirm");assertNotNull(confirm);
            confirm.setStatus(AudioSource.Status.Stopped);audio.updatePresentation(.01f);assertNull(find(scene,"sound-ui-confirm"));
            assertTrue(audio.isPaused());assertEquals(AudioSource.Status.Paused,find(scene,"music-normal").getStatus());
        }
    }

    @Test void menuMusicAndAmbienceFollowIndependentMusicAndSfxSettings() {
        Node scene=new Node();
        try(AudioDirector audio=new AudioDirector(new DesktopAssetManager(true),TestAudioRenderer.create(),new Listener(),scene)) {
            audio.startMenu();audio.setVolumes(1,0,1);
            assertEquals(0,find(scene,"music-menu").getVolume());assertTrue(find(scene,"sound-menu-ambience").getVolume()>0);
            audio.setVolumes(1,1,0);assertTrue(find(scene,"music-menu").getVolume()>0);assertEquals(0,find(scene,"sound-menu-ambience").getVolume());
            audio.setVolumes(0,1,1);audio.ui(UiCue.NAVIGATE);
            scene.depthFirstTraversal(node->{if(node instanceof AudioNode sound)assertEquals(0,sound.getVolume());});
            audio.stopMenu();assertEquals(0,audio.menuSourceCount());
        }
    }

    private static AudioNode find(Node root,String name) {
        AudioNode[] found={null};root.depthFirstTraversal(node->{if(node instanceof AudioNode source&&name.equals(source.getName()))found[0]=source;});return found[0];
    }
}
