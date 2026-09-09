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
    private static final Set<String> OWN_CONTACT_CUE=Set.of("machine-gun","cannon","cannon-ricochet","ballistic","ram");
    private record EventKey(GameEvent.Type type,long id,int subject) {}
    private record DelayedImpact(GameEvent event,double due) {}
    private static final class Voice {
        final AudioNode node;
        final Group group;
        final int priority;
        final String loopKey;
        final String sample;
        float gain;
        Voice(AudioNode node, Group group, int priority, String loopKey, float gain,String sample) {
            this.node=node; this.group=group; this.priority=priority; this.loopKey=loopKey; this.gain=gain;
            this.sample=sample;
        }
    }
    private final AudioRenderer renderer;
    private final AssetManager assets;
    private final Listener listener;
    private final AudioConfig config;
    private final Node audioRoot = new Node("match-audio");
    private final Map<String,AudioData> buffers = new LinkedHashMap<>();
    private final Map<String,List<String>> cueBanks;
    private final Map<String,Integer> nextTake = new HashMap<>();
    private final Set<EventKey> acceptedEvents = new LinkedHashSet<>();
    private final Deque<DelayedImpact> delayedImpacts=new ArrayDeque<>();
    private final List<Voice> voices = new ArrayList<>();
    private final Map<String,Voice> loops = new HashMap<>();
    private static final class Motion { float priorSpeed,priorTurbo=100,enginePitch=1; }
    private final Map<Integer,Motion> motion=new HashMap<>();
    private AudioNode music,bossTrack;
    private String musicAsset,bossAsset;
    private float bossBlend,bossTarget;
    private static final float MUSIC_CROSSFADE_SECONDS=2;
    private AudioCapture capture;
    private double impactClock;
    private boolean closed, paused, matchActive, warningWasActive;
    private float master = 1, musicVolume = 1, sfxVolume = 1, duck, lowHpClock;
    private UUID sessionId;

    public AudioDirector(AssetManager assets, AudioRenderer renderer, Listener listener, Node parent) {
        this(assets, renderer, listener, parent, AudioConfig.load());
    }
    AudioDirector(AssetManager assets, AudioRenderer renderer, Listener listener, Node parent, AudioConfig config) {
        this.assets=assets;this.renderer=renderer; this.listener=listener; this.config=config;
        musicAsset=config.musicAsset();bossAsset=config.musicAsset();
        cueBanks=config.cueBanks();
        parent.attachChild(audioRoot);
        if (renderer == null) return; // Application emits the explicit no-device/no-audio diagnostic.
        try {
            music=loadMusic(config.musicAsset(),"music-metalmania");audioRoot.attachChild(music);
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
        startMatch(config.musicAsset(),config.musicAsset());
    }

    /** Called during loading. Both campaign streams are opened before combat; no asset is loaded on a boss transition. */
    public void startMatch(String normalAsset,String intenseAsset) {
        if (closed) throw new IllegalStateException("AudioDirector is closed");
        validateMusicPath(normalAsset);validateMusicPath(intenseAsset);
        stopMatch();
        prepareMusic(normalAsset,intenseAsset);
        matchActive=true; paused=false;
        if (music != null) {music.setTimeOffset(0);renderer.playSource(music);}
        applyVolumes();
    }

    /** Idempotent phase input. Reversing a fade keeps the same pair and musical timeline. */
    public void bossMusic(boolean active) {
        if(closed||!matchActive||bossTrack==null)return;
        bossTarget=active?1:0;
        if(!paused)ensureMusicSources();
    }

    private AudioNode loadMusic(String path,String name)throws IOException {
        AudioKey key=new AudioKey(path,true,false);
        AudioNode node=new AudioNode(PcmWave.stream(assets.locateAsset(key)),key);
        node.setName(name);node.setPositional(false);node.setLooping(true);node.setVolume(0);return node;
    }

    private void prepareMusic(String normalAsset,String intenseAsset) {
        if(musicAsset.equals(normalAsset)&&bossAsset.equals(intenseAsset))return;
        if(renderer==null) {musicAsset=normalAsset;bossAsset=intenseAsset;return;}
        AudioNode normal=null,intense=null;
        try {
            normal=loadMusic(normalAsset,normalAsset.equals(config.musicAsset())?"music-metalmania":"music-normal");
            if(!normalAsset.equals(intenseAsset)) {
                intense=loadMusic(intenseAsset,"music-boss");
                if(Math.abs(normal.getAudioData().getDuration()-intense.getAudioData().getDuration())>1f/48000)
                    throw new IOException("Campaign music mixes must have exactly the same duration");
            }
        } catch(IOException|RuntimeException e) {
            disposeMusic(normal);disposeMusic(intense);throw new IllegalStateException("Cannot load arena music "+normalAsset+" / "+intenseAsset,e);
        }
        disposeMusic(music);disposeMusic(bossTrack);music=normal;bossTrack=intense;musicAsset=normalAsset;bossAsset=intenseAsset;
        audioRoot.attachChild(music);if(bossTrack!=null)audioRoot.attachChild(bossTrack);
    }

    private static void validateMusicPath(String path) {
        if(path==null||!path.startsWith("audio/")||!path.endsWith(".wav")||path.contains("..")||path.contains("\\"))
            throw new IllegalArgumentException("Arena music requires a local stereo PCM WAV: "+path);
    }

    private void ensureMusicSources() {
        if(renderer==null||!matchActive||paused)return;
        AudioNode incoming=bossTarget>0?bossTrack:music,playing=bossTarget>0?music:bossTrack;
        if(incoming==null||incoming.getStatus()!=AudioSource.Status.Stopped)return;
        prune();
        while(voices.size()+musicSourceCount()>=config.sourceLimit())
            remove(voices.stream().min(Comparator.comparingInt(voice->voice.priority)).orElseThrow());
        float seconds=playing==null?0:playing.getPlaybackTime();
        incoming.setTimeOffset(seconds%incoming.getAudioData().getDuration());
        incoming.setVolume(0);renderer.playSource(incoming);
    }

    private void advanceMusic(float dt) {
        if(bossTrack==null)return;
        ensureMusicSources();
        float step=dt/MUSIC_CROSSFADE_SECONDS;
        bossBlend=bossTarget>bossBlend?Math.min(bossTarget,bossBlend+step):Math.max(bossTarget,bossBlend-step);
        if(bossBlend==bossTarget) {
            AudioNode outgoing=bossTarget>0?music:bossTrack;
            if(outgoing.getStatus()!=AudioSource.Status.Stopped) {
                if(capture!=null)capture.stop(outgoing);renderer.stopSource(outgoing);outgoing.setVolume(0);
            }
        }
    }

    public void stopMatch() {
        if (renderer != null) {
            for (Voice voice : List.copyOf(voices)) remove(voice);
            if (music != null) { if(capture!=null)capture.stop(music);renderer.stopSource(music); }
            if (bossTrack != null) { if(capture!=null)capture.stop(bossTrack);renderer.stopSource(bossTrack); }
            if (paused) renderer.resumeAll();
        }
        sessionId=null; matchActive=false; paused=false; warningWasActive=false; lowHpClock=0; duck=0;bossBlend=0;bossTarget=0;
        nextTake.clear(); acceptedEvents.clear(); delayedImpacts.clear();impactClock=0;
        motion.clear();
    }

    public void update(MatchSession session, WorldQuery world, float dt) {
        if (closed || renderer == null) return;
        prune();
        if (!matchActive || paused || session == null || world == null) return;
        if (sessionId == null) sessionId=session.sessionId;
        if (!sessionId.equals(session.sessionId)) {
            startMatch(musicAsset,bossAsset); sessionId=session.sessionId;
        }
        dt=Math.max(0, Math.min(dt, .1f));
        advanceMusic(dt);
        impactClock+=dt;
        for(Iterator<DelayedImpact> pending=delayedImpacts.iterator();pending.hasNext();) {
            DelayedImpact impact=pending.next();int subject=impact.event.subjectId();
            if(subject>=0&&!session.vehicles.get(subject).alive()) {pending.remove();continue;}
            if(impact.due<=impactClock) {playImpact(impact.event);pending.remove();}
        }
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
            Motion previous=motion.computeIfAbsent(id,key->new Motion());
            float acceleration=dt > 0 ? Math.max(0, (speed-previous.priorSpeed)/dt) : 0;
            float blend=clamp(speed/23f, 0, 1);
            float targetPitch=clamp(.82f+speed*.019f+Math.min(acceleration,12)*.012f,
                    config.enginePitchMin(), config.enginePitchMax());
            previous.enginePitch+=(targetPitch-previous.enginePitch)*(1-(float)Math.exp(-dt/config.engineSmoothingSeconds()));
            float own=vehicle.player ? .84f : .49f;
            loop("idle-"+id,"engine-idle",Group.ENGINE,vehicle.player?72:22,position,velocity,
                    own*(1-blend*.85f),previous.enginePitch);
            loop("drive-"+id,"engine-drive",Group.ENGINE,vehicle.player?72:22,position,velocity,
                    own*blend,clamp(previous.enginePitch*.87f,.5f,2));
            Vector3f right=world.rotation(id).mult(Vector3f.UNIT_X);
            float lateral=Math.abs(velocity.dot(right));
            // Grounded lateral movement, not a key state, determines tyre noise.
            float slipping=world.grounded(id)?clamp((lateral-1.8f)/8f,0,1):0;
            loop("slip-"+id,"tyre-slip",Group.ENGINE,vehicle.player?55:12,position,velocity,
                    slipping*own*.8f,clamp(.87f+lateral*.015f,.5f,2));
            boolean boosting=vehicle.turbo < previous.priorTurbo-.01f;
            loop("turbo-"+id,"turbo-loop",Group.ENGINE,vehicle.player?68:25,position,velocity,
                    boosting?own*.9f:0,1);
            previous.priorSpeed=speed; previous.priorTurbo=vehicle.turbo;
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
            // The composition root stops match loops before delivering the final death and result.
            if(!matchActive && event.type()!=GameEvent.Type.MATCH_FINISHED && event.type()!=GameEvent.Type.DESTROYED)continue;
            if(!acceptedEvents.add(new EventKey(event.type(),event.eventId(),event.subjectId())))continue;
            if(acceptedEvents.size()>2048)acceptedEvents.remove(acceptedEvents.iterator().next());
            boolean player=event.sourceId()==0;
            String kind=event.kind()==null?"":event.kind().toLowerCase(Locale.ROOT);
            switch (event.type()) {
                case SHOT -> {
                    String id=switch(kind) {
                        case "cannon" -> "cannon-launch";case "ballistic" -> "ballistic-launch";
                        case "ballistic-fall" -> "ballistic-fall";case "power" -> "power-launch";
                        case "homing" -> "homing-launch";case "napalm" -> "napalm-launch";
                        case "freeze" -> "freeze-launch";default -> "machine-gun";
                    };
                    float gain=id.equals("machine-gun")?.32f:id.equals("ballistic-fall")?.7f:1;
                    shot(id,Group.WEAPON,player?96:58,event.origin(),gain,1);
                }
                case IMPACT -> { if(kind.equals("machine-gun"))contact(event); }
                case EXPLOSION -> {
                    String cue=switch(kind) {
                        case "cannon-ricochet" -> "cannon-ricochet";case "cannon" -> "cannon-hit";
                        case "ballistic" -> "ballistic-explosion";case "mine" -> "mine-detonate";
                        case "power" -> "power-explosion";case "napalm" -> "napalm-explosion";default -> "explosion";
                    };
                    shot(cue,Group.WEAPON,player?93:74,event.position(),kind.equals("cannon-ricochet")?.8f:.93f,1);
                    if (event.value()>=35 || Set.of("power","mine","cannon","ballistic").contains(kind)) duck=config.musicDuckSeconds();
                }
                case RAM -> {
                    boolean involvesPlayer=event.subjectId()==0||player;
                    Voice contact=allocate("ram-hit",Group.WEAPON,involvesPlayer?98:66,null,event.position(),
                            clamp(event.value()/16f,.14f,1),1);
                    if(contact!=null&&involvesPlayer)contact.node.setRefDistance(config.referenceDistance()*2);
                    if(involvesPlayer&&event.value()>=4)duck=config.musicDuckSeconds();
                }
                case DAMAGE -> { if (event.value()>=1 && !OWN_CONTACT_CUE.contains(kind)) shot("metal-hit",Group.WEAPON,event.subjectId()==0?83:35,
                        event.position(),clamp(event.value()/25f,.1f,.7f),1); }
                case DESTROYED -> {
                    delayedImpacts.removeIf(impact->impact.event.subjectId()==event.subjectId());
                    shot("destroyed",Group.WEAPON,94,event.position(),1,1);
                    duck=config.musicDuckSeconds();
                }
                case EMPTY -> { if (player || event.subjectId()==0) shot("empty",Group.UI,98,null,.62f,1); }
                case MINE_PLACED -> shot("mine-place",Group.WEAPON,player?94:52,event.position(),.8f,1);
                case FIRE_STARTED -> loop("fire-"+event.eventId(),"napalm-fire",Group.WEAPON,64,
                        event.position(),Vector3f.ZERO,.55f,1);
                case FIRE_ENDED -> stopLoop("fire-"+event.eventId());
                case FREEZE -> shot("freeze-hit",Group.THREAT,event.subjectId()==0?100:80,event.position(),.8f,1);
                case SHIELD -> shot("shield-on",Group.THREAT,player?100:80,event.position(),.8f,1);
                case SHIELD_HIT -> contact(event);
                case SHIELD_ENDED -> shot("shield-end",Group.THREAT,event.subjectId()==0?95:70,event.position(),.7f,1);
                case CONTROL_ENDED -> { if(kind.equals("freeze"))shot("freeze-end",Group.THREAT,
                        event.subjectId()==0?95:70,event.position(),.7f,1); }
                case PICKUP -> {
                    String id=switch(kind) {
                        case "homing-ammo" -> "pickup-homing";
                        case "power-ammo" -> "pickup-power";
                        case "mine-ammo" -> "pickup-mine";
                        case "napalm-ammo" -> "pickup-napalm";
                        case "ballistic-ammo" -> "pickup-ballistic";
                        case "cannon-ammo" -> "pickup-cannon";
                        case "repair" -> "pickup-repair";
                        case "turbo" -> "pickup-turbo";
                        default -> null;
                    };
                    // Only a confirmed positive grant can celebrate a pickup. Threats, including
                    // active hazard loops (82), retain their sources even during a pickup rush.
                    boolean own=event.subjectId()==0;
                    if(id!=null && event.value()>0)
                        shot(id,Group.UI,own?78:32,own?null:event.position(),own?.85f:.32f,1);
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
        if (!closed && !paused && renderer!=null) { prune(); shot(confirm?"ui-confirm":"ui-nav",Group.UI,110,null,.55f,1); applyVolumes(); }
    }
    /** Results keep their finishing sounds only; no vehicle or arena loops are recreated. */
    public void updateTail(float dt) {
        if(closed||renderer==null)return;
        prune();
        if(paused)return;
        duck=Math.max(0,duck-clamp(dt,0,.1f));
        applyVolumes();
    }
    public void pause() {
        if (closed || paused) return;
        // jME 3.8.1 pauseSource can mark a just-finished OpenAL one-shot Paused
        // while the device still reports Stopped. Freeze the device atomically;
        // source states and queued stream buffers stay intact for exact resumption.
        if (renderer!=null) renderer.pauseAll();
        paused=true;
    }
    public void resume() {
        if (closed || !paused) return;
        if (renderer!=null) renderer.resumeAll();
        paused=false;
        ensureMusicSources();
    }
    public void setVolumes(float master, float music, float sfx) {
        this.master=clamp(master,0,1); musicVolume=clamp(music,0,1); sfxVolume=clamp(sfx,0,1);
        if (renderer!=null && !closed) applyVolumes();
    }
    public int voiceCount() {
        return (int)voices.stream().filter(v->v.node.getStatus()!=AudioSource.Status.Stopped).count()
                +musicSourceCount();
    }
    public int musicSourceCount() { return playing(music)+playing(bossTrack); }
    private static int playing(AudioNode node) {return node!=null&&node.getStatus()!=AudioSource.Status.Stopped?1:0;}
    public float musicPlaybackSeconds() { return playing(bossTrack)>0&&bossBlend>=.5f?bossTrack.getPlaybackTime():music==null?0:music.getPlaybackTime(); }
    public boolean isPaused() { return paused; }
    /** Caller owns capture.close(); attaching or detaching never creates an audio renderer. */
    public void setCapture(AudioCapture next) {
        if(capture!=null)capture.stopAll();
        capture=next;
        if(capture!=null)captureVoices();
    }
    int pendingImpactCount() {return delayedImpacts.size();}

    private void contact(GameEvent event) {
        float delay=event.cosmeticImpactDelaySeconds();
        if(delay<=0) {playImpact(event);return;}
        // At most 128 cosmetic contacts; no native source is reserved while a tracer travels.
        if(delayedImpacts.size()>=128)delayedImpacts.removeFirst();
        delayedImpacts.addLast(new DelayedImpact(event,impactClock+delay));
    }
    private void playImpact(GameEvent event) {
        if(event.type()==GameEvent.Type.SHIELD_HIT)
            shot("shield-hit",Group.THREAT,event.subjectId()==0?100:80,event.position(),.8f,1);
        else shot("metal-hit",Group.WEAPON,event.subjectId()==0?83:48,event.position(),event.subjectId()<0?.32f:.65f,1);
    }

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
        List<String> takes=cueBanks.get(asset);
        if (takes==null) throw new IllegalArgumentException("Required cue missing: "+asset);
        if (position!=null && listener!=null && listener.getLocation().distance(position)>config.maximumDistance()) return null;
        int budget=config.sourceLimit()-Math.max(1,musicSourceCount());
        if (voices.size()>=budget) {
            Voice weakest=voices.stream().min(Comparator.comparingInt(v->v.priority)).orElseThrow();
            if (weakest.priority>=priority) return null;
            remove(weakest);
        }
        int take=nextTake.getOrDefault(asset,0);
        String sample=takes.get(take);
        nextTake.put(asset,(take+1)%takes.size());
        AudioNode node=new AudioNode(buffers.get(sample),new AudioKey("audio/"+sample+".wav",false));
        node.setName("sound-"+asset); node.setLooping(loopKey!=null); node.setPositional(position!=null);
        node.setRefDistance(config.referenceDistance()); node.setMaxDistance(config.maximumDistance());
        node.setPitch(pitch);
        if (position!=null) node.setLocalTranslation(position);
        Voice voice=new Voice(node,group,priority,loopKey,gain,"audio/"+sample+".wav");
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
        float normalWeight=bossTrack==null?1:(float)Math.cos(bossBlend*Math.PI/2);
        float bossWeight=bossTrack==null?0:(float)Math.sin(bossBlend*Math.PI/2);
        float total=musicGain*(normalWeight+bossWeight);
        for(Voice voice:voices) total+=gain(voice);
        // Conservative signal bound: even coincident full-scale samples remain below 0 dBFS.
        // Spatial distance attenuation only lowers the real mix further. No DSP/device replacement.
        float headroom=total>1?1/total:1;
        if(music!=null) music.setVolume(musicGain*normalWeight*headroom);
        if(bossTrack!=null)bossTrack.setVolume(musicGain*bossWeight*headroom);
        for(Voice voice:voices) voice.node.setVolume(gain(voice)*headroom);
        if(capture!=null)captureVoices();
    }
    private void captureVoices() {
        if(listener==null||paused)return;
        Vector3f position=listener.getLocation(),right=listener.getRotation().mult(Vector3f.UNIT_X);
        if(music!=null&&music.getStatus()==AudioSource.Status.Playing)
            capture.observe(music,musicAsset,true,false,music.getVolume(),music.getPitch(),Vector3f.ZERO,
                    position,right,config.referenceDistance(),config.maximumDistance(),music.getPlaybackTime());
        if(bossTrack!=null&&bossTrack.getStatus()==AudioSource.Status.Playing)
            capture.observe(bossTrack,bossAsset,true,false,bossTrack.getVolume(),bossTrack.getPitch(),Vector3f.ZERO,
                    position,right,config.referenceDistance(),config.maximumDistance(),bossTrack.getPlaybackTime());
        for(Voice voice:voices)if(voice.node.getStatus()==AudioSource.Status.Playing) {
            AudioNode node=voice.node;
            capture.observe(node,voice.sample,node.isLooping(),node.isPositional(),node.getVolume(),node.getPitch(),
                    node.getLocalTranslation(),position,right,node.getRefDistance(),node.getMaxDistance(),node.getPlaybackTime());
        }
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
        if(capture!=null)capture.stop(voice.node);
        renderer.stopSource(voice.node); voice.node.removeFromParent(); voices.remove(voice);
        if (voice.loopKey!=null) loops.remove(voice.loopKey);
    }
    @Override public void close() {
        if (closed) return;
        stopMatch();
        disposeMusic(music);disposeMusic(bossTrack);music=null;bossTrack=null;
        audioRoot.removeFromParent(); buffers.clear(); closed=true;
    }
    private void disposeMusic(AudioNode node) {
        if(node==null)return;
        if(capture!=null)capture.stop(node);renderer.stopSource(node);
        AudioStream stream=(AudioStream)node.getAudioData();renderer.deleteAudioData(stream);stream.close();node.removeFromParent();
    }
    private static float clamp(float value,float low,float high) {
        if (!Float.isFinite(value)) return low;
        return Math.max(low,Math.min(high,value));
    }
}
