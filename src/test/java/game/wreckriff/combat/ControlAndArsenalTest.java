package game.wreckriff.combat;

import com.jme3.math.*;
import game.wreckriff.config.Configs;
import game.wreckriff.input.VehicleCommand;
import game.wreckriff.simulation.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ControlAndArsenalTest {
    private final MatchSession session=new MatchSession(19,360);
    private CombatRules rules=Configs.load("combat",CombatRules.class);
    private CombatSystem combat=new CombatSystem(session,rules);
    private final FlatWorld world=new FlatWorld();
    private void tick(Map<Integer,VehicleCommand> commands) {
        combat.beginTick(commands,world);combat.advanceProjectiles(world);combat.resolveDamage(world);game.wreckriff.simulation.MatchRuntime.finishTick(session);
    }
    private void tick(VehicleCommand command) {tick(Map.of(0,command));}
    private VehicleCommand ability(AbilityId id) {return new VehicleCommand(0,0,0,false,false,false,false,null,0,false,false,id);}
    private VehicleCommand fire() {return new VehicleCommand(0,0,0,false,false,false,true,null,0,false,false,AbilityId.NONE);}
    @Test void fullHealthAndIndependentArsenalStartAtApprovedValues() {
        assertEquals(800,session.vehicle(0).maximumHp);assertEquals(800,session.vehicle(0).hp);
        for(int i=1;i<5;i++)assertEquals(400,session.vehicle(i).hp);
        for(AbilityId ability:AbilityId.values())assertEquals(0,session.vehicle(0).abilityCooldown(ability));
        assertEquals(3,session.vehicle(0).weapon(WeaponType.MINE).ammo);
        assertEquals(6,session.vehicle(0).weapon(WeaponType.NAPALM).maximumAmmo);
        WeaponType type=WeaponType.HOMING;for(int i=0;i<6;i++)type=type.cycle(1);assertEquals(WeaponType.HOMING,type);
    }
    @Test void shieldWinsOverSameTickDamageAndCleansesExistingFreeze() {
        world.positions[1].set(0,1,8);world.rotations[1]=new Quaternion().fromAngleAxis(FastMath.PI,Vector3f.UNIT_Y);
        session.vehicle(0).frozenTicks=120;world.frozen.add(0);
        combat.queueDamage(0,1,100,"power",10000);combat.queueDamage(0,-1,15,"recovery",10001);
        tick(ability(AbilityId.SHIELD));
        assertEquals(755,session.vehicle(0).hp,.0001f);assertFalse(session.vehicle(0).controlled());
        assertFalse(world.frozen.contains(0));assertEquals(360,session.vehicle(0).controlImmunityTicks);
        assertEquals(300,session.vehicle(0).shieldTicks);assertEquals(1920,session.vehicle(0).abilityCooldown(AbilityId.SHIELD));
    }
    @Test void pendingLethalDamageRejectsFreezeAndDoesNotEmitAcceptedControl() {
        world.positions[1].set(0,1,8);world.nextSweep=new WorldQuery.Hit(1,world.positions[1],Vector3f.UNIT_Y,.5f);
        combat.queueDamage(1,0,1000,"power",10002);tick(ability(AbilityId.FREEZE));
        assertFalse(session.vehicle(1).alive());assertFalse(session.vehicle(1).controlled());
        assertTrue(combat.drainEvents().stream().noneMatch(e->e.type()==GameEvent.Type.FREEZE));
    }
    @Test void freezeAllowsWeaponsAndShieldCleansesIt() {
        session.vehicle(0).frozenTicks=120;world.frozen.add(0);
        tick(fire());assertEquals(5,session.vehicle(0).weapon(WeaponType.HOMING).ammo);
        tick(ability(AbilityId.SHIELD));assertFalse(session.vehicle(0).controlled());assertEquals(300,session.vehicle(0).shieldTicks);
        var ended=combat.drainEvents().stream().filter(e->e.type()==GameEvent.Type.CONTROL_ENDED).toList();
        assertEquals(1,ended.size());assertEquals("freeze",ended.getFirst().kind());
    }
    @Test void freezeDoesNotRefreshAndPostControlImmunityExpiresAtExactBoundary() {
        world.positions[1].set(0,1,8);world.nextSweep=new WorldQuery.Hit(1,world.positions[1],Vector3f.UNIT_Y,.5f);
        tick(ability(AbilityId.FREEZE));assertEquals(240,session.vehicle(1).frozenTicks);assertTrue(world.frozen.contains(1));
        session.vehicle(0).abilityCooldown(AbilityId.FREEZE,0);world.nextSweep=new WorldQuery.Hit(1,world.positions[1],Vector3f.UNIT_Y,.5f);
        tick(ability(AbilityId.FREEZE));assertEquals(239,session.vehicle(1).frozenTicks);
        for(int i=0;i<239;i++)tick(VehicleCommand.NONE);
        assertEquals(0,session.vehicle(1).frozenTicks);assertEquals(360,session.vehicle(1).controlImmunityTicks);assertFalse(world.frozen.contains(1));
        for(int i=0;i<360;i++)tick(VehicleCommand.NONE);
        assertEquals(0,session.vehicle(1).controlImmunityTicks);
    }
    @Test void blockedControlAndRejectedProjectileCapDoNotInventHitsOrSpendCooldown() {
        var json=Configs.gson().toJsonTree(rules).getAsJsonObject();json.addProperty("maximumProjectiles",1);
        combat=new CombatSystem(session,Configs.gson().fromJson(json,CombatRules.class));
        tick(fire());tick(ability(AbilityId.FREEZE));assertEquals(0,session.vehicle(0).abilityCooldown(AbilityId.FREEZE));
        assertFalse(session.vehicle(1).controlled());
    }
    @Test void mineArmsThenTriggersEnemyOnlyAndOwnerCapIsAtomic() {
        session.vehicle(0).selectedWeapon=WeaponType.MINE;tick(fire());assertEquals(1,combat.mines().size());
        assertEquals(2,session.vehicle(0).weapon(WeaponType.MINE).ammo);
        Vector3f position=combat.mines().getFirst().position();world.positions[0].set(position.add(0,1,0));
        for(int i=0;i<73;i++)tick(VehicleCommand.NONE);assertEquals(1,combat.mines().size());
        world.positions[1].set(position.add(0,1,0));tick(VehicleCommand.NONE);
        assertTrue(combat.mines().isEmpty());assertTrue(session.vehicle(1).hp<400);assertTrue(session.vehicle(0).hp<800);
        for(int i=0;i<2;i++) {
            world.positions[0].set(i*10,1,30);session.vehicle(0).weapon(WeaponType.MINE).cooldownTicks=0;tick(fire());
        }
        session.vehicle(0).weapon(WeaponType.MINE).ammo=2;session.vehicle(0).weapon(WeaponType.MINE).cooldownTicks=0;
        world.positions[0].set(40,1,30);tick(fire());assertEquals(2,combat.mines().size());assertEquals(2,session.vehicle(0).weapon(WeaponType.MINE).ammo);assertEquals(0,session.vehicle(0).weapon(WeaponType.MINE).cooldownTicks);
    }
    @Test void unsupportedMineAndClosedPlacementAreRejectedWithoutCost() {
        session.vehicle(0).selectedWeapon=WeaponType.MINE;world.supported=false;tick(fire());
        assertTrue(combat.mines().isEmpty());assertEquals(3,session.vehicle(0).weapon(WeaponType.MINE).ammo);
        world.supported=true;world.nextSweep=new WorldQuery.Hit(-1,new Vector3f(0,1,-1),Vector3f.UNIT_Z,.2f);tick(fire());
        assertEquals(3,session.vehicle(0).weapon(WeaponType.MINE).ammo);assertEquals(0,session.vehicle(0).weapon(WeaponType.MINE).cooldownTicks);
    }
    @Test void napalmReservesAtLaunchThenReleasesOnTtlAndClear() {
        session.vehicle(0).selectedWeapon=WeaponType.NAPALM;tick(fire());
        assertEquals(1,combat.reservedFireZones());assertEquals(2,session.vehicle(0).weapon(WeaponType.NAPALM).ammo);
        for(int i=0;i<360;i++)tick(VehicleCommand.NONE);
        assertEquals(0,combat.reservedFireZones());assertTrue(combat.fireZones().isEmpty());
        tick(fire());assertEquals(1,combat.reservedFireZones());combat.clear();assertEquals(0,combat.reservedFireZones());
    }
    @Test void allAirborneNapalmSlotsAreReservedAndTheSeventhShotIsFreeToReject() {
        session.vehicle(0).selectedWeapon=WeaponType.NAPALM;
        var slot=session.vehicle(0).weapon(WeaponType.NAPALM);slot.ammo=6;
        for(int i=0;i<6;i++){slot.cooldownTicks=0;tick(fire());}
        assertEquals(6,combat.reservedFireZones());assertEquals(6,combat.projectiles().size());
        slot.cooldownTicks=0;slot.ammo=1;tick(fire());
        assertEquals(1,slot.ammo);assertEquals(0,slot.cooldownTicks);assertEquals(6,combat.reservedFireZones());
        combat.clear();assertEquals(0,combat.reservedFireZones());assertTrue(combat.projectiles().isEmpty());
    }
    @Test void unsupportedNapalmImpactReleasesReservationButDoesNotRefundValidShot() {
        session.vehicle(0).selectedWeapon=WeaponType.NAPALM;world.supported=false;
        world.nextSweep=new WorldQuery.Hit(-1,new Vector3f(0,2,10),Vector3f.UNIT_Z,.5f);tick(fire());
        assertEquals(0,combat.reservedFireZones());assertTrue(combat.fireZones().isEmpty());
        assertEquals(2,session.vehicle(0).weapon(WeaponType.NAPALM).ammo);
        assertEquals(1,combat.drainEvents().stream().filter(e->e.type()==GameEvent.Type.EXPLOSION&&e.kind().equals("napalm")).count());
    }
    @Test void fireDoesNotStackAndCannotReachAnotherSurfaceOrHiddenHull() {
        session.vehicle(0).selectedWeapon=WeaponType.NAPALM;
        for(int i=0;i<2;i++) {
            session.vehicle(0).weapon(WeaponType.NAPALM).cooldownTicks=0;
            world.nextSweep=new WorldQuery.Hit(-1,new Vector3f(0,0,10),Vector3f.UNIT_Y,.5f);tick(fire());
        }
        assertEquals(2,combat.fireZones().size());assertEquals(0,combat.reservedFireZones());
        world.positions[1].set(0,1,10);world.positions[2].set(1,7,10);world.surfaceIds[2]=1;
        world.positions[3].set(-1,1,10);world.hidden.add(3);
        combat.drainEvents();for(int i=0;i<30;i++)tick(VehicleCommand.NONE);
        assertEquals(392.5f,session.vehicle(1).hp,.001f);assertEquals(400,session.vehicle(2).hp);assertEquals(400,session.vehicle(3).hp);
        assertEquals(1,combat.drainEvents().stream().filter(e->e.type()==GameEvent.Type.DAMAGE&&e.kind().equals("napalm-fire")).count());
        assertTrue(combat.fireZones().getFirst().surfacePoints().size()<=81);
    }
    @Test void olderOwnFireCannotReduceOverlappingEnemyDamage() {
        assertOverlappingOwnAndEnemyFire(0,1);
    }
    @Test void newerOwnFireCannotReduceOverlappingEnemyDamage() {
        assertOverlappingOwnAndEnemyFire(1,0);
    }
    private void assertOverlappingOwnAndEnemyFire(int firstOwner,int secondOwner) {
        for(int owner:new int[]{firstOwner,secondOwner}) {
            session.vehicle(owner).selectedWeapon=WeaponType.NAPALM;
            world.nextSweep=new WorldQuery.Hit(-1,new Vector3f(0,0,10),Vector3f.UNIT_Y,.5f);
            tick(Map.of(owner,fire()));
        }
        assertEquals(2,combat.fireZones().size());
        world.positions[0].set(0,1,10);
        world.positions[2].set(1,1,10);
        float playerHp=session.vehicle(0).hp,botHp=session.vehicle(2).hp;
        combat.drainEvents();
        for(int i=0;i<30;i++)tick(VehicleCommand.NONE);
        assertEquals(playerHp-7.5f,session.vehicle(0).hp,.001f);
        assertEquals(botHp-7.5f,session.vehicle(2).hp,.001f);
        var fireDamage=combat.drainEvents().stream()
                .filter(e->e.type()==GameEvent.Type.DAMAGE&&e.kind().equals("napalm-fire")).toList();
        assertEquals(2,fireDamage.size());
        for(GameEvent damage:fireDamage) {
            assertEquals(firstOwner,damage.sourceId(),"Attribution follows the oldest covering zone independently of damage");
            assertEquals(7.5f,damage.value(),.001f);
        }
    }
    private static class FlatWorld implements WorldQuery {
        final Vector3f[] positions={new Vector3f(0,1,0),new Vector3f(80,1,80),new Vector3f(90,1,80),new Vector3f(100,1,80),new Vector3f(110,1,80)};
        final Quaternion[] rotations={new Quaternion(),new Quaternion(),new Quaternion(),new Quaternion(),new Quaternion()};
        final int[] surfaceIds=new int[5];final Set<Integer> hidden=new HashSet<>(),frozen=new HashSet<>();
        boolean supported=true;Hit nextSweep;
        public Vector3f position(int id){return positions[id].clone();}public Vector3f velocity(int id){return new Vector3f();}
        public Quaternion rotation(int id){return rotations[id].clone();}public boolean grounded(int id){return true;}public float mass(int id){return 1100;}
        public Hit ray(Vector3f from,Vector3f to,int ignored){return null;}
        public Hit sweep(Vector3f from,Vector3f to,float radius,int ignored,float stepStart,float stepEnd){Hit hit=nextSweep;nextSweep=null;return hit;}
        public Hit staticSweep(Vector3f from,Vector3f to,float radius){return null;}
        public boolean visible(Vector3f from,Vector3f to,int id){return !hidden.contains(id);}
        public float distanceToHull(int id,Vector3f point){return Math.max(0,positions[id].distance(point)-1);}
        public void impulse(int id,Vector3f linear,Vector3f torque,float angularCap) {}
        public Vector3f closestHullPoint(int id,Vector3f from) { return position(id); }
        public void immobilize(int id,boolean active){if(active)frozen.add(id);else frozen.remove(id);}
        public Support support(Vector3f from,float depth){
            if(!supported)return null;
            for(int i=0;i<5;i++)if(surfaceIds[i]!=0&&Math.abs(from.x-positions[i].x)<.05f&&Math.abs(from.z-positions[i].z)<.05f)return new Support(surfaceIds[i],new Vector3f(from.x,6,from.z),Vector3f.UNIT_Y);
            return new Support(0,new Vector3f(from.x,0,from.z),Vector3f.UNIT_Y);
        }
    }
}
