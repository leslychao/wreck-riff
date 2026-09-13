package game.wreckriff.ai;

import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import game.wreckriff.arena.ArenaDefinition;
import game.wreckriff.arena.TestWorld;
import game.wreckriff.config.VehicleProfile;
import game.wreckriff.config.VehicleRules;
import game.wreckriff.input.VehicleCommand;
import game.wreckriff.simulation.*;
import java.util.List;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Driver decisions against observed geometry; authored native routes verify the resulting manoeuvre. */
class BotRouteTurnTest {
    @Test void anOpenSupportedApronTurnsForwardForEveryChassisInsteadOfFollowingTheWholeRouteInReverse() {
        for(String profile:List.of("rivet","grinder","spark","boss_foreman","boss_prefect","boss_emcee")) {
            var fixture=new Fixture(profile);
            var command=fixture.next();
            assertTrue(command.throttle()>0,profile+" must start a forward turn");
            assertEquals(0,command.brakeReverse(),profile);
            assertTrue(Math.abs(command.steer())>.5f,profile+" must actually turn");
            assertFalse(command.recover());
        }
    }

    @Test void aRealFrontalWallKeepsTheClearReverseCorridor() {
        var fixture=new Fixture("rivet");fixture.world.frontWall=true;
        var command=fixture.next();
        assertEquals(0,command.throttle());assertTrue(command.brakeReverse()>0);
    }
    @Test void aCloseFinalPickupInsideTheGrinderTurningCircleKeepsTheShortReverseApproach() {
        var fixture=new Fixture("grinder");fixture.world.delegate.positions[0].z=-48;
        var command=fixture.next();
        assertTrue(fixture.bots.route(0).isEmpty());
        assertEquals(0,command.throttle());assertTrue(command.brakeReverse()>0);
        assertFalse(command.recover());
    }

    @Test void anUnsupportedSideOrRampCannotAuthorizeAnOpenFloorTurningCircle() {
        for(boolean ramp:new boolean[]{false,true}) {
            var fixture=new Fixture("rivet");fixture.world.narrowSupport=!ramp;fixture.world.ramp=ramp;
            var command=fixture.next();
            assertEquals(0,command.throttle());assertTrue(command.brakeReverse()>0);
        }
    }

    @Test void establishedBackingEndsWhenTheCarReachesAnOpenSupportedApron() {
        var fixture=new Fixture("rivet");fixture.world.frontWall=true;
        assertTrue(fixture.next().brakeReverse()>0);
        fixture.world.frontWall=false;fixture.world.delegate.velocities[0]=new Vector3f(0,0,-4);
        fixture.session.tick+=AiRules.load().decisionTicks();
        var command=fixture.next();
        assertTrue(command.throttle()>0,"Ordinary forward command brakes then changes direction through VehicleController");
        assertEquals(0,command.brakeReverse());assertTrue(Math.abs(command.steer())>.5f);
    }

    @Test void genuineProgressAroundTheTurnCountsButAStationaryCarStillTriggersStuckRecovery() {
        for(boolean moving:new boolean[]{true,false}) {
            var fixture=new Fixture("rivet");fixture.next();
            for(int tick=1;tick<=200;tick++) {
                if(moving) {
                    float angle=tick*(float)Math.PI/600;
                    fixture.world.delegate.rotations[0]=new Quaternion().fromAngleAxis(-angle,Vector3f.UNIT_Y);
                    fixture.world.delegate.positions[0].set(-4*(1-(float)Math.cos(angle)),fixture.world.profile.roadOffset(),-45+4*(float)Math.sin(angle));
                    fixture.world.delegate.velocities[0]=fixture.world.forward(0).mult(4*(float)Math.PI/5);
                }
                fixture.next();
            }
            assertEquals(moving?0:1,fixture.bots.metrics(0).reverseAttempts(),"Only measured movement may avoid stuck detection");
        }
    }

    @Test void reachingTheCapturedHeadingReconnectsTheRouteEvenWhenTheOldLocalTargetRemainsInsideTheCircle() {
        var fixture=new Fixture("boss_foreman");
        fixture.next();assertTrue(fixture.bots.route(0).isEmpty(),"Initial nearby socket uses the direct final connector");
        // Sample a wide half-circle: the local point stays inside the circle
        // and its relative angle remains >1.2 rad even at the final heading.
        for(int degrees:new int[]{60,120,180}) {
            float angle=degrees*(float)Math.PI/180;
            fixture.world.delegate.positions[0].set(-19*(1-(float)Math.cos(angle)),fixture.world.profile.roadOffset(),-45+19*(float)Math.sin(angle));
            fixture.world.delegate.rotations[0]=new Quaternion().fromAngleAxis(-angle,Vector3f.UNIT_Y);
            fixture.world.delegate.velocities[0]=fixture.world.forward(0).mult(4);
            fixture.next();
        }
        assertFalse(fixture.bots.route(0).isEmpty(),"Completion must reconnect from the real pose instead of retaining the old inner connector");
        assertEquals(0,fixture.bots.metrics(0).reverseAttempts());
    }

    @Test void anAdjoiningRoadMeshCanUseTheNearbyJunctionInsteadOfTheFarNodeBehindTheCar() {
        var base=ArenaDefinition.load();var surfaces=new ArrayList<>(base.surfaces());
        var ground=surfaces.stream().filter(s->s.id().equals("road-ground")).findFirst().orElseThrow();
        surfaces.add(new ArenaDefinition.Surface("seam-east",ground.geometryId(),ground.level(),ground.grip()));
        var nodes=List.of(new ArenaDefinition.NavNode(0,new ArenaDefinition.Vec3(0,0,-45),ground.id()),
                new ArenaDefinition.NavNode(1,new ArenaDefinition.Vec3(-30,0,-45),ground.id()),
                new ArenaDefinition.NavNode(2,new ArenaDefinition.Vec3(40,0,-45),"seam-east"));
        var edges=List.of(new ArenaDefinition.NavEdge("west",0,1,20,12,ArenaDefinition.Transition.ROAD,"",true),
                new ArenaDefinition.NavEdge("east",0,2,20,12,ArenaDefinition.Transition.ROAD,"",true));
        var pickups=List.of(new ArenaDefinition.Pickup("west-supply",ArenaDefinition.PickupType.POWER_AMMO,new ArenaDefinition.Vec3(-30,0,-45),3000));
        var arena=new ArenaDefinition(base.schemaVersion(),base.id(),base.metadata(),base.bounds(),base.boxes(),base.ramps(),base.spawns(),
                pickups,base.hazards(),nodes,edges,surfaces,base.launchPads(),base.drops(),base.destructibles(),base.secrets(),base.barriers(),base.bosses(),
                base.layoutRevision(),base.meshes(),base.districts(),base.roads());
        var session=new MatchSession(42,360);for(int id=1;id<session.vehicles.size();id++)session.vehicle(id).hp=0;
        var world=new TurnWorld("rivet");world.surfaceId="seam-east";world.delegate.positions[0].x=3;
        var bots=new BotController(session,arena,AiRules.load());bots.commands(world);
        assertEquals(List.of(0,1),bots.route(0),"A visible, supported 3 m seam must not force the 37 m detour to the current mesh's node");
    }

    @Test void aForwardContinuationMayPassTwoInnerRoadSamplesOnlyWhenItsFullHullCorridorIsClear() {
        var base=ArenaDefinition.load();
        var nodes=List.of(new ArenaDefinition.NavNode(0,new ArenaDefinition.Vec3(0,0,-45),"road-ground"),
                new ArenaDefinition.NavNode(1,new ArenaDefinition.Vec3(-5,0,-45),"road-ground"),
                new ArenaDefinition.NavNode(2,new ArenaDefinition.Vec3(17,0,-45),"road-ground"));
        var edges=List.of(new ArenaDefinition.NavEdge("inner",0,1,20,12,ArenaDefinition.Transition.ROAD,"",true),
                new ArenaDefinition.NavEdge("onward",1,2,20,12,ArenaDefinition.Transition.ROAD,"",true));
        var pickups=List.of(new ArenaDefinition.Pickup("onward-supply",ArenaDefinition.PickupType.POWER_AMMO,new ArenaDefinition.Vec3(17,0,-45),3000));
        var arena=new ArenaDefinition(base.schemaVersion(),base.id(),base.metadata(),base.bounds(),base.boxes(),base.ramps(),base.spawns(),
                pickups,base.hazards(),nodes,edges,base.surfaces(),base.launchPads(),base.drops(),base.destructibles(),base.secrets(),base.barriers(),base.bosses(),
                base.layoutRevision(),base.meshes(),base.districts(),base.roads());
        for(boolean blocked:new boolean[]{false,true}) {
            var session=new MatchSession(42,360);for(int id=1;id<session.vehicles.size();id++)session.vehicle(id).hp=0;
            var world=new TurnWorld("rivet");world.delegate.positions[0].x=3;world.blockForwardConnector=blocked;
            world.delegate.rotations[0]=new Quaternion().fromAngleAxis((float)Math.PI/2,Vector3f.UNIT_Y);
            var bots=new BotController(session,arena,AiRules.load());var command=bots.commands(world).get(0);
            boolean followsOpenForwardRoad=command.throttle()>0&&Math.abs(command.steer())<.1f;
            assertEquals(!blocked,followsOpenForwardRoad,"An observed wall must prevent the direct continuation");
        }
    }

    private static final class Fixture {
        final MatchSession session=new MatchSession(42,360);
        final TurnWorld world;
        final BotController bots;
        Fixture(String profile) {
            world=new TurnWorld(profile);
            for(int id=1;id<session.vehicles.size();id++)session.vehicle(id).hp=0;
            var arena=ArenaDefinition.load().withPickups(List.of(new ArenaDefinition.Pickup("behind-route",
                    ArenaDefinition.PickupType.POWER_AMMO,new ArenaDefinition.Vec3(0,0,-53),3000)));
            bots=new BotController(session,arena,AiRules.load());
        }
        VehicleCommand next() {session.tick++;return bots.commands(world).get(0);}
    }

    private static final class TurnWorld implements WorldQuery {
        final TestWorld delegate=new TestWorld();final VehicleProfile profile;
        boolean frontWall,narrowSupport,ramp,blockForwardConnector;String surfaceId="road-ground";
        TurnWorld(String id) {
            var rules=VehicleRules.load();profile=id.startsWith("boss_")?VehicleProfile.boss(id,rules):VehicleProfile.player(id,rules);
            delegate.positions[0]=new Vector3f(0,profile.roadOffset(),-45);
        }
        public Vector3f position(int id){return delegate.position(id);}
        public Vector3f velocity(int id){return delegate.velocity(id);}
        public Quaternion rotation(int id){return delegate.rotation(id);}
        public VehicleProfile profile(int id){return profile;}
        public RoadContext roadContext(int id){return new RoadContext(surfaceId,0,1,ramp?RoadContext.Motion.RAMP:RoadContext.Motion.ROAD,"","",0);}
        public boolean grounded(int id){return true;}
        public float mass(int id){return profile.mass();}
        public Hit ray(Vector3f from,Vector3f to,int id){return delegate.ray(from,to,id);}
        public Support support(Vector3f point,float depth) {
            if(narrowSupport&&Math.abs(point.x)>profile.width()/2+1)return null;
            return new Support(0,new Vector3f(point.x,0,point.z),Vector3f.UNIT_Y);
        }
        public Hit sweep(Vector3f from,Vector3f to,float radius,int ignored,float start,float end) {
            if(blockForwardConnector&&from.x<12-radius&&to.x>=12-radius) {
                float fraction=(12-radius-from.x)/(to.x-from.x);
                return new Hit(-1,from.clone().interpolateLocal(to,fraction),Vector3f.UNIT_X.negate(),fraction);
            }
            float wall=-42-radius;
            if(frontWall&&to.z>from.z&&to.z>=wall) {
                float fraction=Math.clamp((wall-from.z)/(to.z-from.z),0,1);
                return new Hit(-1,from.clone().interpolateLocal(to,fraction),new Vector3f(0,0,-1),fraction);
            }
            return null;
        }
        public Hit staticSweep(Vector3f from,Vector3f to,float radius){return null;}
        public boolean visible(Vector3f from,Vector3f to,int id){return true;}
        public float distanceToHull(int id,Vector3f point){return delegate.distanceToHull(id,point);}
        public Vector3f closestHullPoint(int id,Vector3f point){return delegate.closestHullPoint(id,point);}
        public void impulse(int id,Vector3f linear,Vector3f torque,float maximum){throw new AssertionError("Only ordinary driver commands are allowed");}
    }
}
