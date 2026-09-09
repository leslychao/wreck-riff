package game.wreckriff.ai;

import com.jme3.math.Vector3f;
import game.wreckriff.arena.ArenaDefinition;

/** Per-brain action state. Perception, route choice and physical commands remain in BotController. */
final class BossTactics {
    enum Phase { CRUISE, TELEGRAPH, CHARGE, RECOVERY }
    record Timing(int telegraph,int charge,int recovery,int pause,float minimumRange,float maximumRange) {}
    final String profileId;
    final Timing timing;
    Phase phase=Phase.CRUISE;
    long beganTick,untilTick,chargeStartedTick,nextRamTick=360,nextProtocolTick=720,nextLaunchTick=1200,nextSearchTick;
    long lastContactTick=Long.MIN_VALUE/2,lastSeenTick=Long.MIN_VALUE/2;
    int mode=1,launchIndex,side=1;
    Vector3f targetPoint,chargeDirection,lastSeen;
    boolean completionPending;

    BossTactics(String profileId) {
        this.profileId=profileId;
        timing=switch(profileId) {
            case "boss_foreman" -> new Timing(132,264,240,480,25,55);
            case "boss_prefect" -> new Timing(108,216,216,600,25,50);
            case "boss_emcee" -> new Timing(120,216,192,480,24,48);
            case "boss_ash_shepherd" -> new Timing(144,288,240,600,25,55);
            case "boss_director" -> new Timing(144,276,264,480,25,55);
            default -> throw new IllegalArgumentException("Unknown boss policy: "+profileId);
        };
    }
    void observed(BotObservation.Opponent target) {
        if(target!=null){lastSeen=target.position();lastSeenTick=target.observedTick();}
    }
    boolean canRam(long tick,int sessionMode) {
        return phase==Phase.CRUISE&&tick>=nextRamTick&&(!profileId.equals("boss_prefect")||sessionMode>=3);
    }
    void beginRam(long tick,Vector3f position,Vector3f observedTarget) {
        targetPoint=observedTarget.clone();chargeDirection=observedTarget.subtract(position);chargeDirection.y=0;chargeDirection.normalizeLocal();
        phase=Phase.TELEGRAPH;beganTick=tick;untilTick=tick+timing.telegraph();completionPending=false;
    }
    void advance(long tick,int sessionMode) {
        mode=Math.max(mode,Math.clamp(sessionMode,1,3));
        if(phase==Phase.CRUISE||tick<untilTick)return;
        if(phase==Phase.TELEGRAPH){phase=Phase.CHARGE;beganTick=tick;chargeStartedTick=tick;untilTick=tick+timing.charge();}
        else if(phase==Phase.CHARGE)finishCharge(tick);
        else {phase=Phase.CRUISE;beganTick=tick;untilTick=0;side=-side;}
    }
    void finishCharge(long tick) {
        if(phase!=Phase.CHARGE)return;
        phase=Phase.RECOVERY;beganTick=tick;untilTick=tick+timing.recovery();completionPending=true;
        // Modes change only the between-action pause, never advertised warning/charge/recovery durations.
        nextRamTick=untilTick+timing.pause()-(mode-1)*120;
    }
    void cancel(long tick) {
        if(phase==Phase.CHARGE)finishCharge(tick);
        else if(phase==Phase.TELEGRAPH){phase=Phase.RECOVERY;beganTick=tick;untilTick=tick+timing.recovery();nextRamTick=untilTick+timing.pause();}
    }
    int protocolCooldown() {return profileId.equals("boss_prefect")?1680:profileId.equals("boss_emcee")?1440:1920;}
    float standOff() {
        return switch(profileId){case "boss_prefect"->45;case "boss_ash_shepherd"->38;case "boss_director"->42;case "boss_emcee"->30;default->28;};
    }
    ArenaDefinition.Vec3 point() {return targetPoint==null?null:new ArenaDefinition.Vec3(targetPoint.x,targetPoint.y,targetPoint.z);}
}
