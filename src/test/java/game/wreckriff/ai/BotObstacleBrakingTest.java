package game.wreckriff.ai;

import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import game.wreckriff.arena.ArenaDefinition;
import game.wreckriff.arena.TestWorld;
import game.wreckriff.input.VehicleCommand;
import game.wreckriff.simulation.MatchSession;
import game.wreckriff.simulation.WorldQuery;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BotObstacleBrakingTest {
    @Test void allThreeSweepsReachBeyondTheOldLimitAndBrakeAtCruiseTopAndTurboSpeed() {
        float previous=0;
        for(float speed:new float[]{28,34,46}) {
            var fixture=new Fixture();fixture.world.delegate.velocities[0]=new Vector3f(0,0,speed);
            fixture.world.wallDistance=24;
            VehicleCommand command=fixture.next();
            assertEquals(0,command.throttle());assertTrue(command.brakeReverse()>0);
            assertFalse(command.turbo());assertEquals(3,fixture.world.probeLengths.size());
            float length=fixture.world.probeLengths.getFirst();
            assertTrue(length>24&&length>previous);assertTrue(length<=AiRules.load().sightRange());
            assertTrue(fixture.world.probeLengths.stream().allMatch(value->value==length));previous=length;
        }
    }

    @Test void noseClearanceStopsASlowCarInsteadOfAcceleratingItsHullIntoTheWall() {
        var fixture=new Fixture();fixture.world.delegate.velocities[0]=new Vector3f(0,0,1);
        fixture.world.wallDistance=3;
        VehicleCommand command=fixture.next();
        assertEquals(0,command.throttle());assertEquals(1,command.brakeReverse());
    }

    @Test void parallelSideWallsKeepTheExistingCautiousPassInsteadOfBecomingAFrontalStop() {
        var fixture=new Fixture();fixture.world.delegate.velocities[0]=new Vector3f(0,0,4);
        fixture.world.parallelSide=true;
        VehicleCommand command=fixture.next();
        assertTrue(command.throttle()>0);assertEquals(0,command.brakeReverse());
    }

    @Test void unobstructedStraightChaseAcceleratesBeyondThePreviousCruiseSpeed() {
        var fixture=new Fixture();fixture.world.delegate.velocities[0]=new Vector3f(0,0,23);
        VehicleCommand command=fixture.next();
        assertEquals(1,command.throttle());assertEquals(0,command.brakeReverse());
        assertEquals(28,AiRules.load().cruiseSpeed());assertEquals(12,AiRules.load().maximumLookAhead());
    }

    private static final class Fixture {
        final MatchSession session=new MatchSession(42,360);
        final ProbeWorld world=new ProbeWorld();
        final BotController bots;
        Fixture() {
            for(int id=2;id<5;id++)session.vehicle(id).hp=0;
            world.delegate.positions[0]=new Vector3f(0,.8f,-50);
            world.delegate.positions[1]=new Vector3f(0,.8f,15);
            bots=new BotController(session,ArenaDefinition.load(),AiRules.load());
            bots.commands(world);world.probeLengths.clear();
        }
        VehicleCommand next() {session.tick++;return bots.commands(world).get(0);}
    }

    /** Rule boundary double; the native test separately verifies actual braking and body clearance. */
    private static final class ProbeWorld implements WorldQuery {
        final TestWorld delegate=new TestWorld();final List<Float> probeLengths=new ArrayList<>();
        float wallDistance=Float.POSITIVE_INFINITY;boolean parallelSide;
        public Vector3f position(int id){return delegate.position(id);}
        public Vector3f velocity(int id){return delegate.velocity(id);}
        public Quaternion rotation(int id){return delegate.rotation(id);}
        public boolean grounded(int id){return true;}
        public float mass(int id){return delegate.mass(id);}
        public Hit ray(Vector3f a,Vector3f b,int id){return delegate.ray(a,b,id);}
        public Hit sweep(Vector3f from,Vector3f to,float radius,int ignored,float start,float end) {
            Vector3f delta=to.subtract(from);
            if(ignored==0&&Math.abs(from.y-position(0).y-.85f)<.001f&&delta.z>0&&Math.abs(delta.x)<.001f) {
                float length=delta.length();probeLengths.add(length);
                if(parallelSide&&Math.abs(from.x)>1)return new Hit(-1,from.clone(),Vector3f.UNIT_X,0);
                float travel=wallDistance-radius;
                if(travel<=length)return new Hit(-1,from.add(0,0,travel),new Vector3f(0,0,-1),Math.max(0,travel/length));
            }
            return null;
        }
        public Hit staticSweep(Vector3f a,Vector3f b,float radius){return null;}
        public boolean visible(Vector3f a,Vector3f b,int id){return true;}
        public float distanceToHull(int id,Vector3f point){return delegate.distanceToHull(id,point);}
        public Vector3f closestHullPoint(int id,Vector3f point){return delegate.closestHullPoint(id,point);}
        public void impulse(int id,Vector3f linear,Vector3f torque,float maximum){throw new AssertionError("AI must issue driver commands only");}
    }
}
