package game.wreckriff.simulation;

/** Applied health values in transaction order. Does not own or modify health. */
public record HealthChange(float hpBefore,float hpAfter) {
    public HealthChange {
        if(!Float.isFinite(hpBefore)||!Float.isFinite(hpAfter)||hpBefore<0||hpAfter<0)
            throw new IllegalArgumentException("Finite nonnegative health required");
    }
}
