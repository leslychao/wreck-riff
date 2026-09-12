package game.wreckriff.diagnostics;

import game.wreckriff.config.ProgressStore;
import java.util.*;

/** Bounded menu/load orchestration only: combat and outcomes remain owned by MatchRuntime. */
public final class SoakSchedule {
    public static final int MINIMUM_SECONDS=1800,MINIMUM_MAP_CHANGES=10,MINIMUM_RETRIES=20;
    public static final double MATCH_DWELL_SECONDS=30,UNLOAD_SETTLE_SECONDS=1;
    public record Selection(String arenaId,ProgressStore.Mode mode,String profileId,boolean retry) {}
    private final List<String> arenas;
    private final Set<String> visited=new LinkedHashSet<>();
    private final Set<String> loadedModes=new LinkedHashSet<>();
    private int visit,loadsThisVisit,mapChanges,retries,loads;
    private UUID previousSession;
    private String previousArena;
    private double measuredSeconds;

    public SoakSchedule(List<String> arenaIds,String firstArena) {
        if(arenaIds.isEmpty()||new HashSet<>(arenaIds).size()!=arenaIds.size()||!arenaIds.contains(firstArena))
            throw new IllegalArgumentException("Soak requires distinct registered arenas and a registered first arena");
        var ordered=new ArrayList<>(arenaIds);Collections.rotate(ordered,-ordered.indexOf(firstArena));arenas=List.copyOf(ordered);
    }
    public Selection first() {return selection(false);}
    public Selection next() {
        if(loads==0)throw new IllegalStateException("First match has not loaded");
        if(loadsThisVisit>=3){visit++;loadsThisVisit=0;return selection(false);}
        return selection(true);
    }
    private Selection selection(boolean retry) {
        String id=arenas.get(visit%arenas.size());
        var mode=id.equals(ProgressStore.LEGACY_ARENA)?ProgressStore.Mode.LEGACY:
                (visit/arenas.size())%2==0?ProgressStore.Mode.ARENA:ProgressStore.Mode.BOSS_DUEL;
        return new Selection(id,mode,List.of("rivet","grinder","spark").get((visit%arenas.size())%3),retry);
    }
    /** Count completed native reconstruction, never an attempted menu click or loading screen. */
    public void loaded(Selection selection,UUID sessionId,String actualArena,ProgressStore.Mode actualMode,String profileId) {
        Objects.requireNonNull(sessionId);
        if(sessionId.equals(previousSession)||!selection.arenaId.equals(actualArena)||selection.mode!=actualMode||!selection.profileId.equals(profileId))
            throw new IllegalStateException("Soak transition did not construct the requested independent match");
        if(selection.retry) {
            if(previousSession==null||!actualArena.equals(previousArena))throw new IllegalStateException("Retry changed arena");
            retries++;
        } else if(previousArena!=null&&!previousArena.equals(actualArena))mapChanges++;
        previousSession=sessionId;previousArena=actualArena;loads++;loadsThisVisit++;
        visited.add(actualArena);loadedModes.add(actualArena+"/"+actualMode);
    }
    public void frame(FrameSample sample) {if(sample.drawable())measuredSeconds+=sample.seconds();}
    public double measuredSeconds() {return measuredSeconds;}
    public int retries() {return retries;}
    public boolean resourcesWarmed() {return loadedModes.size()>=arenas.size()*2-1;}
    public boolean coverageComplete() {return measuredSeconds>=MINIMUM_SECONDS&&mapChanges>=MINIMUM_MAP_CHANGES&&retries>=MINIMUM_RETRIES&&visited.size()==arenas.size();}
    public Map<String,Object> evidence() {
        return Map.of("measuredSeconds",measuredSeconds,"mapChanges",mapChanges,"retries",retries,"completedLoads",loads,
                "arenaIds",List.copyOf(visited),"loadedModes",List.copyOf(loadedModes),"allRegisteredArenas",visited.size()==arenas.size(),
                "resourcesWarmed",resourcesWarmed(),"coverageComplete",coverageComplete());
    }
}
