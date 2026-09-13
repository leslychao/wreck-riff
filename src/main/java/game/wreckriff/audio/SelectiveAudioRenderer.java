package game.wreckriff.audio;

import com.jme3.audio.AudioSource;
import com.jme3.audio.openal.*;
import java.util.*;

/** Uses the stock renderer's lock and decoder; no copied mixer, device or streaming implementation. */
public final class SelectiveAudioRenderer extends ALAudioRenderer implements SourcePauseRenderer {
    private List<? extends AudioSource> batch;
    private List<AudioSource> pausedSources;
    private boolean resuming;

    public SelectiveAudioRenderer(AL al, ALC alc, EFX efx) { super(al,alc,efx); }

    @Override public List<AudioSource> pauseSources(Collection<? extends AudioSource> sources) {
        try {handoff(sources,false);return pausedSources;}
        finally {pausedSources=List.of();}
    }

    @Override public void resumeSources(Collection<? extends AudioSource> sources) {
        try {handoff(sources,true);}finally {pausedSources=List.of();}
    }

    private void handoff(Collection<? extends AudioSource> sources,boolean resume) {
        if(batch!=null)throw new IllegalStateException("Reentrant audio pause handoff");
        List<? extends AudioSource> requested=List.copyOf(sources);
        pauseAll();
        try {
            batch=requested;resuming=resume;pausedSources=List.of();
            // ALAudioRenderer.update holds its own private threadLock while calling our hook.
            // The decoder cannot change native stream state between refill, poll and pause.
            super.update(0);
        } finally {
            batch=null;
            resumeAll();
        }
    }

    @Override public void updateInRenderThread(float tpf) {
        if(batch==null) {super.updateInRenderThread(tpf);return;}
        // A starving loop may be OAL Stopped / JME Playing. Restore its queued PCM before
        // pauseSource; polling alone intentionally does not reclaim that streaming source.
        super.updateInDecoderThread(0);
        super.updateInRenderThread(0);
        List<AudioSource> paused=new ArrayList<>();
        for(AudioSource source:batch) {
            if(resuming) {
                if(source.getStatus()==AudioSource.Status.Paused)super.playSource(source);
            } else if(source.getStatus()==AudioSource.Status.Playing) {
                super.pauseSource(source);
                paused.add(source);
            }
        }
        // Detect a native mismatch while the handoff is still inside the renderer lock.
        super.updateInRenderThread(0);
        pausedSources=List.copyOf(paused);
    }
}
