package game.wreckriff.presentation;

import com.jme3.bounding.BoundingBox;
import com.jme3.math.*;
import com.jme3.scene.*;
import game.wreckriff.arena.*;
import game.wreckriff.simulation.*;
import org.junit.jupiter.api.Test;
import java.nio.FloatBuffer;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real exported models and clock-driven presentation; this does not certify an OpenGL frame. */
class PickupPresentationTest {
    @Test void eightRealModelsAreDistinctFiniteSmallMeshesWithRegisteredIdentities()throws Exception {
        Set<String> hashes=new HashSet<>();
        for(PickupStyle style:PickupStyle.values()) {
            try(var bytes=Objects.requireNonNull(getClass().getResourceAsStream("/"+style.model()))) {
                assertTrue(hashes.add(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.readAllBytes()))),style.name());
            }
            Spatial model=PresentationTestAssets.shared().loadModel(style.model());model.updateGeometricState();
            assertEquals("original-java-procedural",model.getUserData("assetOrigin"));assertEquals(style.kind(),model.getUserData("pickupKind"));
            BoundingBox bounds=assertInstanceOf(BoundingBox.class,model.getWorldBound());
            Vector3f min=bounds.getMin(null),max=bounds.getMax(null);
            assertTrue(Vector3f.isValidVector(min)&&Vector3f.isValidVector(max),style.name());
            assertTrue(max.x-min.x>.2f&&max.z-min.z>.2f&&max.y-min.y>.1f,style+" is not a volume");
            assertTrue(max.x-min.x<=2.1f&&max.z-min.z<=2.1f&&max.y-min.y<=2.1f,style+" must fit the low pickup pad");
            int[] triangles={0},geometries={0};
            model.depthFirstTraversal(node->{
                assertEquals(0,node.getNumControls(),"Item models cannot introduce another simulation owner");
                if(node instanceof Geometry geometry) {
                    geometries[0]++;Mesh mesh=geometry.getMesh();triangles[0]+=mesh.getTriangleCount();
                    assertNotNull(geometry.getMaterial());assertTrue(mesh.getVertexCount()>0);
                    for(var type:List.of(VertexBuffer.Type.Position,VertexBuffer.Type.Normal,VertexBuffer.Type.TexCoord,VertexBuffer.Type.Tangent)) {
                        var buffer=mesh.getFloatBuffer(type);if(buffer==null)continue;
                        FloatBuffer data=buffer.asReadOnlyBuffer();data.rewind();
                        while(data.hasRemaining())assertTrue(Float.isFinite(data.get()),style+" non-finite "+type);
                    }
                }
            });
            assertTrue(geometries[0]>0&&geometries[0]<=8,style+" should share batched materials");
            assertTrue(triangles[0]>40&&triangles[0]<10000,style+" unexpectedly empty or unbounded mesh");
        }
        assertEquals(8,hashes.size());
    }

    @Test void availableModelsBobRotateAndUseStableDifferentPhasesWithoutMovingTheSocket() {
        try(Fixture f=new Fixture()) {
            String first=f.arena.pickups().get(0).id(),second=f.arena.pickups().get(1).id();
            var initial=f.visual.frame(first);var secondFrame=f.visual.frame(second);
            assertTrue(initial.available());assertTrue(initial.modelVisible());assertEquals(1,initial.scale());assertFalse(initial.collected());
            assertTrue(initial.height()>=.95f&&initial.height()<=1.25f);assertNotEquals(initial.yaw(),secondFrame.yaw());
            Node holder=(Node)f.visual.root().getChild("pickup-"+first);Vector3f socket=holder.getLocalTranslation().clone();
            for(int frame=0;frame<120;frame++)f.visual.update(0);
            assertEquals(initial,f.visual.frame(first),"Render calls alone must not advance a paused pickup");
            f.session.tick=72;f.visual.update(0);
            assertEquals(FastMath.TWO_PI*.6f/12,f.visual.frame(first).yaw()-initial.yaw(),.00001f);
            assertNotEquals(initial.height(),f.visual.frame(first).height());assertEquals(socket,holder.getLocalTranslation());
            f.session.tick=1440;f.visual.update(0);
            assertEquals(initial.yaw(),f.visual.frame(first).yaw(),.00001f);
            assertEquals(initial.height(),f.visual.frame(first).height(),.00001f);
            assertTrue(f.bursts.isEmpty());assertTrue(f.systems.drainEvents().isEmpty());
        }
    }

    @Test void successfulGrantShrinksForPointTwoSecondsAndRespawnsForPointTwoFiveSeconds() {
        try(Fixture f=new Fixture()) {
            var pickup=f.pickup(ArenaDefinition.PickupType.CANNON_AMMO);f.session.tick=600;
            f.world.positions[0]=pickup.position().vector().add(0,.8f,0);f.visual.update(0);
            f.systems.collectPickups(f.world);List<GameEvent> events=f.systems.drainEvents();assertEquals(1,events.size());
            f.visual.accept(events);f.visual.accept(events);f.visual.update(0);
            assertEquals(List.of("cannon-ammo"),f.bursts);assertTrue(f.visual.frame(pickup.id()).collected());
            assertFalse(f.visual.frame(pickup.id()).available());assertEquals(1,f.visual.frame(pickup.id()).scale());
            f.session.tick=612;f.visual.update(0);assertEquals(.5f,f.visual.frame(pickup.id()).scale(),.00001f);
            f.session.tick=624;f.visual.update(0);
            assertFalse(f.visual.frame(pickup.id()).modelVisible(),"Exactly 0.2 seconds completes the shrink");
            assertFalse(f.visual.frame(pickup.id()).collected());
            Node holder=(Node)f.visual.root().getChild("pickup-"+pickup.id());
            assertNotEquals(Spatial.CullHint.Always,holder.getChild("pickup-symbol").getCullHint(),"Empty road symbol remains visible");
            long respawn=600L+pickup.respawnTicks();f.session.tick=respawn-1;f.visual.update(0);assertFalse(f.visual.frame(pickup.id()).modelVisible());
            f.session.tick=respawn;f.visual.update(0);assertTrue(f.visual.frame(pickup.id()).available());assertTrue(f.visual.frame(pickup.id()).modelVisible());
            assertTrue(f.visual.frame(pickup.id()).scale()>0&&f.visual.frame(pickup.id()).scale()<.1f);
            f.session.tick=respawn+15;f.visual.update(0);assertEquals(.5f,f.visual.frame(pickup.id()).scale(),.00001f);
            f.session.tick=respawn+30;f.visual.update(0);assertEquals(1,f.visual.frame(pickup.id()).scale(),.00001f);
            assertEquals(1,f.bursts.size(),"Respawn never creates a success burst");
        }
    }

    @Test void restoredUnavailableSocketStartsQuietAndIgnoresPreviousSessionSuccess() {
        try(Fixture previous=new Fixture()) {
            var pickup=previous.pickup(ArenaDefinition.PickupType.HOMING_AMMO);
            previous.world.positions[0]=pickup.position().vector().add(0,.8f,0);
            previous.systems.collectPickups(previous.world);var oldEvents=previous.systems.drainEvents();
            var snapshot=previous.systems.snapshot();
            MatchSession restored=new MatchSession(42,360);ArenaSystems systems=new ArenaSystems(restored,previous.arena);
            systems.restore(snapshot,null,new NavGraph(previous.arena));List<String> bursts=new ArrayList<>();Node parent=new Node();
            try(var visual=new PickupPresentation(PresentationTestAssets.shared(),parent,restored,previous.arena,systems,(kind,point)->bursts.add(kind))) {
                assertFalse(visual.frame(pickup.id()).available());assertFalse(visual.frame(pickup.id()).modelVisible());assertFalse(visual.frame(pickup.id()).collected());
                visual.accept(oldEvents);visual.update(0);assertTrue(bursts.isEmpty());assertTrue(systems.drainEvents().isEmpty());
                assertFalse(visual.frame(pickup.id()).modelVisible());
            }
            assertEquals(0,parent.getQuantity());
        }
    }

    @Test void disablingGlowKeepsIdentityAndBotCollectionStillRemovesTheWorldItem() {
        try(Fixture f=new Fixture()) {
            var pickup=f.pickup(ArenaDefinition.PickupType.NAPALM_AMMO);f.visual.setGlow(false);f.visual.update(0);
            Node holder=(Node)f.visual.root().getChild("pickup-"+pickup.id());Geometry symbol=(Geometry)holder.getChild("pickup-symbol");
            assertEquals(ColorRGBA.Black,symbol.getMaterial().getParam("GlowColor").getValue());
            assertEquals(PickupStyle.NAPALM.color(),symbol.getMaterial().getParam("Color").getValue());
            f.world.positions[1]=pickup.position().vector().add(0,.8f,0);f.systems.collectPickups(f.world);
            var events=f.systems.drainEvents();assertEquals(1,events.getFirst().subjectId());f.visual.accept(events);f.visual.update(0);
            assertTrue(f.visual.frame(pickup.id()).collected());assertEquals(List.of("napalm-ammo"),f.bursts);
            assertFalse(f.visual.frame(pickup.id()).available());
        }
    }

    @Test void malformedSocketOrKindIsRejectedAndZeroGrantHasNoSuccess() {
        try(Fixture f=new Fixture()) {
            var pickup=f.pickup(ArenaDefinition.PickupType.CANNON_AMMO);
            GameEvent valid=new GameEvent(GameEvent.Type.PICKUP,1,0,0,pickup.position().vector(),"cannon-ammo",1)
                    .forObject(pickup.id()).inSession(f.session.sessionId);
            assertThrows(IllegalArgumentException.class,()->f.visual.accept(List.of(valid.forObject("missing-socket"))));
            var wrong=new GameEvent(GameEvent.Type.PICKUP,2,0,0,pickup.position().vector(),"homing-ammo",1)
                    .forObject(pickup.id()).inSession(f.session.sessionId);
            assertThrows(IllegalArgumentException.class,()->f.visual.accept(List.of(wrong)));
            var empty=new GameEvent(GameEvent.Type.PICKUP,3,0,0,pickup.position().vector(),"cannon-ammo",0)
                    .forObject(pickup.id()).inSession(f.session.sessionId);
            f.visual.accept(List.of(empty));assertTrue(f.bursts.isEmpty());assertFalse(f.visual.frame(pickup.id()).collected());
        }
    }

    private static final class Fixture implements AutoCloseable {
        final ArenaDefinition arena=ArenaDefinition.load();final MatchSession session=new MatchSession(42,360);
        final ArenaSystems systems=new ArenaSystems(session,arena);final TestWorld world=new TestWorld();
        final Node parent=new Node();final List<String> bursts=new ArrayList<>();
        final PickupPresentation visual=new PickupPresentation(PresentationTestAssets.shared(),parent,session,arena,systems,(kind,position)->bursts.add(kind));
        ArenaDefinition.Pickup pickup(ArenaDefinition.PickupType type){return arena.pickups().stream().filter(p->p.type()==type).findFirst().orElseThrow();}
        @Override public void close(){visual.close();assertEquals(0,parent.getQuantity());}
    }
}
