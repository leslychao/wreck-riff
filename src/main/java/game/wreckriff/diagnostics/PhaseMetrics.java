package game.wreckriff.diagnostics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/** Fixed phase histograms and the 32 worst frames. Sampling never performs file I/O. */
public final class PhaseMetrics {
    public static final int MAXIMUM_SPIKES=32;
    private final EnumMap<FrameSample.Phase,FrameMetrics> phases=new EnumMap<>(FrameSample.Phase.class);
    private final PriorityQueue<FrameSample> spikes=new PriorityQueue<>(Comparator.comparingDouble(FrameSample::seconds));
    private int maximumParticipants,maximumBodies,maximumProjectiles,maximumSfx,maximumEffects,maximumLaunching;
    private double droppedSeconds;
    public void add(FrameSample sample) {
        phases.computeIfAbsent(sample.phase(),ignored->new FrameMetrics()).add(sample.seconds());
        droppedSeconds+=sample.droppedSimulationSeconds();
        maximumParticipants=Math.max(maximumParticipants,sample.participants());maximumBodies=Math.max(maximumBodies,sample.bodies());
        maximumProjectiles=Math.max(maximumProjectiles,sample.projectiles());maximumSfx=Math.max(maximumSfx,sample.sfxVoices());
        maximumEffects=Math.max(maximumEffects,sample.effects());maximumLaunching=Math.max(maximumLaunching,sample.launchingVehicles());
        if(sample.seconds()>.033&&(spikes.size()<MAXIMUM_SPIKES||sample.seconds()>spikes.peek().seconds())) {
            if(spikes.size()==MAXIMUM_SPIKES)spikes.remove();spikes.add(sample);
        }
    }
    public double droppedSimulationSeconds() {return droppedSeconds;}
    public Map<String,Object> snapshot() {
        var data=new LinkedHashMap<String,Object>();var histograms=new LinkedHashMap<String,Object>();
        phases.forEach((phase,metrics)->histograms.put(phase.name(),metrics.snapshot()));
        List<FrameSample> worst=new ArrayList<>(spikes);worst.sort(Comparator.comparingDouble(FrameSample::seconds).reversed());
        data.put("phases",histograms);data.put("worstFrames",List.copyOf(worst));data.put("maximumStoredSpikes",MAXIMUM_SPIKES);
        data.put("maximumParticipants",maximumParticipants);data.put("maximumBodies",maximumBodies);
        data.put("maximumProjectiles",maximumProjectiles);data.put("maximumSfxVoices",maximumSfx);
        data.put("maximumEffects",maximumEffects);data.put("maximumLaunchingVehicles",maximumLaunching);
        data.put("droppedSimulationSeconds",droppedSeconds);return data;
    }
}
