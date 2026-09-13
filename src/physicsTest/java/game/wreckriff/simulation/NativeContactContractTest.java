package game.wreckriff.simulation;

import com.jme3.bullet.collision.shapes.BoxCollisionShape;
import com.jme3.math.*;
import game.wreckriff.arena.ArenaDefinition;
import game.wreckriff.combat.CombatSystem;
import game.wreckriff.config.VehicleRules;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeContactContractTest {
    @Test void neonRustProxyReportsItsConcreteSurfaceFinishInNativeRayAndSweep() {
        var arena=game.wreckriff.config.Configs.load("arena-neon-zero",ArenaDefinition.class);
        var box=arena.boxes().stream().filter(part->part.material().equals("rust")).findFirst().orElseThrow();
        try(var world=new PhysicsWorld(VehicleRules.load())) {
            world.configureArena(arena);world.addStatic(box.id(),new BoxCollisionShape(new Vector3f(2,.5f,2)),new Vector3f(0,-.5f,0),new Quaternion());
            var ray=world.ray(new Vector3f(0,2,0),new Vector3f(0,-2,0),-1);
            var sweep=world.staticSweep(new Vector3f(0,2,0),new Vector3f(0,-2,0),.1f);
            assertNotNull(ray);assertNotNull(sweep);assertEquals(ContactSurface.CONCRETE,ray.surface());assertEquals(ContactSurface.CONCRETE,sweep.surface());
        }
    }
    @Test void surfaceRevisionTracksOnlyNativeSurfacePoseAndIncarnationWithoutQueriesChangingIt() {
        var world=new PhysicsWorld(VehicleRules.load());
        try(world) {
            assertEquals(0,world.surfaceRevision(null));assertEquals(0,world.surfaceRevision("missing"));
            var shape=new BoxCollisionShape(new Vector3f(.3f,1,.3f));
            world.addStatic("wall",shape,new Vector3f(0,1,0),new Quaternion());
            long wall=world.surfaceRevision("wall");assertTrue(wall>0);
            var hit=world.staticSweep(new Vector3f(-3,1,0),new Vector3f(3,1,0),.025f);
            assertNotNull(hit);assertEquals("wall",hit.objectId());assertEquals(wall,world.surfaceRevision(hit.objectId()));
            world.addMovingBox("ceiling",new Vector3f(2,.2f,2),new Vector3f(0,4,0),new Quaternion());
            long ceiling=world.surfaceRevision("ceiling");assertTrue(ceiling>0);assertEquals(wall,world.surfaceRevision("wall"));
            var overhead=world.staticSweep(new Vector3f(0,3,0),new Vector3f(0,6,0),.025f);
            assertNotNull(overhead);assertEquals("ceiling",overhead.objectId(),"The existing static sweep also sees authored kinematic surfaces");
            world.moveArenaBody("ceiling",new Vector3f(0,4,0),new Quaternion());world.step();
            assertEquals(ceiling,world.surfaceRevision("ceiling"));assertEquals(wall,world.surfaceRevision("wall"));
            world.moveArenaBody("ceiling",new Vector3f(4,4,0),new Quaternion());
            long moved=world.surfaceRevision("ceiling");assertNotEquals(ceiling,moved);
            assertNull(world.staticSweep(new Vector3f(0,3,0),new Vector3f(0,6,0),.025f));
            Quaternion rotation=new Quaternion().fromAngleAxis(.4f,Vector3f.UNIT_Z);
            world.moveArenaBody("ceiling",new Vector3f(4,4,0),rotation);long rotated=world.surfaceRevision("ceiling");
            assertNotEquals(moved,rotated);
            world.moveArenaBody("ceiling",new Vector3f(4,4,0),rotation);world.step();
            assertEquals(rotated,world.surfaceRevision("ceiling"));
            world.moveArenaBody("ceiling",new Vector3f(4,4,0),rotation.mult(-1));
            assertEquals(rotated,world.surfaceRevision("ceiling"),"Equivalent quaternion signs describe the same physical pose");
            assertEquals(wall,world.surfaceRevision("wall"));assertTrue(world.removeStatic("wall"));assertEquals(0,world.surfaceRevision("wall"));
            world.addStatic("wall",shape,new Vector3f(0,1,0),new Quaternion());
            assertTrue(world.surfaceRevision("wall")>0);assertNotEquals(wall,world.surfaceRevision("wall"));
            assertTrue(world.removeArenaBody("ceiling"));assertEquals(0,world.surfaceRevision("ceiling"));
        }
        assertEquals(0,world.surfaceRevision("wall"));
    }
    @Test void strongestNativeRamSurvivesPairReversalAndLaterTargetMovementForBothDamageEvents() {
        try(var world=new PhysicsWorld(VehicleRules.load())) {
            var session=new MatchSession(42,360);var combat=new CombatSystem(session,session.combatRules);
            for(var state:session.vehicles)world.addVehicle(state.id,new Vector3f(30+state.id*8,4,30),new Quaternion());
            var weak=nativeRam(world,10,0);var strong=nativeRam(world,20,3);
            assertTrue(strong.closingSpeed()>weak.closingSpeed());assertTrue(strong.point().distance(weak.point())>1);
            combat.queueRam(weak.first(),weak.second(),weak.closingSpeed(),weak.point(),weak.normal(),weak.firstContact(),weak.secondContact());
            combat.queueRam(strong.second(),strong.first(),strong.closingSpeed(),strong.point(),strong.normal().negate(),strong.secondContact(),strong.firstContact());
            combat.queueRam(weak.first(),weak.second(),weak.closingSpeed(),weak.point(),weak.normal(),weak.firstContact(),weak.secondContact());
            // The captured local contacts must not be reconstructed from this later pose.
            world.teleport(0,new Vector3f(-20,4,12),new Quaternion());world.teleport(1,new Vector3f(20,4,12),new Quaternion());
            float firstHp=session.vehicle(0).hp,secondHp=session.vehicle(1).hp;
            combat.resolveDamage(world);var losses=combat.drainEvents().stream().filter(e->e.type()==GameEvent.Type.DAMAGE).toList();
            assertEquals(2,losses.size());assertEquals(losses.getFirst().eventId(),losses.getLast().eventId());
            var rules=session.combatRules.ram();float expected=Math.min(rules.maximumDamage(),rules.damagePerExcessSpeed()*(strong.closingSpeed()-rules.minimumClosingSpeed()));
            for(var loss:losses) {
                boolean first=loss.subjectId()==strong.first();
                assertEquals(expected,loss.value(),.0001f);assertEquals(strong.point(),loss.position());
                assertEquals(first?strong.firstContact():strong.secondContact(),loss.vehicleContact());
                assertEquals(first?strong.normal().negate():strong.normal(),loss.normal());
                assertEquals(ContactSurface.METAL,loss.surface());
                assertTrue(world.vehicleContact(loss.subjectId(),loss.position(),loss.normal()).localPoint().distance(loss.vehicleContact().localPoint())>1);
            }
            assertEquals(firstHp-expected,session.vehicle(0).hp,.0001f);assertEquals(secondHp-expected,session.vehicle(1).hp,.0001f);
            combat.clear();
        }
    }
    private static PhysicsWorld.Ram nativeRam(PhysicsWorld world,float speed,float z) {
        world.teleport(0,new Vector3f(-1.2f,4,z),new Quaternion());world.teleport(1,new Vector3f(1.2f,4,z),new Quaternion());
        world.vehicle(0).setLinearVelocity(new Vector3f(speed,0,0));world.vehicle(1).setLinearVelocity(new Vector3f(-speed,0,0));
        world.step();for(int tick=1;tick<20&&world.rams().isEmpty();tick++)world.step();
        assertFalse(world.rams().isEmpty(),"Native fixture requires a real body collision");return world.rams().getFirst();
    }
    @Test void movingRotatingSweepCapturesTargetLocalAnchorAtActualContactTime() {
        try(var world=new PhysicsWorld(VehicleRules.load())) {
            world.addVehicle(0,new Vector3f(0,5,0),new Quaternion());
            world.vehicle(0).setLinearVelocity(new Vector3f(120,0,0));
            world.vehicle(0).setAngularVelocity(new Vector3f(0,6,0));world.step();
            var hit=world.sweep(new Vector3f(0,5,-8),new Vector3f(0,5,8),.1f,-1,0,1);
            assertNotNull(hit);assertEquals(0,hit.vehicleId());assertNotNull(hit.vehicleContact());
            var pose=world.interpolatedPose(0,hit.fraction());
            Vector3f restored=pose.position().add(pose.rotation().mult(hit.vehicleContact().localPoint()));
            assertTrue(restored.distance(hit.point())<.0001f,"Local point must use the collision pose, not end-of-tick pose");
            assertTrue(pose.rotation().mult(hit.vehicleContact().localNormal()).distance(hit.normal())<.0001f);
            Vector3f wrong=world.position(0).add(world.rotation(0).mult(hit.vehicleContact().localPoint()));
            assertTrue(wrong.distance(hit.point())>.05f,"Fixture must have meaningful movement after contact");
            assertEquals(ContactSurface.METAL,hit.surface());
        }
    }
    @Test void realRayAndSupportUseAuthoredStaticMaterialAndDefensiveCoordinates() {
        try(var world=new PhysicsWorld(VehicleRules.load())) {
            var arena=ArenaDefinition.load();world.configureArena(arena);
            var box=arena.boxes().stream().filter(b->b.material().equals("concrete")).findFirst().orElseThrow();
            world.addStatic(box.id(),new BoxCollisionShape(new Vector3f(2,.5f,2)),new Vector3f(0,-.5f,0),new Quaternion());
            var hit=world.ray(new Vector3f(0,2,0),new Vector3f(0,-2,0),-1);
            assertNotNull(hit);assertEquals(ContactSurface.CONCRETE,hit.surface());assertNull(hit.vehicleContact());
            var support=world.support(new Vector3f(0,1,0),2);assertNotNull(support);
            assertEquals(ContactSurface.CONCRETE,support.surface());support.point().setY(99);
            assertEquals(0,support.point().y,.001f);
        }
    }
    @Test void nativeRamRetainsTwoOutwardLocalContactNormals() {
        try(var world=new PhysicsWorld(VehicleRules.load())) {
            world.addVehicle(0,new Vector3f(-1.2f,4,0),new Quaternion());
            world.addVehicle(1,new Vector3f(1.2f,4,0),new Quaternion());
            world.vehicle(0).setLinearVelocity(new Vector3f(20,0,0));world.vehicle(1).setLinearVelocity(new Vector3f(-20,0,0));
            for(int tick=0;tick<20&&world.rams().isEmpty();tick++)world.step();
            assertFalse(world.rams().isEmpty());var ram=world.rams().getFirst();
            assertNotNull(ram.firstContact());assertNotNull(ram.secondContact());
            assertTrue(ram.firstContact().localNormal().x>.8f);
            assertTrue(ram.secondContact().localNormal().x<-.8f);
            assertTrue(ram.firstContact().localPoint().x>0);assertTrue(ram.secondContact().localPoint().x<0);
        }
    }
}
