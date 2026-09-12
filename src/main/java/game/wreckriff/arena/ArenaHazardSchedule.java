package game.wreckriff.arena;

import game.wreckriff.config.ProgressStore;
import game.wreckriff.simulation.MatchSession;
import java.util.*;
import java.util.function.Predicate;

/** Fixed-tick schedule owned exclusively by ArenaSystems. No physics, damage or wall clock. */
final class ArenaHazardSchedule {
    record Timing(int warning,int active,int rest,String sector,boolean barrier,boolean statue) {}
    static final class State {
        ProgressStore.HazardPhase phase=ProgressStore.HazardPhase.READY;
        long remaining,cycle,beganTick=Long.MIN_VALUE;
        ArenaSystems.BossAction completion;
    }
    private final ArenaDefinition arena;
    private final MatchSession session;
    private final LinkedHashMap<String,Timing> timing=new LinkedHashMap<>();
    private final LinkedHashMap<String,State> states=new LinkedHashMap<>();
    private final List<String> completed=new ArrayList<>();
    private final List<ArenaSystems.BossAction> completedActions=new ArrayList<>();
    private long cooldown,cursor,lastTick=Long.MIN_VALUE;
    private record Request(String id,ArenaSystems.BossAction completion) {}
    // A queued AI intention is discarded with the old boss on retry; only already telegraphed map events persist.
    private Request requested;

    ArenaHazardSchedule(MatchSession session,ArenaDefinition arena) {
        this.arena=arena;this.session=session;cursor=Math.floorMod(session.seed,Integer.MAX_VALUE);
        for(var hazard:arena.hazards())add(hazard.id(),new Timing(hazard.warningTicks(),hazard.activeTicks(),hazard.offTicks(),hazard.sector(),false,false));
        for(var barrier:arena.barriers())add(barrier.id(),new Timing(barrier.warningTicks(),barrier.activeTicks(),barrier.offTicks(),barrier.sector(),true,false));
        for(var object:arena.destructibles())if(object.effect()==ArenaDefinition.ObjectEffect.STATUE)
            add(object.id(),new Timing(object.delayTicks(),1,0,object.id(),false,true));
        cooldown=arena.hazards().stream().mapToInt(ArenaDefinition.Hazard::offTicks).min().orElse(0);
    }
    private void add(String id,Timing value) {timing.put(id,value);states.put(id,new State());}
    State state(String id) {return Objects.requireNonNull(states.get(id),"Unknown arena event: "+id);}
    Timing timing(String id) {return Objects.requireNonNull(timing.get(id),"Unknown arena event: "+id);}
    private boolean pending(State state) {return state.phase==ProgressStore.HazardPhase.WARNING||state.phase==ProgressStore.HazardPhase.ACTIVE;}
    int pendingDamage() {return (int)states.entrySet().stream().filter(e->!timing(e.getKey()).barrier()&&pending(e.getValue())).count();}
    private int budget() {return arena.id().equals("doomsday_arena")&&session.phase==MatchSession.Phase.BOSS_COMBAT&&session.bossMode==3?2:1;}
    boolean request(String id,ArenaSystems.BossAction completion) {
        timing(id); // Unknown IDs are programmer/configuration errors, never queue entries.
        if(completion!=null&&session.phase==MatchSession.Phase.BOSS_COMBAT&&session.outcome==MatchSession.Outcome.NONE) {
            if(requested!=null)return requested.id().equals(id)&&requested.completion()==completion;
            if(start(id,completion))return true;
            if(timing(id).statue())return false;
            requested=new Request(id,completion);return true;
        }
        return start(id,completion);
    }
    private boolean start(String id,ArenaSystems.BossAction completion) {
        var value=timing(id);var state=state(id);
        if(!session.combatPhase()||session.outcome!=MatchSession.Outcome.NONE||state.phase!=ProgressStore.HazardPhase.READY||value.statue())return false;
        if(cooldown>0&&pendingDamage()==0)return false;
        if(states.entrySet().stream().anyMatch(e->timing(e.getKey()).statue()&&pending(e.getValue())))return false;
        if(value.barrier()) {
            if(states.entrySet().stream().anyMatch(e->timing(e.getKey()).barrier()&&pending(e.getValue())))return false;
        } else {
            if(pendingDamage()>=budget())return false;
            if(states.entrySet().stream().anyMatch(e->pending(e.getValue())&&timing(e.getKey()).sector().equals(value.sector())))return false;
            var hazard=arena.hazards().stream().filter(h->h.id().equals(id)).findFirst().orElseThrow();
            if(states.entrySet().stream().filter(e->pending(e.getValue())&&!timing(e.getKey()).barrier()).anyMatch(e->{
                var other=arena.hazards().stream().filter(h->h.id().equals(e.getKey())).findFirst().orElseThrow();
                return hazard.minX()<other.maxX()&&hazard.maxX()>other.minX()&&hazard.minZ()<other.maxZ()&&hazard.maxZ()>other.minZ();
            }))return false;
        }
        begin(id,completion);cooldown=Math.max(cooldown,value.rest());return true;
    }
    private void begin(String id,ArenaSystems.BossAction completion) {
        var state=state(id);state.phase=ProgressStore.HazardPhase.WARNING;state.remaining=timing(id).warning();state.cycle++;state.completion=completion;state.beganTick=session.tick;
    }
    void cancel(String id) {var state=state(id);state.phase=ProgressStore.HazardPhase.COOLDOWN;state.remaining=timing(id).rest();state.completion=null;}
    void statue(String id) {cancelPreparedAndActive(0);begin(id,null);}
    void cancelPreparedAndActive(long ticks) {
        requested=null;
        cooldown=Math.max(cooldown,ticks);
        for(var entry:states.entrySet()) {
            if(timing(entry.getKey()).statue())continue;
            var state=entry.getValue();
            if(pending(state)) {state.phase=ProgressStore.HazardPhase.COOLDOWN;state.remaining=Math.max(timing(entry.getKey()).rest(),ticks);state.completion=null;}
            else if(ticks>0) {state.phase=ProgressStore.HazardPhase.COOLDOWN;state.remaining=Math.max(state.remaining,ticks);}
        }
    }
    void advance(Predicate<String> barrierClear) {
        if(lastTick==session.tick||!session.combatPhase()||session.outcome!=MatchSession.Outcome.NONE)return;
        lastTick=session.tick;cooldown=Math.max(0,cooldown-1);
        for(var entry:states.entrySet()) {
            String id=entry.getKey();var state=entry.getValue();var value=timing(id);
            if(state.phase==ProgressStore.HazardPhase.WARNING&&state.beganTick==session.tick)continue;
            if(state.remaining>0)state.remaining--;
            if(state.remaining!=0)continue;
            switch(state.phase) {
                case WARNING -> {
                    if(value.statue()) {completed.add(id);state.phase=ProgressStore.HazardPhase.DISABLED;}
                    else if(value.barrier()&&!barrierClear.test(id)) {state.phase=ProgressStore.HazardPhase.COOLDOWN;state.remaining=value.rest();state.completion=null;}
                    else {state.phase=ProgressStore.HazardPhase.ACTIVE;state.remaining=value.active();}
                }
                case ACTIVE -> {
                    completed.add(id);if(state.completion!=null)completedActions.add(state.completion);state.completion=null;
                    state.phase=value.statue()?ProgressStore.HazardPhase.DISABLED:ProgressStore.HazardPhase.COOLDOWN;state.remaining=value.rest();
                }
                case COOLDOWN -> state.phase=ProgressStore.HazardPhase.READY;
                default -> { }
            }
        }
        if(requested!=null&&session.phase==MatchSession.Phase.BOSS_COMBAT&&start(requested.id(),requested.completion()))requested=null;
        if(requested==null&&cooldown==0&&pendingDamage()==0&&!arena.hazards().isEmpty()) {
            for(int i=0;i<arena.hazards().size();i++) {
                String id=arena.hazards().get(Math.floorMod(cursor++,arena.hazards().size())).id();
                if(request(id,null))break;
            }
        }
    }
    List<String> drainCompleted() {var result=List.copyOf(completed);completed.clear();return result;}
    List<ArenaSystems.BossAction> drainActions() {var result=List.copyOf(completedActions);completedActions.clear();return result;}
    Map<String,ProgressStore.HazardState> snapshot() {
        var result=new LinkedHashMap<String,ProgressStore.HazardState>();
        states.forEach((id,s)->result.put(id,new ProgressStore.HazardState(s.phase,s.remaining,0,s.cycle)));return result;
    }
    long cooldown() {return cooldown;}
    long cursor() {return cursor;}
    void restore(ProgressStore.ArenaState saved) {
        if(!saved.hazards().keySet().equals(states.keySet()))throw new IllegalArgumentException("Checkpoint arena event IDs differ");
        for(var entry:saved.hazards().entrySet()) {
            var value=entry.getValue();var timing=timing(entry.getKey());
            long limit=switch(value.phase()) {case WARNING->timing.warning();case ACTIVE->timing.active();case COOLDOWN->Math.max(timing.rest(),1440);default->0;};
            var hazard=arena.hazards().stream().filter(h->h.id().equals(entry.getKey())).findFirst();
            long damageLimit=value.phase()==ProgressStore.HazardPhase.ACTIVE&&hazard.isPresent()?hazard.get().damageIntervalTicks():0;
            if(value.remainingTicks()>limit||value.cooldownTicks()>damageLimit||(value.phase()==ProgressStore.HazardPhase.DISABLED&&!timing.statue()))
                throw new IllegalArgumentException("Invalid saved arena event duration: "+entry.getKey());
        }
        var pending=saved.hazards().entrySet().stream().filter(e->e.getValue().phase()==ProgressStore.HazardPhase.WARNING||e.getValue().phase()==ProgressStore.HazardPhase.ACTIVE).toList();
        if(pending.stream().filter(e->!timing(e.getKey()).barrier()).count()>budget()
                ||pending.stream().filter(e->timing(e.getKey()).barrier()).count()>1)throw new IllegalArgumentException("Saved arena hazard budget exceeded");
        Set<String> sectors=new HashSet<>();
        for(var entry:pending)if(!sectors.add(timing(entry.getKey()).sector()))throw new IllegalArgumentException("Saved arena sectors overlap");
        for(int first=0;first<pending.size();first++)for(int second=first+1;second<pending.size();second++) {
            String firstId=pending.get(first).getKey(),secondId=pending.get(second).getKey();
            var a=arena.hazards().stream().filter(h->h.id().equals(firstId)).findFirst();
            var b=arena.hazards().stream().filter(h->h.id().equals(secondId)).findFirst();
            if(a.isPresent()&&b.isPresent()&&a.get().minX()<b.get().maxX()&&a.get().maxX()>b.get().minX()
                    &&a.get().minZ()<b.get().maxZ()&&a.get().maxZ()>b.get().minZ())throw new IllegalArgumentException("Saved arena zones overlap");
        }
        long maximumRest=timing.values().stream().mapToLong(Timing::rest).max().orElse(0);
        if(saved.eventCooldownTicks()>Math.max(1440,maximumRest))throw new IllegalArgumentException("Invalid saved arena cooldown");
        saved.hazards().forEach((id,value)->{var state=state(id);state.phase=value.phase();state.remaining=value.remainingTicks();state.cycle=value.cycle();state.completion=null;state.beganTick=Long.MIN_VALUE;});
        cooldown=saved.eventCooldownTicks();cursor=saved.randomState();lastTick=Long.MIN_VALUE;requested=null;
    }
}
