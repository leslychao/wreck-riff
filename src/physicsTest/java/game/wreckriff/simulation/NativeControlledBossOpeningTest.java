package game.wreckriff.simulation;

import game.wreckriff.arena.*;
import game.wreckriff.combat.*;
import game.wreckriff.config.*;
import game.wreckriff.input.VehicleCommand;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** A close scripted opening must exercise the bound ballistic weapon against a real supported hull. */
@org.junit.jupiter.api.extension.ExtendWith(game.wreckriff.arena.NativeArenaAssets.class)
class NativeControlledBossOpeningTest {
    @Test void foremanControlledFixturePlacesRivetInsideAnActualBoundWeaponTrajectory() {
        var rules=VehicleRules.load();var combat=Configs.load("combat",CombatRules.class);
        var arena=ArenaRegistry.load().definition("construction_17");var boss=arena.bosses().getFirst();
        var session=new MatchSession(42,arena,MatchSession.Mode.BOSS_DUEL,combat);
        try(var world=new PhysicsWorld(rules)) {
            var content=new ArenaFactory(NativeArenaAssets.MANAGER).build(arena);
            for(var body:content.bodies())world.addStatic(body);
            var player=VehicleProfile.rivet(rules);var start=NativeEncounterBalanceProbeTest.controlledStart(arena,world,player);
            world.addVehicle(0,start.position(),start.rotation(),player);
            try(var runtime=new MatchRuntime(session,world,arena,content.graph(),rules)) {
                for(int tick=0;tick<360;tick++)world.step();runtime.skipIntro();
                assertTrue(session.vehicles.stream().flatMap(v->v.weapons().stream()).allMatch(slot->slot.ammo==0));
                float bossDamage=0,cannonDamage=0,minimumCannonClearance=Float.POSITIVE_INFINITY;int boundShots=0;
                String opening="boss did not spawn";boolean openingArmed=false;
                for(int tick=0;tick<15*120&&session.outcome==MatchSession.Outcome.NONE;tick++) {
                    if(!openingArmed&&session.phase==MatchSession.Phase.BOSS_COMBAT) {
                        NativeEncounterBalanceProbeTest.armControlledOpening(session);openingArmed=true;
                    }
                    var commands=new HashMap<Integer,VehicleCommand>();commands.put(0,VehicleCommand.NONE);
                    if(session.bossParticipantId>=0)commands.put(session.bossParticipantId,
                            NativeEncounterBalanceProbeTest.stationaryFire(tick%240<120?boss.primary():boss.secondary()));
                    var events=runtime.tick(commands,true);
                    if(session.bossParticipantId>=0&&session.phase==MatchSession.Phase.BOSS_COMBAT&&opening.equals("boss did not spawn")) {
                        int id=session.bossParticipantId;
                        opening="player="+world.position(0)+", boss="+world.position(id)+", forward="+world.forward(id)
                                +", muzzle="+world.muzzle(id)+", muzzle LOS="+world.ray(world.muzzle(id),world.position(0).add(0,.5f,0),id);
                        assertEquals(4,world.supportedWheelContacts(0));assertEquals(4,world.supportedWheelContacts(id));
                    }
                    for(var event:events) {
                        if(event.sourceId()==session.bossParticipantId&&event.type()==GameEvent.Type.SHOT
                                &&Set.of(boss.primary().id(),boss.secondary().id()).contains(event.kind()))boundShots++;
                        if(event.sourceId()==session.bossParticipantId&&event.subjectId()==0&&event.type()==GameEvent.Type.DAMAGE
                                &&Set.of(boss.primary().id(),boss.secondary().id(),"machine-gun").contains(event.kind()))bossDamage+=event.value();
                        if(event.sourceId()==session.bossParticipantId&&event.subjectId()==0&&event.type()==GameEvent.Type.DAMAGE
                                &&event.kind().equals("cannon"))cannonDamage+=event.value();
                    }
                    for(var projectile:runtime.combat().projectiles())if(projectile.ownerId()==session.bossParticipantId&&projectile.kind().equals("cannon"))
                        minimumCannonClearance=Math.min(minimumCannonClearance,world.distanceToHull(0,projectile.position()));
                }
                System.out.println("CONTROLLED_OPENING "+opening+", boundShots="+boundShots+", damage="+bossDamage+", cannonDamage="+cannonDamage+", closestCannon="+minimumCannonClearance);
                assertTrue(boundShots>0,"Boss must fire its actual bound arsenal");
                assertTrue(bossDamage>0,"Controlled opening misses the real hull: "+opening+", closest cannon="+minimumCannonClearance);
                assertTrue(cannonDamage>0,"The configured Cannon trajectory must cause actual DAMAGE, not just approach the hull");
                assertEquals(0,world.teleportGeneration(0));assertEquals(0,world.teleportGeneration(session.bossParticipantId));
            }
        }
    }
}
