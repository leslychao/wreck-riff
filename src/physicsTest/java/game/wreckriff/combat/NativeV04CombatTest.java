package game.wreckriff.combat;

import com.jme3.bullet.collision.shapes.BoxCollisionShape;
import com.jme3.math.*;
import game.wreckriff.arena.*;
import game.wreckriff.config.*;
import game.wreckriff.input.VehicleCommand;
import game.wreckriff.simulation.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NativeV04CombatTest {
    private static VehicleCommand fire(WeaponType type) {return new VehicleCommand(0,0,0,false,false,false,true,type,0,false,false,AbilityId.NONE);}
    private record Scene(MatchSession session,PhysicsWorld world,MatchRuntime runtime) implements AutoCloseable {
        public void close(){runtime.close();}
    }
    private Scene scene(Vector3f shooter,Quaternion facing,Vector3f target,int seconds) {
        var session=new MatchSession(42,seconds);var tuning=VehicleRules.load();var world=new PhysicsWorld(tuning);
        world.addStatic(new BoxCollisionShape(new Vector3f(100,.5f,100)),new Vector3f(0,-.5f,0),new Quaternion());
        world.addVehicle(0,shooter,facing);world.addVehicle(1,target,new Quaternion());
        world.addVehicle(2,new Vector3f(-40,1,-40),new Quaternion());world.addVehicle(3,new Vector3f(40,1,-40),new Quaternion());
        world.addVehicle(4,new Vector3f(40,1,40),new Quaternion());
        for(int i=0;i<240;i++)world.step();
        ArenaDefinition arena=ArenaDefinition.load();return new Scene(session,world,new MatchRuntime(session,world,arena,new NavGraph(arena),tuning));
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void realSideCannonFlipsRivetAndLethalWreckKeepsItsMomentumForExactlyThreeSeconds(boolean lethal) {
        try(Scene scene=scene(new Vector3f(-10,1,0),new Quaternion().fromAngleAxis(FastMath.HALF_PI,Vector3f.UNIT_Y),new Vector3f(0,1,0),lethal?1:360)) {
            if(lethal)scene.session.vehicle(1).hp=40;
            List<GameEvent> events=new ArrayList<>(scene.runtime.tick(Map.of(0,fire(WeaponType.CANNON)),false));
            int elapsed=0;
            while(scene.session.vehicle(1).hp==(lethal?40:400)&&elapsed++<60)events.addAll(scene.runtime.tick(Map.of(),false));
            assertEquals(lethal?0:335,scene.session.vehicle(1).hp,.001f,"The native side shot must hit the intended hull directly");
            assertTrue(scene.world.velocity(1).x>11,"The target must receive real horizontal momentum");
            assertTrue(scene.world.velocity(1).y>7,"The target must receive real lift");
            assertTrue(scene.world.vehicle(1).getAngularVelocity().length()>4);
            assertTrue(scene.world.vehicle(1).getAngularVelocity().length()<=6.001f);
            long deathTick=scene.session.tick;
            if(lethal)assertEquals(360,scene.runtime.wreckRemainingTicks(1));
            float minimumUp=1;
            for(int i=0;i<120;i++) {
                if(scene.session.outcome==MatchSession.Outcome.NONE)events.addAll(scene.runtime.tick(Map.of(),false));
                else scene.runtime.tickPhysicsTail();
                minimumUp=Math.min(minimumUp,scene.world.rotation(1).mult(Vector3f.UNIT_Y).y);
            }
            assertTrue(minimumUp<-.5f,"Actual body must turn upside down; minimum up="+minimumUp);
            if(lethal) {
                assertEquals(240,scene.runtime.wreckRemainingTicks(1));assertTrue(scene.world.containsVehicle(1));
                assertNotEquals(MatchSession.Outcome.NONE,scene.session.outcome);
                long frozenMatchTick=scene.session.tick;float hp=scene.session.vehicle(0).hp;
                for(int i=0;i<239;i++)scene.runtime.tickPhysicsTail();
                assertEquals(1,scene.runtime.wreckRemainingTicks(1));assertTrue(scene.world.containsVehicle(1));
                scene.runtime.tickPhysicsTail();assertEquals(0,scene.runtime.wreckRemainingTicks(1));assertFalse(scene.world.containsVehicle(1));
                assertEquals(frozenMatchTick,scene.session.tick);assertEquals(hp,scene.session.vehicle(0).hp);
                Vector3f finalPose=scene.world.position(1);scene.runtime.tickPhysicsTail();assertEquals(finalPose,scene.world.position(1));
                assertEquals(1,events.stream().filter(e->e.type()==GameEvent.Type.DESTROYED&&e.subjectId()==1).count());
                assertTrue(deathTick<120);
            }
        }
    }
    @Test void frozenSideHitRetainsNativeXZAndRotationConstraintButCanLift() {
        try(Scene scene=scene(new Vector3f(-10,1,0),new Quaternion().fromAngleAxis(FastMath.HALF_PI,Vector3f.UNIT_Y),new Vector3f(0,1,0),360)) {
            Vector3f start=scene.world.position(1);Quaternion rotation=scene.world.rotation(1);
            scene.session.vehicle(1).frozenTicks=240;scene.world.immobilize(1,true);
            scene.runtime.tick(Map.of(0,fire(WeaponType.CANNON)),false);
            float highest=start.y;
            for(int i=0;i<90;i++) {scene.runtime.tick(Map.of(),false);highest=Math.max(highest,scene.world.position(1).y);}
            Vector3f displacement=scene.world.position(1).subtract(start);displacement.y=0;
            assertTrue(displacement.length()<.05f);assertTrue(Math.abs(rotation.dot(scene.world.rotation(1)))>.999f);
            assertTrue(highest>start.y+1);assertTrue(scene.session.vehicle(1).controlled());
        }
    }
    @ParameterizedTest @ValueSource(floats={5,15,30,60})
    void assistedNapalmActuallyHitsStationaryNativeCarsAcrossItsRange(float distance) {
        float z=2.5f+(float)Math.sqrt(distance*distance-.55f*.55f)+(distance==5?.001f:-.001f);
        try(Scene scene=scene(new Vector3f(0,1,0),new Quaternion(),new Vector3f(0,1,z),360)) {
            var first=scene.runtime.tick(Map.of(0,fire(WeaponType.NAPALM)),false);
            assertEquals(1,scene.runtime.combat().napalmAssistTarget(0),"Native aim range="+distance);
            List<GameEvent> events=new ArrayList<>(first);
            for(int i=0;i<360&&!scene.runtime.combat().projectiles().isEmpty();i++)events.addAll(scene.runtime.tick(Map.of(),false));
            assertTrue(scene.session.vehicle(1).hp<400,"Native napalm must damage target at "+distance);
            assertEquals(1,events.stream().filter(e->e.type()==GameEvent.Type.EXPLOSION&&e.kind().equals("napalm")).count());
        }
    }
    @Test void nativeCollisionUsesActualMassAndNoSecondGameImpulse() {
        float lightResponse=collisionResponse(1100),heavyResponse=collisionResponse(2200);
        assertTrue(lightResponse>8);assertTrue(heavyResponse>3);assertTrue(heavyResponse<lightResponse*.85f,
                "Heavier target responds less: "+lightResponse+" vs "+heavyResponse);
    }
    @Test void ricochetRemainderOnlyUsesTheRemainingPartOfTheMovingTargetsNativeStep() {
        try(var world=new PhysicsWorld(VehicleRules.load())) {
            world.addVehicle(0,new Vector3f(0,10,-2.5f),new Quaternion());
            world.addVehicle(1,new Vector3f(-2,10,2.82f),new Quaternion());
            for(int id=2;id<5;id++)world.addVehicle(id,new Vector3f(40+id*5,10,40),new Quaternion());
            for(int id=0;id<5;id++) {world.vehicle(id).setGravity(Vector3f.ZERO);world.vehicle(id).setDamping(0,0);world.vehicle(id).setMaxSuspensionForce(0);}
            // A thin ledge above the lower hull catches the ball without touching the
            // crossing car. Exaggerated lateral speed makes the time-domain mismatch
            // unambiguous: the car crosses before the bounce and is clear afterwards.
            world.addStatic(new BoxCollisionShape(new Vector3f(.05f,.025f,.025f)),new Vector3f(0,10.57f,.6875f),new Quaternion());
            world.vehicle(1).setLinearVelocity(new Vector3f(840,0,0));
            MatchSession session=new MatchSession(1,360);CombatSystem combat=new CombatSystem(session,session.combatRules);
            combat.beginTick(Map.of(0,fire(WeaponType.CANNON)),world);world.step();combat.advanceProjectiles(world);combat.resolveDamage(world);
            var events=combat.drainEvents();
            assertEquals(1,events.stream().filter(e->e.type()==GameEvent.Type.EXPLOSION&&e.kind().equals("cannon-ricochet")).count(),
                    ()->"target="+world.position(1)+"; events="+events);
            assertTrue(events.stream().noneMatch(e->e.type()==GameEvent.Type.IMPACT&&e.subjectId()==1),"The late segment must not re-hit the target's old position");
            assertEquals(1,combat.projectiles().size());assertEquals(1,combat.projectiles().getFirst().ricochets());
            var bounce=events.stream().filter(e->e.type()==GameEvent.Type.IMPACT&&e.subjectId()==-1).findFirst().orElseThrow();
            Vector3f from=bounce.position().add(bounce.normal().mult(.255f)),to=combat.projectiles().getFirst().position();
            WorldQuery.Hit whole=world.sweep(from,to,.25f,0,0,1),remaining=world.sweep(from,to,.25f,0,.9f,1);
            assertNotNull(whole,"The fixture must expose the old full-step false hit");assertEquals(1,whole.vehicleId());assertNull(remaining);
        }
    }
    @Test void ballisticWarningMatchesTheNativeRoofHitAndLeavesNoDamageBelowIt() {
        try(Scene scene=scene(new Vector3f(0,1,0),new Quaternion(),new Vector3f(0,1,40),360)) {
            scene.world.addStatic(new BoxCollisionShape(new Vector3f(12,.25f,12)),new Vector3f(0,6,42),new Quaternion());
            scene.runtime.tick(Map.of(0,fire(WeaponType.BALLISTIC)),false);
            Map<Long,CombatSystem.BallisticWarningView> warnings=new HashMap<>();Set<Long> hitIds=new HashSet<>();
            for(int i=0;i<850;i++) {
                for(var warning:scene.runtime.combat().ballisticWarnings())warnings.putIfAbsent(warning.id(),warning);
                for(var event:scene.runtime.tick(Map.of(),false))if(event.type()==GameEvent.Type.IMPACT&&event.kind().equals("ballistic-fall")) {
                    assertTrue(warnings.containsKey(event.eventId()));var predicted=warnings.get(event.eventId());
                    assertTrue(event.position().distance(predicted.point())<.05f);assertEquals(6.25f,event.position().y,.05f);
                    assertTrue(event.normal().dot(predicted.normal())>.99f);hitIds.add(event.eventId());
                }
            }
            assertEquals(4,hitIds.size());assertEquals(400,scene.session.vehicle(1).hp);
            assertTrue(scene.runtime.combat().fireZones().isEmpty());assertEquals(0,scene.runtime.combat().occupiedProjectileSlots());
        }
    }
    private float collisionResponse(float targetMass) {
        try(var world=new PhysicsWorld(VehicleRules.load())) {
            world.addVehicle(0,new Vector3f(-3,10,0),new Quaternion());world.addVehicle(1,new Vector3f(3,10,0),new Quaternion());
            world.vehicle(1).setMass(targetMass);assertEquals(targetMass,world.mass(1));
            for(int id=0;id<2;id++) {
                world.vehicle(id).setGravity(Vector3f.ZERO);world.vehicle(id).setDamping(0,0);world.vehicle(id).setFriction(0);
                world.vehicle(id).setMaxSuspensionForce(0);world.vehicle(id).setFrictionSlip(0);
            }
            world.vehicle(0).setLinearVelocity(new Vector3f(20,0,0));
            for(int i=0;i<90;i++)world.step();
            assertEquals(22000,world.mass(0)*world.velocity(0).x+world.mass(1)*world.velocity(1).x,10,
                    "Native pair must conserve horizontal momentum without an added gameplay push");
            return world.velocity(1).x;
        }
    }
    @ParameterizedTest @ValueSource(floats={0,.2f})
    void floorAndRampTrajectoriesAreUnaffectedByCarOnlyRestitution(float slope) {
        var json=Configs.gson().toJsonTree(VehicleRules.load()).getAsJsonObject();json.addProperty("carPairRestitution",.0064f);
        var baseline=Configs.gson().fromJson(json,VehicleRules.class);
        try(var oldWorld=new PhysicsWorld(baseline);var newWorld=new PhysicsWorld(VehicleRules.load())) {
            Quaternion rotation=new Quaternion().fromAngleAxis(slope,Vector3f.UNIT_Z);
            for(var world:List.of(oldWorld,newWorld)) {
                var floor=world.addStatic(new BoxCollisionShape(new Vector3f(30,.5f,30)),new Vector3f(0,-.5f,0),rotation);
                assertEquals(0,floor.getRestitution());world.addVehicle(0,new Vector3f(0,3,0),new Quaternion());
            }
            for(int i=0;i<480;i++) {
                oldWorld.step();newWorld.step();
                assertTrue(oldWorld.position(0).distance(newWorld.position(0))<.001f,"Different surface bounce at tick "+i);
                assertTrue(oldWorld.velocity(0).distance(newWorld.velocity(0))<.001f);
            }
        }
    }
}
