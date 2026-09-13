package game.wreckriff.audio;

import com.jme3.asset.DesktopAssetManager;
import com.jme3.audio.*;
import com.jme3.audio.openal.*;
import com.jme3.math.Vector3f;
import com.jme3.scene.Node;
import game.wreckriff.simulation.GameEvent;
import org.junit.jupiter.api.Test;

import java.lang.reflect.*;
import java.nio.IntBuffer;
import java.util.*;

import static com.jme3.audio.openal.AL.*;
import static org.junit.jupiter.api.Assertions.*;

/** Runs the pinned jME renderer against a deterministic device, not an audible hardware test. */
class OpenAlPauseTest {
    private static final UUID SESSION_ID=new UUID(0,101);
    private static final String MUSIC="audio/music/dead-air-yard.wav";
    @Test void independentBossSongStartsAtZeroAndSelectivePauseKeepsBothSources() {
        Node scene=new Node();
        try(Device device=new Device();AudioDirector director=new AudioDirector(new DesktopAssetManager(true),device.renderer,new Listener(),scene)) {
            director.startMatch(SESSION_ID,"audio/music/construction_17-normal.wav","audio/music/construction_17-boss.wav");
            AudioNode normal=find(scene,"music-normal"),boss=find(scene,"music-boss");
            device.advanceMusic(normal,60_000);
            assertEquals(1.25f,director.musicPlaybackSeconds(),.00001f);
            director.bossMusic(true);
            assertEquals(0,boss.getTimeOffset(),.00001f,"Independent boss recording starts at its own opening");
            assertEquals(2,director.musicSourceCount());int plays=device.plays;
            director.pause();director.bossMusic(true);director.resume();
            assertEquals(plays+2,device.plays,"Both paused native sources are resumed without reallocating or seeking");
            director.stopMatch();assertEquals(0,director.voiceCount());
        }
        assertEquals(0,scene.getQuantity());
    }

    @Test void pinnedRendererReproducesFinishedOneShotPauseRace() {
        try (Device device = new Device()) {
            Node scene = new Node();
            try (AudioDirector director = director(device, scene)) {
                director.startMatch(SESSION_ID,MUSIC,MUSIC);
                director.accept(List.of(shot()));
                AudioNode shot = find(scene, "sound-machine-gun");
                device.finish(shot);
                assertEquals(AudioSource.Status.Playing, shot.getStatus(), "jME has not polled natural completion yet");
                device.renderer.pauseSource(shot);
                AssertionError error = assertThrows(AssertionError.class, () -> device.renderer.update(0));
                assertTrue(error.getMessage().contains("OAL: Stopped, JME: Paused"));
            }
        }
    }

    @Test void selectivePauseSurvivesFinishedShotRaceWhileMenuRemainsAudible() {
        try (Device device = new Device()) {
            Node scene = new Node();
            try (AudioDirector director = director(device, scene)) {
                director.startMatch(SESSION_ID,MUSIC,MUSIC);
                director.accept(List.of(shot()));
                AudioNode shot = find(scene, "sound-machine-gun"), music = find(scene, "music-normal");
                device.advanceMusic(music, 4_800); // 0.1 seconds of stereo PCM.
                float position = director.musicPlaybackSeconds();
                assertEquals(.1f, position, .00001f);
                device.finish(shot); // Exact boundary that crashed the benchmark.
                int plays = device.plays;
                director.pause(); director.pause();
                assertFalse(device.paused,"Device must be running for menu sounds after the atomic handoff");
                assertEquals(1, device.pauseCalls);
                assertDoesNotThrow(() -> device.renderer.update(0));
                assertEquals(AudioSource.Status.Stopped, shot.getStatus());
                device.advanceMusic(music, 48_000);
                assertEquals(position, director.musicPlaybackSeconds(), .00001f);
                director.ui(UiCue.CONFIRM);
                AudioNode click=find(scene,"sound-ui-confirm");
                assertNotNull(click);assertEquals(AudioSource.Status.Playing,click.getStatus());
                assertEquals(AL_PLAYING,device.state(click));
                assertEquals(AudioSource.Status.Paused,music.getStatus());
                director.resume(); director.resume();
                assertFalse(device.paused);
                assertEquals(2, device.resumeCalls);
                assertEquals(plays+2, device.plays, "One immediate UI play and one in-place music resume");
                assertSame(music, find(scene, "music-normal"));
                assertEquals(position, director.musicPlaybackSeconds(), .00001f);
                device.advanceMusic(music, 4_800);
                assertEquals(position + .1f, director.musicPlaybackSeconds(), .00001f);
                assertDoesNotThrow(() -> device.renderer.update(0));
            }
        }
    }

    @Test void activeOneShotAndMusicKeepTheirNativeChannelAndPcmOffsetAcrossPausedUi() {
        Node scene=new Node();
        try(Device device=new Device();AudioDirector director=director(device,scene)) {
            director.startMatch(SESSION_ID,MUSIC,MUSIC);director.accept(List.of(shot()));
            AudioNode music=find(scene,"music-normal"),shot=find(scene,"sound-machine-gun");
            device.advanceMusic(music,4800);device.advanceMusic(shot,480);
            int musicChannel=music.getChannel(),shotChannel=shot.getChannel();
            float musicTime=music.getPlaybackTime(),shotTime=shot.getPlaybackTime();
            director.pause();director.ui(UiCue.NAVIGATE);director.ui(UiCue.CONFIRM);
            device.advanceMusic(music,48000);device.advanceMusic(shot,48000);
            assertEquals(musicTime,music.getPlaybackTime());assertEquals(shotTime,shot.getPlaybackTime());
            assertEquals(AL_PAUSED,device.state(shot));assertEquals(AL_PAUSED,device.state(music));
            director.updatePresentation(.1f);director.resume();
            assertEquals(musicChannel,music.getChannel());assertEquals(shotChannel,shot.getChannel());
            assertEquals(musicTime,music.getPlaybackTime());assertEquals(shotTime,shot.getPlaybackTime());
            assertDoesNotThrow(()->device.renderer.update(0));
        }
    }

    @Test void starvingStreamIsRefilledBeforeSelectivePauseAndCannotBecomeStoppedPausedMismatch() {
        Node scene=new Node();
        try(Device device=new Device();AudioDirector director=director(device,scene)) {
            director.startMatch(SESSION_ID,MUSIC,MUSIC);
            AudioNode music=find(scene,"music-normal");int channel=music.getChannel();
            device.starveAtPause=music;
            assertDoesNotThrow(director::pause);
            assertTrue(device.fillsDuringPause>0,"Starved streaming buffers are filled while the device is frozen");
            assertEquals(AL_PAUSED,device.state(music));assertEquals(AudioSource.Status.Paused,music.getStatus());
            float position=music.getPlaybackTime();director.ui(UiCue.BACK);device.advanceMusic(music,48000);
            assertEquals(position,music.getPlaybackTime());assertDoesNotThrow(()->device.renderer.update(0));
            director.resume();assertEquals(channel,music.getChannel());assertEquals(AL_PLAYING,device.state(music));
            assertEquals(position,music.getPlaybackTime());assertDoesNotThrow(()->device.renderer.update(0));
        }
    }

    @Test void pausedRetryAndCloseReleaseTheDeviceWithoutGrowingSources() {
        try (Device device = new Device()) {
            Node scene = new Node();
            AudioDirector director = director(device, scene);
            try {
                for (int retry = 0; retry < 20; retry++) {
                    director.startMatch(SESSION_ID,MUSIC,MUSIC);
                    director.accept(List.of(shot()));
                    director.pause();
                    director.stopMatch();
                    assertFalse(device.paused);
                    assertEquals(0, director.voiceCount());
                    assertDoesNotThrow(() -> device.renderer.update(0));
                }
                director.startMatch(SESSION_ID,MUSIC,MUSIC); director.pause();
            } finally {
                director.close();
            }
            assertFalse(device.paused);
            assertEquals(device.pauseCalls, device.resumeCalls);
            assertEquals(0, scene.getQuantity());
            assertDoesNotThrow(() -> device.renderer.update(0));
        }
    }

    private static AudioDirector director(Device device, Node scene) {
        return new AudioDirector(new DesktopAssetManager(true), device.renderer, new Listener(), scene);
    }
    private static GameEvent shot() {
        return new GameEvent(GameEvent.Type.SHOT, 1, 0, 0, Vector3f.ZERO, "machine-gun", 0).inSession(SESSION_ID);
    }
    private static AudioNode find(Node root, String name) {
        AudioNode[] found = {null};
        root.depthFirstTraversal(s -> { if (s instanceof AudioNode node && name.equals(node.getName())) found[0] = node; });
        return found[0];
    }

    /** Only AL playback state/offset and ALC device processing are simulated; jME code is unchanged. */
    private static final class Device implements AutoCloseable {
        final Map<Integer, Integer> states = new HashMap<>(), offsets = new HashMap<>(), processed=new HashMap<>();
        final Map<Integer, Deque<Integer>> queued = new HashMap<>();
        volatile boolean paused;
        int nextSource, nextBuffer = 100, plays, pauseCalls, resumeCalls;
        AudioSource starveAtPause;
        int fillsDuringPause;
        final AudioRenderer previous = AudioContext.getAudioRenderer();
        final ALAudioRenderer renderer;

        Device() {
            AL al = proxy(AL.class, this::al);
            ALC alc = proxy(ALC.class, (method, args) -> switch (method.getName()) {
                case "isCreated" -> true;
                case "alcGetString" -> "Deterministic OpenAL test device";
                case "alcIsExtensionPresent" -> "ALC_SOFT_pause_device".equals(args[0]);
                case "alcDevicePauseSOFT" -> { freeze();yield null; }
                case "alcDeviceResumeSOFT" -> { paused = false; resumeCalls++; yield null; }
                default -> zero(method);
            });
            renderer = new SelectiveAudioRenderer(al, alc, proxy(EFX.class, (method, args) -> zero(method)));
            renderer.initialize();
            AudioContext.setAudioRenderer(renderer);
        }
        synchronized void finish(AudioNode source) { states.put(source.getChannel() + 1, AL_STOPPED); }
        synchronized int state(AudioNode source){return states.getOrDefault(source.getChannel()+1,AL_INITIAL);}
        private synchronized void freeze() {
            paused=true;pauseCalls++;
            if(starveAtPause!=null) {
                int id=starveAtPause.getChannel()+1;
                states.put(id,AL_STOPPED);offsets.put(id,0);processed.put(id,queued.get(id).size());
                starveAtPause=null;
            }
        }
        synchronized void advanceMusic(AudioNode music, int frames) {
            int id = music.getChannel() + 1;
            if (!paused && states.get(id) == AL_PLAYING)
                offsets.merge(id,frames*music.getAudioData().getChannels()*2,Integer::sum);
        }
        private synchronized Object al(Method method, Object[] args) {
            switch (method.getName()) {
                case "alGetString": return "Deterministic OpenAL test device";
                case "alGenSources": states.put(++nextSource, AL_INITIAL); return nextSource;
                case "alGenBuffers": {
                    IntBuffer buffers = (IntBuffer) args[1];
                    for (int i = 0; i < (int) args[0]; i++) buffers.put(i, ++nextBuffer);
                    return null;
                }
                case "alSourcePlay": states.put((int) args[0], AL_PLAYING); plays++; return null;
                case "alSourcePause": {
                    int id = (int) args[0];
                    if (states.get(id) == AL_PLAYING) states.put(id, AL_PAUSED);
                    return null; // OpenAL pause of a stopped source is a no-op.
                }
                case "alSourceStop": states.put((int) args[0], AL_STOPPED); return null;
                case "alSourcei": {
                    if ((int) args[1] == AL_BUFFER && (int) args[2] == 0) {
                        queued.remove((int) args[0]); offsets.remove((int) args[0]);processed.remove((int)args[0]);
                    }
                    return null;
                }
                case "alGetSourcei": {
                    int id = (int) args[0], param = (int) args[1];
                    return switch (param) {
                        case AL_SOURCE_STATE -> states.getOrDefault(id, AL_INITIAL);
                        case AL_BYTE_OFFSET -> offsets.getOrDefault(id, 0);
                        case AL_BUFFERS_QUEUED -> queued.getOrDefault(id, new ArrayDeque<>()).size();
                        case AL_BUFFERS_PROCESSED -> processed.getOrDefault(id,0);
                        default -> 0;
                    };
                }
                case "alSourceQueueBuffers": {
                    if(paused)fillsDuringPause++;
                    Deque<Integer> ids = queued.computeIfAbsent((int) args[0], ignored -> new ArrayDeque<>());
                    IntBuffer buffers = (IntBuffer) args[2];
                    for (int i = 0; i < (int) args[1]; i++) ids.add(buffers.get(i));
                    return null;
                }
                case "alSourceUnqueueBuffers": {
                    Deque<Integer> ids = queued.get((int) args[0]);
                    IntBuffer buffers = (IntBuffer) args[2];
                    for (int i = 0; i < (int) args[1]; i++) buffers.put(i, ids.removeFirst());
                    processed.computeIfPresent((int)args[0],(key,value)->Math.max(0,value-(int)args[1]));
                    return null;
                }
                default: return zero(method);
            }
        }
        @Override public void close() {
            renderer.cleanup();
            AudioContext.setAudioRenderer(previous);
        }
        private interface Call { Object invoke(Method method, Object[] args); }
        private static <T> T proxy(Class<T> type, Call call) {
            return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                    (proxy, method, args) -> call.invoke(method, args)));
        }
        private static Object zero(Method method) {
            if (method.getReturnType() == int.class) return 0;
            if (method.getReturnType() == float.class) return 0f;
            if (method.getReturnType() == boolean.class) return false;
            return null;
        }
    }
}
