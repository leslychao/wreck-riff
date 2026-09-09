package game.wreckriff.audio;

import com.jme3.asset.AssetManager;
import com.jme3.audio.*;
import com.jme3.math.Vector3f;
import com.jme3.scene.Node;
import game.wreckriff.simulation.*;
import java.io.*;
import java.util.*;

/** One render-thread audio owner. Never creates a device, never uses untracked playInstance voices. */
public final class AudioDirector implements AutoCloseable {
    private enum Group { ENGINE, WEAPON, THREAT, UI }
    private static final class Voice {
        final AudioNode node;
        final Group group;
        final int priority;
        final String loopKey;
        float gain;
        Voice(AudioNode node, Group group, int priority, String loopKey, float gain) {
            this.node=node; this.group=group; this.priority=priority; this.loopKey=loopKey; this.gain=gain;
        }
    }
    private final AudioRenderer renderer;
    private final Listener listener;
    private final AudioConfig config;
    private final Node audioRoot = new Node("match-audio");
    private final Map<String,AudioData> buffers = new LinkedHashMap<>();
    private final List<Voice> voices = new ArrayList<>();
    private final Map<String,Voice> loops = new HashMap<>();
    private final float[] priorSpeed = new float[5], priorTurbo = new float[5], enginePitch = new float[5];
    private AudioNode music;
    private boolean closed, paused, matchActive, warningWasActive;
    private float master = 1, musicVolume = 1, sfxVolume = 1, duck, lowHpClock;
    private UUID sessionId;
    private WorldQuery currentWorld;

    public AudioDirector(AssetManager assets, AudioRenderer renderer, Listener listener, Node parent) {
        this(assets, renderer, listener, parent, AudioConfig.load());
    }
    AudioDirector(AssetManager assets, AudioRenderer renderer, Listener listener, Node parent, AudioConfig config) {
        this.renderer=renderer; this.listener=listener; this.config=config;
        Arrays.fill(enginePitch, 1);
        Arrays.fill(priorTurbo, 100);
        parent.attachChild(audioRoot);
        if (renderer == null) return; // Application emits the explicit no-device/no-audio diagnostic.
        try {
            AudioKey key = new AudioKey(config.musicAsset(), true, false);
            music = new AudioNode(PcmWave.stream(assets.locateAsset(key)), key);
            music.setName("music-metalmania");
            music.setPositional(false); music.setLooping(true);
            audioRoot.attachChild(music);
            for (String id : config.effects()) {
                AudioData data = assets.loadAsset(new AudioKey("audio/" + id + ".wav", false));
                if (data.getChannels() != 1) throw new IOException("Positional effect is not mono: " + id);
                buffers.put(id, data);
            }
            applyVolumes();
        } catch (IOException | RuntimeException e) {
            close();
            throw new IllegalStateException("Cannot initialize required audio assets", e);
        }
    }

    public void startMatch() {
        if (closed) throw new IllegalStateException("AudioDirector is closed");
        stopMatch();
        matchActive=true; paused=false;
        if (music != null) renderer.playSource(music);
    }

    public void stopMatch() {
        if (renderer != null) {
            for (Voice voice : List.copyOf(voices)) remove(voice);
            if (music != null) renderer.stopSource(music);
        }
        sessionId=null; currentWorld=null; matchActive=false; paused=false; warningWasActive=false; lowHpClock=0; duck=0;
        Arrays.fill(priorSpeed, 0); Arrays.fill(priorTurbo, 100); Arrays.fill(enginePitch, 1);
    }

    public void update(MatchSession session, WorldQuery world, float dt) {
        if (closed || renderer == null) return;
        prune();
        if (!matchActive || paused || session == null || world == null) return;
        if (sessionId == null) sessionId=session.sessionId;
        if (!sessionId.equals(session.sessionId)) {
            startMatch(); sessionId=session.sessionId;
        }
        currentWorld=world;
        dt=Math.max(0, Math.min(dt, .1f));
        duck=Math.max(0, duck-dt);
        lowHpClock=Math.max(0, lowHpClock-dt);
        for (VehicleState vehicle : session.vehicles) {
            int id=vehicle.id;
            if (!vehicle.alive() || session.outcome != MatchSession.Outcome.NONE) {
                stopLoop("idle-"+id); stopLoop("drive-"+id); stopLoop("slip-"+id); stopLoop("turbo-"+id);
                continue;
            }
            Vector3f velocity=world.velocity(id), position=world.position(id);
            float speed=velocity.length();
            float acceleration=dt > 0 ? Math.max(0, (speed-priorSpeed[id])/dt) : 0;
            float blend=clamp(speed/23f, 0, 1);
            float targetPitch=clamp(.82f+speed*.019f+Math.min(acceleration,12)*.012f,
                    config.enginePitchMin(), config.enginePitchMax());
            enginePitch[id]+=(targetPitch-enginePitch[id])*(1-(float)Math.exp(-dt/config.engineSmoothingSeconds()));
            float own=vehicle.player ? .84f : .49f;
            loop("idle-"+id,"engine-idle",Group.ENGINE,vehicle.player?72:22,position,velocity,
                    own*(1-blend*.85f),enginePitch[id]);
            loop("drive-"+id,"engine-drive",Group.ENGINE,vehicle.player?72:22,position,velocity,
                    own*blend,clamp(enginePitch[id]*.87f,.5f,2));
            Vector3f right=world.rotation(id).mult(Vector3f.UNIT_X);
            float lateral=Math.abs(velocity.dot(right));
            // Grounded lateral movement, not a key state, determines tyre noise.
            float slipping=world.grounded(id)?clamp((lateral-1.8f)/8f,0,1):0;
            loop("slip-"+id,"tyre-slip",Group.ENGINE,vehicle.player?55:12,position,velocity,
                    slipping*own*.8f,clamp(.87f+lateral*.015f,.5f,2));
            boolean boosting=vehicle.turbo < priorTurbo[id]-.01f;
            loop("turbo-"+id,"turbo-loop",Group.ENGINE,vehicle.player?68:25,position,velocity,
                    boosting?own*.9f:0,1);
            priorSpeed[id]=speed; priorTurbo[id]=vehicle.turbo;
            if (vehicle.player && vehicle.hp<=vehicle.maximumHp*.25f && lowHpClock<=0) {
                shot("low-hp",Group.THREAT,100,null,.65f,1); lowHpClock=3;
            }
        }
        applyVolumes();
    }

    /** Arena owner supplies phase and actual zone position; audio never runs another hazard clock. */
    public void hazard(boolean warning, boolean active, Vector3f position) {
        if (closed || renderer == null || paused || !matchActive) return;
        if (warning && !warningWasActive) shot("hazard-warning",Group.THREAT,100,position,.95f,1);
        warningWasActive=warning;
        loop("hazard","hazard-active",Group.THREAT,82,position,Vector3f.ZERO,active?.65f:0,1);
        applyVolumes();
    }

    public void accept(List<GameEvent> events) {
        if (closed || renderer == null || paused) return;
        prune();
        for (GameEvent event : events) {
            // Results are UI feedback after the composition root has stopped the match loops.
            if(!matchActive && event.type()!=GameEvent.Type.MATCH_FINISHED)continue;
            boolean player=event.sourceId()==0;
            String kind=event.kind()==null?"":event.kind().toLowerCase(Locale.ROOT);
            switch (event.type()) {
                case SHOT -> {
                    String id=kind.contains("power")?"power-launch":kind.contains("homing")?"homing-launch"
                            :kind.contains("napalm")?"napalm-launch":kind.contains("freeze")?"freeze"
                            :kind.contains("stun")?"stun":"machine-gun";
                    float gain=id.equals("machine-gun")?.32f:1;
                    Vector3f sourcePosition=id.equals("machine-gun") && currentWorld!=null
                            ? currentWorld.muzzle(event.sourceId()) : event.position();
                    shot(id,Group.WEAPON,player?96:58,sourcePosition,gain,1);
                }
                case EXPLOSION -> {
                    shot(kind.contains("mine")?"mine-detonate":"explosion",Group.WEAPON,player?93:74,event.position(),.93f,1);
                    if (event.value()>=35 || kind.contains("power")) duck=config.musicDuckSeconds();
                }
                case DAMAGE -> { if (event.value()>=1) shot("metal-hit",Group.WEAPON,event.subjectId()==0?83:35,
                        event.position(),clamp(event.value()/25f,.1f,.7f),1); }
                case DESTROYED -> {
                    shot("destroyed",Group.WEAPON,94,event.position(),1,1);
                    duck=config.musicDuckSeconds();
                }
                case PULSE -> shot("pulse",Group.WEAPON,player?98:80,event.position(),1,1);
                case EMPTY -> { if (player || event.subjectId()==0) shot("empty",Group.UI,98,null,.62f,1); }
                case MINE_PLACED -> shot("mine-place",Group.WEAPON,player?94:52,event.position(),.8f,1);
                case FIRE_STARTED -> loop("fire-"+event.eventId(),"napalm-fire",Group.WEAPON,64,
                        event.position(),Vector3f.ZERO,.55f,1);
                case FIRE_ENDED -> stopLoop("fire-"+event.eventId());
                case FREEZE -> shot("freeze",Group.THREAT,event.subjectId()==0?100:80,event.position(),.8f,.85f);
                case STUN -> shot("stun",Group.THREAT,event.subjectId()==0?100:80,event.position(),.9f,1);
                case SHIELD -> shot("shield",Group.THREAT,player?100:80,event.position(),.8f,1);
                case CONTROL_ENDED -> { /* Expiry is conveyed by HUD/VFX; no repeated alert. */ }
                case PICKUP -> {
                    String id=kind.contains("repair")?"pickup-repair":kind.contains("turbo")?"pickup-turbo":"pickup-ammo";
                    shot(id,Group.UI,event.subjectId()==0?95:40,event.subjectId()==0?null:event.position(),.85f,1);
                }
                case MATCH_FINISHED -> {
                    String id=kind.contains("victory")?"victory":kind.contains("defeat")?"defeat":"draw";
                    shot(id,Group.UI,110,null,.95f,1);
                }
            }
        }
        applyVolumes();
    }

    public void ui(boolean confirm) {
        if (!closed && renderer!=null) { prune(); shot(confirm?"ui-confirm":"ui-nav",Group.UI,110,null,.55f,1); applyVolumes(); }
    }
    public void pause() {
        if (closed || paused) return;
        paused=true;
        if (renderer==null) return;
        if (music!=null && music.getStatus()==AudioSource.Status.Playing) renderer.pauseSource(music);
        for (Voice voice:voices) if (voice.group!=Group.UI && voice.node.getStatus()==AudioSource.Status.Playing)
            renderer.pauseSource(voice.node);
    }
    public void resume() {
        if (closed || !paused) return;
        paused=false;
        if (renderer==null) return;
        if (music!=null && music.getStatus()==AudioSource.Status.Paused) renderer.playSource(music);
        for (Voice voice:voices) if (voice.node.getStatus()==AudioSource.Status.Paused) renderer.playSource(voice.node);
    }
    public void setVolumes(float master, float music, float sfx) {
        this.master=clamp(master,0,1); musicVolume=clamp(music,0,1); sfxVolume=clamp(sfx,0,1);
        if (renderer!=null && !closed) applyVolumes();
    }
    public int voiceCount() {
        return (int)voices.stream().filter(v->v.node.getStatus()!=AudioSource.Status.Stopped).count()
                +(music!=null && music.getStatus()!=AudioSource.Status.Stopped?1:0);
    }
    public float musicPlaybackSeconds() { return music==null?0:music.getPlaybackTime(); }
    public boolean isPaused() { return paused; }

    private void loop(String key, String asset, Group group, int priority, Vector3f position, Vector3f velocity,
            float gain, float pitch) {
        if (gain<.012f) { stopLoop(key); return; }
        Voice voice=loops.get(key);
        if (voice==null) {
            voice=allocate(asset,group,priority,key,position,gain,pitch);
            if (voice==null) return;
            loops.put(key,voice);
        }
        voice.gain=gain; voice.node.setPitch(pitch);
        voice.node.setLocalTranslation(position); voice.node.setVelocity(velocity);
    }
    private void shot(String asset, Group group, int priority, Vector3f position, float gain, float pitch) {
        allocate(asset,group,priority,null,position,gain,pitch);
    }
    private Voice allocate(String asset, Group group, int priority, String loopKey, Vector3f position, float gain, float pitch) {
        AudioData data=buffers.get(asset);
        if (data==null) throw new IllegalArgumentException("Required effect missing: "+asset);
        if (position!=null && listener!=null && listener.getLocation().distance(position)>config.maximumDistance()) return null;
        int budget=config.sourceLimit()-1; // Music owns its reserved source, including while paused.
        if (voices.size()>=budget) {
            Voice weakest=voices.stream().min(Comparator.comparingInt(v->v.priority)).orElseThrow();
            if (weakest.priority>=priority) return null;
            remove(weakest);
        }
        AudioNode node=new AudioNode(data,new AudioKey("audio/"+asset+".wav",false));
        node.setName("sound-"+asset); node.setLooping(loopKey!=null); node.setPositional(position!=null);
        node.setRefDistance(config.referenceDistance()); node.setMaxDistance(config.maximumDistance());
        node.setPitch(pitch);
        if (position!=null) node.setLocalTranslation(position);
        Voice voice=new Voice(node,group,priority,loopKey,gain);
        node.setVolume(0);
        // Initialize a detached subtree before attaching to the dirty application graph.
        // Its initial world position is already valid when OpenAL starts the source; jME owns
        // subsequent geometric passes. Updating an attached leaf here violates jME assertions.
        node.updateGeometricState(); audioRoot.attachChild(node);
        voices.add(voice); renderer.playSource(node);
        return voice;
    }
    private void applyVolumes() {
        float musicGain=0;
        if (music!=null) {
            float attenuation=1-(1-config.musicDuckGain())*clamp(duck/config.musicDuckSeconds(),0,1);
            musicGain=master*config.masterHeadroom()*musicVolume*config.musicGain()*attenuation;
        }
        float total=musicGain;
        for(Voice voice:voices) total+=gain(voice);
        // Conservative signal bound: even coincident full-scale samples remain below 0 dBFS.
        // Spatial distance attenuation only lowers the real mix further. No DSP/device replacement.
        float headroom=total>1?1/total:1;
        if(music!=null) music.setVolume(musicGain*headroom);
        for(Voice voice:voices) voice.node.setVolume(gain(voice)*headroom);
    }
    private float gain(Voice voice) {
        float group=switch(voice.group) {
            case ENGINE -> config.engineGain(); case WEAPON -> config.weaponsGain();
            case THREAT -> config.threatsGain(); case UI -> config.interfaceGain();
        };
        return master*config.masterHeadroom()*sfxVolume*group*voice.gain;
    }
    private void prune() {
        for (Voice voice:List.copyOf(voices)) if (voice.node.getStatus()==AudioSource.Status.Stopped) remove(voice);
    }
    private void stopLoop(String key) { Voice voice=loops.get(key); if (voice!=null) remove(voice); }
    private void remove(Voice voice) {
        renderer.stopSource(voice.node); voice.node.removeFromParent(); voices.remove(voice);
        if (voice.loopKey!=null) loops.remove(voice.loopKey);
    }
    @Override public void close() {
        if (closed) return;
        stopMatch();
        if (music!=null) {
            AudioStream stream=(AudioStream)music.getAudioData();
            renderer.deleteAudioData(stream); stream.close(); music.removeFromParent(); music=null;
        }
        audioRoot.removeFromParent(); buffers.clear(); closed=true;
    }
    private static float clamp(float value,float low,float high) {
        if (!Float.isFinite(value)) return low;
        return Math.max(low,Math.min(high,value));
    }
}
