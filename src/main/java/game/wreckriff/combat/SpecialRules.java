package game.wreckriff.combat;

import game.wreckriff.config.VehicleDefinition;

/** Approved first-playtest tuning. All three specials share the existing fixed-step combat owner. */
public final class SpecialRules {
    public static final float PULSE_WINDUP=.3f, PULSE_RANGE=12, PULSE_HALF_ANGLE=40;
    public static final float PULSE_DAMAGE=60, PULSE_IMPULSE=8800, PULSE_MAX_DELTA_SPEED=9;
    public static final float GRINDER_WINDUP=.6f, GRINDER_SEARCH=3, GRINDER_CONTACT=1.5f;
    public static final float GRINDER_DPS=80, GRINDER_DAMAGE_CAP=120;
    public static final float GRINDER_SPEED_CAP=12, GRINDER_STEER_MULTIPLIER=.6f;
    public static final float DASH_SECONDS=.35f, DASH_SIDE_SPEED=20, DASH_SPEED_CAP=48;
    public static final float BOMB_FUSE=.7f, BOMB_DAMAGE=70, BOMB_RADIUS=4;
    public static final int MAXIMUM_BOMBS=32;
    private SpecialRules() {}
    public static float cooldownSeconds(String profileId) {
        return switch(VehicleDefinition.forId(profileId)) {case RIVET->14;case GRINDER->20;case SPARK->12;};
    }
}
