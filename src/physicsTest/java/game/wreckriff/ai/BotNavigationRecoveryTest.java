package game.wreckriff.ai;

import com.jme3.math.FastMath;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import game.wreckriff.arena.*;
import game.wreckriff.config.VehicleRules;
import game.wreckriff.simulation.*;
import game.wreckriff.vehicle.VehicleController;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Replays upright navigation poses from the ten-seed battle without combat or transform correction. */
class BotNavigationRecoveryTest {
    @Test void opponentsCanUseTheGarageFloorForASupportedPassingCorridor() {
        var definition=ArenaDefinition.load();var session=new MatchSession(1,360);var rules=VehicleRules.load();
        for(int id=2;id<5;id++)session.vehicle(id).hp=0;
        try(var world=new PhysicsWorld(rules)) {
            var content=new ArenaFactory(NativeArenaAssets.MANAGER).build(definition);
            for(var body:content.bodies())world.addStatic(body.shape(),body.position(),body.rotation());
            world.addVehicle(0,new Vector3f(-54,.85f,22),new Quaternion().fromAngleAxis(FastMath.HALF_PI,Vector3f.UNIT_Y));
            world.addVehicle(1,new Vector3f(-34,.85f,22),new Quaternion().fromAngleAxis(-FastMath.HALF_PI,Vector3f.UNIT_Y));
            for(int tick=0;tick<240;tick++)world.step();
            var drivers=List.of(new VehicleController(world,session.vehicle(0),rules),new VehicleController(world,session.vehicle(1),rules));
            var bots=new BotController(session,definition,AiRules.load());
            boolean passed=false;
            for(int tick=0;tick<1800;tick++) {
                session.tick=tick;var commands=bots.commands(world);
                for(int id=0;id<2;id++) {
                    var command=commands.get(id).withoutAttacks();var recovery=drivers.get(id).prepare(command,tick);
                    assertFalse(recovery.recovered()||recovery.fatal(),"Garage traffic used recovery");drivers.get(id).drive(command);
                }
                world.step();for(int id=0;id<2;id++)drivers.get(id).recordSafePose(tick);
                passed|=world.position(0).x>world.position(1).x && world.position(0).distance(world.position(1))>12;
            }
            assertTrue(passed,"Cars never passed on the open garage floor: "+world.position(0)+" / "+world.position(1));
        }
    }
    @ParameterizedTest
    @CsvSource({
            "-50.17,0.85,14.07,-167.77,repair-deck",
            "-46.88,0.85,27.80,-166.22,repair-deck",
            "-54.60,0.85,28.91,135.92,repair-deck",
            "30.29,6.85,-0.91,-148.84,repair-garage",
            "64.04,6.85,0.16,127.56,repair-garage"
    })
    void uprightBattlePoseCanFollowItsRouteWithoutRecovery(float x,float y,float z,float yaw,String pickupId) {
        ArenaDefinition definition=ArenaDefinition.load();
        var target=definition.pickups().stream().filter(p->p.id().equals(pickupId)).findFirst().orElseThrow();
        var fixture=new ArenaDefinition(definition.schemaVersion(),definition.id(),definition.bounds(),definition.boxes(),
                definition.ramps(),definition.spawns(),List.of(target),definition.hazard(),definition.nodes(),definition.edges());
        var session=new MatchSession(42,360);
        session.vehicle(0).hp=60;
        for(int id=1;id<5;id++)session.vehicle(id).hp=0;
        var rules=VehicleRules.load();
        try(var world=new PhysicsWorld(rules)) {
            var content=new ArenaFactory(NativeArenaAssets.MANAGER).build(definition);
            for(var body:content.bodies())world.addStatic(body.shape(),body.position(),body.rotation());
            world.addVehicle(0,new Vector3f(x,y,z),new Quaternion().fromAngleAxis(yaw*FastMath.DEG_TO_RAD,Vector3f.UNIT_Y));
            world.vehicle(0).brake(rules.brakeForce());
            for(int tick=0;tick<240;tick++)world.step();
            world.vehicle(0).brake(0);
            var driver=new VehicleController(world,session.vehicle(0),rules);
            var systems=new ArenaSystems(session,fixture);
            var bots=new BotController(session,fixture,AiRules.load(),systems::activePickups);
            int tick=0;
            while(session.vehicle(0).hp==60 && tick<7200) {
                session.tick=tick;
                var command=bots.commands(world).get(0).withoutAttacks();
                var recovery=driver.prepare(command,tick);
                assertFalse(recovery.recovered() || recovery.fatal(),"Upright route needed recovery at "+world.position(0));
                driver.drive(command);world.step();driver.recordSafePose(tick);systems.collectPickups(world);
                if(tick%600==0)System.out.printf("Upright route start=%.2f,%.2f,%.2f t=%.1f pos=%s command=%s route=%s%n",
                        x,y,z,tick/120f,world.position(0),command,bots.route(0));
                tick++;
            }
            assertTrue(session.vehicle(0).hp>60,"Route never reached repair: "+world.position(0));
        }
    }
}
