package game.wreckriff.presentation;

import com.jme3.font.BitmapText;
import com.jme3.material.RenderState;
import com.jme3.math.Vector3f;
import com.jme3.scene.*;
import game.wreckriff.arena.*;
import game.wreckriff.combat.CombatRules;
import game.wreckriff.config.Configs;
import game.wreckriff.simulation.MatchSession;
import java.io.StringReader;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ArenaSignTest {
    private static final ArenaDefinition.Vec3 POSITION=new ArenaDefinition.Vec3(12,8,25);

    @Test void optionalSignsKeepOldConstructorsAndStrictJsonCompatibility() {
        var old=new ArenaArt.Scene(1,"dead-air-yard","test","original",List.of(),List.of());
        assertTrue(old.signs().isEmpty());
        assertTrue(new ArenaArt.Scene(2,"dead-air-yard","test","original",List.of(),List.of(),List.of(),List.of()).signs().isEmpty());
        var json=Configs.gson().toJsonTree(old).getAsJsonObject();json.remove("signs");
        assertTrue(ArenaArt.read(new StringReader(json.toString())).signs().isEmpty());
        json.add("signs",com.google.gson.JsonNull.INSTANCE);
        assertThrows(IllegalArgumentException.class,()->ArenaArt.read(new StringReader(json.toString())));
        json.remove("signs");json.addProperty("singns","typo");
        assertThrows(IllegalArgumentException.class,()->ArenaArt.read(new StringReader(json.toString())));
        var current=scene("dead-air-yard",List.of(sign("archive","",90)));
        assertEquals(current,ArenaArt.read(new StringReader(Configs.gson().toJson(current))));
        assertTrue(ArenaArt.load(ArenaRegistry.load().definition("dead-air-yard")).signs().isEmpty());
    }

    @Test void sizesCoordinatesYawAndIdentityAreValidated() {
        for(float invalid:new float[]{0,-1,Float.NaN,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class,()->new ArenaArt.Sign("a","","ARCHIVE",POSITION,0,invalid,1));
            assertThrows(IllegalArgumentException.class,()->new ArenaArt.Sign("a","","ARCHIVE",POSITION,0,1,invalid));
        }
        assertThrows(IllegalArgumentException.class,()->new ArenaArt.Sign("a","","ARCHIVE",POSITION,Float.NaN,1,1));
        assertThrows(IllegalArgumentException.class,()->new ArenaArt.Sign(" ","","ARCHIVE",POSITION,0,1,1));
        assertThrows(IllegalArgumentException.class,()->new ArenaArt.Sign("a",""," \n ",POSITION,0,1,1));
        assertThrows(IllegalArgumentException.class,()->new ArenaDefinition.Vec3(Float.NaN,0,0));
        var sign=sign("a","",0);
        assertThrows(IllegalArgumentException.class,()->scene("dead-air-yard",List.of(sign,sign)));
        var zero=new ArenaDefinition.Vec3(0,0,0);var one=new ArenaDefinition.Vec3(1,1,1);
        var part=new ArenaArt.Part("a","","",ArenaArt.Shape.BOX,"steel",zero,one,zero);
        assertThrows(IllegalArgumentException.class,()->new ArenaArt.Scene(2,"dead-air-yard","test","original",List.of(),List.of(part),List.of(),List.of(),List.of(sign)));
        assertThrows(UnsupportedOperationException.class,()->scene("dead-air-yard",List.of(sign)).signs().add(sign));
    }

    @Test void textIsActualCyrillicGlyphGeometryCentredWithinTheRequestedFrameAndFacingLocalFront() {
        var arena=ArenaRegistry.load().definition("dead-air-yard");
        Node root=attach(arena,scene(arena.id(),List.of(sign("archive","",90))));
        Node panel=(Node)root.getChild("archive");BitmapText text=(BitmapText)panel.getChild("archive-text");
        assertEquals("ГОРОДСКОЙ АРХИВ\nZERO 17",text.getText());
        assertEquals(POSITION.vector(),panel.getLocalTranslation());
        assertTrue(panel.getLocalRotation().mult(Vector3f.UNIT_Z).distance(Vector3f.UNIT_X)<.0001f);
        assertNotNull(text.getWorldBound());
        List<Geometry> glyphs=geometries(text);assertFalse(glyphs.isEmpty());
        float minX=Float.POSITIVE_INFINITY,maxX=Float.NEGATIVE_INFINITY,minY=Float.POSITIVE_INFINITY,maxY=Float.NEGATIVE_INFINITY;
        for(var glyph:glyphs) {
            assertTrue(glyph.getTriangleCount()>20,"The words must have actual glyph quads");
            assertEquals(RenderState.FaceCullMode.Back,glyph.getMaterial().getAdditionalRenderState().getFaceCullMode());
            assertTrue(glyph.getMaterial().getAdditionalRenderState().isDepthTest());
            var vertices=(java.nio.FloatBuffer)glyph.getMesh().getBuffer(VertexBuffer.Type.Position).getData();
            var indices=glyph.getMesh().getIndexBuffer();
            Vector3f a=vertex(vertices,indices.get(0)),b=vertex(vertices,indices.get(1)),c=vertex(vertices,indices.get(2));
            assertTrue(b.subtract(a).cross(c.subtract(a)).z>0,"Visible front face must point along local +Z");
            for(int i=0;i<vertices.limit();i+=3) {
                Vector3f local=panel.worldToLocal(glyph.localToWorld(new Vector3f(vertices.get(i),vertices.get(i+1),vertices.get(i+2)),null),null);
                minX=Math.min(minX,local.x);maxX=Math.max(maxX,local.x);minY=Math.min(minY,local.y);maxY=Math.max(maxY,local.y);
                assertEquals(0,local.z,.0001f,"Authored centre is already in front of the solid panel");
            }
        }
        assertEquals(0,(minX+maxX)/2,.0001f);assertEquals(0,(minY+maxY)/2,.0001f);
        assertTrue(maxX-minX<=12.0001f);assertTrue(maxY-minY<=3.0001f);
        assertEquals(text.getLocalScale().x,text.getLocalScale().y);
        Mesh mesh=glyphs.getFirst().getMesh();Object buffer=mesh.getBuffer(VertexBuffer.Type.Position).getData();
        var transform=text.getLocalTransform().clone();
        for(int i=0;i<120;i++){root.updateLogicalState(.016f);root.updateGeometricState();}
        assertSame(mesh,glyphs.getFirst().getMesh());assertSame(buffer,mesh.getBuffer(VertexBuffer.Type.Position).getData());
        assertEquals(transform,text.getLocalTransform());assertEquals(0,panel.getNumControls());
    }

    @Test void rootAndCanonicalAnchorsAreAllowedButTyposAndMotionGroupsAreRejected() {
        var arena=ArenaRegistry.load().definition("dead-air-yard");
        var root=attach(arena,scene(arena.id(),List.of(sign("empty","",0),sign("exterior","exterior",0),sign("box",arena.boxes().getFirst().id(),0))));
        assertNotNull(root.getChild("empty"));assertNotNull(root.getChild("exterior"));assertNotNull(root.getChild("box"));
        assertThrows(IllegalArgumentException.class,()->attach(arena,scene(arena.id(),List.of(sign("bad","missing-panel",0)))));
        var group=new ArenaArt.Group("wheel",POSITION,ArenaArt.Motion.ROTATE_Z,5,0);
        var scene=new ArenaArt.Scene(2,arena.id(),"test","original",List.of(group),List.of(),List.of(),List.of(),List.of(sign("bad","wheel",0)));
        assertThrows(IllegalArgumentException.class,()->attach(arena,scene));
    }

    @Test void signsShareTheDestructibleLifetimeAndPauseNeverMutatesTheText() {
        var arena=ArenaRegistry.load().definition("construction_17");var object=arena.destructibles().getFirst();
        Node root=attach(arena,scene(arena.id(),List.of(sign("gate-sign",object.geometryId(),0),sign("neighbour","",0))));
        var session=new MatchSession(42,arena,MatchSession.Mode.ARENA,Configs.load("combat",CombatRules.class));
        var systems=new ArenaSystems(session,arena);var presentation=ArenaPresentation.attach(PresentationTestAssets.shared(),root,session,arena,systems);
        Node anchor=(Node)root.getChild("art-anchor-"+object.geometryId());assertNotNull(anchor);
        assertSame(anchor,root.getChild("gate-sign").getParent());
        BitmapText text=(BitmapText)root.getChild("gate-sign-text");var pose=text.getLocalTransform().clone();
        for(int i=0;i<120;i++)presentation.update(.5f);
        assertEquals(pose,text.getLocalTransform());assertNotEquals(Spatial.CullHint.Always,anchor.getCullHint());
        systems.damageObject(object.geometryId(),0,object.maximumHp(),"test",1);presentation.update(0);
        assertEquals(Spatial.CullHint.Always,anchor.getCullHint());
        assertNotEquals(Spatial.CullHint.Always,root.getChild("neighbour").getCullHint());
        assertEquals(pose,text.getLocalTransform());
    }

    private static ArenaArt.Sign sign(String id,String anchor,float yaw) {
        return new ArenaArt.Sign(id,anchor,"ГОРОДСКОЙ АРХИВ\nZERO 17",POSITION,yaw,12,3);
    }
    private static ArenaArt.Scene scene(String arenaId,List<ArenaArt.Sign> signs) {
        return new ArenaArt.Scene(2,arenaId,"test","original",List.of(),List.of(),List.of(),List.of(),signs);
    }
    private static Node attach(ArenaDefinition arena,ArenaArt.Scene scene) {
        var assets=PresentationTestAssets.shared();Node root=new Node("arena");
        ArenaArt.attach(assets,root,arena,new SurfaceMaterials(assets),scene);root.updateGeometricState();return root;
    }
    private static List<Geometry> geometries(Spatial spatial) {
        List<Geometry> result=new ArrayList<>();spatial.depthFirstTraversal(child->{if(child instanceof Geometry geometry)result.add(geometry);});return result;
    }
    private static Vector3f vertex(java.nio.FloatBuffer positions,int index) {
        return new Vector3f(positions.get(index*3),positions.get(index*3+1),positions.get(index*3+2));
    }
}
