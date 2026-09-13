package game.wreckriff.presentation;

import com.jme3.bullet.collision.shapes.BoxCollisionShape;
import com.jme3.math.*;
import com.jme3.scene.*;
import game.wreckriff.arena.*;
import game.wreckriff.combat.CombatRules;
import game.wreckriff.config.*;
import game.wreckriff.simulation.*;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class ArenaMechanismPresentationPhysicsTest {
    @ParameterizedTest @ValueSource(strings={"construction_17","euphoria_park","neon_zero"})
    void presentationFollowsActualNativeWarningAndActivePosesWithoutOwningAnyCollider(String arenaId) {
        var arena=ArenaRegistry.load().definition(arenaId);var session=new MatchSession(42,arena,MatchSession.Mode.ARENA,Configs.load("combat",CombatRules.class));
        session.phase=MatchSession.Phase.ARENA_COMBAT;var systems=new ArenaSystems(session,arena);var graph=new NavGraph(arena);
        var hazard=arena.hazards().stream().filter(h->h.type()==ArenaDefinition.HazardType.CRANE||h.type()==ArenaDefinition.HazardType.CAROUSEL||h.type()==ArenaDefinition.HazardType.TRAFFIC).findFirst().orElseThrow();
        Node scene=new Node("arena");
        try(var world=new PhysicsWorld(VehicleRules.load())) {
            for(var participant:session.vehicles) {
                var car=world.addVehicle(participant.id,new Vector3f(800+participant.id*10,2,800),new Quaternion());
                car.setGravity(Vector3f.ZERO);
            }
            int participantBodies=world.bodyCount();
            phase(systems,world,graph,hazard,ProgressStore.HazardPhase.WARNING,hazard.warningTicks());
            int initialBodies=world.bodyCount();assertTrue(initialBodies>participantBodies);
            try(var presentation=new ArenaMechanismPresentation(NativeArenaAssets.MANAGER,scene,session,arena,systems)) {
                var first=systems.mechanisms().stream().filter(v->v.hazardId().equals(hazard.id())).findFirst().orElseThrow();
                Node model=(Node)scene.getChild(first.id());assertNativeAndVisual(world,first,model);
                if(hazard.type()==ArenaDefinition.HazardType.CRANE)assertEquals(hazard.minY()+.1f+12,first.position().y,.001f);
                var warningPose=model.getLocalTransform().clone();var saved=systems.snapshot();
                for(int frame=0;frame<90;frame++)presentation.update(.3f);
                assertEquals(warningPose,model.getLocalTransform());assertEquals(saved,systems.snapshot());assertEquals(initialBodies,world.bodyCount());
                session.tick+=60;
                phase(systems,world,graph,hazard,ProgressStore.HazardPhase.ACTIVE,hazard.activeTicks()-60);
                presentation.synchronize();var active=systems.mechanisms().stream().filter(v->v.hazardId().equals(hazard.id())).findFirst().orElseThrow();
                assertSame(model,scene.getChild(active.id()));assertNativeAndVisual(world,active,model);
                assertNotEquals(warningPose,model.getLocalTransform());assertEquals(initialBodies,world.bodyCount());
                if(hazard.type()==ArenaDefinition.HazardType.CRANE)assertEquals(hazard.minY()+.1f+1.5f,active.position().y,.001f);
                if(hazard.type()==ArenaDefinition.HazardType.TRAFFIC)assertEquals(8,active.position().distance(first.position()),.001f);
                if(hazard.type()==ArenaDefinition.HazardType.CAROUSEL)assertEquals(0,active.rotation().mult(Vector3f.UNIT_X).x,.001f);
                systems.stop(world);presentation.synchronize();assertEquals(participantBodies,world.bodyCount());
                assertEquals(Spatial.CullHint.Always,model.getLocalCullHint());
            }
            assertEquals(0,scene.getQuantity());assertEquals(participantBodies,world.bodyCount());
        }
    }

    private static void phase(ArenaSystems systems,PhysicsWorld world,NavGraph graph,ArenaDefinition.Hazard target,
                              ProgressStore.HazardPhase phase,long remaining) {
        var saved=systems.snapshot();var states=new LinkedHashMap<>(saved.hazards());
        states.put(target.id(),new ProgressStore.HazardState(phase,remaining,0,1));
        systems.restore(new ProgressStore.ArenaState(saved.pickups(),saved.objects(),states,saved.eventCooldownTicks(),saved.randomState()),world,graph);
        systems.synchronizeGeometry(world,graph);
        world.step(); // The real loop performs the native step after updating the kinematic transforms.
    }
    private static void assertNativeAndVisual(PhysicsWorld world,ArenaSystems.MechanismView view,Node model) {
        assertNotNull(model);assertEquals(view.position(),model.getLocalTranslation());assertEquals(view.rotation(),model.getLocalRotation());
        var nativeBody=world.space().getRigidBodyList().stream().filter(body->body.getPhysicsLocation(null).distance(view.position())<.001f).findFirst().orElseThrow();
        assertTrue(nativeBody.isKinematic());assertTrue(nativeBody.isContactResponse());
        assertEquals(view.halfExtents(),((BoxCollisionShape)nativeBody.getCollisionShape()).getHalfExtents(null));
        Quaternion nativeRotation=nativeBody.getPhysicsRotation(null);
        assertTrue(Math.abs(nativeRotation.dot(model.getLocalRotation()))>.9999f);
        var top=view.position().add(0,view.halfExtents().y+2,0);var bottom=view.position().subtract(0,view.halfExtents().y+2,0);
        assertEquals(view.id(),world.ray(top,bottom,-1).objectId(),"The rendered body is the actual projectile/contact obstacle");
    }
}
