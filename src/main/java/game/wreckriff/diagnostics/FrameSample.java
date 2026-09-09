package game.wreckriff.diagnostics;

import java.util.Objects;

/** Context of the preceding rendered frame, whose real wall duration is reported by jME next update. */
public record FrameSample(long frame,long tick,String arenaId,Phase phase,double seconds,
                          int participants,int bodies,int projectiles,int sfxVoices,int effects,int launchingVehicles,boolean drawable,
                          double droppedSimulationSeconds) {
    public enum Phase {
        MENU,LOADING,INTRO,ARENA_COMBAT,BOSS_ENTRY,BOSS_COMBAT,PAUSED,RESULTS,TRANSITION,ERROR;
        public boolean combat() {return this==ARENA_COMBAT||this==BOSS_COMBAT;}
    }
    public FrameSample {
        Objects.requireNonNull(arenaId);Objects.requireNonNull(phase);
        if(frame<0||tick<0||!Double.isFinite(seconds)||seconds<0||participants<0||bodies<0||projectiles<0||sfxVoices<0||effects<0||launchingVehicles<0
                ||!Double.isFinite(droppedSimulationSeconds)||droppedSimulationSeconds<0)throw new IllegalArgumentException("Invalid frame sample");
    }
}
