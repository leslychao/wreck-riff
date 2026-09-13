package game.wreckriff.simulation;

import com.jme3.math.*;
import game.wreckriff.ai.*;
import game.wreckriff.arena.*;
import game.wreckriff.input.VehicleCommand;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BotSupportedHullTest {
    @Test void overturnedNativeHullContactRequestsOrdinaryDriverRightingInsteadOfAnEndlessFlightPause() {
        var arena=ArenaDefinition.load();var session=new MatchSession(42,360);
        for(var vehicle:session.vehicles)if(vehicle.id!=0)vehicle.hp=0;
        var world=new HullWorld();var bots=new BotController(session,arena,AiRules.load());
        var flying=bots.commands(world).get(0);assertEquals(VehicleCommand.NONE,flying);
        world.contact=true;session.tick++;
        var righting=bots.commands(world).get(0);assertTrue(righting.throttle()>.2f);
        assertFalse(righting.recover());assertFalse(righting.selectedWeapon());assertFalse(righting.machineGun());
        assertEquals(BotController.State.RECOVER,bots.state(0));assertNotEquals(BotController.TransitionPhase.FLIGHT,bots.navigation(0).phase());
        world.contact=false;session.tick++;assertEquals(VehicleCommand.NONE,bots.commands(world).get(0));
    }
    @Test void launchOwnerRetainsControlEvenIfAReportedHullContactExistsDuringCompression() {
        var arena=ArenaDefinition.load();var session=new MatchSession(42,360);
        for(var vehicle:session.vehicles)if(vehicle.id!=0)vehicle.hp=0;
        var world=new HullWorld();world.contact=true;world.launch=true;
        var bots=new BotController(session,arena,AiRules.load());
        for(int tick=0;tick<180;tick++){session.tick=tick;assertEquals(VehicleCommand.NONE,bots.commands(world).get(0));}
        assertEquals(BotController.TransitionPhase.FLIGHT,bots.navigation(0).phase());
    }
    @Test void aPersistentlyHighCentredHullStillReachesTheExistingTimedRecovery() {
        var arena=ArenaDefinition.load();var session=new MatchSession(42,360);
        for(var vehicle:session.vehicles)if(vehicle.id!=0)vehicle.hp=0;
        var world=new HullWorld();world.contact=true;var rules=AiRules.load();var bots=new BotController(session,arena,rules);
        int requested=-1;
        for(int tick=0;tick<2400&&requested<0;tick++) {
            session.tick=tick;var command=bots.commands(world).get(0);
            if(command.recover())requested=tick;
        }
        assertTrue(requested>=rules.recoveryAfterTicks(),"A supported but immobile hull gets the full normal recovery delay");
        assertTrue(requested<2400,"A failed self-righting attempt must not suppress recovery forever");
    }
    private static final class HullWorld implements WorldQuery {
        boolean contact,launch;
        public Vector3f position(int id){return new Vector3f(-60,4,-48);}
        public Vector3f velocity(int id){return new Vector3f();}
        public Quaternion rotation(int id){return new Quaternion().fromAngleAxis(FastMath.PI,Vector3f.UNIT_Z);}
        public boolean grounded(int id){return false;}public boolean chassisSupported(int id){return contact;}
        public float mass(int id){return 1100;}
        public RoadContext roadContext(int id){return new RoadContext("road-ground",0,1,launch?RoadContext.Motion.LAUNCH:RoadContext.Motion.AIRBORNE,"","",0);}
        public Hit ray(Vector3f from,Vector3f to,int ignored){return null;}
        public Hit sweep(Vector3f a,Vector3f b,float radius,int id,float start,float end){return null;}
        public Hit staticSweep(Vector3f a,Vector3f b,float radius){return null;}
        public boolean visible(Vector3f from,Vector3f to,int target){return false;}
        public float distanceToHull(int id,Vector3f point){return position(id).distance(point);}
        public Vector3f closestHullPoint(int id,Vector3f point){return position(id);}
        public void impulse(int id,Vector3f linear,Vector3f torque,float maximum){fail("AI must use the shared driver, not apply a righting impulse itself");}
    }
}
