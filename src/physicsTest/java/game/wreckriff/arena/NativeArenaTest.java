package game.wreckriff.arena;

import game.wreckriff.combat.AbilityId;

import game.wreckriff.combat.WeaponType;

import com.jme3.asset.DesktopAssetManager;
import com.jme3.math.*;
import game.wreckriff.ai.*;
import game.wreckriff.config.VehicleRules;
import game.wreckriff.config.Configs;
import game.wreckriff.combat.CombatRules;
import game.wreckriff.combat.CombatSystem;
import game.wreckriff.input.VehicleCommand;
import game.wreckriff.simulation.*;
import game.wreckriff.vehicle.VehicleController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Native wheel/mesh interaction and real command-driven route acceptance. */
class NativeArenaTest {
    private static final VehicleCommand GAS=new VehicleCommand(1,0,0,false,false,false,false,null,0,false,false,AbilityId.NONE);
    private final ArenaDefinition definition=ArenaDefinition.load();
    private final VehicleRules vehicleRules=VehicleRules.load();

    private PhysicsWorld arenaWorld() {
        PhysicsWorld world=new PhysicsWorld(vehicleRules);
        ArenaContent content=new ArenaFactory(NativeArenaAssets.MANAGER).build(definition);
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
                VehicleController driver=new VehicleController(world,new VehicleState(0,"Rivet",true,game.wreckriff.config.Configs.load("combat",game.wreckriff.combat.CombatRules.class)),vehicleRules);
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
    @Test void routeFromBesideRampUsesAnUnobstructedConnectorToItsBase() {
        driveToRepair("repair-deck",new Vector3f(46.7f,.85f,-26.8f),0);
    }
    @Test void carFacingGarageWallBacksIntoSpaceBeforeFollowingItsRoute() {
        driveToRepair("repair-garage",new Vector3f(-49,.85f,12.7f),180);
    }
    @Test void carBesideDeckRailCanBackAlongSupportedFloor() {
        driveToRepair("repair-deck",new Vector3f(32.5f,6.85f,7.2f),39);
    }
    @Test void anOpenForwardConnectorDoesNotRequireACircleAroundTheNearestNode() {
        driveToRepair("repair-deck",new Vector3f(63.9f,.85f,25),-46);
        driveToRepair("repair-deck",new Vector3f(31,.85f,-27),140);
    }
    @ParameterizedTest(name="P11 real collection: {0}")
    @ValueSource(strings={"homing-south","homing-north","power-west","power-east","turbo-hazard","turbo-north","mine-west","mine-north","napalm-deck","napalm-south"})
    void everyAmmoAndTurboPickupIsReachableAndCollectedByANativeVehicle(String pickupId) {
        driveToPickup(pickupId,null,0);
    }
    @Test void aReversalOnEitherRampStaysInItsDriveableCorridor() {
        driveToPickup("power-east",new Vector3f(54,3.85f,24),-135);
        driveToPickup("power-west",new Vector3f(54,3.85f,-24),45);
    }
    @Test void aDeckExitApproachesTheRampOpeningWithoutCuttingItsRailCorner() {
        driveToPickup("power-east",new Vector3f(44.45f,6.85f,7.32f),-72.5f);
    }
    private void driveToPickup(String pickupId,Vector3f overrideStart,float overrideYaw) {
        var target=definition.pickups().stream().filter(p->p.id().equals(pickupId)).findFirst().orElseThrow();
        var fixture=definition.withPickups(List.of(target));
        MatchSession session=new MatchSession(42,360);
        new CombatSystem(session,Configs.load("combat",CombatRules.class));
        for (int id=1;id<5;id++) session.vehicle(id).hp=0;
        switch (target.type()) {
            case HOMING_AMMO -> session.vehicle(0).weapon(WeaponType.HOMING).ammo=0;
            case POWER_AMMO -> session.vehicle(0).weapon(WeaponType.POWER).ammo=0;
            case MINE_AMMO -> session.vehicle(0).weapon(WeaponType.MINE).ammo=0;
            case NAPALM_AMMO -> session.vehicle(0).weapon(WeaponType.NAPALM).ammo=0;
            case TURBO_CELL -> session.vehicle(0).turbo=0;
            default -> throw new IllegalArgumentException("Repair has separate full route fixtures");
        }
        int spawnId=switch(pickupId) {
            case "homing-north" -> 2;
            case "power-west" -> 0;
            case "power-east" -> 3;
            default -> 4;
        };
        var spawn=definition.spawns().stream().filter(s->s.id()==spawnId).findFirst().orElseThrow();
        Vector3f start=spawn.position().vector().add(0,.85f,0);float yaw=spawn.yawDegrees();
        if (pickupId.equals("turbo-north")) {
            // Start on its approach corridor so passive regen remains below full when the cell is reached.
            // Regeneration is the unchanged production VehicleController behavior.
            start=new Vector3f(30,.85f,55);yaw=-90;
        }
        if (overrideStart!=null) { start=overrideStart;yaw=overrideYaw; }
        try (PhysicsWorld world=arenaWorld()) {
            world.addVehicle(0,start,new Quaternion().fromAngleAxis(yaw*FastMath.DEG_TO_RAD,Vector3f.UNIT_Y));
            if (overrideStart!=null) world.vehicle(0).brake(vehicleRules.brakeForce());
            settle(world);world.vehicle(0).brake(0);
            var driver=new VehicleController(world,session.vehicle(0),vehicleRules);
            var systems=new ArenaSystems(session,fixture);
            var bots=new BotController(session,fixture,AiRules.load(),systems::activePickups);
            boolean collected=false;int tick=0;
            while (!collected && tick<10800) {
                session.tick=tick;
                var command=bots.commands(world).get(0).withoutAttacks();
                var recovery=driver.prepare(command,tick);
                assertFalse(recovery.recovered() || recovery.fatal(),"Resource route used recovery: "+pickupId);
                driver.drive(command);world.step();driver.recordSafePose(tick);systems.collectPickups(world);
                collected=systems.drainEvents().stream().anyMatch(e->e.type()==GameEvent.Type.PICKUP && e.subjectId()==0);
                tick++;
            }
            System.out.printf(Locale.ROOT,"P11 pickup=%s collected=%s seconds=%.2f position=%s recoveries=%d%n",
                    pickupId,collected,tick/120f,world.position(0),session.vehicle(0).recoveries);
            assertTrue(collected,"The native car never collected "+pickupId+" position="+world.position(0));
            assertFalse(systems.active(pickupId));
            assertTrue(Math.abs(world.position(0).y-target.position().y())<=2);
        }
    }
    @Test void nearbyOpponentsPassInsteadOfBrakingNoseToNose() {
        drivePastOpponent(new Vector3f(-18,.85f,45),new Vector3f(18,.85f,45));
    }
    @Test void nearbyOpponentsUseAShortPassingCorridorOnTheUpperDeck() {
        drivePastOpponent(new Vector3f(35,6.85f,0),new Vector3f(65,6.85f,0));
    }
    private void drivePastOpponent(Vector3f first,Vector3f second) {
        MatchSession session=new MatchSession(1,360);
        for (int id=2;id<5;id++) session.vehicle(id).hp=0;
        try (PhysicsWorld world=arenaWorld()) {
            world.addVehicle(0,first,new Quaternion().fromAngleAxis(FastMath.HALF_PI,Vector3f.UNIT_Y));
            world.addVehicle(1,second,new Quaternion().fromAngleAxis(-FastMath.HALF_PI,Vector3f.UNIT_Y));
            settle(world);
            var drivers=List.of(new VehicleController(world,session.vehicle(0),vehicleRules),new VehicleController(world,session.vehicle(1),vehicleRules));
            BotController bots=new BotController(session,definition,AiRules.load());
            boolean approached=false,passed=false;
            for (int tick=0;tick<960;tick++) {
                session.tick=tick;
                var commands=bots.commands(world);
                for (int id=0;id<2;id++) {
                    var command=commands.get(id).withoutAttacks();
                    var recovery=drivers.get(id).prepare(command,tick);
                    assertFalse(recovery.recovered() || recovery.fatal(),"A nearby opponent must not force recovery");
                    drivers.get(id).drive(command);
                }
                world.step();
                float distance=world.position(0).distance(world.position(1));
                approached|=distance<22;
                passed|=approached && world.position(0).x>world.position(1).x && distance>12;
                for (int id=0;id<2;id++) drivers.get(id).recordSafePose(tick);
            }
            assertTrue(approached,"Fixture must exercise close traffic");
            assertTrue(passed,"Drivers must physically pass each other through a clear side corridor");
        }
    }
    private void driveToRepair(String pickupId,Vector3f start,float yaw) {
        var target=definition.pickups().stream().filter(p->p.id().equals(pickupId)).findFirst().orElseThrow();
        // The selected repair is the only enabled resource in this route fixture. The complete arena,
        // colliders and authored graph are identical to the game; the fixture only fixes the goal.
        ArenaDefinition fixture=definition.withPickups(List.of(target));
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
