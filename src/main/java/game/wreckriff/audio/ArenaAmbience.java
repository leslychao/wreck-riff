package game.wreckriff.audio;

import com.jme3.math.Vector3f;
import game.wreckriff.arena.ArenaDefinition;
import java.util.*;

/** Fixed machinery attached to authored casings; AudioDirector owns every actual voice. */
public final class ArenaAmbience {
    public static final int MAX_LOOPS=3;
    public static final Set<String> CUES=Set.of("ambient-motor","ambient-rotor","ambient-ventilation");
    public record Emitter(String anchor,String cue,ArenaDefinition.Vec3 position,float radius,float gain,float pitch) {
        public Emitter {
            if(anchor==null||anchor.isBlank()||!CUES.contains(cue)||position==null
                    ||!Float.isFinite(radius)||radius<=8||radius>120||!Float.isFinite(gain)||gain<=0||gain>.6f
                    ||!Float.isFinite(pitch)||pitch<.75f||pitch>1.25f)throw new IllegalArgumentException("Invalid ambient emitter");
        }
    }
    private static final class Channel {
        final Emitter emitter;
        final Vector3f position;
        final String key;
        float gain,score;
        boolean selected;
        Channel(Emitter emitter){this.emitter=emitter;position=emitter.position.vector();key="ambient-"+emitter.anchor;}
    }
    private final List<Emitter> emitters;
    private final Channel[] channels;
    private float selectionClock;

    public ArenaAmbience(ArenaDefinition arena){this(placements(arena));}
    ArenaAmbience(List<Emitter> emitters) {
        this.emitters=List.copyOf(emitters);
        if(emitters.size()>48||emitters.stream().map(Emitter::anchor).distinct().count()!=emitters.size())
            throw new IllegalArgumentException("Ambient emitter count/identity");
        channels=emitters.stream().map(Channel::new).toArray(Channel[]::new);
    }
    public List<Emitter> emitters(){return emitters;}

    private static List<Emitter> placements(ArenaDefinition arena) {
        List<Emitter> result=new ArrayList<>();
        for(var box:arena.boxes()) {
            String id=box.id(),cue=null;float radius=65,gain=.44f,pitch=1;
            switch(arena.id()) {
                case "construction_17" -> {
                    if(id.startsWith("dress-construction-")&&(id.endsWith("drive-motor")||id.endsWith("mixer-motor")))cue="ambient-motor";
                    else if(id.startsWith("dress-construction-")&&id.endsWith("electric-pump")){cue="ambient-rotor";radius=48;gain=.35f;}
                }
                case "neon_zero" -> {
                    if(id.startsWith("dress-neon-tunnel-vent-case-")){cue="ambient-ventilation";radius=62;gain=.4f;}
                    else if(id.startsWith("dress-neon-tunnel-pump-bank-motor-hull-")){cue="ambient-motor";radius=48;gain=.3f;}
                    else if(id.startsWith("dress-neon-parking-")&&id.contains("charge-")&&id.contains("-terminal-")){
                        cue="ambient-ventilation";radius=32;gain=.24f;
                    }
                }
                case "euphoria_park" -> {
                    if(id.equals("dress-carnival-ferris-drive-motor-housing")){cue="ambient-rotor";radius=75;pitch=.85f;}
                    else if(id.equals("dress-carnival-orbit-drive-display-motor-housing")){cue="ambient-motor";radius=58;gain=.35f;}
                }
                default -> { /* Dead Air Yard retains its classic soundscape. */ }
            }
            if(cue!=null)result.add(new Emitter(id,cue,box.center(),radius,gain,pitch));
        }
        return result;
    }

    void update(AudioDirector audio,Vector3f listener,float dt) {
        selectionClock-=dt;
        boolean mayStart=selectionClock<=0;
        for(Channel channel:channels) {
            float distance=listener.distance(channel.position);
            float fade=distance>=channel.emitter.radius?0:1-distance/channel.emitter.radius;
            channel.score=channel.emitter.gain*fade*fade;
        }
        if(mayStart) {
            // Only a few dozen fixed casings. Three scans at 4 Hz avoid route
            // searches, sorting allocations and a changing emitter list per frame.
            selectionClock=.25f;
            for(Channel channel:channels){channel.score*=channel.selected?1.15f:1;channel.selected=false;}
            for(int slot=0;slot<MAX_LOOPS;slot++) {
                Channel best=null;
                for(Channel candidate:channels)if(!candidate.selected&&candidate.score>.001f
                        &&(best==null||candidate.score>best.score))best=candidate;
                if(best==null)break;
                best.selected=true;
            }
        }
        float smoothing=1-(float)Math.exp(-dt/.45f);
        // Retire old slots before any replacement is started, including within
        // one render callback. A brief transition can never use six sources.
        for(Channel channel:channels)if(!channel.selected||channel.score<=0) {
            channel.gain=0;
            audio.ambientLoop(channel.key,channel.emitter.cue,channel.position,0,channel.emitter.pitch,false);
        }
        for(Channel channel:channels) {
            // Hard zero outside range and immediate slot retirement keep the
            // cap true even while the incoming source fades up.
            if(!channel.selected||channel.score<=0)continue;
            channel.gain+=(channel.emitter.gain-channel.gain)*smoothing;
            float distance=listener.distance(channel.position),fade=Math.max(0,1-distance/channel.emitter.radius);
            audio.ambientLoop(channel.key,channel.emitter.cue,channel.position,
                    channel.gain*fade*fade,channel.emitter.pitch,mayStart);
        }
    }

    void stop(AudioDirector audio) {
        for(Channel channel:channels) {
            channel.gain=0;channel.selected=false;
            audio.ambientLoop(channel.key,channel.emitter.cue,channel.position,0,channel.emitter.pitch,false);
        }
        selectionClock=0;
    }
}
