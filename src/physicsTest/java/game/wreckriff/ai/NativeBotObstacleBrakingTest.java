package game.wreckriff.ai;

import com.jme3.bullet.collision.shapes.BoxCollisionShape;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import game.wreckriff.arena.ArenaDefinition;
import game.wreckriff.combat.AbilityId;
import game.wreckriff.config.VehicleRules;
import game.wreckriff.input.VehicleCommand;
import game.wreckriff.simulation.MatchSession;
import game.wreckriff.simulation.PhysicsWorld;
import game.wreckriff.vehicle.VehicleController;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class NativeBotObstacleBrakingTest {
    @ParameterizedTest @ValueSource(floats={28,34,46})
    void realAiBrakeCommandsStopBeforeARealWallAtCruiseTopAndTurboSpeed(float initialSpeed) {
        try(var fixture=new Fixture(initialSpeed)) {
            float firstBrakeDistance=0;boolean stopped=false;
            for(int tick=1;tick<=600;tick++) {
                fixture.session.tick=tick;
                var command=fixture.bots.commands(fixture.world).get(0);
                if(command.brakeReverse()>0&&firstBrakeDistance==0)firstBrakeDistance=fixture.wallNear-fixture.world.position(0).z;
                // Isolate the longitudinal stopping promise: retain AI gas/brake/turbo,
                // but do not let avoidance steering pass this wide wall sideways.
                fixture.driver.drive(new VehicleCommand(command.throttle(),command.brakeReverse(),0,false,command.turbo(),
                        false,false,null,0,false,false,AbilityId.NONE));
                fixture.world.step();
                assertTrue(fixture.noseGap()>.35f,"Hull reached the wall at "+initialSpeed+" m/s; gap="+fixture.noseGap());
                if(fixture.world.velocity(0).length()<.5f){stopped=true;break;}
            }
            assertTrue(firstBrakeDistance>14,"Braking began inside the obsolete 14 m maximum probe: "+firstBrakeDistance);
            assertTrue(stopped,"Native AI-driven brakes did not stop the car");
        }
    }

    @ParameterizedTest @ValueSource(floats={28,46})
    void unmodifiedAiCommandsAvoidAFrontalWallWithoutHullContact(float initialSpeed) {
        try(var fixture=new Fixture(initialSpeed)) {
            for(int tick=1;tick<=360;tick++) {
                fixture.session.tick=tick;
                fixture.driver.drive(fixture.bots.commands(fixture.world).get(0).withoutAttacks());fixture.world.step();
                assertTrue(fixture.noseGap()>.15f,"Full AI steering and braking hit the frontal wall");
            }
        }
    }

    private static final class Fixture implements AutoCloseable {
        final VehicleRules rules=VehicleRules.load();
        final MatchSession session=new MatchSession(42,360);
        final PhysicsWorld world=new PhysicsWorld(rules);
        final VehicleController driver;
        final BotController bots;
        final float wallNear=10;
        Fixture(float speed) {
            for(int id=2;id<5;id++)session.vehicle(id).hp=0;
            world.addStatic("floor",new BoxCollisionShape(new Vector3f(200,.5f,200)),new Vector3f(0,-.5f,0),new Quaternion());
            world.addVehicle(0,new Vector3f(0,.85f,-55),new Quaternion());
            world.addVehicle(1,new Vector3f(0,.85f,15),new Quaternion());
            for(int tick=0;tick<240;tick++)world.step();
            driver=new VehicleController(world,session.vehicle(0),rules);
            bots=new BotController(session,ArenaDefinition.load(),AiRules.load());bots.commands(world);
            // The obstacle appears after a clear chase was chosen, so stopping must
            // come from the 120 Hz driver probes before the next perception decision.
            world.addStatic("wall",new BoxCollisionShape(new Vector3f(100,5,.5f)),new Vector3f(0,5,wallNear+.5f),new Quaternion());
            world.vehicle(0).setLinearVelocity(new Vector3f(0,0,speed));
        }
        float noseGap() {
            var bounds=world.profile(0).hullBounds();float front=Float.NEGATIVE_INFINITY;
            for(float x:new float[]{bounds.minX(),bounds.maxX()})for(float y:new float[]{bounds.minY(),bounds.maxY()})for(float z:new float[]{bounds.minZ(),bounds.maxZ()})
                front=Math.max(front,world.position(0).add(world.rotation(0).mult(new Vector3f(x,y,z))).z);
            return wallNear-front;
        }
        public void close(){world.close();}
    }
}
