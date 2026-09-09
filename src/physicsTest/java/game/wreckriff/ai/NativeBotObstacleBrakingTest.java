package game.wreckriff.ai;

import com.jme3.bullet.collision.shapes.BoxCollisionShape;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import game.wreckriff.arena.ArenaDefinition;
import game.wreckriff.combat.AbilityId;
import game.wreckriff.config.VehicleProfile;
import game.wreckriff.config.VehicleRules;
import game.wreckriff.input.VehicleCommand;
import game.wreckriff.simulation.MatchSession;
import game.wreckriff.simulation.PhysicsWorld;
import game.wreckriff.vehicle.VehicleController;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class NativeBotObstacleBrakingTest {
    @Test void fastestPrefectProfileStopsAtTheSightLimitUsingActualPartialAiBraking() {
        var rules=VehicleRules.load();var profile=VehicleProfile.boss("boss_prefect",rules);
        float initialSpeed=rules.turboSpeed()*profile.turboMultiplier();
        assertEquals(55.2f,initialSpeed,.001f);
        try(var fixture=new Fixture(initialSpeed,profile.id(),AiRules.load().sightRange(),true)) {
            assertEquals(75,fixture.wallNear-fixture.world.position(0).z,.01f);
            float minimumGap=Float.POSITIVE_INFINITY;int partialBrakeSteps=0;boolean stopped=false;
            for(int tick=1;tick<=900;tick++) {
                fixture.session.tick=tick;
                var command=fixture.bots.commands(fixture.world).get(0);
                if(tick==1) {
                    assertEquals(1,command.brakeReverse(),"The capped probe must request full braking immediately");
                    assertEquals(0,command.throttle());assertFalse(command.turbo());
                }
                if(command.brakeReverse()>0&&command.brakeReverse()<1)partialBrakeSteps++;
                // Keep the straight route and all actual AI brake magnitudes. Turning away
                // would not prove that a 75 m observation supports this profile's stopping distance.
                fixture.driver.drive(new VehicleCommand(command.throttle(),command.brakeReverse(),0,false,command.turbo(),
                        false,false,null,0,false,false,AbilityId.NONE));
                fixture.world.step();minimumGap=Math.min(minimumGap,fixture.noseGap());
                assertTrue(minimumGap>.35f,"Prefect hull reached the wall with partial AI braking: "+minimumGap);
                if(fixture.world.velocity(0).length()<.5f){stopped=true;break;}
            }
            assertTrue(stopped,"Prefect never completed its native stop inside the 75 m observation");
            assertTrue(partialBrakeSteps>0,"The test must exercise partial AI braking, not just constant full braking");
            System.out.printf(java.util.Locale.ROOT,"AI_SIGHT_LIMIT profile=%s speed=%.3f minimumHullGap=%.6f partialBrakeSteps=%d%n",
                    profile.id(),initialSpeed,minimumGap,partialBrakeSteps);
        }
    }

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
        final float wallNear;
        Fixture(float speed) {this(speed,"rivet",65,false);}
        Fixture(float speed,String profileId,float wallDistance,boolean straightRoute) {
            wallNear=-55+wallDistance;
            for(int id=2;id<5;id++)session.vehicle(id).hp=0;
            world.addStatic("floor",new BoxCollisionShape(new Vector3f(200,.5f,200)),new Vector3f(0,-.5f,0),new Quaternion());
            var profile=profileId.equals("rivet")?VehicleProfile.rivet(rules):VehicleProfile.boss(profileId,rules);
            world.addVehicle(0,new Vector3f(0,profile.roadOffset()+.5f,-55),new Quaternion(),profile);
            world.addVehicle(1,new Vector3f(0,.85f,15),new Quaternion());
            if(straightRoute){session.vehicle(0).hp=60;session.vehicle(1).hp=0;world.removeVehicle(1);}
            for(int tick=0;tick<240;tick++)world.step();
            driver=new VehicleController(world,session.vehicle(0),rules);
            bots=new BotController(session,straightRoute?straightRepairRoute():ArenaDefinition.load(),AiRules.load());bots.commands(world);
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

    private static ArenaDefinition straightRepairRoute() {
        var base=ArenaDefinition.load();
        var floor=new ArenaDefinition.BoxPart("floor",new ArenaDefinition.Vec3(0,-.5f,0),new ArenaDefinition.Vec3(400,1,400),"concrete",true);
        var target=new ArenaDefinition.Pickup("far-repair",ArenaDefinition.PickupType.REPAIR,new ArenaDefinition.Vec3(0,0,100),120);
        var nodes=new ArrayList<ArenaDefinition.NavNode>();var edges=new ArrayList<ArenaDefinition.NavEdge>();
        for(int id=0;id<=8;id++) {
            nodes.add(new ArenaDefinition.NavNode(id,new ArenaDefinition.Vec3(0,0,-55+155*id/8f),"road-ground"));
            if(id>0)edges.add(new ArenaDefinition.NavEdge("straight-"+id,id-1,id,20,10,ArenaDefinition.Transition.ROAD,"",true));
        }
        return new ArenaDefinition(base.schemaVersion(),"braking-corridor",base.metadata(),new ArenaDefinition.Bounds(-200,200,-200,200,-8),
                List.of(floor),List.of(),base.spawns(),List.of(target),List.of(),nodes,edges,
                List.of(new ArenaDefinition.Surface("road-ground","floor",0,1)),List.of(),List.of(),List.of(),List.of(),List.of(),List.of());
    }
}
