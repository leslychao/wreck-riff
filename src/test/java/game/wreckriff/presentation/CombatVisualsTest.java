package game.wreckriff.presentation;

import com.jme3.asset.DesktopAssetManager;
import com.jme3.math.*;
import com.jme3.renderer.*;
import com.jme3.scene.*;
import com.jme3.scene.control.AbstractControl;
import game.wreckriff.simulation.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CombatVisualsTest {
    @Test void effectsAreBoundedFiniteAndRemovedAfterExpiryAndClose() {
        Node scene=new Node();
        try(CombatVisuals visuals=new CombatVisuals(new DesktopAssetManager(true),scene,world())) {
            MatchSession session=new MatchSession(42,180);
            for(int i=0;i<500;i++)visuals.accept(List.of(
                    event(GameEvent.Type.EXPLOSION,i,"power",6),event(GameEvent.Type.PULSE,i,"pulse",14),
                    event(GameEvent.Type.SHOT,i,"machine-gun",8)));
            assertTrue(visuals.effectCount()<=CombatVisuals.PARTICLE_LIMIT+CombatVisuals.RING_LIMIT+64);
            visuals.update(List.of(),session,.016f);
            int[] draws={0};
            scene.depthFirstTraversal(s->{if(s instanceof Geometry geometry) {
                draws[0]++;
                var vertices=(java.nio.FloatBuffer)geometry.getMesh().getBuffer(VertexBuffer.Type.Position).getData();
                for(int i=0;i<vertices.limit();i++)assertTrue(Float.isFinite(vertices.get(i)));
                assertEquals(vertices.limit()/3,geometry.getMesh().getVertexCount());
                var colors=(java.nio.FloatBuffer)geometry.getMesh().getBuffer(VertexBuffer.Type.Color).getData();
                assertEquals(vertices.limit()/3*4,colors.limit());
                if(geometry.getName().equals("particles-and-tracers")) {
                    assertTrue(vertices.limit()/3<=CombatVisuals.PARTICLE_LIMIT*6+64*12);
                    for(var kind:List.of(VertexBuffer.Type.TexCoord,VertexBuffer.Type.TexCoord2)) {
                        var data=(java.nio.FloatBuffer)geometry.getMesh().getBuffer(kind).getData();
                        assertEquals(vertices.limit()/3*2,data.limit());
                        for(int i=0;i<data.limit();i++)assertTrue(Float.isFinite(data.get(i)));
                    }
                }
            }});
            assertEquals(3,draws[0]);
            for(int frame=0;frame<200;frame++)visuals.update(List.of(),session,.02f);
            assertEquals(0,visuals.effectCount());
        }
        assertEquals(0,scene.getQuantity());
    }
    @Test void lowHpSmokeStopsAfterDestructionAndRetryOwnsNoGlobalEffects() {
        Node scene=new Node();MatchSession session=new MatchSession(1,180);session.vehicle(0).hp=59;
        for(int retry=0;retry<20;retry++) {
            try(CombatVisuals visuals=new CombatVisuals(new DesktopAssetManager(true),scene,world())) {
                session.vehicle(0).hp=59;visuals.update(List.of(),session,.016f);
                assertTrue(visuals.effectCount()>0);
                session.vehicle(0).hp=0;
                for(int frame=0;frame<80;frame++)visuals.update(List.of(),session,.02f);
                assertEquals(0,visuals.effectCount());
            }
            assertEquals(0,scene.getQuantity());
        }
    }
    @Test void damagedCarSmokeUsesSingleSoftSpriteWithBoundedRadiusAndOpacity() {
        Node scene=new Node();MatchSession session=new MatchSession(1,180);session.vehicle(0).hp=40;
        try(CombatVisuals visuals=new CombatVisuals(new DesktopAssetManager(true),scene,world())) {
            for(int frame=0;frame<90;frame++)visuals.update(List.of(),session,1f/60);
            Geometry geometry=(Geometry)((Node)scene.getChild("combat-visuals")).getChild("particles-and-tracers");
            Mesh mesh=geometry.getMesh();
            var positions=(java.nio.FloatBuffer)mesh.getBuffer(VertexBuffer.Type.Position).getData();
            var shapes=(java.nio.FloatBuffer)mesh.getBuffer(VertexBuffer.Type.TexCoord2).getData();
            var colors=(java.nio.FloatBuffer)mesh.getBuffer(VertexBuffer.Type.Color).getData();
            assertTrue(mesh.getVertexCount()>0);assertEquals(0,mesh.getVertexCount()%6);
            for(int vertex=0;vertex<mesh.getVertexCount();vertex++) {
                assertTrue(shapes.get(vertex*2)<=.55f,"Smoke radius must never grow into metre-scale walls");
                assertEquals(1,shapes.get(vertex*2+1));
                assertTrue(colors.get(vertex*4+3)<=.24f,"Single smoke sprite must remain translucent");
                int first=(vertex/6)*6;
                for(int axis=0;axis<3;axis++)assertEquals(positions.get(first*3+axis),positions.get(vertex*3+axis),
                        "All six vertices use one world centre; billboard offsets are camera-facing in the shader");
            }
            assertEquals(com.jme3.material.RenderState.BlendMode.Alpha,geometry.getMaterial().getAdditionalRenderState().getBlendMode());
            assertFalse(geometry.getMaterial().getAdditionalRenderState().isDepthWrite());
        }
    }
    @Test void transparentParticleBufferIsSortedForActualCameraIncludingRearView() {
        Node scene=new Node();
        try(CombatVisuals visuals=new CombatVisuals(new DesktopAssetManager(true),scene,world())) {
            visuals.accept(List.of(new GameEvent(GameEvent.Type.EXPLOSION,1,0,0,new Vector3f(0,1,4),"power",6),
                    new GameEvent(GameEvent.Type.EXPLOSION,2,0,0,new Vector3f(0,1,18),"power",6)));
            visuals.update(List.of(),new MatchSession(1,180),.016f);
            Geometry geometry=(Geometry)((Node)scene.getChild("combat-visuals")).getChild("particles-and-tracers");
            Camera camera=new Camera(1280,720);camera.setLocation(new Vector3f(0,2,0));
            for(Vector3f look:List.of(new Vector3f(0,2,20),new Vector3f(0,2,-20))) {
                camera.lookAt(look,Vector3f.UNIT_Y);
                geometry.getControl(AbstractControl.class).render(null,new ViewPort("test-camera",camera));
                var positions=(java.nio.FloatBuffer)geometry.getMesh().getBuffer(VertexBuffer.Type.Position).getData();
                float previous=Float.POSITIVE_INFINITY;
                for(int vertex=0;vertex<geometry.getMesh().getVertexCount();vertex+=6) {
                    Vector3f position=new Vector3f(positions.get(vertex*3),positions.get(vertex*3+1),positions.get(vertex*3+2));
                    float depth=position.subtract(camera.getLocation()).dot(camera.getDirection());
                    assertTrue(depth<=previous+.00001f,"Sprites must be ordered from back to front");previous=depth;
                }
            }
        }
    }
    private static GameEvent event(GameEvent.Type type,long id,String kind,float value) {
        return new GameEvent(type,id,0,0,new Vector3f(0,1,8),kind,value);
    }
    private static WorldQuery world() {
        return new WorldQuery() {
            public Vector3f position(int id){return new Vector3f(id*5,1,0);}
            public Vector3f velocity(int id){return Vector3f.ZERO;}
            public Quaternion rotation(int id){return Quaternion.IDENTITY;}
            public boolean grounded(int id){return true;}
            public float mass(int id){return 1100;}
            public Hit ray(Vector3f a,Vector3f b,int id){return null;}
            public Hit sweep(Vector3f a,Vector3f b,float r,int id){return null;}
            public boolean visible(Vector3f a,Vector3f b,int id){return true;}
            public float distanceToHull(int id,Vector3f p){return 0;}
            public void impulse(int id,Vector3f impulse){}
        };
    }
}
