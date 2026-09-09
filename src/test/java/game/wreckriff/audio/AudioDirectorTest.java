package game.wreckriff.audio;

import com.jme3.asset.DesktopAssetManager;
import com.jme3.asset.AssetManager;
import com.jme3.asset.AssetInfo;
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
    private static final UUID SESSION_ID=new UUID(0,101);
    private static final String MUSIC="audio/metalmania.wav";
    @Test void explicitSessionBindingRejectsOldOrUnboundPickupsBeforeTheyCanPoisonDedupe() {
        Node scene=new Node();UUID foreign=new UUID(0,202);
        try(AudioDirector director=new AudioDirector(new DesktopAssetManager(true),renderer(),new Listener(),scene)) {
            director.startMatch(SESSION_ID,MUSIC,MUSIC);
            var stale=new GameEvent(GameEvent.Type.PICKUP,77,0,0,Vector3f.ZERO,"cannon-ammo",1).inSession(foreign);
            var unbound=new GameEvent(GameEvent.Type.PICKUP,77,0,0,Vector3f.ZERO,"cannon-ammo",1);
            director.accept(List.of(stale,unbound));assertEquals(1,director.voiceCount());
            var oldSession=new MatchSession(42,180,game.wreckriff.config.Configs.load("combat",game.wreckriff.combat.CombatRules.class),foreign);
            director.update(oldSession,world(Vector3f.ZERO),.1f);
            assertEquals(1,director.voiceCount(),"A stale render/update cannot silently replace the authoritative session");
            var current=event(GameEvent.Type.PICKUP,77,0,0,"cannon-ammo",1);
            director.accept(List.of(current,current));assertEquals(2,director.voiceCount());
            assertNotNull(find(scene,"sound-pickup-cannon"));
        }
    }

    @Test void retryRebindsSessionAndOnlyThatSessionsTerminalTailMayPlayAfterStop() {
        Node scene=new Node();UUID retry=new UUID(0,303);
        try(AudioDirector director=new AudioDirector(new DesktopAssetManager(true),renderer(),new Listener(),scene)) {
            director.startMatch(SESSION_ID,MUSIC,MUSIC);
            var original=event(GameEvent.Type.PICKUP,8,0,0,"power-ammo",1);director.accept(List.of(original));
            director.startMatch(retry,MUSIC,MUSIC);director.accept(List.of(original));assertEquals(1,director.voiceCount());
            var newPickup=new GameEvent(GameEvent.Type.PICKUP,8,0,0,Vector3f.ZERO,"power-ammo",1).inSession(retry);
            director.accept(List.of(newPickup));assertEquals(2,director.voiceCount());
            director.stopMatch();
            var oldResult=event(GameEvent.Type.MATCH_FINISHED,9,0,-1,"victory",0);
            var currentResult=new GameEvent(GameEvent.Type.MATCH_FINISHED,9,0,-1,Vector3f.ZERO,"victory",0).inSession(retry);
            director.accept(List.of(oldResult,newPickup,currentResult,currentResult));
            assertEquals(1,director.voiceCount());assertNotNull(find(scene,"sound-victory"));assertNull(find(scene,"sound-pickup-power"));
        }
    }

    @Test void everyPickupUsesItsExactCueAndOnlyConfirmsAPositiveGrant() {
        Node scene=new Node();
        Map<String,String> cues=Map.of("homing-ammo","homing","power-ammo","power","mine-ammo","mine",
                "napalm-ammo","napalm","ballistic-ammo","ballistic","cannon-ammo","cannon","repair","repair","turbo","turbo");
        try(AudioDirector director=new AudioDirector(new DesktopAssetManager(true),renderer(),new Listener(),scene)) {
            director.startMatch(SESSION_ID,MUSIC,MUSIC);assertEquals(1,director.voiceCount(),"Available pickups have no idle sound");
            long id=1;
            for(var cue:cues.entrySet()) {
                GameEvent pickup=event(GameEvent.Type.PICKUP,id++,0,0,cue.getKey(),1);
                int before=director.voiceCount();director.accept(List.of(pickup));director.accept(List.of(pickup));
                assertEquals(before+1,director.voiceCount(),"Duplicate delivery must not replay a pickup");
                AudioNode node=find(scene,"sound-pickup-"+cue.getValue());assertNotNull(node,cue.getKey());
                assertFalse(node.isPositional(),"Own confirmation remains clear at any camera distance");
                assertFalse(node.isLooping());assertEquals(0,director.pendingImpactCount());
            }
            int before=director.voiceCount();
            director.accept(List.of(event(GameEvent.Type.PICKUP,id++,0,0,"cannon-ammo",0),
                    event(GameEvent.Type.PICKUP,id++,0,0,"repair",-1),
                    event(GameEvent.Type.PICKUP,id++,0,0,"unknown-ammo",1),
                    event(GameEvent.Type.PICKUP,id,0,0,"repair-unrecognized",1)));
            assertEquals(before,director.voiceCount());assertNull(find(scene,"sound-pickup-ammo"));
        }
    }

    @Test void eachWeaponPickupCyclesThreeActualTakesWithoutImmediateRepetition() {
        Node scene=new Node();
        try(AudioDirector director=new AudioDirector(new DesktopAssetManager(true),renderer(),new Listener(),scene)) {
            long id=1;
            for(String kind:List.of("homing","power","mine","napalm","ballistic","cannon")) {
                director.startMatch(SESSION_ID,MUSIC,MUSIC);AudioData prior=null;
                Set<AudioData> heard=Collections.newSetFromMap(new IdentityHashMap<>());
                for(int take=0;take<9;take++) {
                    director.accept(List.of(event(GameEvent.Type.PICKUP,id++,0,0,kind+"-ammo",1)));
                    AudioNode node=find(scene,"sound-pickup-"+kind);assertNotNull(node,kind);
                    if(prior!=null)assertNotSame(prior,node.getAudioData(),kind+" immediate repeat");
                    heard.add(node.getAudioData());prior=node.getAudioData();node.setStatus(AudioSource.Status.Stopped);
                }
                assertEquals(3,heard.size(),kind);
            }
        }
    }

    @Test void enemyPickupIsQuieterPositionalAndCulledBeyondTheAudibleRange() {
        Node scene=new Node();Listener listener=new Listener();listener.setLocation(Vector3f.ZERO);
        try(AudioDirector director=new AudioDirector(new DesktopAssetManager(true),renderer(),listener,scene)) {
            director.startMatch(SESSION_ID,MUSIC,MUSIC);
            Vector3f upperRoad=new Vector3f(14,8,6);
            director.accept(List.of(new GameEvent(GameEvent.Type.PICKUP,1,0,0,upperRoad,"cannon-ammo",1).inSession(SESSION_ID)));
            AudioNode own=find(scene,"sound-pickup-cannon");
            director.accept(List.of(new GameEvent(GameEvent.Type.PICKUP,2,3,3,upperRoad,"cannon-ammo",1).inSession(SESSION_ID)));
            AudioNode enemy=find(scene,"sound-pickup-cannon");
            assertNotSame(own,enemy);assertTrue(enemy.isPositional());assertFalse(own.isPositional());
            assertEquals(upperRoad,enemy.getLocalTranslation());assertEquals(8,enemy.getRefDistance());
            assertEquals(120,enemy.getMaxDistance());assertTrue(enemy.getVolume()<own.getVolume()*.5f);
            director.accept(List.of(new GameEvent(GameEvent.Type.PICKUP,3,4,4,new Vector3f(121,0,0),"cannon-ammo",1).inSession(SESSION_ID)));
            assertEquals(3,director.voiceCount(),"Far enemy pickup must not allocate a source");
            assertSame(enemy,find(scene,"sound-pickup-cannon"));
        }
    }

    @Test void pickupFloodCannotEvictDangerCuesAndDoesNotQueueSoundsAcrossPauseRetryOrExit() {
        Node scene=new Node();
        try(AudioDirector director=new AudioDirector(new DesktopAssetManager(true),renderer(),new Listener(),scene)) {
            director.startMatch(SESSION_ID,MUSIC,MUSIC);director.hazard(true,true,Vector3f.ZERO);
            AudioNode warning=find(scene,"sound-hazard-warning"),danger=find(scene,"sound-hazard-active");
            List<GameEvent> flood=new ArrayList<>();
            for(int i=0;i<200;i++)flood.add(event(GameEvent.Type.PICKUP,i,0,0,"homing-ammo",1));
            director.accept(flood);assertEquals(32,director.voiceCount());assertEquals(0,director.pendingImpactCount());
            assertSame(warning,find(scene,"sound-hazard-warning"));assertSame(danger,find(scene,"sound-hazard-active"));
            double[] total={0};scene.depthFirstTraversal(node->{if(node instanceof AudioNode audio)total[0]+=audio.getVolume();});
            assertTrue(total[0]<=1.000001,"Pickup rush respects the shared mix ceiling");
            director.startMatch(SESSION_ID,MUSIC,MUSIC);assertEquals(1,director.voiceCount());
            director.pause();director.accept(List.of(event(GameEvent.Type.PICKUP,800,0,0,"power-ammo",1)));
            director.resume();director.updateTail(.1f);assertNull(find(scene,"sound-pickup-power"));
            director.accept(List.of(flood.getFirst()));assertEquals(2,director.voiceCount(),"Retry clears pickup dedupe");
            director.stopMatch();director.accept(List.of(event(GameEvent.Type.PICKUP,801,0,0,"power-ammo",1)));
            director.updateTail(.1f);assertEquals(0,director.voiceCount());
            assertEquals(1,((Node)scene.getChild("match-audio")).getQuantity());
        }
        assertEquals(0,scene.getQuantity());
    }

    @Test void campaignCrossfadeUsesTwoSynchronizedSourcesAndDoesNotRestartOnRepeatedPhaseUpdates() {
        Node scene=new Node();MatchSession session=match(1,180);
        try(AudioDirector director=new AudioDirector(new DesktopAssetManager(true),renderer(),new Listener(),scene)) {
            director.startMatch(SESSION_ID,"audio/campaign/construction_17-normal.wav","audio/campaign/construction_17-boss.wav");
            AudioNode normal=find(scene,"music-normal"),boss=find(scene,"music-boss");
            assertEquals(1,director.musicSourceCount());assertEquals(AudioSource.Status.Stopped,boss.getStatus());
            director.bossMusic(true);
            assertEquals(2,director.musicSourceCount());
            for(int i=0;i<10;i++) {director.bossMusic(true);director.update(session,world(Vector3f.ZERO),.1f);}
            assertTrue(normal.getVolume()>0&&boss.getVolume()>0,"Both prepared streams participate in the crossfade");
            float blendVolume=boss.getVolume();director.pause();director.update(session,world(Vector3f.ZERO),.1f);
            assertEquals(blendVolume,boss.getVolume());director.resume();
            for(int i=0;i<12;i++)director.update(session,world(Vector3f.ZERO),.1f);
            assertEquals(1,director.musicSourceCount());assertEquals(AudioSource.Status.Stopped,normal.getStatus());
            assertSame(boss,find(scene,"music-boss"));
            for(int i=0;i<10;i++)director.bossMusic(true);
            assertEquals(1,director.musicSourceCount());
            director.stopMatch();assertEquals(0,director.voiceCount());
        }
        assertEquals(0,scene.getQuantity());
    }

    @Test void crossfadeIncludesBothMusicSourcesInTheHardGlobalSourceAndGainBudget() {
        Node scene=new Node();
        try(AudioDirector director=new AudioDirector(new DesktopAssetManager(true),renderer(),new Listener(),scene)) {
            director.startMatch(SESSION_ID,"audio/campaign/neon_zero-normal.wav","audio/campaign/neon_zero-boss.wav");
            for(int i=0;i<60;i++)director.accept(List.of(event(GameEvent.Type.DAMAGE,i,1,2,"metal",2)));
            assertEquals(32,director.voiceCount());
            director.bossMusic(true);assertEquals(32,director.voiceCount());assertEquals(2,director.musicSourceCount());
            director.update(match(1,180),world(Vector3f.ZERO),.1f);
            director.hazard(true,false,Vector3f.ZERO);
            double[] total={0};scene.depthFirstTraversal(node->{if(node instanceof AudioNode audio)total[0]+=audio.getVolume();});
            assertTrue(total[0]<=1.000001,"Both music gains are counted by mix headroom");
            assertTrue(director.voiceCount()<=32);assertNotNull(find(scene,"sound-hazard-warning"));
        }
    }

    @Test void changingArenaDisposesOldStreamsAndRetryNeverAccumulatesMusicNodes() {
        Node scene=new Node();
        try(AudioDirector director=new AudioDirector(new DesktopAssetManager(true),renderer(),new Listener(),scene)) {
            for(String arena:List.of("construction_17","neon_zero","euphoria_park","ash_necropolis","doomsday_arena")) {
                director.startMatch(SESSION_ID,"audio/campaign/"+arena+"-normal.wav","audio/campaign/"+arena+"-boss.wav");
                assertEquals(2,((Node)scene.getChild("match-audio")).getQuantity());
                AudioNode original=find(scene,"music-normal");
                director.startMatch(SESSION_ID,"audio/campaign/"+arena+"-normal.wav","audio/campaign/"+arena+"-boss.wav");
                assertSame(original,find(scene,"music-normal"));assertEquals(1,director.voiceCount());
                director.bossMusic(true);director.stopMatch();assertEquals(0,director.voiceCount());
            }
            director.startMatch(SESSION_ID,MUSIC,MUSIC);assertNotNull(find(scene,"music-metalmania"));
            assertEquals(1,((Node)scene.getChild("match-audio")).getQuantity());
        }
        assertEquals(0,scene.getQuantity());
    }

    @Test void campaignMusicOwnsAtMostTheCurrentPairOfFileHandlesAndClosesBothOnShutdown() {
        DesktopAssetManager delegate=new DesktopAssetManager(true);int[] open={0};
        AssetManager[] holder=new AssetManager[1];
        holder[0]=(AssetManager)Proxy.newProxyInstance(AssetManager.class.getClassLoader(),new Class<?>[]{AssetManager.class},(proxy,method,args)->{
            if(method.getName().equals("locateAsset")) {
                AssetInfo source=(AssetInfo)method.invoke(delegate,args);
                if(source==null)return null;
                return new AssetInfo(holder[0],source.getKey()) {
                    @Override public java.io.InputStream openStream() {
                        var input=source.openStream();open[0]++;
                        return new java.io.FilterInputStream(input) {
                            private boolean closed;
                            @Override public void close()throws java.io.IOException {
                                if(closed)return;closed=true;
                                try {super.close();}finally {open[0]--;}
                            }
                        };
                    }
                };
            }
            return method.invoke(delegate,args);
        });
        try(AudioDirector director=new AudioDirector(holder[0],renderer(),new Listener(),new Node())) {
            assertEquals(1,open[0]);
            for(String arena:List.of("construction_17","neon_zero","euphoria_park","ash_necropolis","doomsday_arena")) {
                director.startMatch(SESSION_ID,"audio/campaign/"+arena+"-normal.wav","audio/campaign/"+arena+"-boss.wav");
                assertEquals(2,open[0]);director.bossMusic(true);director.stopMatch();assertEquals(2,open[0]);
            }
        }
        assertEquals(0,open[0],"Both current streams, and all earlier arena streams, must release their owned file handles");
    }

    @Test void pausedPhaseChangeDoesNotAllocateOrAdvanceMusicUntilResumed() {
        Node scene=new Node();
        try(AudioDirector director=new AudioDirector(new DesktopAssetManager(true),renderer(),new Listener(),scene)) {
            director.startMatch(SESSION_ID,"audio/campaign/euphoria_park-normal.wav","audio/campaign/euphoria_park-boss.wav");
            director.pause();director.bossMusic(true);assertEquals(1,director.musicSourceCount());
            director.resume();assertEquals(2,director.musicSourceCount());
            director.stopMatch();director.bossMusic(true);assertEquals(0,director.voiceCount());
        }
    }

    @Test void a02PauseResumesSameMusicObjectAndRetryDoesNotGrowSources() {
        Node scene=new Node();
        try(AudioDirector director=new AudioDirector(new DesktopAssetManager(true),renderer(),new Listener(),scene)) {
            AudioNode music=find(scene,"music-metalmania");
            for(int retry=0;retry<20;retry++) {
                director.startMatch(SESSION_ID,MUSIC,MUSIC);
                MatchSession session=match(retry,180);
                director.update(session,world(new Vector3f(0,0,12)),1f/60);
                int original=director.voiceCount();
                assertTrue(original>1);
                director.pause(); assertTrue(director.isPaused());
                assertEquals(AudioSource.Status.Playing,music.getStatus(),"Device pause preserves source state and stream position");
                director.ui(true);
                assertEquals(original,director.voiceCount(),"Paused UI must not enqueue clicks for later playback");
                director.resume();
                assertSame(music,find(scene,"music-metalmania"));
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
            director.startMatch(SESSION_ID,MUSIC,MUSIC);
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
            director.startMatch(SESSION_ID,MUSIC,MUSIC);MatchSession session=match(42,180);
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
        director.startMatch(SESSION_ID,MUSIC,MUSIC);director.pause();director.resume();director.accept(List.of());
        director.update(match(1,180),world(Vector3f.ZERO),.02f);
        assertEquals(0,director.voiceCount());director.close();director.close();
        assertEquals(0,scene.getQuantity());
    }
    @Test void resultStingerPlaysAfterMatchCleanupWithoutRestartingMusic() {
        Node scene=new Node();
        try(AudioDirector director=new AudioDirector(new DesktopAssetManager(true),renderer(),new Listener(),scene)) {
            director.startMatch(SESSION_ID,MUSIC,MUSIC);director.stopMatch();
            director.accept(List.of(event(GameEvent.Type.MATCH_FINISHED,1,0,-1,"victory",0)));
            assertNotNull(find(scene,"sound-victory"));assertEquals(1,director.voiceCount());
            assertEquals(AudioSource.Status.Stopped,find(scene,"music-metalmania").getStatus());
        }
    }
    @Test void finalDeathAndResultPlayExactlyOnceThenTailPrunesWithoutRecreatingMatchAudio() {
        Node scene=new Node();
        try(AudioDirector director=new AudioDirector(new DesktopAssetManager(true),renderer(),new Listener(),scene)) {
            director.startMatch(SESSION_ID,MUSIC,MUSIC);director.stopMatch();
            List<GameEvent> finalEvents=List.of(event(GameEvent.Type.DESTROYED,1,1,0,"destroyed",1),
                    event(GameEvent.Type.MATCH_FINISHED,1,0,-1,"victory",0),
                    event(GameEvent.Type.SHOT,2,0,0,"machine-gun",6),
                    event(GameEvent.Type.FIRE_STARTED,3,0,0,"napalm",5));
            director.accept(finalEvents);
            AudioNode stinger=find(scene,"sound-victory");assertNotNull(stinger);
            AudioNode destruction=find(scene,"sound-destroyed");assertNotNull(destruction);
            assertEquals(2,director.voiceCount());
            director.accept(finalEvents);assertEquals(2,director.voiceCount(),"Repeated final batch cannot replay either cue");
            director.hazard(true,true,Vector3f.ZERO);
            director.updateTail(.1f);assertSame(stinger,find(scene,"sound-victory"));
            assertSame(destruction,find(scene,"sound-destroyed"));assertEquals(2,director.voiceCount());
            stinger.setStatus(AudioSource.Status.Stopped);destruction.setStatus(AudioSource.Status.Stopped);
            director.updateTail(.1f);assertEquals(0,director.voiceCount());assertNull(find(scene,"sound-victory"));
            assertNull(find(scene,"sound-destroyed"));
            director.accept(finalEvents);assertEquals(0,director.voiceCount(),"Finished tail events remain deduplicated");
            assertEquals(1,((Node)scene.getChild("match-audio")).getQuantity());
            assertEquals(AudioSource.Status.Stopped,find(scene,"music-metalmania").getStatus());
        }
    }
    @Test void fireZoneAudioHasOneTrackedLoopPerZoneAndStopsOnItsExpiry() {
        Node scene=new Node();
        try(AudioDirector director=new AudioDirector(new DesktopAssetManager(true),renderer(),new Listener(),scene)) {
            director.startMatch(SESSION_ID,MUSIC,MUSIC);
            director.accept(List.of(event(GameEvent.Type.FIRE_STARTED,41,0,0,"napalm-fire",5),
                    event(GameEvent.Type.FIRE_STARTED,42,1,1,"napalm-fire",5)));
            assertEquals(3,director.voiceCount());
            director.accept(List.of(event(GameEvent.Type.FIRE_STARTED,41,0,0,"napalm-fire",5)));
            assertEquals(3,director.voiceCount(),"Duplicate zone event cannot create a second loop");
            director.pause(); director.resume();
            director.accept(List.of(event(GameEvent.Type.FIRE_ENDED,41,0,0,"napalm-fire",0)));
            assertEquals(2,director.voiceCount());
            director.accept(List.of(event(GameEvent.Type.FIRE_ENDED,42,1,1,"napalm-fire",0)));
            assertEquals(1,director.voiceCount());
            assertNull(find(scene,"sound-napalm-fire"));
        }
    }
    @Test void lowHealthAlertTracksQuarterOfPlayerMaximumInsteadOfTheOldAbsoluteThreshold() {
        Node scene=new Node();
        try(AudioDirector director=new AudioDirector(new DesktopAssetManager(true),renderer(),new Listener(),scene)) {
            director.startMatch(SESSION_ID,MUSIC,MUSIC);MatchSession session=match(42,180);
            session.vehicles.getFirst().hp=201;
            director.update(session,world(Vector3f.ZERO),.02f);
            assertNull(find(scene,"sound-low-hp"));
            session.vehicles.getFirst().hp=200;
            director.update(session,world(Vector3f.ZERO),.02f);
            assertNotNull(find(scene,"sound-low-hp"));
        }
    }
    @Test void newWeaponsAndControlsUseTheirOwnCues() {
        Node scene=new Node();
        try(AudioDirector director=new AudioDirector(new DesktopAssetManager(true),renderer(),new Listener(),scene)) {
            director.startMatch(SESSION_ID,MUSIC,MUSIC);
            director.accept(List.of(event(GameEvent.Type.SHOT,1,0,0,"napalm",0),
                    event(GameEvent.Type.MINE_PLACED,2,0,0,"mine",0),
                    event(GameEvent.Type.EXPLOSION,3,1,0,"mine",70),
                    event(GameEvent.Type.FREEZE,4,1,0,"freeze",2),
                    event(GameEvent.Type.CONTROL_ENDED,5,1,0,"freeze",0),
                    event(GameEvent.Type.SHIELD,6,0,0,"shield",2.5f),
                    event(GameEvent.Type.SHIELD_HIT,7,0,1,"homing",35),
                    event(GameEvent.Type.SHIELD_ENDED,8,0,0,"shield",0)));
            for(String name:List.of("napalm-launch","mine-place","mine-detonate","freeze-hit","freeze-end","shield-on","shield-hit","shield-end"))
                assertNotNull(find(scene,"sound-"+name),name);
            assertNull(find(scene,"sound-machine-gun"),"New shots must not fall through to the old default cue");
        }
    }
    @Test void recordedTakesNeverRepeatImmediatelyAndUseAtLeastThreeActualBuffers() {
        Node scene=new Node();
        try(AudioDirector director=new AudioDirector(new DesktopAssetManager(true),renderer(),new Listener(),scene)) {
            director.startMatch(SESSION_ID,MUSIC,MUSIC);
            for(String cue:List.of("machine-gun","metal-hit","explosion")) {
                Set<AudioData> heard=Collections.newSetFromMap(new IdentityHashMap<>());
                AudioData prior=null;
                for(int i=0;i<6;i++) {
                    GameEvent.Type type=cue.equals("machine-gun")?GameEvent.Type.SHOT
                            :cue.equals("metal-hit")?GameEvent.Type.IMPACT:GameEvent.Type.EXPLOSION;
                    director.accept(List.of(event(type,100+i,1,0,cue.equals("explosion")?"homing":"machine-gun",35)));
                    AudioData sample=find(scene,"sound-"+cue).getAudioData();
                    if(prior!=null)assertNotSame(prior,sample,cue+" repeated its previous take");
                    heard.add(sample);prior=sample;
                }
                assertEquals(3,heard.size(),cue);
            }
        }
    }
    @Test void cannonBallisticAndRamOwnOneContactCueWithThreeTakesAndNoDuplicateImpactLayers() {
        record Cue(GameEvent.Type type,String kind,String bank) {}
        List<Cue> cues=List.of(new Cue(GameEvent.Type.SHOT,"cannon","cannon-launch"),
                new Cue(GameEvent.Type.EXPLOSION,"cannon-ricochet","cannon-ricochet"),
                new Cue(GameEvent.Type.EXPLOSION,"cannon","cannon-hit"),
                new Cue(GameEvent.Type.SHOT,"ballistic","ballistic-launch"),
                new Cue(GameEvent.Type.SHOT,"ballistic-fall","ballistic-fall"),
                new Cue(GameEvent.Type.EXPLOSION,"ballistic","ballistic-explosion"),
                new Cue(GameEvent.Type.RAM,"ram","ram-hit"));
        Node scene=new Node();
        try(AudioDirector director=new AudioDirector(new DesktopAssetManager(true),renderer(),new Listener(),scene)) {
            for(Cue cue:cues) {
                director.startMatch(SESSION_ID,MUSIC,MUSIC);
                Set<AudioData> samples=Collections.newSetFromMap(new IdentityHashMap<>());AudioData prior=null;
                for(int i=0;i<6;i++) {
                    List<GameEvent> batch=new ArrayList<>();
                    batch.add(event(cue.type,100+i,1,0,cue.kind,20));
                    if(cue.type==GameEvent.Type.EXPLOSION||cue.type==GameEvent.Type.RAM) {
                        batch.add(event(GameEvent.Type.IMPACT,100+i,1,0,cue.kind,20));
                        batch.add(event(GameEvent.Type.DAMAGE,100+i,1,0,cue.kind,20));
                        batch.add(event(GameEvent.Type.DAMAGE,100+i,0,1,cue.kind,20));
                    }
                    director.accept(batch);director.accept(batch);
                    assertEquals(i+2,director.voiceCount(),cue.bank+" needs one source per accepted event, plus music");
                    AudioNode node=find(scene,"sound-"+cue.bank);assertNotNull(node,cue.bank);
                    AudioData sample=node.getAudioData();if(prior!=null)assertNotSame(prior,sample);
                    samples.add(sample);prior=sample;
                    assertNull(find(scene,"sound-metal-hit"));assertNull(find(scene,"sound-machine-gun"));
                    assertEquals(0,director.pendingImpactCount());
                }
                assertEquals(3,samples.size(),cue.bank);
            }
        }
    }
    @Test void ramCrushGainFollowsAuthoritativeClosingSpeed() {
        Node scene=new Node();
        try(AudioDirector director=new AudioDirector(new DesktopAssetManager(true),renderer(),new Listener(),scene)) {
            director.startMatch(SESSION_ID,MUSIC,MUSIC);director.accept(List.of(event(GameEvent.Type.RAM,1,1,0,"ram",5)));
            AudioNode mild=find(scene,"sound-ram-hit");
            assertTrue(mild.isPositional());assertEquals(16,mild.getRefDistance(),.001f,"Player contact stays audible from chase camera distance");
            director.accept(List.of(event(GameEvent.Type.RAM,2,1,0,"ram",20)));
            AudioNode hard=find(scene,"sound-ram-hit");assertNotSame(mild,hard);
            assertTrue(hard.getVolume()>mild.getVolume()*2,"A hard ram must carry more of the same premixed crush cue");
            director.pause();director.accept(List.of(event(GameEvent.Type.RAM,3,1,0,"ram",20)));
            assertEquals(3,director.voiceCount(),"Paused rams cannot queue sounds");director.resume();
            director.stopMatch();director.accept(List.of(event(GameEvent.Type.RAM,4,1,0,"ram",20)));
            assertEquals(0,director.voiceCount(),"Results cannot restart ramming sounds");
        }
    }
    @Test void sharedEventIdsKeepDifferentTypesAndSubjectsButDuplicateDeliveryIsIgnored() {
        Node scene=new Node();
        try(AudioDirector director=new AudioDirector(new DesktopAssetManager(true),renderer(),new Listener(),scene)) {
            director.startMatch(SESSION_ID,MUSIC,MUSIC);
            MatchSession session=match(42,180);
            director.update(session,world(Vector3f.ZERO),.01f);
            int baseline=director.voiceCount();
            Vector3f muzzle=new Vector3f(3,2,4),contact=new Vector3f(8,2,9);
            List<GameEvent> events=List.of(new GameEvent(GameEvent.Type.SHOT,71,0,0,contact,"machine-gun",6,muzzle,Vector3f.ZERO).inSession(SESSION_ID),
                    new GameEvent(GameEvent.Type.IMPACT,71,1,0,contact,"machine-gun",6,muzzle,Vector3f.UNIT_Y).inSession(SESSION_ID),
                    event(GameEvent.Type.SHIELD_HIT,71,1,0,"machine-gun",6),
                    event(GameEvent.Type.SHIELD_HIT,71,2,0,"machine-gun",6));
            director.accept(events);
            assertEquals(baseline+3,director.voiceCount());
            assertEquals(1,director.pendingImpactCount());
            director.update(session,world(Vector3f.ZERO),.05f);
            assertEquals(baseline+4,director.voiceCount());
            assertEquals(muzzle,find(scene,"sound-machine-gun").getLocalTranslation());
            assertEquals(contact,find(scene,"sound-metal-hit").getLocalTranslation());
            director.accept(events);assertEquals(baseline+4,director.voiceCount());
            director.startMatch(SESSION_ID,MUSIC,MUSIC);director.update(session,world(Vector3f.ZERO),.01f);director.accept(events);
            director.update(session,world(Vector3f.ZERO),.05f);
            assertEquals(baseline+4,director.voiceCount(),"Retry clears event dedupe state");
        }
    }
    @Test void bulletContactDelayIsSharedBoundedPausedAndClearedByDeathOrRetry() {
        Node scene=new Node();MatchSession session=match(42,180);
        try(AudioDirector director=new AudioDirector(new DesktopAssetManager(true),renderer(),new Listener(),scene)) {
            director.startMatch(SESSION_ID,MUSIC,MUSIC);director.update(session,world(Vector3f.ZERO),.01f);
            GameEvent hit=new GameEvent(GameEvent.Type.IMPACT,901,1,0,new Vector3f(18,0,0),"machine-gun",6,Vector3f.ZERO,Vector3f.UNIT_Y).inSession(SESSION_ID);
            assertEquals(.1f,hit.cosmeticImpactDelaySeconds(),1e-6);
            director.accept(List.of(hit));assertNull(find(scene,"sound-metal-hit"));
            director.pause();director.update(session,world(Vector3f.ZERO),.1f);
            assertEquals(1,director.pendingImpactCount());assertNull(find(scene,"sound-metal-hit"));
            director.resume();director.update(session,world(Vector3f.ZERO),.05f);
            assertNull(find(scene,"sound-metal-hit"));director.update(session,world(Vector3f.ZERO),.051f);
            assertNotNull(find(scene,"sound-metal-hit"));assertEquals(0,director.pendingImpactCount());
            List<GameEvent> flood=new ArrayList<>();
            for(int i=0;i<160;i++)flood.add(new GameEvent(GameEvent.Type.SHIELD_HIT,1000+i,1,0,new Vector3f(18,0,0),"machine-gun",6).inSession(SESSION_ID));
            director.accept(flood);assertEquals(128,director.pendingImpactCount());
            director.accept(List.of(event(GameEvent.Type.DESTROYED,2000,1,0,"destroyed",1)));
            assertEquals(0,director.pendingImpactCount());
            director.accept(List.of(new GameEvent(GameEvent.Type.IMPACT,3000,-1,0,new Vector3f(18,0,0),"machine-gun",6).inSession(SESSION_ID)));
            assertEquals(1,director.pendingImpactCount());director.startMatch(SESSION_ID,MUSIC,MUSIC);assertEquals(0,director.pendingImpactCount());
        }
    }
    private static MatchSession match(long seed,int seconds) {
        return new MatchSession(seed,seconds,game.wreckriff.config.Configs.load("combat",game.wreckriff.combat.CombatRules.class),SESSION_ID);
    }
    private static GameEvent event(GameEvent.Type type,long id,int subject,int source,String kind,float value) {
        return new GameEvent(type,id,subject,source,Vector3f.ZERO,kind,value).inSession(SESSION_ID);
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
            public Hit sweep(Vector3f a,Vector3f b,float r,int id,float stepStart,float stepEnd){return null;}
            public Hit staticSweep(Vector3f a,Vector3f b,float r){return null;}
            public boolean visible(Vector3f a,Vector3f b,int id){return true;}
            public float distanceToHull(int id,Vector3f p){return 0;}
            public void impulse(int id,Vector3f linear,Vector3f angular,float cap){}
            public Vector3f closestHullPoint(int id,Vector3f from){return position(id);}
        };
    }
}
