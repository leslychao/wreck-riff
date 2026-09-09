package game.wreckriff.simulation;

import game.wreckriff.combat.AbilityId;
import game.wreckriff.combat.WeaponType;
import game.wreckriff.arena.*;

import com.jme3.bullet.collision.shapes.BoxCollisionShape;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import game.wreckriff.config.VehicleRules;
import game.wreckriff.input.VehicleCommand;
import game.wreckriff.vehicle.VehicleController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class NativeRecoveryTest {
    private static final VehicleCommand RECOVER = new VehicleCommand(0, 0, 0, false, false, false, false, null, 0, false, true,AbilityId.NONE);

    @ParameterizedTest @ValueSource(strings={"clear","closed","teleported","manual"})
    void cannonArcCanRetreatOverAWallOnlyWhileItsRecordedNativePathRemainsValid(String route) {
        VehicleRules rules=VehicleRules.load();MatchSession session=new MatchSession(42,360);
        try(PhysicsWorld world=new PhysicsWorld(rules)) {
            world.addStatic(new BoxCollisionShape(new Vector3f(100,.5f,100)),new Vector3f(0,-.5f,0),new Quaternion());
            world.addStatic(new BoxCollisionShape(new Vector3f(.25f,.75f,30)),new Vector3f(79,.75f,0),new Quaternion());
            world.addVehicle(0,new Vector3f(69,1,0),new Quaternion().fromAngleAxis((float)Math.PI/2,Vector3f.UNIT_Y));
            world.addVehicle(1,new Vector3f(74,1,0),new Quaternion());
            for(int id=2;id<5;id++)world.addVehicle(id,new Vector3f(-40,1,-40+id*10),new Quaternion());
            for(int i=0;i<240;i++)world.step();
            ArenaDefinition arena=ArenaDefinition.load();
            try(MatchRuntime runtime=new MatchRuntime(session,world,arena,new NavGraph(arena),rules)) {
                for(int i=0;i<120;i++)runtime.tick(Map.of(),false);
                VehicleCommand cannon=new VehicleCommand(0,0,0,false,false,false,true,WeaponType.CANNON,0,false,false,AbilityId.NONE);
                runtime.tick(Map.of(0,cannon),false);
                boolean cannonHit=false,crossed=false,directBlocked=false,intervened=false,recovered=false,fatal=false;Vector3f emergencyPose=null;
                for(int i=0;i<360;i++) {
                    Vector3f before=world.position(1);
                    if(before.x>81&&!intervened) {
                        crossed=true;intervened=true;
                        if(route.equals("closed"))world.addStatic(new BoxCollisionShape(new Vector3f(.25f,6,30)),new Vector3f(79,6,0),new Quaternion());
                        if(route.equals("teleported")) {
                            Vector3f linear=world.velocity(1),angular=world.vehicle(1).getAngularVelocity();
                            world.teleport(1,before,world.rotation(1));world.vehicle(1).setLinearVelocity(linear);world.vehicle(1).setAngularVelocity(angular);
                        }
                        if(route.equals("manual")) {world.vehicle(1).setLinearVelocity(Vector3f.ZERO);world.vehicle(1).setAngularVelocity(Vector3f.ZERO);}
                    }
                    if(before.x>82||(route.equals("manual")&&crossed)) {emergencyPose=before;directBlocked|=world.staticSweep(before.add(0,.5f,0),new Vector3f(74,.929f,0),.2f)!=null;}
                    Map<Integer,VehicleCommand> commands=route.equals("manual")&&crossed?Map.of(1,RECOVER):Map.of();
                    for(var event:runtime.tick(commands,false)) {
                        cannonHit|=event.type()==GameEvent.Type.EXPLOSION&&event.kind().equals("cannon")&&event.subjectId()==1;
                        if(event.type()==GameEvent.Type.DAMAGE&&event.subjectId()==1) {
                            recovered|=event.kind().equals("recovery");fatal|=event.kind().equals("out-of-bounds");
                        }
                    }
                    if(recovered||fatal)break;
                }
                assertTrue(cannonHit,"The scenario requires an accepted direct Cannon hit");
                assertTrue(crossed,"Real Cannon must carry the chassis over the wall; final="+world.position(1));
                assertTrue(directBlocked,"The direct recovery segment must be blocked by the wall; emergency="+emergencyPose+" final="+world.position(1));
                assertEquals(route.equals("clear"),recovered,"Only continuous, clear native history authorizes retreat");
                assertEquals(route.equals("closed")||route.equals("teleported"),fatal);
                if(recovered) {assertEquals(1,session.vehicle(1).recoveries);assertTrue(world.position(1).x<76);assertEquals(4,world.wheelContacts(1));}
            }
        }
    }

    @Test void recoveryRejectsNewerTwoWheelEdgePoseAndUsesFullySupportedHistory() {
        VehicleRules rules = VehicleRules.load();
        try (PhysicsWorld world = new PhysicsWorld(rules)) {
            // Real native platform: x=-40..0, with no support beyond the right edge.
            world.addStatic(new BoxCollisionShape(new Vector3f(20, 0.5f, 30)), new Vector3f(-20, -0.5f, 0), new Quaternion());
            world.addVehicle(0, new Vector3f(-15, 1, 0), new Quaternion());
            for (int tick = 0; tick < 240; tick++) world.step();
            assertEquals(4, world.wheelContacts(0));
            var state = new VehicleState(0, "Recovery test", true,game.wreckriff.config.Configs.load("combat",game.wreckriff.combat.CombatRules.class));
            var driver = new VehicleController(world, state, rules);

            // This recorded pose differs from the constructor's initial fallback.
            Vector3f supported = new Vector3f(-10, world.position(0).y, 0);
            world.teleport(0, supported, new Quaternion());
            assertEquals(4, world.wheelContacts(0));
            assertTrue(world.freePose(0, supported, world.rotation(0)));
            driver.recordSafePose(240);

            Vector3f edge = new Vector3f(-0.3f, supported.y, 0);
            world.teleport(0, edge, new Quaternion());
            assertEquals(2, world.wheelContacts(0), "Native fixture must have exactly two unsupported wheels");
            assertTrue(world.freePose(0, edge, world.rotation(0)), "The failure is missing support, not an overlapping chassis");
            assertEquals(1, world.rotation(0).mult(Vector3f.UNIT_Y).y, 0.0001f);
            var ground = world.ray(edge.add(0, 0.4f, 0), edge.add(0, -2, 0), 0);
            assertNotNull(ground, "The old centre-only recovery ray still sees ground below this unsafe edge pose");
            assertEquals(-1, ground.vehicleId());
            assertTrue(ground.normal().y > 0.99f);
            driver.recordSafePose(252);

            // Request recovery on solid ground after both historical poses are old enough.
            // No newer positions are recorded while holding the recovery button.
            world.teleport(0, new Vector3f(-5, supported.y, 0), new Quaternion());
            assertEquals(4, world.wheelContacts(0));
            VehicleController.Recovery recovery = VehicleController.Recovery.NONE;
            int requestTicks = Math.round(rules.recoveryHold() * MatchSession.TICKS_PER_SECOND);
            long tick = 600;
            for (int held = 0; held < requestTicks; held++, tick++) {
                assertTrue(world.velocity(0).length() < 2, "Manual recovery speed condition remains satisfied");
                recovery = driver.prepare(RECOVER, tick);
                if (held < requestTicks - 1) {
                    assertEquals(VehicleController.Recovery.NONE, recovery, "Recovery must wait for the complete hold interval");
                    world.step();
                }
            }
            assertTrue(recovery.recovered(), "A fully supported historical pose remains available");
            assertFalse(recovery.fatal());
            assertEquals(rules.recoveryCost(), recovery.cost());
            assertEquals(1, state.recoveries);
            assertTrue(world.position(0).distance(supported) < 0.001f, "The newer two-wheel edge pose must not replace fully supported history");
            assertTrue(world.position(0).distance(edge) > 5);
            assertEquals(4, world.wheelContacts(0), "The restored vehicle must have native support under all four wheels");
            assertEquals(VehicleController.Recovery.NONE, driver.prepare(VehicleCommand.NONE, tick));
        }
    }
}
