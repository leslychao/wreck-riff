package game.wreckriff.diagnostics;

import java.util.*;

/** Fixed resource baselines. A growing sample never replaces the baseline that it is checked against. */
public final class ResourceRetention {
    public static final long BUFFER_ALLOWANCE_BYTES=4L*1024*1024;
    public static final int TRACKER_ALLOWANCE=64,TEXTURE_ALLOWANCE=8,MINIMUM_UNLOAD_COMPARISONS=3;
    public record Key(String arenaId,String mode,String phase,String profileId,String topology) {}
    public record Sample(String stage,double elapsedSeconds,long observedAtEpochMillis,Key key,int bodies,int listeners,int tickListeners,
                         int projectiles,int voices,int trackers,long directBufferBytes,int textures,int saveQueueDepth,
                         boolean saveWorkerScheduled,long revision,long persistedRevision,boolean collectionRequested) {}
    private final Map<Key,Sample> loadBaselines=new LinkedHashMap<>();
    private final List<Sample> samples=new ArrayList<>();
    private final Set<String> errors=new LinkedHashSet<>();
    private Sample unloadBaseline;
    private int unloadComparisons;
    public void add(Sample sample,boolean resourcesWarmed) {
        if(samples.size()<1000)samples.add(sample);else error("Resource evidence sample limit reached");
        if(sample.saveQueueDepth<0||sample.saveQueueDepth>1)error("Progress queue exceeded its coalesced one-snapshot bound");
        if(sample.voices>32)error("Audio voice count exceeded 32");
        if(sample.stage.equals("LOAD")) {
            var baseline=loadBaselines.get(sample.key);
            if(baseline==null) {
                if(loadBaselines.size()<256)loadBaselines.put(sample.key,sample);else error("Resource baseline key limit reached");
            } else if(sample.bodies!=baseline.bodies||sample.listeners!=baseline.listeners||sample.tickListeners!=baseline.tickListeners)
                error("Native load baseline changed for "+sample.key);
            if(sample.projectiles!=0)error("Projectiles survived a match load");
        } else if(sample.stage.equals("UNLOAD")) {
            if(sample.bodies!=0||sample.listeners!=0||sample.tickListeners!=0||sample.projectiles!=0||sample.voices!=0)
                error("Match-owned resources survived unload on "+sample.key.arenaId);
            if(sample.saveQueueDepth!=0||sample.saveWorkerScheduled||sample.revision!=sample.persistedRevision)
                error("Progress did not settle after unloading "+sample.key.arenaId);
            if(resourcesWarmed) {
                if(unloadBaseline==null)unloadBaseline=sample;
                else {
                    unloadComparisons++;
                    if(sample.trackers>unloadBaseline.trackers+TRACKER_ALLOWANCE)error("Native trackers exceeded the fixed warmed unload baseline");
                    if(sample.directBufferBytes>unloadBaseline.directBufferBytes+BUFFER_ALLOWANCE_BYTES)error("Direct buffers exceeded the fixed warmed unload baseline");
                    if(sample.textures<0||sample.textures>unloadBaseline.textures+TEXTURE_ALLOWANCE)error("GPU textures exceeded the fixed warmed unload baseline or were unavailable");
                }
            }
        } else error("Unknown resource sample stage");
    }
    public void error(String error) {if(errors.size()<100)errors.add(error);}
    public boolean passed() {return errors.isEmpty()&&unloadComparisons>=MINIMUM_UNLOAD_COMPARISONS;}
    public boolean failed() {return !errors.isEmpty();}
    public List<Map<String,Object>> samples() {
        return samples.stream().map(sample->{
            var result=new LinkedHashMap<String,Object>();
            result.put("stage",sample.stage);result.put("elapsedSeconds",sample.elapsedSeconds);result.put("observedAtEpochMillis",sample.observedAtEpochMillis);
            result.put("arenaId",sample.key.arenaId);result.put("mode",sample.key.mode);result.put("phase",sample.key.phase);result.put("profileId",sample.key.profileId);result.put("topology",sample.key.topology);
            result.put("bodies",sample.bodies);result.put("listeners",sample.listeners);result.put("tickListeners",sample.tickListeners);result.put("projectiles",sample.projectiles);
            result.put("voices",sample.voices);result.put("trackers",sample.trackers);result.put("directBufferBytes",sample.directBufferBytes);result.put("textures",sample.textures);
            result.put("saveQueueDepth",sample.saveQueueDepth);result.put("saveWorkerScheduled",sample.saveWorkerScheduled);result.put("revision",sample.revision);result.put("persistedRevision",sample.persistedRevision);
            result.put("collectionRequested",sample.collectionRequested);return (Map<String,Object>)result;
        }).toList();
    }
    public Map<String,Object> evidence() {
        return Map.of("status",failed()?"FAIL":passed()?"PASS":"PENDING","errors",List.copyOf(errors),"loadBaselineKeys",loadBaselines.size(),
                "unloadComparisons",unloadComparisons,"minimumUnloadComparisons",MINIMUM_UNLOAD_COMPARISONS,
                "fixedAllowances",Map.of("directBufferBytes",BUFFER_ALLOWANCE_BYTES,"nativeTrackers",TRACKER_ALLOWANCE,"textures",TEXTURE_ALLOWANCE),
                "collectionPolicy","Explicit GC requested only after match unload; one second of rendered menu settling precedes retained-resource samples");
    }
}
