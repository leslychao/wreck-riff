package game.wreckriff.combat;

import com.jme3.math.*;
import game.wreckriff.input.VehicleCommand;
import game.wreckriff.simulation.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NewArsenalTest {
    final MatchSession session=new MatchSession(19,360);
    final CombatSystem combat=new CombatSystem(session,session.combatRules);
    final TestWorld world=new TestWorld();
    static VehicleCommand fire(WeaponType type) {return new VehicleCommand(0,0,0,false,false,false,true,type,0,false,false,AbilityId.NONE);}
    List<GameEvent> tick(Map<Integer,VehicleCommand> commands) {
        combat.beginTick(commands,world);combat.advanceProjectiles(world);combat.resolveDamage(world);session.finishTick();return combat.drainEvents();
    }
    List<GameEvent> tick(VehicleCommand command) {return tick(Map.of(0,command));}
    @ParameterizedTest @ValueSource(floats={5,15,30,60})
    void napalmSolvesTheArcAtEveryApprovedDistance(float distance) {
        world.positions[1].set(world.muzzle(0).add(0,0,distance));
        tick(fire(WeaponType.NAPALM));assertEquals(1,combat.napalmAssistTarget(0));
        ProjectileState projectile=combat.projectiles().getFirst();
        float time=Math.clamp(distance/28,.25f,2.2f);
        assertEquals(distance/time,projectile.originalVelocity.z,.0001f);
        assertEquals(9*time,projectile.originalVelocity.y,.0001f);
        assertEquals(world.positions[1].y,projectile.launchPosition.y+projectile.originalVelocity.y*time-9*time*time,.0001f);
    }
    @Test void napalmPrioritizesAimAndClampsLeadWhileUnassistedShotUsesThirtyMetres() {
        Vector3f origin=world.muzzle(0);
        world.positions[1].set(origin.add(1,0,15));world.positions[2].set(origin.add(.4f,0,40));
        world.velocities[2].set(100,0,0);
        tick(fire(WeaponType.NAPALM));assertEquals(2,combat.napalmAssistTarget(0));
        ProjectileState projectile=combat.projectiles().getFirst();
        float time=(float)Math.sqrt(5.4f*5.4f+40*40)/28;
        assertEquals(5.4f,projectile.originalVelocity.x*time,.001f);
        combat.clear();world.hidden.addAll(Set.of(1,2));session.vehicle(0).weapon(WeaponType.NAPALM).cooldownTicks=0;
        tick(fire(WeaponType.NAPALM));assertEquals(-1,combat.napalmAssistTarget(0));
        projectile=combat.projectiles().getFirst();assertEquals(28,projectile.originalVelocity.z,.0001f);
        time=30f/28;assertEquals(0,projectile.launchPosition.y+projectile.originalVelocity.y*time-9*time*time,.0001f);
    }
    @Test void napalmGuidanceRespectsTurnAndPathBoundsAndNeverReacquiresAfterOcclusion() {
        world.positions[1].set(world.muzzle(0).add(0,0,55));tick(fire(WeaponType.NAPALM));
        ProjectileState projectile=combat.projectiles().getFirst();float oldHeading=0;
        for(int i=0;i<400&&!projectile.exploded;i++) {
            world.positions[1].x=i<80?20:-20;world.velocities[1].x=i<80?35:-35;
            if(i==90)world.hidden.add(1);if(i==120)world.hidden.clear();
            tick(VehicleCommand.NONE);
            float heading=(float)Math.atan2(projectile.velocity.x,projectile.velocity.z);
            assertTrue(Math.abs(heading-oldHeading)<=Math.toRadians(18)/120+.00001,"Turn limit at "+i);oldHeading=heading;
            Vector3f original=projectile.launchPosition.add(projectile.originalVelocity.mult(projectile.ageTicks*MatchSession.DT));
            Vector3f delta=projectile.position.subtract(original);delta.y=0;
            assertTrue(delta.length()<=3.001f,"Deviation at "+i+": "+delta.length());
            if(i>=114)assertEquals(-1,projectile.targetId());
        }
    }
    @Test void twoRicochetsAndThirdContactHaveIndependentIdsAndOneFinalExplosion() {
        world.hits.add(new WorldQuery.Hit(-1,new Vector3f(0,1,20),new Vector3f(0,0,-1),.2f));
        world.hits.add(new WorldQuery.Hit(-1,new Vector3f(0,1,18),new Vector3f(0,0,1),.2f));
        world.hits.add(new WorldQuery.Hit(-1,new Vector3f(0,1,21),new Vector3f(0,0,-1),.2f));
        var events=tick(fire(WeaponType.CANNON));
        var explosions=events.stream().filter(e->e.type()==GameEvent.Type.EXPLOSION).toList();
        assertEquals(List.of("cannon-ricochet","cannon-ricochet","cannon"),explosions.stream().map(GameEvent::kind).toList());
        assertEquals(3,explosions.stream().map(GameEvent::eventId).distinct().count());assertTrue(combat.projectiles().isEmpty());
        assertEquals(List.of(0,-1,-1),world.ignored);
    }
    @Test void cannonCanHitOwnerAfterBounceAndDoesNotAddDirectSplashTwice() {
        world.hits.add(new WorldQuery.Hit(-1,new Vector3f(0,1,20),new Vector3f(0,0,-1),.2f));
        world.hits.add(new WorldQuery.Hit(0,new Vector3f(0,1,1),Vector3f.UNIT_Z,.2f));
        var events=tick(fire(WeaponType.CANNON));
        assertEquals(800-32.5f,session.vehicle(0).hp,.001f);
        assertEquals(1,events.stream().filter(e->e.type()==GameEvent.Type.DAMAGE&&e.subjectId()==0).count());
        assertTrue(world.impulses.get(0).z<0);assertEquals(6,world.angularCaps.get(0));
    }
    @Test void cannonImpulseSurvivesLethalDamageAndUsesActualMassWithHeavyCaps() {
        world.positions[1].set(0,1,10);session.vehicle(1).hp=20;world.masses[1]=2200;
        Vector3f point=new Vector3f(.7f,1.5f,8);
        world.hits.add(new WorldQuery.Hit(1,point,new Vector3f(0,0,-1),.5f));
        var events=tick(fire(WeaponType.CANNON));assertEquals(0,session.vehicle(1).hp);
        assertTrue(world.impulses.get(1).z>13000);assertTrue(world.impulses.get(1).z/world.mass(1)<6.1f);
        assertEquals(point.subtract(world.position(1)).cross(world.impulses.get(1)),world.torques.get(1));
        assertEquals(6,world.angularCaps.get(1));assertEquals(1,events.stream().filter(e->e.type()==GameEvent.Type.DESTROYED).count());
    }
    @Test void wallBetweenWeaponBaseAndMuzzleUsesTheSameFirstRicochetProfile() {
        world.blockMuzzle=true;
        var events=tick(fire(WeaponType.CANNON));
        var explosions=events.stream().filter(e->e.type()==GameEvent.Type.EXPLOSION).toList();
        assertEquals(1,explosions.size());assertEquals("cannon-ricochet",explosions.getFirst().kind());
        assertEquals(3.5f,explosions.getFirst().value());assertEquals(1,combat.projectiles().getFirst().ricochets());
    }
    @Test void ballisticCorrectionsAreBoundedAndStopPermanentlyWhenTheTargetDies() {
        world.floor=true;world.positions[1].set(0,1,40);
        for(int i=0;i<18;i++)tick(VehicleCommand.NONE);
        tick(fire(WeaponType.BALLISTIC));world.positions[1].x=30;
        Set<Long> ids=new HashSet<>();List<Vector3f> centers=new ArrayList<>();
        for(int i=0;i<700;i++) {
            for(var warning:combat.ballisticWarnings())if(ids.add(warning.id())) {
                int index=centers.size();Vector3f offset=switch(index) {case 0->new Vector3f(-2,0,0);case 1->new Vector3f(0,0,2);case 2->new Vector3f(2,0,0);default->new Vector3f(0,0,-2);};
                Vector3f center=warning.point().subtract(offset);centers.add(center);
                assertTrue(center.distance(new Vector3f(0,0,40))<=6.001f);
                if(index>0)assertTrue(center.distance(centers.get(index-1))<=3.001f);
                if(index==1)session.vehicle(1).hp=0;
            }
            tick(VehicleCommand.NONE);
        }
        assertEquals(4,centers.size());assertEquals(3,centers.get(0).x,.01f);assertEquals(6,centers.get(1).x,.01f);
        assertTrue(centers.get(1).distance(centers.get(2))<.0001f,"No correction after target death, within float precision");
        assertTrue(centers.get(2).distance(centers.get(3))<.0001f,"The following charge retains the frozen centre");
    }
    @Test void salvoReservesFiveSlotsAtomicallyAndFreesCancelledCarrierReservations() {
        for(var vehicle:session.vehicles)vehicle.initializeWeapon(WeaponType.HOMING,12,12);
        for(int i=0;i<12;i++) {
            Map<Integer,VehicleCommand> commands=new HashMap<>();
            for(var vehicle:session.vehicles) {vehicle.weapon(WeaponType.HOMING).cooldownTicks=0;commands.put(vehicle.id,fire(WeaponType.HOMING));}
            tick(commands);
        }
        assertEquals(60,combat.occupiedProjectileSlots());tick(fire(WeaponType.BALLISTIC));
        assertEquals(2,session.vehicle(0).weapon(WeaponType.BALLISTIC).ammo);assertEquals(0,combat.reservedBallisticCharges());
        combat.clear();world.blockMuzzle=true;tick(fire(WeaponType.BALLISTIC));
        assertEquals(1,session.vehicle(0).weapon(WeaponType.BALLISTIC).ammo);assertEquals(0,combat.occupiedProjectileSlots());
        assertTrue(combat.ballisticWarnings().isEmpty());
    }
    @Test void fourWarnedRoofHitsUseDistinctIdsAndCannotDamageTheLowerFloorOrCreateFire() {
        world.floor=true;world.roof=true;world.positions[1].set(0,1,40);
        List<GameEvent> events=new ArrayList<>();Map<Long,Long> warned=new HashMap<>();
        events.addAll(tick(fire(WeaponType.BALLISTIC)));
        for(int i=0;i<850;i++) {
            for(var warning:combat.ballisticWarnings()) {
                warned.putIfAbsent(warning.id(),session.tick);assertEquals(6,warning.point().y,.001f);
                Vector3f copy=warning.point();copy.zero();assertEquals(6,warning.point().y,.001f);
            }
            var next=tick(VehicleCommand.NONE);events.addAll(next);
            for(var event:next)if(event.type()==GameEvent.Type.SHOT&&event.kind().equals("ballistic-fall"))
                assertTrue(session.tick-warned.get(event.eventId())>=60);
        }
        var shots=events.stream().filter(e->e.type()==GameEvent.Type.SHOT&&e.kind().equals("ballistic-fall")).toList();
        assertEquals(4,shots.size());
        var hits=events.stream().filter(e->e.type()==GameEvent.Type.EXPLOSION&&e.kind().equals("ballistic")).toList();
        assertEquals(4,hits.size());assertEquals(4,hits.stream().map(GameEvent::eventId).distinct().count());
        for(var hit:hits)assertEquals(6.03f,hit.position().y,.001f);
        assertEquals(400,session.vehicle(1).hp);assertTrue(combat.fireZones().isEmpty());
        assertEquals(0,combat.occupiedProjectileSlots());assertTrue(combat.ballisticWarnings().isEmpty());
    }
    @Test void ramFeedbackIsThrottledSeparatelyAndNeverQueuesAnotherPhysicalImpulse() {
        for(int i=0;i<37;i++) {
            combat.queueRam(0,1,10,new Vector3f(0,1,1),Vector3f.UNIT_Z);
            combat.resolveDamage(world);session.finishTick();
        }
        var events=combat.drainEvents();assertEquals(3,events.stream().filter(e->e.type()==GameEvent.Type.RAM).count());
        assertEquals(2,events.stream().filter(e->e.type()==GameEvent.Type.DAMAGE).count());assertTrue(world.impulses.isEmpty());
    }
    static final class TestWorld implements WorldQuery {
        final Vector3f[] positions=new Vector3f[5],velocities=new Vector3f[5];final float[] masses={1100,1100,1100,1100,1100};
        final Set<Integer> hidden=new HashSet<>();final Deque<Hit> hits=new ArrayDeque<>();final List<Integer> ignored=new ArrayList<>();
        final Map<Integer,Vector3f> impulses=new HashMap<>(),torques=new HashMap<>();final Map<Integer,Float> angularCaps=new HashMap<>();
        boolean floor,roof,blockMuzzle;
        TestWorld() {for(int id=0;id<5;id++){positions[id]=new Vector3f(100+id*20,1,100);velocities[id]=new Vector3f();}positions[0].set(0,1,0);}
        public Vector3f position(int id){return positions[id].clone();}public Vector3f velocity(int id){return velocities[id].clone();}
        public Quaternion rotation(int id){return new Quaternion();}public boolean grounded(int id){return true;}public float mass(int id){return masses[id];}
        public Hit ray(Vector3f from,Vector3f to,int owner){return blockMuzzle&&from.distance(to)<2?new Hit(-1,from,Vector3f.UNIT_Z,.2f):null;}
        public Hit sweep(Vector3f from,Vector3f to,float radius,int owner,float stepStart,float stepEnd){ignored.add(owner);return hits.isEmpty()?staticSweep(from,to,radius):hits.removeFirst();}
        public Hit staticSweep(Vector3f from,Vector3f to,float radius){
            if(!floor)return null;
            Hit roofHit=roof?plane(from,to,radius,6,true):null;return roofHit==null?plane(from,to,radius,0,false):roofHit;
        }
        private Hit plane(Vector3f from,Vector3f to,float radius,float height,boolean restricted) {
            if(from.y<height+radius||to.y>height+radius||from.y==to.y)return null;
            float fraction=(from.y-height-radius)/(from.y-to.y);Vector3f point=from.clone().interpolateLocal(to,fraction).subtractLocal(0,radius,0);
            if(restricted&&(Math.abs(point.x)>12||point.z<28||point.z>52))return null;
            return new Hit(-1,point,Vector3f.UNIT_Y,fraction);
        }
        public boolean visible(Vector3f from,Vector3f to,int target){return !hidden.contains(target)&&!(roof&&from.y>6&&to.y<6&&Math.abs(to.x)<12&&to.z>28&&to.z<52);}
        public float distanceToHull(int id,Vector3f point){return Math.max(0,point.distance(position(id))-1);}
        public Vector3f closestHullPoint(int id,Vector3f from){Vector3f offset=from.subtract(position(id));return position(id).add(offset.normalize());}
        public void impulse(int id,Vector3f linear,Vector3f angular,float cap){impulses.put(id,linear.clone());torques.put(id,angular.clone());angularCaps.put(id,cap);}
        public Support support(Vector3f from,float depth){float y=roof&&Math.abs(from.x)<12&&from.z>=28&&from.z<=52&&from.y>6?6:0;return new Support(y==6?1:0,new Vector3f(from.x,y,from.z),Vector3f.UNIT_Y);}
    }
}
