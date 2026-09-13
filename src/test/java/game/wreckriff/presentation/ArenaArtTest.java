package game.wreckriff.presentation;

import com.jme3.math.*;
import com.jme3.scene.*;
import game.wreckriff.arena.*;
import game.wreckriff.simulation.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ArenaArtTest {
    @Test void allFourScenesHaveTheirOwnLandmarksAndLocalProvenance() {
        var registry=ArenaRegistry.load();
        var landmarks=Map.of("dead-air-yard","mast-beacon","construction_17","crane-lattice","neon_zero","neon-tower","euphoria_park","ferris-rim");
        assertEquals(4,registry.entries().size());
        for(var entry:registry.entries()) {
            var arena=registry.definition(entry.id());var art=ArenaArt.load(arena);
            assertTrue(art.source().contains("author_arena_art.py"));assertFalse(art.license().isBlank());
            assertTrue(art.parts().size()>=250);assertTrue(art.parts().stream().anyMatch(p->p.id().startsWith(landmarks.get(entry.id()))));
            if(arena.layoutRevision()>=2) {
                assertTrue(art.parts().stream().anyMatch(p->p.id().startsWith("clerestory")),"Real interior facades");
                assertTrue(art.parts().stream().anyMatch(p->p.id().startsWith("lane-dash")),"Authored roads must be visible");
                assertFalse(art.parts().stream().anyMatch(p->p.id().startsWith("district-horizon")||p.id().startsWith("perimeter-panel")),
                        "The three locations must not share a repeated box-and-panel horizon");
            }
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
        assertEquals(1,root.getNumControls());
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
                    draws[0]++;var mesh=geometry.getMesh();assertTrue(mesh.getTriangleCount()>0);
                    var positions=(java.nio.FloatBuffer)mesh.getBuffer(VertexBuffer.Type.Position).getData();
                    for(int i=0;i<positions.limit();i++)assertTrue(Float.isFinite(positions.get(i)));
                }
            });
            assertTrue(cells[0]>8);assertTrue(draws[0]<ArenaArt.load(arena).parts().size()*.75f,entry.id()+" requires static batching");
            assertTrue(draws[0]<1200,entry.id()+" excessive draws: "+draws[0]);
        }
    }
    @Test void lightingKeepsNightRoadsReadableAndRoadRepairsRemainTextured() {
        for(var theme:ArenaDefinition.Theme.values()) {
            var profile=SceneLighting.profile(theme);
            assertTrue(profile.ambient().r>=.20f&&profile.ambient().g>=.25f&&profile.ambient().b>=.33f);
            assertTrue(profile.ambient().g+profile.key().g+profile.rim().g>=1.1f);assertTrue(profile.glow()>0&&profile.glow()<1);
        }
        var material=new SurfaceMaterials(PresentationTestAssets.shared()).material("road-patch");assertNotNull(material.getParam("DiffuseMap"));
        var art=ArenaArt.load(ArenaRegistry.load().definition("construction_17"));
        assertTrue(art.parts().stream().filter(p->p.id().startsWith("asphalt-repair")).allMatch(p->p.shape()==ArenaArt.Shape.PATCH&&p.size().y()<=.008f));
        assertTrue(art.parts().stream().anyMatch(p->p.id().startsWith("roof-expansion-joint")));
    }
    private static Node scene(ArenaDefinition arena) {
        Node root=new Node(arena.id());var assets=PresentationTestAssets.shared();ArenaArt.attach(assets,root,arena,new SurfaceMaterials(assets));return root;
    }
}
