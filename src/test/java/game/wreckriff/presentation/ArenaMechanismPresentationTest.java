package game.wreckriff.presentation;

import com.jme3.math.*;
import com.jme3.scene.*;
import game.wreckriff.arena.*;
import game.wreckriff.simulation.MatchSession;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ArenaMechanismPresentationTest {
    @Test void originalLoadArmAndServiceCarStayInsideTheirNativeEnvelopeWithFewBatches() {
        for(String arenaId:List.of("construction_17","euphoria_park","neon_zero")) {
            try(var f=new Fixture(arenaId)) {
                var view=f.show();Node body=f.body();f.parent.updateGeometricState();
                assertEquals(view.position(),body.getLocalTranslation());assertEquals(view.rotation(),body.getLocalRotation());
                assertEquals(Vector3f.UNIT_XYZ,body.getLocalScale());assertEquals("original-java-procedural",body.getUserData("assetOrigin"));
                int[] draws={0},triangles={0};
                body.depthFirstTraversal(part->{if(part instanceof Geometry geometry) {
                    draws[0]++;triangles[0]+=geometry.getTriangleCount();assertNotNull(geometry.getMaterial());
                    var positions=geometry.getMesh().getFloatBuffer(VertexBuffer.Type.Position);Vector3f h=view.halfExtents();
                    for(int i=0;i<positions.limit();i+=3) {
                        Vector3f p=body.worldToLocal(geometry.localToWorld(new Vector3f(positions.get(i),positions.get(i+1),positions.get(i+2)),null),null);
                        assertTrue(Math.abs(p.x)<=h.x+.0001f,view.type()+" width "+p);
                        assertTrue(Math.abs(p.y)<=h.y+.0001f,view.type()+" height "+p);
                        assertTrue(Math.abs(p.z)<=h.z+.0001f,view.type()+" length "+p);
                    }
                    for(var kind:List.of(VertexBuffer.Type.Position,VertexBuffer.Type.Normal,VertexBuffer.Type.Tangent)) {
                        var buffer=geometry.getMesh().getFloatBuffer(kind);assertNotNull(buffer);
                        for(int i=0;i<buffer.limit();i++)assertTrue(Float.isFinite(buffer.get(i)));
                    }
                }});
                assertTrue(draws[0]>=3&&draws[0]<=5,"Material batch budget "+draws[0]);
                assertTrue(triangles[0]<=1800,"Triangle budget "+triangles[0]);
                assertNull(body.getChild("hazard-warning"),"The existing hazard owner retains the only warning");
            }
        }
    }

    @Test void nativePoseChangesAtSameTickButRenderTimeCannotMoveAnyMechanism() {
        try(var f=new Fixture("euphoria_park")) {
            f.show();Node body=f.body();Transform initial=body.getLocalTransform().clone();
            for(int i=0;i<120;i++)f.visual.update(.5f);
            assertEquals(initial,body.getLocalTransform(),"Paused session cannot advance a second visual clock");
            var view=f.views.getFirst();Vector3f position=new Vector3f(27,5,39);
            Quaternion rotation=new Quaternion().fromAngleAxis(1.83f,Vector3f.UNIT_Y);
            f.views=List.of(new ArenaSystems.MechanismView(view.id(),view.hazardId(),view.type(),view.halfExtents(),position,rotation));
            f.visual.synchronize();assertSame(body,f.body());assertEquals(position,body.getLocalTranslation());assertEquals(rotation,body.getLocalRotation());
            assertEquals(0L,body.<Long>getUserData("presentationTick"));
            f.session.tick=120;f.visual.update(0);assertEquals(120L,body.<Long>getUserData("presentationTick"));
            assertEquals(position,body.getLocalTranslation());
        }
    }

    @Test void removalRespawnAndCheckpointReuseOnlyOneModelPerHazardAndCleanupDetachesEverything() {
        var f=new Fixture("neon_zero");var view=f.show();Node body=f.body();Node root=(Node)f.parent.getChild("arena-mechanisms");
        int quantity=root.getQuantity();
        for(int i=0;i<25;i++) {
            f.views=List.of();f.visual.synchronize();assertEquals(Spatial.CullHint.Always,body.getLocalCullHint());
            f.views=List.of(view);f.visual.synchronize();assertSame(body,f.body());assertEquals(Spatial.CullHint.Inherit,body.getLocalCullHint());
            assertEquals(quantity,root.getQuantity());
        }
        var h=view.halfExtents().mult(.9f);
        f.views=List.of(new ArenaSystems.MechanismView("restored-body",view.hazardId(),view.type(),h,view.position(),view.rotation()));
        f.visual.synchronize();assertNull(body.getParent());assertNotNull(root.getChild("restored-body"));assertEquals(quantity,root.getQuantity());
        f.close();f.close();assertEquals(0,f.parent.getQuantity());assertEquals(0,root.getNumControls());assertEquals(0,root.getQuantity());
        f.visual.synchronize();assertEquals(0,f.parent.getQuantity());
    }

    @Test void trafficThresholdsAreFlushAtTheActualRouteEndsAndNoseFacesTravelOnEitherAxis() {
        for(boolean alongX:new boolean[]{true,false}) {
            var base=ArenaRegistry.load().definition("neon_zero");var original=base.hazards().stream().filter(h->h.type()==ArenaDefinition.HazardType.TRAFFIC).findFirst().orElseThrow();
            var traffic=new ArenaDefinition.Hazard(original.id(),original.type(),original.sector(),
                    alongX?14:62,alongX?118:80,alongX?62:14,alongX?80:118,original.minY(),original.maxY(),
                    original.offTicks(),original.warningTicks(),original.activeTicks(),original.damageIntervalTicks(),original.damage());
            var arena=withHazards(base,List.of(traffic));
            try(var f=new Fixture(arena)) {
                var view=f.show();Node body=f.body();Node shell=(Node)body.getChild("service-vehicle-shell");
                Quaternion nativeRotation=new Quaternion().fromAngleAxis(alongX?0:FastMath.HALF_PI,Vector3f.UNIT_Y);
                f.views=List.of(new ArenaSystems.MechanismView(view.id(),view.hazardId(),view.type(),view.halfExtents(),view.position(),nativeRotation));
                f.visual.synchronize();f.parent.updateGeometricState();
                Vector3f facing=shell.getWorldRotation().mult(Vector3f.UNIT_Z);
                assertTrue(facing.dot(alongX?Vector3f.UNIT_X:Vector3f.UNIT_Z)>.9999f);
                for(int end=0;end<2;end++) {
                    Node marker=(Node)f.parent.getChild("service-"+(end==0?"entry-":"exit-")+traffic.id());
                    float along=34+end*64;Vector3f expected=alongX?new Vector3f(along,.008f,71):new Vector3f(71,.008f,along);
                    assertEquals(expected.x,marker.getLocalTranslation().x,.0001f);assertEquals(expected.y,marker.getLocalTranslation().y,.0001f);
                    assertEquals(expected.z,marker.getLocalTranslation().z,.0001f);
                    assertEquals(1,marker.getQuantity());var mesh=((Geometry)marker.getChild(0)).getMesh();var p=mesh.getFloatBuffer(VertexBuffer.Type.Position);
                    for(int i=1;i<p.limit();i+=3)assertTrue(Math.abs(p.get(i))<=.0041f,"No phantom curb or closed service gate");
                }
            }
        }
    }

    @Test void snapshotsAreDefensiveAndInvalidOrUnregisteredPosesAreRejected() {
        try(var f=new Fixture("construction_17")) {
            var view=f.show();Vector3f original=view.position();view.position().set(999,999,999);view.halfExtents().zero();view.rotation().set(0,0,0,0);
            f.visual.synchronize();assertEquals(original,f.body().getLocalTranslation());
            f.views=List.of(view,view);assertThrows(IllegalArgumentException.class,f.visual::synchronize);
            f.views=List.of(new ArenaSystems.MechanismView(view.id(),"missing",view.type(),view.halfExtents(),view.position(),view.rotation()));
            assertThrows(IllegalArgumentException.class,f.visual::synchronize);
            for(Vector3f invalid:List.of(new Vector3f(0,1,1),new Vector3f(Float.NaN,1,1),new Vector3f(1,-1,1))) {
                f.views=List.of(new ArenaSystems.MechanismView(view.id(),view.hazardId(),view.type(),invalid,view.position(),view.rotation()));
                assertThrows(IllegalArgumentException.class,f.visual::synchronize);
            }
        }
    }

    private static ArenaDefinition withHazards(ArenaDefinition a,List<ArenaDefinition.Hazard> hazards) {
        return new ArenaDefinition(a.schemaVersion(),a.id(),a.metadata(),a.bounds(),a.boxes(),a.ramps(),a.spawns(),a.pickups(),hazards,a.nodes(),a.edges(),
                a.surfaces(),a.launchPads(),a.drops(),a.destructibles(),a.secrets(),a.barriers(),a.bosses());
    }
    private static final class Fixture implements AutoCloseable {
        final ArenaDefinition arena;final MatchSession session=new MatchSession(42,360);final Node parent=new Node("arena");
        final ArenaMechanismPresentation visual;List<ArenaSystems.MechanismView> views=List.of();
        Fixture(String id) {this(ArenaRegistry.load().definition(id));}
        Fixture(ArenaDefinition arena) {this.arena=arena;visual=new ArenaMechanismPresentation(PresentationTestAssets.shared(),parent,session,arena,()->views);}
        ArenaSystems.MechanismView show() {
            var hazard=arena.hazards().stream().filter(h->h.type()==ArenaDefinition.HazardType.CRANE||h.type()==ArenaDefinition.HazardType.CAROUSEL||h.type()==ArenaDefinition.HazardType.TRAFFIC).findFirst().orElseThrow();
            Vector3f half=switch(hazard.type()) {case CRANE->new Vector3f(4,1.5f,4);case CAROUSEL->new Vector3f(17,.65f,.65f);default->new Vector3f(2.3f,1.1f,1.1f);};
            var view=new ArenaSystems.MechanismView("mechanism-"+hazard.id(),hazard.id(),hazard.type(),half,new Vector3f(31,5,29),new Quaternion().fromAngleAxis(.73f,Vector3f.UNIT_Y));
            views=List.of(view);visual.synchronize();return view;
        }
        Node body() {return (Node)parent.getChild(views.getFirst().id());}
        @Override public void close() {visual.close();}
    }
}
