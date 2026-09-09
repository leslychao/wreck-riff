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
    private record RecoveryEpisode(int seed,int vehicleId,long tick,Vector3f before,Vector3f after,
            Vector3f forward,Vector3f velocity,float upright,Vector3f goal,List<Integer> path,
            long ticksSinceExternalDamage,String lastExternalDamage) {}
    private record BatchReport(int schemaVersion,String scenario,String os,String javaVersion,double wallSeconds,
            double botMinutes,int recoveries,double recoveriesPerTenBotMinutes,long maximumStationaryTicks,
            List<BattleResult> battles,List<RecoveryEpisode> recoveryEpisodes,List<String> failures) {}

    @Test void tenSeededMatchesRespectNavigationAndRecoveryAcceptance() throws Exception {
        long started=System.nanoTime();
        ArenaDefinition arena=ArenaDefinition.load();VehicleRules vehicleRules=VehicleRules.load();
        var assets=new DesktopAssetManager(true);
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
                while (session.outcome==MatchSession.Outcome.NONE && session.tick<360L*120) {
                    Vector3f[] before=new Vector3f[5],forward=new Vector3f[5],velocity=new Vector3f[5];float[] upright=new float[5];
                    for (int id=0;id<5;id++) {
                        before[id]=world.position(id);forward[id]=world.forward(id);velocity[id]=world.velocity(id);
                        upright[id]=world.rotation(id).mult(Vector3f.UNIT_Y).y;
                    }
                    List<GameEvent> events=runtime.tick(VehicleCommand.NONE,true);
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
                        if (event.type()==GameEvent.Type.DAMAGE && event.kind().equals("recovery")) {
                            int id=event.subjectId();
                            recoveryEpisodes.add(new RecoveryEpisode(seed,id,session.tick-1,before[id],world.position(id),forward[id],velocity[id],
                                    upright[id],runtime.bots().metrics(id).destination(),runtime.bots().route(id),
                                    lastExternalTick[id]<0?-1:session.tick-1-lastExternalTick[id],lastExternalKind[id]));
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
        BatchReport report=new BatchReport(1,"seed 0..9, five AI, actual native physics and full MatchRuntime, no render",
                System.getProperty("os.name"),System.getProperty("java.version"),(System.nanoTime()-started)/1e9,
                botMinutes,totalRecoveries,recoveryRate,maximumStationaryTicks,List.copyOf(results),List.copyOf(recoveryEpisodes),List.copyOf(failures));
        Path output=Path.of("build","reports","ai-batch.json");Files.createDirectories(output.getParent());
        Files.writeString(output,Configs.gson().toJson(report),StandardCharsets.UTF_8);
        assertTrue(failures.isEmpty(),String.join("\n",failures)+"\nSee "+output.toAbsolutePath());
    }
}
