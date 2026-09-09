package game.wreckriff.presentation;

import com.jme3.math.*;
import com.jme3.scene.*;
import game.wreckriff.arena.*;
import game.wreckriff.simulation.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ArenaArtTest {
    @Test void allSixLocalScenesHaveUniqueLandmarksProvenanceAndNoDecorationAcrossDrivingVolumes() {
        ArenaRegistry registry=ArenaRegistry.load();
        Map<String,String> landmarks=Map.of("dead-air-yard","mast-beacon", "construction_17","crane-lattice",
                "neon_zero","neon-tower", "euphoria_park","ferris-rim", "ash_necropolis","bell-tower", "doomsday_arena","director-screen");
        assertEquals(6,registry.entries().size());
        for(var entry:registry.entries()) {
            var arena=registry.definition(entry.id());var art=ArenaArt.load(arena);
            assertTrue(art.source().contains("author_arena_art.py"));assertFalse(art.license().isBlank());
            assertTrue(art.parts().size()>=250,entry.id()+" must be an authored scene, not a palette switch");
            assertTrue(art.parts().stream().anyMatch(part->part.id().startsWith(landmarks.get(entry.id()))));
            Map<String,ArenaArt.Group> groups=new HashMap<>();for(var group:art.groups())groups.put(group.id(),group);
            Map<String,ArenaDefinition.BoxPart> boxes=new HashMap<>();for(var box:arena.boxes())boxes.put(box.id(),box);
            Set<String> dynamic=new HashSet<>();for(var object:arena.destructibles())dynamic.add(object.geometryId());
            for(var barrier:arena.barriers())dynamic.add(barrier.geometryId());
            for(var part:art.parts()) {
                assertFalse(dynamic.contains(part.anchor()),"A gate overlay would survive destruction: "+part.id());
                Vector3f position=part.position().vector();
                if(!part.group().isEmpty())position.addLocal(groups.get(part.group()).position().vector());
                Vector3f half=rotatedHalf(part);
                if(part.anchor().equals("exterior")) {
                    var b=arena.bounds();
                    assertTrue(position.x+half.x<=b.minX()+.06f||position.x-half.x>=b.maxX()-.06f
                            ||position.z+half.z<=b.minZ()+.06f||position.z-half.z>=b.maxZ()-.06f,
                            entry.id()+"/"+part.id()+" enters driving bounds");
                } else {
                    var anchor=boxes.get(part.anchor());assertNotNull(anchor,part.id()+" missing solid/surface anchor");
                    assertTrue(anchor.collision());
                    assertTrue(Math.abs(position.x-anchor.center().x())+half.x<=anchor.size().x()/2+.065f,
                            entry.id()+"/"+part.id()+" widens collision footprint X");
                    assertTrue(Math.abs(position.z-anchor.center().z())+half.z<=anchor.size().z()/2+.065f,
                            entry.id()+"/"+part.id()+" widens collision footprint Z");
                    boolean road=arena.surfaces().stream().anyMatch(surface->surface.geometryId().equals(part.anchor()));
                    if(road)assertTrue(position.y-half.y>=anchor.center().y()+anchor.size().y()/2
                                    &&position.y+half.y<=anchor.center().y()+anchor.size().y()/2+.04f,
                            part.id()+" must remain flush with a real driving surface");
                }
            }
        }
    }

    @Test void motionUsesSessionTickAndScreensReactToBossPhaseWithoutAccumulatingControls() {
        var arena=ArenaRegistry.load().definition("euphoria_park");Node root=scene(arena);
        var session=new MatchSession(42,arena,MatchSession.Mode.ARENA,game.wreckriff.config.Configs.load("combat",game.wreckriff.combat.CombatRules.class));
        var visual=ArenaPresentation.attach(PresentationTestAssets.shared(),root,session,arena,new ArenaSystems(session,arena));
        Node wheel=(Node)root.getChild("art-motion-euphoria-ferris-wheel");assertNotNull(wheel);
        session.tick=100;visual.update(.016f);Quaternion pose=wheel.getLocalRotation().clone();
        for(int n=0;n<100;n++)visual.update(.1f);
        assertEquals(pose,wheel.getLocalRotation(),"Paused simulation must freeze ambient motion");
        session.tick=200;visual.update(.016f);assertNotEquals(pose,wheel.getLocalRotation());
        assertEquals(1,root.getNumControls());

        arena=ArenaRegistry.load().definition("doomsday_arena");root=scene(arena);
        session=new MatchSession(42,arena,MatchSession.Mode.ARENA,game.wreckriff.config.Configs.load("combat",game.wreckriff.combat.CombatRules.class));
        visual=ArenaPresentation.attach(PresentationTestAssets.shared(),root,session,arena,new ArenaSystems(session,arena));
        Node screen=(Node)root.getChild("art-motion-director-screen");Geometry eye=(Geometry)screen.getChild(0);
        ColorRGBA before=((ColorRGBA)eye.getMaterial().getParam("Color").getValue()).clone();
        session.phase=MatchSession.Phase.BOSS_ENTRY;visual.update(.016f);
        assertNotEquals(before,eye.getMaterial().getParam("Color").getValue());
        assertEquals("BOSS_ENTRY",screen.getUserData("presentationPhase"));
    }

    @Test void authoredMeshesAreFiniteAndStaticSceneryIsBatchedBySpatialCell() {
        var registry=ArenaRegistry.load();
        for(var entry:registry.entries()) {
            var arena=registry.definition(entry.id());Node root=scene(arena);root.updateGeometricState();
            int[] draws={0};
            root.depthFirstTraversal(spatial->{if(spatial instanceof Geometry geometry) {
                draws[0]++;Mesh mesh=geometry.getMesh();assertTrue(mesh.getTriangleCount()>0);
                var positions=(java.nio.FloatBuffer)mesh.getBuffer(VertexBuffer.Type.Position).getData();
                for(int i=0;i<positions.limit();i++)assertTrue(Float.isFinite(positions.get(i)));
            }});
            assertTrue(draws[0]<ArenaArt.load(arena).parts().size()*.75f,entry.id()+" static geometry must be batched");
            assertTrue(draws[0]<600,entry.id()+" scenery has too many draw calls: "+draws[0]);
        }
    }

    @Test void lightingProfilesAreDifferentButKeepNightRoadsReadable() {
        Set<ColorRGBA> skies=new HashSet<>();
        for(var theme:ArenaDefinition.Theme.values()) {
            var profile=SceneLighting.profile(theme);skies.add(profile.sky());
            assertTrue(profile.ambient().r>=.28f&&profile.ambient().g>=.29f&&profile.ambient().b>=.38f);
            assertTrue(profile.glow()>0&&profile.glow()<1);
        }
        assertEquals(6,skies.size());
    }

    private static Node scene(ArenaDefinition arena) {
        Node root=new Node(arena.id());var assets=PresentationTestAssets.shared();
        ArenaArt.attach(assets,root,arena,new SurfaceMaterials(assets));return root;
    }
    private static Vector3f rotatedHalf(ArenaArt.Part part) {
        Vector3f size=part.size().vector().mult(.5f);
        var rotation=new Quaternion().fromAngles(part.rotation().vector().mult(FastMath.DEG_TO_RAD).toArray(null)).toRotationMatrix();
        return new Vector3f(Math.abs(rotation.m00)*size.x+Math.abs(rotation.m01)*size.y+Math.abs(rotation.m02)*size.z,
                Math.abs(rotation.m10)*size.x+Math.abs(rotation.m11)*size.y+Math.abs(rotation.m12)*size.z,
                Math.abs(rotation.m20)*size.x+Math.abs(rotation.m21)*size.y+Math.abs(rotation.m22)*size.z);
    }
}
