package game.wreckriff.simulation;

import game.wreckriff.arena.ArenaDefinition;
import game.wreckriff.combat.CombatRules;
import game.wreckriff.config.Configs;
import java.util.*;

/** Match data. MatchRuntime owns phase/outcome transitions and all native mutations. */
public final class MatchSession {
    public static final int TICKS_PER_SECOND=120;
    public static final float DT=1f/TICKS_PER_SECOND;
    public enum Mode { LEGACY, CAMPAIGN, ARENA, BOSS_DUEL }
    public enum Phase { INTRO, ARENA_COMBAT, BOSS_ENTRY, BOSS_COMBAT, RESULT, ERROR }
    public enum Outcome { NONE, VICTORY, DEFEAT, DRAW }
    public final UUID sessionId;
    public final long seed;
    public final String arenaId;
    public final Mode mode;
    public final List<VehicleState> vehicles;
    public final CombatRules combatRules;
    private final List<VehicleState> participants=new ArrayList<>();
    public long tick,activeTicks;
    public Phase phase;
    public long phaseTicks;
    public Outcome outcome=Outcome.NONE;
    public String outcomeReason="";
    public int bossParticipantId=-1,bossMode=1;
    public boolean preBossRepairApplied,checkpointRequested;
    private final long maximumTicks;

    public MatchSession(long seed,int durationSeconds) { this(seed,durationSeconds,Configs.load("combat",CombatRules.class)); }
    public MatchSession(long seed,int durationSeconds,CombatRules rules) {
        this(seed,"dead-air-yard",Mode.LEGACY,4,durationSeconds,rules,UUID.randomUUID());
    }
    public MatchSession(long seed,int durationSeconds,CombatRules rules,UUID sessionId) {
        this(seed,"dead-air-yard",Mode.LEGACY,4,durationSeconds,rules,sessionId);
    }
    public MatchSession(long seed,ArenaDefinition arena,Mode mode,CombatRules rules) {
        this(seed,arena,mode,rules,UUID.randomUUID());
    }
    public MatchSession(long seed,ArenaDefinition arena,Mode mode,CombatRules rules,UUID sessionId) {
        this(seed,arena.id(),mode,mode==Mode.BOSS_DUEL?0:arena.metadata().normalEnemies(),arena.metadata().durationSeconds(),rules,sessionId);
        if((mode==Mode.LEGACY)!=arena.bosses().isEmpty())throw new IllegalArgumentException("Arena/mode mismatch");
    }
    private MatchSession(long seed,String arenaId,Mode mode,int enemies,int durationSeconds,CombatRules rules,UUID sessionId) {
        if(enemies<0||durationSeconds<0)throw new IllegalArgumentException("Invalid match settings");
        this.seed=seed;this.sessionId=Objects.requireNonNull(sessionId);this.arenaId=Objects.requireNonNull(arenaId);this.mode=Objects.requireNonNull(mode);
        combatRules=Objects.requireNonNull(rules);
        maximumTicks=durationSeconds==0?Long.MAX_VALUE:Math.multiplyExact((long)durationSeconds,TICKS_PER_SECOND);
        participants.add(new VehicleState(0,"Rivet",true,rules));
        String[] names={"Static","Dent","Buzz","Fuse"};
        for(int id=1;id<=enemies;id++)participants.add(new VehicleState(id,names[(id-1)%names.length]+(id>4?" "+id:""),false,rules));
        vehicles=Collections.unmodifiableList(participants);
        phase=mode==Mode.LEGACY?Phase.ARENA_COMBAT:mode==Mode.BOSS_DUEL?Phase.BOSS_ENTRY:Phase.INTRO;
    }
    VehicleState registerBoss(ArenaDefinition.Boss boss) {
        if(bossParticipantId>=0)throw new IllegalStateException("Boss already registered");
        bossParticipantId=participants.size();
        var state=new VehicleState(bossParticipantId,boss.name(),false,boss.profileId(),0,true,boss.maximumHp(),combatRules);
        state.selectedWeapon=boss.primary();participants.add(state);return state;
    }
    void transition(Phase next) { phase=next;phaseTicks=0; }
    public VehicleState vehicle(int id) { return participants.get(id); }
    public boolean containsParticipant(int id) { return id>=0&&id<participants.size(); }
    public long maximumTicks() { return maximumTicks; }
    public boolean combatPhase() { return phase==Phase.ARENA_COMBAT||phase==Phase.BOSS_COMBAT; }
    public long normalRivalsAlive() { return vehicles.stream().filter(v->!v.player&&!v.boss&&v.alive()).count(); }
    public float seconds() { return (mode==Mode.LEGACY?tick:activeTicks)/(float)TICKS_PER_SECOND; }
}
