package game.wreckriff.presentation;

import com.jme3.math.*;
import com.jme3.scene.*;
import game.wreckriff.arena.*;
import game.wreckriff.simulation.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@org.junit.jupiter.api.extension.ExtendWith(PresentationTestAssets.class)
class ArenaArtTest {
    @Test void allFourScenesHaveTheirOwnLandmarksAndLocalProvenance() {
        var registry=ArenaRegistry.load();
        assertEquals(4,registry.entries().size());
        for(var entry:registry.entries()) {
            var arena=registry.definition(entry.id());var art=ArenaArt.load(arena);
            assertTrue(art.source().startsWith("src/tools/"));assertFalse(art.license().isBlank());
            if(arena.layoutRevision()>=3) {
                assertEquals(2,art.schemaVersion());assertTrue(art.models().size()>=3,"Three distinct authored interiors");
                assertTrue(art.models().stream().allMatch(model->!model.distantAsset().isEmpty()));
                assertFalse(art.lights().isEmpty(),"Interior fixtures must illuminate actual surrounding surfaces");
                assertFalse(art.parts().stream().anyMatch(p->p.id().startsWith("road-asphalt")||p.id().startsWith("lane-dash")),
                        "Road surfaces and markings belong to authored road geometry, not nav-edge overlays");
            } else assertTrue(art.parts().stream().anyMatch(p->p.id().startsWith("mast-beacon")));
        }
    }
    @Test void ferrisWheelUsesSessionTimeAndPausesWithoutAccumulatingControls() {
        var arena=ArenaRegistry.load().definition("euphoria_park");Node root=scene(arena);
        var session=new MatchSession(42,arena,MatchSession.Mode.ARENA,game.wreckriff.config.Configs.load("combat",game.wreckriff.combat.CombatRules.class));
        var visual=ArenaPresentation.attach(PresentationTestAssets.shared(),root,session,arena,new ArenaSystems(session,arena));
        Node wheel=(Node)root.getChild("art-motion-euphoria-ferris-wheel");assertNotNull(wheel);
        session.tick=100;visual.update(.016f);Quaternion pose=wheel.getLocalRotation().clone();
        for(int n=0;n<100;n++)visual.update(.1f);
        assertEquals(pose,wheel.getLocalRotation());session.tick=200;visual.update(.016f);assertNotEquals(pose,wheel.getLocalRotation());
        assertSame(visual,root.getControl(ArenaPresentation.class));
        assertTrue(wheel.getQuantity()<=4,"Rim and spokes must be batched as a rigid assembly");
        Node cabin=(Node)root.getChild("art-motion-euphoria-ferris-cabin-0");assertNotNull(cabin);
        assertTrue(cabin.getQuantity()<=5,"Cabin panels share material batches");
        for(int seconds:new int[]{0,15,30,45,60}) {
            session.tick=(long)seconds*MatchSession.TICKS_PER_SECOND;visual.update(.016f);root.updateGeometricState();
            Vector3f wheelPivot=wheel.localToWorld(new Vector3f(58,0,0),null);
            Vector3f cabinPivot=cabin.localToWorld(new Vector3f(58,0,0),null);
            assertEquals(0,wheelPivot.distance(cabinPivot),.001f,"Cabin stays attached through a whole revolution");
            assertEquals(Vector3f.UNIT_Y,cabin.getWorldRotation().mult(Vector3f.UNIT_Y),"Cabin stays upright");
            assertEquals(1300,wheel.getWorldBound().getCenter().x,.1f,"Batching preserves the authored axle position");
            assertEquals(65,wheel.getWorldBound().getCenter().y,.1f);
            assertEquals(cabinPivot.x,cabin.getWorldBound().getCenter().x,.1f,"Cabin geometry follows its actual pivot");
        }
        var stopped=cabin.getLocalTranslation().clone();
        for(int frame=0;frame<120;frame++)visual.update(.1f);
        assertEquals(stopped,cabin.getLocalTranslation(),"Paused session cannot advance cabin motion");
    }
    @Test void neonInformationScreenReactsToBossPhase() {
        var arena=ArenaRegistry.load().definition("neon_zero");var root=scene(arena);
        var session=new MatchSession(42,arena,MatchSession.Mode.ARENA,game.wreckriff.config.Configs.load("combat",game.wreckriff.combat.CombatRules.class));
        var visual=ArenaPresentation.attach(PresentationTestAssets.shared(),root,session,arena,new ArenaSystems(session,arena));
        Node screen=(Node)root.getChild("art-motion-market-screen");Geometry pixel=(Geometry)screen.getChild(0);
        ColorRGBA before=((ColorRGBA)pixel.getMaterial().getParam("Color").getValue()).clone();session.phase=MatchSession.Phase.BOSS_ENTRY;visual.update(.016f);
        assertNotEquals(before,pixel.getMaterial().getParam("Color").getValue());
    }
    @Test void authoredMeshesAreFiniteAndBatchedByIndependentSpatialCells() {
        var registry=ArenaRegistry.load();
        for(var entry:registry.entries()) {
            var arena=registry.definition(entry.id());Node root=scene(arena);root.updateGeometricState();int[] draws={0},cells={0};
            root.depthFirstTraversal(spatial->{
                if(spatial.getName().startsWith("art-cell-"))cells[0]++;
                if(spatial instanceof Geometry geometry) {
                    // Both authored LOD meshes are loaded, but a hidden alternate
                    // cannot issue a draw. Validate its data without double-counting it.
                    if(geometry.getCullHint()!=Spatial.CullHint.Always)draws[0]++;
                    var mesh=geometry.getMesh();assertTrue(mesh.getTriangleCount()>0);
                    var positions=(java.nio.FloatBuffer)mesh.getBuffer(VertexBuffer.Type.Position).getData();
                    for(int i=0;i<positions.limit();i++)assertTrue(Float.isFinite(positions.get(i)));
                }
            });
            assertTrue(cells[0]>8);
            assertTrue(draws[0]<1200,entry.id()+" excessive draws: "+draws[0]);
        }
    }
    @Test void lightingKeepsNightRoadsReadableAndRoadMaterialsRemainTextured() {
        for(var theme:ArenaDefinition.Theme.values()) {
            var profile=SceneLighting.profile(theme);
            assertTrue(profile.ambient().r>=.20f&&profile.ambient().g>=.25f&&profile.ambient().b>=.33f);
            assertTrue(profile.ambient().g+profile.key().g+profile.rim().g>=1.1f);assertTrue(profile.glow()>0&&profile.glow()<1);
        }
        var material=new SurfaceMaterials(PresentationTestAssets.shared()).material("road-patch");assertNotNull(material.getParam("DiffuseMap"));
        var arena=ArenaRegistry.load().definition("construction_17");
        assertFalse(arena.roads().isEmpty());
        assertTrue(arena.roads().stream().allMatch(road->!road.geometryIds().isEmpty()));
    }
    private static Node scene(ArenaDefinition arena) {
        Node root=new Node(arena.id());var assets=PresentationTestAssets.shared();ArenaArt.attach(assets,root,arena,new SurfaceMaterials(assets));return root;
    }
}
