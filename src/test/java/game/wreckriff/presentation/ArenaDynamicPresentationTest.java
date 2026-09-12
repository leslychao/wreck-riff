package game.wreckriff.presentation;

import com.jme3.math.*;
import com.jme3.scene.*;
import game.wreckriff.arena.*;
import game.wreckriff.combat.CombatRules;
import game.wreckriff.config.Configs;
import game.wreckriff.simulation.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ArenaDynamicPresentationTest {
    @Test void everyLaunchHasDirectionalFlushGeometryInsideItsActualFootprintAndOneOwner() {
        for(var entry:ArenaRegistry.load().entries()) {
            var f=new Fixture(entry.id());
            for(var pad:f.arena.launchPads()) {
                Node node=(Node)f.root.getChild("launch-pad-"+pad.id());assertNotNull(node);
                assertEquals(pad.source().vector(),node.getLocalTranslation());
                assertTrue(node.getLocalRotation().mult(Vector3f.UNIT_Z).dot(pad.direction())>.9999f);
                assertEquals(2,geometries(node).size(),"One deck draw and one live indicator draw per launch pad");
                f.root.updateGeometricState();
                node.depthFirstTraversal(part->{if(part instanceof Geometry geometry) {
                    var positions=(java.nio.FloatBuffer)geometry.getMesh().getBuffer(VertexBuffer.Type.Position).getData();
                    for(int i=0;i<positions.limit();i+=3) {
                        Vector3f p=geometry.localToWorld(new Vector3f(positions.get(i),positions.get(i+1),positions.get(i+2)),null);
                        p=node.worldToLocal(p,null);
                        assertTrue(Math.abs(p.x)<=pad.width()/2+.001f,pad.id()+": local width exceeded at "+p);
                        assertTrue(Math.abs(p.z)<=pad.length()/2+.001f,pad.id()+": local length exceeded at "+p);
                        assertTrue(p.y>=0&&p.y<=.065f,pad.id()+": a decal must not imply a new physical curb at "+p);
                    }
                }});
                assertEquals("READY",node.getUserData("launchPhase"));
                var indicator=geometries(node).stream().map(Geometry::getMaterial)
                        .filter(material->material.getParam("Color")!=null).findFirst().orElseThrow();
                indicator.setColor("Color",ColorRGBA.Black);f.presentation.update(0);
                assertEquals(new ColorRGBA(.42f,.82f,.95f,1),indicator.getParam("Color").getValue(),
                        "The merged arrows must retain the live indicator material");
                var pose=node.getChild("launch-deck").getLocalTransform().clone();
                for(int n=0;n<100;n++)f.presentation.update(.1f);
                assertEquals(pose,node.getChild("launch-deck").getLocalTransform());
            }
            assertThrows(IllegalStateException.class,()->ArenaPresentation.attach(PresentationTestAssets.shared(),f.root,f.session,f.arena,f.systems));
            assertEquals(1,f.root.getNumControls());
        }
    }
    @Test void destroyedPanelAndItsArtDisappearWithoutHidingAdjacentBuilding() {
        var f=new Fixture("construction_17");var object=f.arena.destructibles().getFirst();
        Spatial body=f.root.getChild(object.geometryId());
        Node anchor=new Node("anchored-art");anchor.setUserData("artAnchor",object.geometryId());
        // Re-create the fixture with the anchored authored overlay present at attachment time.
        Node root=f.geometry();root.attachChild(anchor);
        Node neighbour=new Node("neighbour");root.attachChild(neighbour);
        var visual=ArenaPresentation.attach(PresentationTestAssets.shared(),root,f.session,f.arena,f.systems);
        f.systems.damageObject(object.geometryId(),0,object.maximumHp(),"test",1);visual.update(0);
        assertEquals(Spatial.CullHint.Always,root.getChild(object.geometryId()).getCullHint());
        assertEquals(Spatial.CullHint.Always,anchor.getCullHint());
        assertNotEquals(Spatial.CullHint.Always,neighbour.getCullHint());
        assertNotEquals(Spatial.CullHint.Always,body.getCullHint(),"Independent scene nodes are not modified");
    }
    @Test void statueWarningUsesAuthoritativeTwoSecondsAndOpenStateNotRenderTime() {
        var f=new Fixture("ash_necropolis");var object=f.arena.destructibles().stream()
                .filter(d->d.effect()==ArenaDefinition.ObjectEffect.STATUE).findFirst().orElseThrow();
        Node marker=(Node)f.root.getChild("arena-object-feedback-"+object.id());
        f.systems.damageObject(object.geometryId(),0,object.maximumHp(),"test",1);f.presentation.update(0);
        assertEquals("WARNING",marker.getUserData("objectPhase"));
        for(int i=0;i<100;i++)f.presentation.update(.2f);
        assertFalse(f.systems.objectState(object.id()).open());
        assertNotEquals(Spatial.CullHint.Always,f.root.getChild(object.geometryId()).getCullHint());
        f.steps(object.delayTicks()+1);
        assertTrue(f.systems.objectState(object.id()).open());
        assertEquals("OPEN",marker.getUserData("objectPhase"));
        assertEquals(Spatial.CullHint.Always,f.root.getChild(object.geometryId()).getCullHint());
    }
    @Test void barriersAreHiddenDuringWarningAndVisibleOnlyWhenAuthoritativelyActive() {
        var f=new Fixture("neon_zero");var barrier=f.arena.barriers().getFirst();
        Spatial body=f.root.getChild(barrier.geometryId());
        assertEquals(Spatial.CullHint.Always,body.getCullHint());
        f.steps(1680);assertTrue(f.systems.requestHazard(barrier.id(),null));f.presentation.update(0);
        assertEquals(Spatial.CullHint.Always,body.getCullHint());
        Node marker=(Node)f.root.getChild("arena-barrier-feedback-"+barrier.id());
        assertEquals(1,geometries(marker).size(),"All four marker strips share one live material and one draw");
        var lamp=geometries(marker).getFirst().getMaterial();
        ColorRGBA warningColor=((ColorRGBA)lamp.getParam("Color").getValue()).clone();
        assertEquals("WARNING",marker.getUserData("barrierPhase"));
        f.steps(barrier.warningTicks());
        assertSame(lamp,geometries(marker).getFirst().getMaterial());
        assertNotEquals(warningColor,lamp.getParam("Color").getValue(),"Batching cannot freeze the authoritative indicator");
        assertEquals("ACTIVE",marker.getUserData("barrierPhase"));
        assertNotEquals(Spatial.CullHint.Always,body.getCullHint());
        f.steps(barrier.activeTicks());assertEquals(Spatial.CullHint.Always,body.getCullHint());
    }
    @Test void necropolisObelisksActuallyMeetTheirRoofInsteadOfFloatingInSky() {
        var art=ArenaArt.load(ArenaRegistry.load().definition("ash_necropolis"));
        for(var spire:art.parts().stream().filter(p->p.id().startsWith("tomb-obelisk")).toList()) {
            var roof=art.parts().stream().filter(p->p.id().startsWith("setback-roof")
                    &&Math.abs(p.position().x()-spire.position().x())<5&&p.position().z()==spire.position().z()).findFirst().orElseThrow();
            assertTrue(spire.position().y()-spire.size().y()/2<=roof.position().y()+roof.size().y()/2);
        }
    }
    @Test void batchingSeparatesDynamicAnchorsEvenWhenTheyShareAnAnimatedGroup() {
        var f=new Fixture("neon_zero");String gate=f.arena.destructibles().getFirst().geometryId();
        String barrier=f.arena.barriers().getFirst().geometryId();
        var v=new ArenaDefinition.Vec3(0,0,0);var size=new ArenaDefinition.Vec3(1,1,1);
        var group=new ArenaArt.Group("shared",v,ArenaArt.Motion.PULSE,12,0);
        var scene=new ArenaArt.Scene(1,f.arena.id(),"test","original",List.of(group),List.of(
                new ArenaArt.Part("static-overlay","",gate,ArenaArt.Shape.BOX,"steel",v,size,v),
                new ArenaArt.Part("static-overlay-2","",gate,ArenaArt.Shape.BOX,"steel",v,size,v),
                new ArenaArt.Part("static-overlay-3","",gate,ArenaArt.Shape.BOX,"steel",v,size,v),
                new ArenaArt.Part("gate-lamp","shared",gate,ArenaArt.Shape.BOX,"light-cyan",v,size,v),
                new ArenaArt.Part("barrier-lamp","shared",barrier,ArenaArt.Shape.BOX,"light-cyan",v,size,v),
                new ArenaArt.Part("exterior-lamp","shared","exterior",ArenaArt.Shape.BOX,"light-cyan",v,size,v)));
        Node root=new Node();var assets=PresentationTestAssets.shared();
        ArenaArt.attach(assets,root,f.arena,new SurfaceMaterials(assets),scene);
        Node gateNode=(Node)root.getChild("art-anchor-"+gate);Node barrierNode=(Node)root.getChild("art-anchor-"+barrier);
        assertNotNull(gateNode);assertNotNull(barrierNode);
        assertEquals(2,geometries(gateNode).size(),"The static material is batched independently of the live motion group");
        Geometry gateLamp=(Geometry)root.getChild("gate-lamp");
        ColorRGBA before=((ColorRGBA)gateLamp.getMaterial().getParam("Color").getValue()).clone();
        f.steps(1680);assertTrue(f.systems.requestHazard(f.arena.barriers().getFirst().id(),null));
        f.steps(f.arena.barriers().getFirst().warningTicks());
        var presentation=ArenaPresentation.attach(assets,root,f.session,f.arena,f.systems);
        f.session.tick+=300;presentation.update(0);
        assertNotEquals(before,gateLamp.getMaterial().getParam("Color").getValue());
        gateNode.setCullHint(Spatial.CullHint.Always);
        assertTrue(geometries(gateNode).stream().allMatch(g->g.getCullHint()==Spatial.CullHint.Always));
        assertEquals(Spatial.CullHint.Always,root.getChild("gate-lamp").getCullHint());
        assertNotEquals(Spatial.CullHint.Always,root.getChild("barrier-lamp").getCullHint());
        assertNotEquals(Spatial.CullHint.Always,root.getChild("exterior-lamp").getCullHint());
    }
    private static List<Geometry> geometries(Spatial root) {
        List<Geometry> result=new ArrayList<>();root.depthFirstTraversal(part->{if(part instanceof Geometry g)result.add(g);});return result;
    }
    private static final class Fixture {
        final ArenaDefinition arena;final MatchSession session;final ArenaSystems systems;
        final Node root;final ArenaPresentation presentation;
        Fixture(String id) {
            arena=ArenaRegistry.load().definition(id);
            session=new MatchSession(42,arena,arena.bosses().isEmpty()?MatchSession.Mode.LEGACY:MatchSession.Mode.ARENA,Configs.load("combat",CombatRules.class));
            session.phase=MatchSession.Phase.ARENA_COMBAT;session.vehicles.forEach(v->v.hp=0);
            systems=new ArenaSystems(session,arena);root=geometry();
            presentation=ArenaPresentation.attach(PresentationTestAssets.shared(),root,session,arena,systems);
        }
        Node geometry() {
            Node result=new Node("arena");
            for(var box:arena.boxes()) {Geometry geometry=new Geometry(box.id(),SurfaceMesh.box(box.size().x()/2,box.size().y()/2,box.size().z()/2,4));
                geometry.setLocalTranslation(box.center().vector());result.attachChild(geometry);}
            return result;
        }
        void steps(int count) {for(int i=0;i<count;i++){session.tick++;systems.updateHazard(null,(id,amount,cause,event)->{});presentation.update(0);}}
    }
}
