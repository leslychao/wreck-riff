package game.wreckriff.audio;

import com.google.gson.stream.JsonWriter;
import com.jme3.math.Vector3f;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.DoubleSupplier;

/** Optional development recording of allocated voices, reconstructed offline without an audio device. */
public final class AudioCapture implements AutoCloseable {
    private static final int RATE=48_000, MAX_TRACKS=4096, MAX_SNAPSHOTS=153_600;
    private static final long MAX_CACHED_PCM_BYTES=64L*1024*1024;
    private record State(double seconds,float volume,float pitch,Vector3f source,Vector3f listener,Vector3f right) {}
    private static final class Track {
        final int id;
        final String asset;
        final boolean loop,positional;
        final double offset;
        final float referenceDistance,maximumDistance;
        final List<State> states=new ArrayList<>();
        double end;
        Track(int id,String asset,boolean loop,boolean positional,double offset,float reference,float maximum,double end) {
            this.id=id;this.asset=asset;this.loop=loop;this.positional=positional;this.offset=offset;
            referenceDistance=reference;maximumDistance=maximum;this.end=end;
        }
    }
    private record Sample(int channels,long totalFrames,long firstFrame,float[] pcm) {
        float at(double phase,int channel,boolean loop) {
            long frame=(long)phase;
            if (!loop && frame>=totalFrames) return 0;
            long relative=Math.floorMod(frame-firstFrame,totalFrames);
            int loaded=pcm.length/channels;
            if(relative>=loaded)throw new IllegalStateException("Capture sample window exhausted");
            float a=pcm[(int)relative*channels+channel];
            long next=relative+1;
            if(next>=loaded)next=loaded==totalFrames?0:relative;
            float b=!loop && frame+1>=totalFrames?0:pcm[(int)next*channels+channel];
            return a+(b-a)*(float)(phase-frame);
        }
    }
    private final Path output;
    private final DoubleSupplier clock;
    private final double startedAt,duration;
    private final Map<Object,Track> active=new IdentityHashMap<>();
    private final List<Track> tracks=new ArrayList<>();
    private int snapshots;
    private double lastTime;
    private boolean closed;

    public AudioCapture(Path outputDir,DoubleSupplier simulationSeconds,double durationSeconds) {
        if(!Double.isFinite(durationSeconds)||durationSeconds<=0||durationSeconds>40)
            throw new IllegalArgumentException("Audio capture must be 0 < duration <= 40 seconds");
        output=Objects.requireNonNull(outputDir);clock=Objects.requireNonNull(simulationSeconds);duration=durationSeconds;
        startedAt=clock.getAsDouble();
        if(!Double.isFinite(startedAt))throw new IllegalArgumentException("Invalid simulation clock");
    }

    void observe(Object identity,String asset,boolean loop,boolean positional,float volume,float pitch,
            Vector3f source,Vector3f listener,Vector3f right,float reference,float maximum,double playbackOffset) {
        if(closed)return;
        double now=time();
        if(now>=duration)return;
        if(!asset.matches("audio/[a-z0-9-]+\\.wav")||!Float.isFinite(volume)||volume<0||volume>1
                ||!Float.isFinite(pitch)||pitch<.5f||pitch>2||!Double.isFinite(playbackOffset)||playbackOffset<0)
            throw new IllegalArgumentException("Invalid captured voice");
        Track track=active.get(identity);
        if(track==null) {
            if(active.size()>=32||tracks.size()>=MAX_TRACKS)throw new IllegalStateException("Audio capture voice limit exceeded");
            track=new Track(tracks.size(),asset,loop,positional,playbackOffset,reference,maximum,duration);
            active.put(identity,track);tracks.add(track);
        }
        State state=new State(now,volume,pitch,source.clone(),listener.clone(),right.clone());
        if(!track.states.isEmpty()) {
            State prior=track.states.getLast();
            // Several owners can apply gains in one frame. Only its final effective state is audible.
            if(now==prior.seconds) {track.states.set(track.states.size()-1,state);return;}
            if(prior.volume==volume&&prior.pitch==pitch&&prior.source.equals(source)
                    &&prior.listener.equals(listener)&&prior.right.equals(right))return;
        }
        if(++snapshots>MAX_SNAPSHOTS)throw new IllegalStateException("Audio capture snapshot limit exceeded");
        track.states.add(state);
    }

    void stop(Object identity) {
        if(closed)return;
        Track track=active.remove(identity);
        if(track!=null)track.end=time();
    }
    void stopAll() {
        if(closed)return;
        double now=time();
        for(Track track:active.values())track.end=now;
        active.clear();
    }
    private double time() {
        double value=clock.getAsDouble()-startedAt;
        if(!Double.isFinite(value)||value+1e-6<lastTime)throw new IllegalStateException("Capture simulation clock moved backwards");
        lastTime=Math.min(duration,Math.max(lastTime,value));return lastTime;
    }

    @Override public void close() throws IOException {
        if(closed)return;
        stopAll();closed=true;
        Files.createDirectories(output);
        writeJournal();
        float[] left=new float[(int)Math.ceil(duration*RATE)],right=new float[left.length];
        Map<String,Sample> shortSamples=new HashMap<>();
        long cached=0;
        for(Track track:tracks) {
            if(track.states.isEmpty()||track.end<=track.states.getFirst().seconds)continue;
            Sample sample=shortSamples.get(track.asset);
            if(sample==null) {
                sample=load(track);
                if(sample.pcm.length/sample.channels==sample.totalFrames) {
                    cached+=(long)sample.pcm.length*Float.BYTES;
                    if(cached>MAX_CACHED_PCM_BYTES)throw new IOException("Audio capture PCM cache exceeds 64 MiB");
                    shortSamples.put(track.asset,sample);
                }
            }
            mix(track,sample,left,right);
        }
        writeWave(left,right);
        Files.writeString(output.resolve("AUDIO_README.txt"),"Real game viewport; audio.wav reconstructs the actually allocated game voices from the same local samples.\n"
                +"Music and engines are included. Positions, effective gain, pitch and initial playback offsets are in audio-events.json.\n"
                +"The mix uses simulation time, inverse-distance attenuation and equal-power stereo pan. It is not an exact native OpenAL/HRTF or microphone capture; Doppler, device timing and resampler differences are not reproduced.\n"
                +"Limits: 40 seconds, 32 simultaneous voices, 4096 voice starts, 153600 state snapshots, 64 MiB cached short PCM. Capture fails explicitly on overflow.\n",StandardCharsets.UTF_8);
    }

    private Sample load(Track track) throws IOException {
        try(InputStream raw=open(track.asset);BufferedInputStream in=new BufferedInputStream(raw)) {
            PcmWave.Header header=PcmWave.header(in);
            long total=header.dataBytes()/header.frameBytes();
            long first=total<=3L*RATE?0:Math.floorMod((long)(track.offset*RATE),total);
            double maxPitch=track.states.stream().mapToDouble(State::pitch).max().orElse(1);
            long frames=total<=3L*RATE?total:Math.min(total,(long)Math.ceil((duration*maxPitch+.1)*RATE));
            float[] pcm=new float[Math.toIntExact(frames*header.channels())];
            in.skipNBytes(first*header.frameBytes());
            int beforeWrap=(int)Math.min(frames,total-first);
            readPcm(in,pcm,0,beforeWrap*header.channels());
            if(beforeWrap<frames)try(InputStream again=open(track.asset)) {
                PcmWave.header(again);readPcm(again,pcm,beforeWrap*header.channels(),pcm.length-beforeWrap*header.channels());
            }
            return new Sample(header.channels(),total,first,pcm);
        }
    }
    private static InputStream open(String asset) throws IOException {
        InputStream in=AudioCapture.class.getClassLoader().getResourceAsStream(asset);
        if(in==null)throw new FileNotFoundException(asset);return in;
    }
    private static void readPcm(InputStream in,float[] target,int offset,int count) throws IOException {
        byte[] bytes=in.readNBytes(count*2);
        if(bytes.length!=count*2)throw new EOFException("Capture PCM source truncated");
        for(int i=0;i<count;i++)target[offset+i]=(short)((bytes[i*2]&255)|(bytes[i*2+1]<<8))/32768f;
    }
    private static void mix(Track track,Sample sample,float[] left,float[] right) {
        double phase=track.offset*RATE;
        for(int s=0;s<track.states.size();s++) {
            State state=track.states.get(s);
            double end=s+1<track.states.size()?Math.min(track.end,track.states.get(s+1).seconds):track.end;
            int first=Math.max(0,(int)Math.round(state.seconds*RATE)),last=Math.min(left.length,(int)Math.round(end*RATE));
            float gain=state.volume,pan=0;
            if(track.positional) {
                Vector3f delta=state.source.subtract(state.listener);float distance=delta.length();
                gain*=distance>track.maximumDistance?0:track.referenceDistance/Math.max(track.referenceDistance,distance);
                if(distance>1e-5)pan=Math.max(-1,Math.min(1,delta.dot(state.right)/distance));
            }
            float l=sample.channels==2&&!track.positional?gain:gain*(float)Math.sqrt((1-pan)*.5);
            float r=sample.channels==2&&!track.positional?gain:gain*(float)Math.sqrt((1+pan)*.5);
            for(int frame=first;frame<last;frame++,phase+=state.pitch) {
                left[frame]+=sample.at(phase,0,track.loop)*l;
                right[frame]+=sample.at(phase,sample.channels-1,track.loop)*r;
            }
        }
    }
    private void writeWave(float[] left,float[] right) throws IOException {
        try(DataOutputStream out=new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(output.resolve("audio.wav"))))) {
            out.writeBytes("RIFF");little(out,36+left.length*4);out.writeBytes("WAVEfmt ");little(out,16);
            out.writeShort(Short.reverseBytes((short)1));out.writeShort(Short.reverseBytes((short)2));
            little(out,RATE);little(out,RATE*4);out.writeShort(Short.reverseBytes((short)4));out.writeShort(Short.reverseBytes((short)16));
            out.writeBytes("data");little(out,left.length*4);
            for(int i=0;i<left.length;i++) {writeSample(out,left[i]);writeSample(out,right[i]);}
        }
    }
    private static void writeSample(DataOutputStream out,float value)throws IOException {
        if(!Float.isFinite(value)||Math.abs(value)>1.0001)throw new IOException("Captured mix exceeds full scale");
        out.writeShort(Short.reverseBytes((short)Math.round(Math.max(-1,Math.min(1,value))*32767)));
    }
    private static void little(DataOutputStream out,int value)throws IOException {out.writeInt(Integer.reverseBytes(value));}
    private void writeJournal() throws IOException {
        try(JsonWriter out=new JsonWriter(Files.newBufferedWriter(output.resolve("audio-events.json"),StandardCharsets.UTF_8))) {
            out.setIndent("  ");out.beginObject().name("schemaVersion").value(1).name("mix").value("reconstructed; not native OpenAL")
                    .name("durationSeconds").value(duration).name("observedSeconds").value(lastTime).name("voices").beginArray();
            for(Track track:tracks) {
                out.beginObject().name("id").value(track.id).name("asset").value(track.asset).name("loop").value(track.loop)
                        .name("positional").value(track.positional).name("playbackOffsetSeconds").value(track.offset)
                        .name("endSeconds").value(track.end).name("referenceDistance").value(track.referenceDistance)
                        .name("maximumDistance").value(track.maximumDistance).name("states").beginArray();
                for(State state:track.states) {
                    out.beginObject().name("seconds").value(state.seconds).name("volume").value(state.volume).name("pitch").value(state.pitch);
                    vector(out,"source",state.source);vector(out,"listener",state.listener);vector(out,"listenerRight",state.right);out.endObject();
                }
                out.endArray().endObject();
            }
            out.endArray().endObject();
        }
    }
    private static void vector(JsonWriter out,String name,Vector3f value)throws IOException {
        out.name(name).beginArray().value(value.x).value(value.y).value(value.z).endArray();
    }
}
