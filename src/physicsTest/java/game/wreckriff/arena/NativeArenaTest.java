package game.wreckriff.arena;

import com.jme3.asset.DesktopAssetManager;
import com.jme3.math.*;
import game.wreckriff.ai.*;
import game.wreckriff.config.VehicleRules;
import game.wreckriff.input.VehicleCommand;
import game.wreckriff.simulation.*;
import game.wreckriff.vehicle.VehicleController;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Native wheel/mesh interaction and real command-driven route acceptance. */
class NativeArenaTest {
    private static final VehicleCommand GAS=new VehicleCommand(1,0,0,false,false,false,false,false,0,false,false);
    private final ArenaDefinition definition=ArenaDefinition.load();
    private final VehicleRules vehicleRules=VehicleRules.load();

    private PhysicsWorld arenaWorld() {
        PhysicsWorld world=new PhysicsWorld(vehicleRules);
        ArenaContent content=new ArenaFactory(new DesktopAssetManager(true)).build(definition);
        for (var body:content.bodies()) world.addStatic(body.shape(),body.position(),body.rotation());
        return world;
    }
    private void settle(PhysicsWorld world) { for (int i=0;i<240;i++) world.step(); }

    @Test void allFiveSpawnsHaveStableGroundAndClearFullChassis() {
        try (PhysicsWorld world=arenaWorld()) {
            for (var spawn:definition.spawns()) {
                Vector3f position=spawn.position().vector().add(0,.8f,0);
                Quaternion rotation=new Quaternion().fromAngleAxis(spawn.yawDegrees()*FastMath.DEG_TO_RAD,Vector3f.UNIT_Y);
                world.addVehicle(spawn.id(),position,rotation);
                assertTrue(world.freePose(spawn.id(),position,rotation),"Spawn intersects a collider: "+spawn.id());
            }
            settle(world);
            for (var spawn:definition.spawns()) {
                assertEquals(4,world.wheelContacts(spawn.id()),"Spawn suspension: "+spawn.id());
                assertTrue(world.freePose(spawn.id(),world.position(spawn.id()),world.rotation(spawn.id())));
                assertTrue(world.position(spawn.id()).distance(spawn.position().vector())<1);
            }
            System.out.println("P11 all five original spawns clear and stable on real Bullet surfaces");
        }
    }
    @Test void bothRampsAndDeckSeamsArePassableByOrdinaryThrottle() {
        for (int direction:new int[]{1,-1}) {
            try (PhysicsWorld world=arenaWorld()) {
                world.addVehicle(0,new Vector3f(54,.8f,-46*direction),
                        new Quaternion().fromAngleAxis(direction==1?0:FastMath.PI,Vector3f.UNIT_Y));
                settle(world);
                VehicleController driver=new VehicleController(world,new VehicleState(0,"Rivet",true),vehicleRules);
                float highest=world.position(0).y; int tick=0;
                while (world.position(0).z*direction<42 && tick<1800) {
                    driver.drive(GAS);world.step();highest=Math.max(highest,world.position(0).y);tick++;
                    assertTrue(world.position(0).y>-.2f,"Vehicle fell through ramp");
                }
                System.out.printf("P05 direction=%d ticks=%d position=%s maxY=%.3f%n",direction,tick,world.position(0),highest);
                assertTrue(tick<1800,"Throttle could not cross ramp/deck/ramp");
                assertTrue(highest>=6.3f,"The car must actually travel above the upper deck");
            }
        }
    }
    @Test void aiReachesBothRepairsAndDrivesOutOfGarageWithoutRecovery() {
        driveToRepair("repair-deck",new Vector3f(60,.8f,-48),-45);
        driveToRepair("repair-garage",new Vector3f(-60,.8f,-48),0);
    }
    private void driveToRepair(String pickupId,Vector3f start,float yaw) {
        var target=definition.pickups().stream().filter(p->p.id().equals(pickupId)).findFirst().orElseThrow();
        // The selected repair is the only enabled resource in this route fixture. The complete arena,
        // colliders and authored graph are identical to the game; the fixture only fixes the goal.
        ArenaDefinition fixture=new ArenaDefinition(definition.schemaVersion(),definition.id(),definition.bounds(),
                definition.boxes(),definition.ramps(),definition.spawns(),List.of(target),definition.hazard(),definition.nodes(),definition.edges());
        MatchSession session=new MatchSession(42,360);
        session.vehicle(0).hp=60;
        for (int id=1;id<5;id++) session.vehicle(id).hp=0;
        try (PhysicsWorld world=arenaWorld()) {
            world.addVehicle(0,start,new Quaternion().fromAngleAxis(yaw*FastMath.DEG_TO_RAD,Vector3f.UNIT_Y));settle(world);
            VehicleController driver=new VehicleController(world,session.vehicle(0),vehicleRules);
            ArenaSystems systems=new ArenaSystems(session,fixture);
            BotController bots=new BotController(session,fixture,AiRules.load(),systems::activePickups);
            int tick=0;
            while (session.vehicle(0).hp==60 && tick<10800) {
                session.tick=tick;
                VehicleCommand command=bots.commands(world).get(0);
                VehicleController.Recovery recovery=driver.prepare(command,tick);
                assertFalse(recovery.recovered() || recovery.fatal(),"Route used recovery: "+pickupId+" at "+world.position(0));
                driver.drive(command);world.step();driver.recordSafePose(tick);systems.collectPickups(world);
                if (tick%1200==0) System.out.printf("P12 %s t=%.1f pos=%s speed=%.2f state=%s route=%s%n",pickupId,tick/120f,
                        world.position(0),world.velocity(0).length(),bots.state(0),bots.route(0));
                tick++;
            }
            System.out.printf("P12 %s collected=%s elapsed=%.2f pos=%s metrics=%s%n",pickupId,session.vehicle(0).hp>60,tick/120f,
                    world.position(0),bots.metrics(0));
            assertTrue(session.vehicle(0).hp>60,"AI never collected "+pickupId+" position="+world.position(0));
            if (pickupId.equals("repair-garage")) {
                // Keep the AI driving after collection; never place the vehicle outside the building.
                int exitTicks=0;
                while (world.position(0).x>-62 && world.position(0).x<-26 && exitTicks<2400) {
                    session.tick=tick++;
                    VehicleCommand command=bots.commands(world).get(0);
                    VehicleController.Recovery recovery=driver.prepare(command,session.tick);
                    assertFalse(recovery.recovered() || recovery.fatal(),"Garage exit used recovery");
                    driver.drive(command);world.step();driver.recordSafePose(session.tick);exitTicks++;
                }
                assertTrue(exitTicks<2400,"Cannot leave the garage after collecting repair");
                System.out.printf("P12 garage exit elapsed=%.2f pos=%s recoveries=%d%n",exitTicks/120f,
                        world.position(0),session.vehicle(0).recoveries);
            }
        }
    }
}
