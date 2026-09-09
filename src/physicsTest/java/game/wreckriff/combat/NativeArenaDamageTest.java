package game.wreckriff.combat;

import com.jme3.bullet.collision.shapes.BoxCollisionShape;
import com.jme3.math.*;
import game.wreckriff.config.VehicleRules;
import game.wreckriff.input.VehicleCommand;
import game.wreckriff.simulation.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NativeArenaDamageTest {
    record Hit(String geometry,float amount,long event) {}
    final MatchSession session=new MatchSession(32,360);
    final CombatSystem combat=new CombatSystem(session,session.combatRules);
    final List<Hit> damage=new ArrayList<>();
    final List<CombatSystem.ArenaTarget> targets=new ArrayList<>();
    PhysicsWorld world() {
        for(var vehicle:session.vehicles)if(vehicle.id!=0)vehicle.hp=0;
        var world=new PhysicsWorld(VehicleRules.load());
        world.addStatic("floor",new BoxCollisionShape(new Vector3f(80,.5f,80)),new Vector3f(0,-.5f,0),new Quaternion());
        for(var participant:session.vehicles)world.addVehicle(participant.id,
                participant.id==0?new Vector3f(0,.85f,0):new Vector3f(40+participant.id*6,.85f,40),new Quaternion());
        for(int tick=0;tick<360;tick++)world.step();
        combat.configureArenaDamage(()->List.copyOf(targets),(geometry,source,amount,cause,event)->damage.add(new Hit(geometry,amount,event)));
        return world;
    }
    void object(PhysicsWorld world,String id,Vector3f center,Vector3f halfSize,boolean damageable) {
        world.addStatic(id,new BoxCollisionShape(halfSize),center,new Quaternion());
        if(damageable)targets.add(new CombatSystem.ArenaTarget(id,center.subtract(halfSize),center.add(halfSize)));
    }
    void tick(PhysicsWorld world,VehicleCommand command) {
        combat.beginTick(Map.of(0,command),world);world.step();combat.advanceProjectiles(world);
        for(var contact:world.arenaContacts())combat.queueArenaRam(contact.objectId(),contact.vehicleId(),contact.closingSpeed());
        combat.resolveArenaDamage();combat.resolveDamage(world);session.tick++;
    }

    @Test void actualMachineGunRayCarriesTheRegisteredObjectIdIntoOneDamageTransaction() {
        try(var world=world()) {
            object(world,"gate",new Vector3f(0,2,10),new Vector3f(1.5f,2,.3f),true);
            tick(world,new VehicleCommand(0,0,0,false,false,true,false,null,0,false,false,AbilityId.NONE));
            assertEquals(1,damage.size());assertEquals("gate",damage.getFirst().geometry());
            assertEquals(session.combatRules.machineGun().damage(),damage.getFirst().amount());
            var impact=combat.drainEvents().stream().filter(e->e.type()==GameEvent.Type.IMPACT).findFirst().orElseThrow();
            assertEquals("gate",impact.objectId());assertEquals(9.7f,impact.position().z,.01f);
            combat.resolveArenaDamage();assertEquals(1,damage.size());assertEquals(0,session.vehicle(0).damageDealt);
        }
    }

    @Test void powerBlastReachesAnExposedObjectButCannotCrossTheActualSideWall() {
        try(var world=world()) {
            object(world,"impact",new Vector3f(0,2,10),new Vector3f(1,2,.3f),true);
            object(world,"exposed",new Vector3f(-4,2,10),new Vector3f(.5f,2,.5f),true);
            object(world,"hidden",new Vector3f(4,2,10),new Vector3f(.5f,2,.5f),true);
            object(world,"occluder",new Vector3f(2,2,10),new Vector3f(.15f,2,3),false);
            var fire=new VehicleCommand(0,0,0,false,false,false,true,WeaponType.POWER,0,false,false,AbilityId.NONE);
            for(int tick=0;tick<120&&damage.isEmpty();tick++)tick(world,tick==0?fire:VehicleCommand.NONE);
            assertEquals(1,damage.stream().filter(h->h.geometry().equals("impact")).count());
            assertEquals(session.combatRules.power().directDamage(),damage.stream().filter(h->h.geometry().equals("impact")).findFirst().orElseThrow().amount());
            assertTrue(damage.stream().anyMatch(h->h.geometry().equals("exposed")&&h.amount()>0));
            assertTrue(damage.stream().noneMatch(h->h.geometry().equals("hidden")));
        }
    }

    @Test void aRealChassisCollisionDamagesTheStaticObjectUsingTheCommonRamFormula() {
        try(var world=world()) {
            object(world,"gate",new Vector3f(0,2,10),new Vector3f(2,2,.3f),true);
            world.vehicle(0).setLinearVelocity(new Vector3f(0,20e-6f,20));
            float contactSpeed=0;
            for(int tick=0;tick<120&&damage.isEmpty();tick++) {
                world.step();
                for(var contact:world.arenaContacts())if(contact.objectId().equals("gate")) {
                    contactSpeed=Math.max(contactSpeed,contact.closingSpeed());
                    combat.queueArenaRam(contact.objectId(),contact.vehicleId(),contact.closingSpeed());
                    combat.queueArenaRam(contact.objectId(),contact.vehicleId(),contact.closingSpeed());
                }
                combat.resolveArenaDamage();combat.resolveArenaDamage();session.tick++;
            }
            assertTrue(contactSpeed>session.combatRules.ram().minimumClosingSpeed());assertEquals(1,damage.size());
            assertEquals(Math.min(session.combatRules.ram().maximumDamage(),session.combatRules.ram().damagePerExcessSpeed()
                    *(contactSpeed-session.combatRules.ram().minimumClosingSpeed())),damage.getFirst().amount(),.001f);
            assertEquals(0,world.teleportGeneration(0));
        }
    }
}
