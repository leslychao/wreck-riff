package game.wreckriff.audio;

import com.jme3.audio.AudioRenderer;
import com.jme3.audio.AudioSource;
import java.util.Collection;
import java.util.List;

/** The desktop renderer's atomic source handoff, without leaving its device paused. */
public interface SourcePauseRenderer extends AudioRenderer {
    List<AudioSource> pauseSources(Collection<? extends AudioSource> sources);
    void resumeSources(Collection<? extends AudioSource> sources);
}
