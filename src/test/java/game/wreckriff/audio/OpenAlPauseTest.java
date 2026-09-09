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
    @Test void pinnedRendererReproducesFinishedOneShotPauseRace() {
        try (Device device = new Device()) {
            Node scene = new Node();
            try (AudioDirector director = director(device, scene)) {
                director.startMatch();
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

    @Test void devicePauseSurvivesTheRaceAndResumesTheSameMusicAtTheSamePosition() {
        try (Device device = new Device()) {
            Node scene = new Node();
            try (AudioDirector director = director(device, scene)) {
                director.startMatch();
                director.accept(List.of(shot()));
                AudioNode shot = find(scene, "sound-machine-gun"), music = find(scene, "music-metalmania");
                device.advanceMusic(music, 4_800); // 0.1 seconds of stereo PCM.
                float position = director.musicPlaybackSeconds();
                assertEquals(.1f, position, .00001f);
                device.finish(shot); // Exact boundary that crashed the benchmark.
                int plays = device.plays;
                director.pause(); director.pause();
                assertTrue(device.paused);
                assertEquals(1, device.pauseCalls);
                assertDoesNotThrow(() -> device.renderer.update(0));
                assertEquals(AudioSource.Status.Stopped, shot.getStatus());
                device.advanceMusic(music, 48_000);
                assertEquals(position, director.musicPlaybackSeconds(), .00001f);
                director.ui(true);
                assertNull(find(scene, "sound-ui-confirm"), "Paused UI must not queue delayed clicks");
                director.resume(); director.resume();
                assertFalse(device.paused);
                assertEquals(1, device.resumeCalls);
                assertEquals(plays, device.plays, "Resume must not restart sources or music");
                assertSame(music, find(scene, "music-metalmania"));
                assertEquals(position, director.musicPlaybackSeconds(), .00001f);
                device.advanceMusic(music, 4_800);
                assertEquals(position + .1f, director.musicPlaybackSeconds(), .00001f);
                assertDoesNotThrow(() -> device.renderer.update(0));
            }
        }
    }

    @Test void pausedRetryAndCloseReleaseTheDeviceWithoutGrowingSources() {
        try (Device device = new Device()) {
            Node scene = new Node();
            AudioDirector director = director(device, scene);
            try {
                for (int retry = 0; retry < 20; retry++) {
                    director.startMatch();
                    director.accept(List.of(shot()));
                    director.pause();
                    director.stopMatch();
                    assertFalse(device.paused);
                    assertEquals(0, director.voiceCount());
                    assertDoesNotThrow(() -> device.renderer.update(0));
                }
                director.startMatch(); director.pause();
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
        return new GameEvent(GameEvent.Type.SHOT, 1, 0, 0, Vector3f.ZERO, "machine-gun", 0);
    }
    private static AudioNode find(Node root, String name) {
        AudioNode[] found = {null};
        root.depthFirstTraversal(s -> { if (s instanceof AudioNode node && name.equals(node.getName())) found[0] = node; });
        return found[0];
    }

    /** Only AL playback state/offset and ALC device processing are simulated; jME code is unchanged. */
    private static final class Device implements AutoCloseable {
        final Map<Integer, Integer> states = new HashMap<>(), offsets = new HashMap<>();
        final Map<Integer, Deque<Integer>> queued = new HashMap<>();
        volatile boolean paused;
        int nextSource, nextBuffer = 100, plays, pauseCalls, resumeCalls;
        final AudioRenderer previous = AudioContext.getAudioRenderer();
        final ALAudioRenderer renderer;

        Device() {
            AL al = proxy(AL.class, this::al);
            ALC alc = proxy(ALC.class, (method, args) -> switch (method.getName()) {
                case "isCreated" -> true;
                case "alcGetString" -> "Deterministic OpenAL test device";
                case "alcIsExtensionPresent" -> "ALC_SOFT_pause_device".equals(args[0]);
                case "alcDevicePauseSOFT" -> { paused = true; pauseCalls++; yield null; }
                case "alcDeviceResumeSOFT" -> { paused = false; resumeCalls++; yield null; }
                default -> zero(method);
            });
            renderer = new ALAudioRenderer(al, alc, proxy(EFX.class, (method, args) -> zero(method)));
            renderer.initialize();
            AudioContext.setAudioRenderer(renderer);
        }
        synchronized void finish(AudioNode source) { states.put(source.getChannel() + 1, AL_STOPPED); }
        synchronized void advanceMusic(AudioNode music, int frames) {
            int id = music.getChannel() + 1;
            if (!paused && states.get(id) == AL_PLAYING) offsets.merge(id, frames * 4, Integer::sum);
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
                        queued.remove((int) args[0]); offsets.remove((int) args[0]);
                    }
                    return null;
                }
                case "alGetSourcei": {
                    int id = (int) args[0], param = (int) args[1];
                    return switch (param) {
                        case AL_SOURCE_STATE -> states.getOrDefault(id, AL_INITIAL);
                        case AL_BYTE_OFFSET -> offsets.getOrDefault(id, 0);
                        case AL_BUFFERS_QUEUED -> queued.getOrDefault(id, new ArrayDeque<>()).size();
                        default -> 0;
                    };
                }
                case "alSourceQueueBuffers": {
                    Deque<Integer> ids = queued.computeIfAbsent((int) args[0], ignored -> new ArrayDeque<>());
                    IntBuffer buffers = (IntBuffer) args[2];
                    for (int i = 0; i < (int) args[1]; i++) ids.add(buffers.get(i));
                    return null;
                }
                case "alSourceUnqueueBuffers": {
                    Deque<Integer> ids = queued.get((int) args[0]);
                    IntBuffer buffers = (IntBuffer) args[2];
                    for (int i = 0; i < (int) args[1]; i++) buffers.put(i, ids.removeFirst());
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
