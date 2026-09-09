package game.wreckriff.arena;

import com.jme3.asset.DesktopAssetManager;
import com.jme3.math.*;
import game.wreckriff.ai.BotController;
import game.wreckriff.config.*;
import game.wreckriff.input.VehicleCommand;
import game.wreckriff.simulation.*;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Ten complete combats using the same MatchRuntime.tick called by GameApplication. */
class MatchRuntimeTest {
    private record DriverResult(int id,float hp,int recoveries,long aliveTicks,long maximumStationaryTicks,
            float damageDealt,int eliminations,float x,float y,float z) {}
    private record BattleResult(int seed,long ticks,String outcome,String reason,float seconds,int shotEvents,
            float externalDamage,int pickups,int maximumProjectiles,float firstEncounterSeconds,float firstShotSeconds,float firstExternalDamageSeconds,
            int fatalRecoveries,List<DriverResult> drivers) {}
    private record RecoveryEpisode(int seed,int vehicleId,long tick,String cause,Vector3f before,Vector3f after,
            Vector3f forward,Vector3f velocity,float upright,Vector3f goal,List<Integer> path,
            long ticksSinceExternalDamage,String lastExternalDamage,CannonHistory lastDirectCannon) {}
    private record UpSample(long tick,String phase,float upright,int wheelContacts,Vector3f position,
            Vector3f velocity,Vector3f angularVelocity) {}
    private record CannonHistory(long eventId,long acceptedTick,float appliedDamage,Vector3f impactPoint,
            int shieldTicks,int protectionTicks,UpSample beforeHit,long firstUpsideDownTick,
            float timeToUpsideDownSeconds,long stableSupportResetTick,List<UpSample> upHistory) {}
    /** Observation only: no gameplay state or acceptance count is changed by attribution. */
    private static final class CannonTrace {
        final long eventId,acceptedTick;
        final float appliedDamage;
        final Vector3f point;
        final int shieldTicks,protectionTicks;
        final UpSample beforeHit;
        final List<UpSample> history=new ArrayList<>();
        long firstUpsideDownTick=-1,stableSupportResetTick=-1;
        int stableSupportTicks;
        CannonTrace(GameEvent impact,GameEvent damage,VehicleState state,UpSample beforeHit,long tick) {
            eventId=impact.eventId();acceptedTick=tick;appliedDamage=damage.value();point=impact.position();
            shieldTicks=state.shieldTicks;protectionTicks=state.protectionTicks;this.beforeHit=beforeHit;
        }
        void observe(UpSample sample,boolean force) {
            if(stableSupportResetTick>=0)return;
            boolean firstUpsideDown=firstUpsideDownTick<0&&sample.upright()<=-.5f;
            if(firstUpsideDown)firstUpsideDownTick=sample.tick();
            stableSupportTicks=sample.wheelContacts()==4&&sample.upright()>.85f?stableSupportTicks+1:0;
            if(stableSupportTicks>=60)stableSupportResetTick=sample.tick();
            if(force||firstUpsideDown||stableSupportResetTick>=0||history.isEmpty()
                    ||sample.tick()-history.getLast().tick()>=12)history.add(sample);
        }
        CannonHistory snapshot() {
            return new CannonHistory(eventId,acceptedTick,appliedDamage,point,shieldTicks,protectionTicks,beforeHit,
                    firstUpsideDownTick,firstUpsideDownTick<0?-1:(firstUpsideDownTick-acceptedTick)*MatchSession.DT,
                    stableSupportResetTick,List.copyOf(history));
        }
    }
    private static UpSample observePose(PhysicsWorld world,int id,long tick,String phase) {
        return new UpSample(tick,phase,world.rotation(id).mult(Vector3f.UNIT_Y).y,world.wheelContacts(id),
                world.position(id),world.velocity(id),world.containsVehicle(id)?world.vehicle(id).getAngularVelocity():Vector3f.ZERO);
    }
    private record BatchReport(int schemaVersion,String scenario,String os,String javaVersion,double wallSeconds,
            double botMinutes,int recoveries,double recoveriesPerTenBotMinutes,long maximumStationaryTicks,
            List<BattleResult> battles,List<RecoveryEpisode> recoveryEpisodes,List<String> failures) {}

    @Test void tenSeededMatchesRespectNavigationAndRecoveryAcceptance() throws Exception {
        long started=System.nanoTime();
        ArenaDefinition arena=ArenaDefinition.load();VehicleRules vehicleRules=VehicleRules.load();
        var assets=NativeArenaAssets.MANAGER;
        List<BattleResult> results=new ArrayList<>();List<String> failures=new ArrayList<>();
        List<RecoveryEpisode> recoveryEpisodes=new ArrayList<>();
        long totalAliveTicks=0,maximumStationaryTicks=0;int totalRecoveries=0;
        for (int seed=0;seed<10;seed++) {
            MatchSession session=new MatchSession(seed,360);
            PhysicsWorld world=new PhysicsWorld(vehicleRules);
            ArenaContent content=new ArenaFactory(assets).build(arena);
            for (var body:content.bodies()) world.addStatic(body.shape(),body.position(),body.rotation());
            List<ArenaDefinition.Spawn> spawns=arena.shuffledSpawns(seed);
            for (var vehicle:session.vehicles) {
                var spawn=spawns.get(vehicle.id);
                world.addVehicle(vehicle.id,spawn.position().vector().add(0,.85f,0),
                        new Quaternion().fromAngleAxis(spawn.yawDegrees()*FastMath.DEG_TO_RAD,Vector3f.UNIT_Y));
            }
            try (MatchRuntime runtime=new MatchRuntime(session,world,arena,content.graph(),vehicleRules)) {
                for (int settle=0;settle<240;settle++) world.step();
                int shots=0,pickups=0,maxProjectiles=0,fatalRecoveries=0,finished=0;
                float damage=0,firstEncounter=-1,firstShot=-1,firstDamage=-1;
                long[] lastExternalTick={-1,-1,-1,-1,-1};String[] lastExternalKind={"none","none","none","none","none"};
                CannonTrace[] cannonTraces=new CannonTrace[5];
                while (session.outcome==MatchSession.Outcome.NONE && session.tick<360L*120) {
                    Vector3f[] before=new Vector3f[5],forward=new Vector3f[5],velocity=new Vector3f[5];float[] upright=new float[5];
                    UpSample[] beforeSamples=new UpSample[5];
                    for (int id=0;id<5;id++) {
                        beforeSamples[id]=observePose(world,id,session.tick,"before-step");
                        before[id]=beforeSamples[id].position();forward[id]=world.forward(id);velocity[id]=beforeSamples[id].velocity();
                        upright[id]=beforeSamples[id].upright();
                    }
                    List<GameEvent> events=runtime.tick(VehicleCommand.NONE,true);
                    // Only the terminal direct vehicle explosion plus accepted damage establishes
                    // a Cannon chain. Splash, spawn-protected hits and later MG/fire do not.
                    for(var explosion:events)if(explosion.type()==GameEvent.Type.EXPLOSION
                            &&explosion.kind().equals("cannon")&&explosion.subjectId()>=0) {
                        int id=explosion.subjectId();
                        GameEvent accepted=events.stream().filter(e->e.type()==GameEvent.Type.DAMAGE&&e.kind().equals("cannon")
                                &&e.eventId()==explosion.eventId()&&e.subjectId()==id&&e.value()>0).findFirst().orElse(null);
                        if(accepted!=null&&session.vehicle(id).protectionTicks==0) {
                            GameEvent impact=events.stream().filter(e->e.type()==GameEvent.Type.IMPACT
                                    &&e.eventId()==explosion.eventId()&&e.subjectId()==id).findFirst().orElseThrow();
                            cannonTraces[id]=new CannonTrace(impact,accepted,session.vehicle(id),beforeSamples[id],session.tick-1);
                        }
                    }
                    boolean[] recovered=new boolean[5];
                    for(var event:events)if(event.type()==GameEvent.Type.DAMAGE&&(event.kind().equals("recovery")||event.kind().equals("out-of-bounds")))recovered[event.subjectId()]=true;
                    for(int id=0;id<5;id++)if(cannonTraces[id]!=null) {
                        // Recovery teleports in prepare(), so its pre-step pose is the final
                        // physical sample; the upright replacement must not clear the chain.
                        UpSample sample=recovered[id]?beforeSamples[id]:observePose(world,id,session.tick-1,"after-step");
                        cannonTraces[id].observe(sample,recovered[id]||cannonTraces[id].acceptedTick==session.tick-1);
                    }
                    maxProjectiles=Math.max(maxProjectiles,runtime.combat().projectiles().size());
                    if (firstEncounter<0) for (int id=0;id<5;id++) {
                        if (!runtime.bots().observation(id).visible().isEmpty()) {
                            firstEncounter=runtime.bots().observation(id).tick()/120f;break;
                        }
                    }
                    for (var event:events) {
                        if (event.type()==GameEvent.Type.SHOT) { shots++;if (firstShot<0) firstShot=session.seconds(); }
                        if (event.type()==GameEvent.Type.PICKUP) pickups++;
                        if (event.type()==GameEvent.Type.MATCH_FINISHED) finished++;
                        if (event.type()==GameEvent.Type.DAMAGE && event.sourceId()>=0 && event.sourceId()!=event.subjectId()) {
                            damage+=event.value();if (firstDamage<0 && event.value()>0) firstDamage=session.seconds();
                            lastExternalTick[event.subjectId()]=session.tick-1;lastExternalKind[event.subjectId()]=event.kind();
                        }
                        if (event.type()==GameEvent.Type.DAMAGE && (event.kind().equals("recovery")||event.kind().equals("out-of-bounds"))) {
                            int id=event.subjectId();
                            recoveryEpisodes.add(new RecoveryEpisode(seed,id,session.tick-1,event.kind(),before[id],world.position(id),forward[id],velocity[id],
                                    upright[id],runtime.bots().metrics(id).destination(),runtime.bots().route(id),
                                    lastExternalTick[id]<0?-1:session.tick-1-lastExternalTick[id],lastExternalKind[id],
                                    cannonTraces[id]==null?null:cannonTraces[id].snapshot()));
                            cannonTraces[id]=null;
                        }
                        if (event.type()==GameEvent.Type.DAMAGE && event.kind().equals("out-of-bounds")) fatalRecoveries++;
                    }
                }
                List<DriverResult> drivers=new ArrayList<>();
                for (var state:session.vehicles) {
                    BotController.Metrics metrics=runtime.bots().metrics(state.id);
                    Vector3f position=world.position(state.id);
                    drivers.add(new DriverResult(state.id,state.hp,state.recoveries,metrics.aliveTicks(),
                            metrics.maximumUnplannedStationaryTicks(),state.damageDealt,state.eliminations,position.x,position.y,position.z));
                    totalAliveTicks+=metrics.aliveTicks(); totalRecoveries+=state.recoveries;
                    maximumStationaryTicks=Math.max(maximumStationaryTicks,metrics.maximumUnplannedStationaryTicks());
                    if (metrics.maximumUnplannedStationaryTicks()>600) failures.add("seed="+seed+" id="+state.id+" unplanned stationary >5 s: "+metrics.maximumUnplannedStationaryTicks());
                }
                if (session.outcome==MatchSession.Outcome.NONE) failures.add("seed="+seed+" did not finish within360game seconds");
                if (finished!=1) failures.add("seed="+seed+" emitted "+finished+" MatchFinished events");
                if (shots==0 || damage==0) failures.add("seed="+seed+" never produced actual combat damage");
                if (fatalRecoveries>0) failures.add("seed="+seed+" left the arena without a valid safe pose");
                if (maxProjectiles>64) failures.add("seed="+seed+" exceeded projectile limit");
                if (!runtime.tick(VehicleCommand.NONE,true).isEmpty()) failures.add("seed="+seed+" produced events after its final result");
                results.add(new BattleResult(seed,session.tick,session.outcome.name(),session.outcomeReason,session.seconds(),
                        shots,damage,pickups,maxProjectiles,firstEncounter,firstShot,firstDamage,fatalRecoveries,List.copyOf(drivers)));
                System.out.printf(Locale.ROOT,"AI BATCH seed=%d outcome=%s seconds=%.2f shots=%d damage=%.1f pickups=%d recoveries=%d maxStationaryTicks=%d%n",
                        seed,session.outcome,session.seconds(),shots,damage,pickups,
                        session.vehicles.stream().mapToInt(v->v.recoveries).sum(),drivers.stream().mapToLong(DriverResult::maximumStationaryTicks).max().orElse(0));
            }
        }
        double botMinutes=totalAliveTicks/(120.0*60),recoveryRate=totalRecoveries*10.0/botMinutes;
        if (recoveryRate>1) failures.add("Recovery limit exceeded: "+totalRecoveries+" recoveries / "+botMinutes+" bot-minutes = "+recoveryRate+" per10bot-minutes");
        BatchReport report=new BatchReport(2,"seed 0..9, five AI, actual native physics and full MatchRuntime, no render",
                System.getProperty("os.name"),System.getProperty("java.version"),(System.nanoTime()-started)/1e9,
                botMinutes,totalRecoveries,recoveryRate,maximumStationaryTicks,List.copyOf(results),List.copyOf(recoveryEpisodes),List.copyOf(failures));
        Path output=Path.of("build","reports","ai-batch.json");Files.createDirectories(output.getParent());
        Files.writeString(output,Configs.gson().toJson(report),StandardCharsets.UTF_8);
        assertTrue(failures.isEmpty(),String.join("\n",failures)+"\nSee "+output.toAbsolutePath());
    }
}
