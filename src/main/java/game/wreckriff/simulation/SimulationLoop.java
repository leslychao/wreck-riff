package game.wreckriff.simulation;

import game.wreckriff.config.MatchRules;
import java.util.logging.Logger;

/** The only accumulator. Used unchanged by both the rendered game and physics tests. */
public final class SimulationLoop {
    public static final double STEP = 1.0/120.0;
    private final MatchRules rules;
    private double accumulator;
    private double droppedSimulationTime;
    private long steps;
    public SimulationLoop(MatchRules rules) { this.rules=rules; }
    public int advance(double frameSeconds, boolean running, Runnable tick) {
        if (!Double.isFinite(frameSeconds) || frameSeconds<0) throw new IllegalArgumentException("Invalid frame duration");
        if (!running) { accumulator=0; return 0; }
        double accepted=Math.min(frameSeconds,rules.maxFrameSeconds());
        droppedSimulationTime += frameSeconds-accepted;
        accumulator+=accepted;
        int count=0;
        while (accumulator+1e-12>=STEP && count<rules.maxSteps()) {
            tick.run(); accumulator-=STEP; count++; steps++;
        }
        if (accumulator+1e-12>=STEP) {
            double remainder=accumulator%STEP;
            droppedSimulationTime+=accumulator-remainder;
            accumulator=remainder;
            Logger.getLogger(getClass().getName()).warning("Dropped excess simulation debt");
        }
        accumulator=Math.max(0,accumulator);
        return count;
    }
    public void resetAccumulator() { accumulator=0; }
    public float alpha() { return (float)(accumulator/STEP); }
    public double droppedSimulationTime() { return droppedSimulationTime; }
    public long steps() { return steps; }
}
