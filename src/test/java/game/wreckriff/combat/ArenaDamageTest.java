package game.wreckriff.combat;

import com.jme3.math.*;
import game.wreckriff.input.VehicleCommand;
import game.wreckriff.simulation.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ArenaDamageTest {
    record Applied(String geometry,int source,float amount,String cause,long event) {}
    final MatchSession session=new MatchSession(32,360);
    final CombatSystem combat=new CombatSystem(session,session.combatRules);
    final ArenaWorld world=new ArenaWorld();
    final List<Applied> applied=new ArrayList<>();
    final CombatSystem.ArenaTarget gate=target("gate",-1,1,8,10);
    final CombatSystem.ArenaTarget nearby=target("nearby",2,3,8,10);
    static CombatSystem.ArenaTarget target(String id,float minX,float maxX,float minZ,float maxZ) {
        return new CombatSystem.ArenaTarget(id,new Vector3f(minX,0,minZ),new Vector3f(maxX,3,maxZ));
    }
    void configure(CombatSystem.ArenaTarget... targets) {
        combat.configureArenaDamage(()->List.of(targets),(geometry,source,amount,cause,event)->applied.add(new Applied(geometry,source,amount,cause,event)));
    }
    VehicleCommand fire(WeaponType weapon) {return new VehicleCommand(0,0,0,false,false,false,true,weapon,0,false,false,AbilityId.NONE);}
    void attack(VehicleCommand command) {combat.beginTick(Map.of(0,command),world);combat.advanceProjectiles(world);}

    @Test void hitscanDamagesOnlyTheRealRegisteredColliderAndRepeatedResolveDoesNotRepeatIt() {
        configure(gate);world.hitscan=true;
        attack(new VehicleCommand(0,0,0,false,false,true,false,null,0,false,false,AbilityId.NONE));
        assertTrue(applied.isEmpty(),"Object mutations wait for the explicit ordered resolution phase");
        combat.resolveArenaDamage();combat.resolveArenaDamage();combat.resolveDamage(world);
        assertEquals(1,applied.size());assertEquals("gate",applied.getFirst().geometry());
        assertEquals(session.combatRules.machineGun().damage(),applied.getFirst().amount());
        assertEquals(0,session.vehicle(0).damageDealt,"Object damage is excluded from actual vehicle HP statistics");
    }
    @Test void directRocketAndItsSplashDamageTheSameObjectOnlyOnce() {
        configure(gate,nearby);world.nextSweep=world.gateHit();attack(fire(WeaponType.POWER));
        combat.resolveArenaDamage();combat.resolveDamage(world);
        assertEquals(1,applied.stream().filter(a->a.geometry().equals("gate")).count());
        assertEquals(session.combatRules.power().directDamage(),applied.stream().filter(a->a.geometry().equals("gate")).findFirst().orElseThrow().amount());
        assertTrue(applied.stream().anyMatch(a->a.geometry().equals("nearby")&&a.amount()>0&&a.amount()<session.combatRules.power().splashDamage()));
    }
    @Test void anActualBlockingRayPreventsBlastDamageToAnObjectBehindTheWall() {
        configure(gate,nearby);world.blockSide=true;world.nextSweep=world.gateHit();attack(fire(WeaponType.POWER));
        combat.resolveArenaDamage();assertEquals(List.of("gate"),applied.stream().map(Applied::geometry).toList());
        assertTrue(world.sideRays>0,"The explosion must query actual occlusion");
    }
    @Test void ricochetingCannonAppliesTheExistingDirectDamageInsteadOfDoubleAddingTheContactBlast() {
        configure(gate);world.nextSweep=world.gateHit();attack(fire(WeaponType.CANNON));combat.resolveArenaDamage();
        assertEquals(1,applied.size());assertEquals(session.combatRules.cannon().directDamage(),applied.getFirst().amount());
    }
    @Test void arenaRamAggregatesNativeContactsAndHonoursItsPairCooldownAcrossBothResolutionCalls() {
        configure(gate);var ram=session.combatRules.ram();
        combat.queueArenaRam("gate",0,ram.minimumClosingSpeed()+2);combat.queueArenaRam("gate",0,ram.minimumClosingSpeed()+5);
        combat.resolveArenaDamage();combat.queueArenaRam("gate",0,ram.minimumClosingSpeed()+20);combat.resolveArenaDamage();
        assertEquals(1,applied.size());assertEquals(Math.min(ram.maximumDamage(),ram.damagePerExcessSpeed()*5),applied.getFirst().amount());
        session.tick=CombatRules.ticks(ram.cooldownSeconds())-1;combat.queueArenaRam("gate",0,30);combat.resolveArenaDamage();assertEquals(1,applied.size());
        session.tick++;combat.queueArenaRam("gate",0,30);combat.resolveArenaDamage();assertEquals(2,applied.size());
    }
    @Test void missingCollidersAndEndedMatchesCannotReceiveDamageAndClearDropsAllPendingWork() {
        configure(gate);world.hitscan=true;
        combat.queueArenaRam("floor",0,30);combat.resolveArenaDamage();assertTrue(applied.isEmpty());
        attack(new VehicleCommand(0,0,0,false,false,true,false,null,0,false,false,AbilityId.NONE));
        combat.clear();combat.resolveArenaDamage();assertTrue(applied.isEmpty());
        session.outcome=MatchSession.Outcome.VICTORY;combat.queueArenaRam("gate",0,30);combat.resolveArenaDamage();assertTrue(applied.isEmpty());
    }
    @Test void targetBoundsAreDefensiveAndOversizedOrDuplicateCataloguesFailExplicitly() {
        Vector3f min=new Vector3f(),max=new Vector3f(1,1,1);var box=new CombatSystem.ArenaTarget("box",min,max);
        min.x=-100;max.y=100;box.min().x=20;box.max().y=20;
        assertEquals(Vector3f.ZERO,box.min());assertEquals(new Vector3f(1,1,1),box.max());
        assertThrows(IllegalArgumentException.class,()->new CombatSystem.ArenaTarget("box",new Vector3f(Float.NaN,0,0),max));
        combat.configureArenaDamage(()->Collections.nCopies(129,box),(a,b,c,d,e)->{});
        assertThrows(IllegalStateException.class,()->combat.queueArenaRam("box",0,30));
        combat.configureArenaDamage(()->List.of(box,box),(a,b,c,d,e)->{});
        assertThrows(IllegalStateException.class,()->combat.queueArenaRam("box",0,30));
    }

    static final class ArenaWorld implements WorldQuery {
        boolean hitscan,blockSide;int sideRays;Hit nextSweep;
        Hit gateHit(){return new Hit(-1,new Vector3f(0,.6f,8),new Vector3f(0,0,-1),.5f,"gate");}
        public Vector3f position(int id){return id==0?new Vector3f():new Vector3f(1000+id*100,0,1000);}
        public Vector3f velocity(int id){return new Vector3f();}public Quaternion rotation(int id){return new Quaternion();}
        public boolean grounded(int id){return true;}public float mass(int id){return 1100;}
        public Hit ray(Vector3f from,Vector3f to,int ignored){
            if(to.x>=2&&to.x<=3&&to.z>=8&&to.z<=10) {sideRays++;return new Hit(-1,to.clone(),Vector3f.UNIT_X,.5f,blockSide?"wall":"nearby");}
            return hitscan&&from.distance(to)>10?gateHit():null;
        }
        public Hit sweep(Vector3f a,Vector3f b,float radius,int id,float start,float end){Hit result=nextSweep;nextSweep=null;return result;}
        public Hit staticSweep(Vector3f a,Vector3f b,float radius){return null;}
        public boolean visible(Vector3f from,Vector3f to,int id){return true;}
        public float distanceToHull(int id,Vector3f point){return position(id).distance(point);}
        public Vector3f closestHullPoint(int id,Vector3f point){return position(id);}
        public void impulse(int id,Vector3f linear,Vector3f torque,float maximum){}
        public Support support(Vector3f from,float depth){return null;}
    }
}
