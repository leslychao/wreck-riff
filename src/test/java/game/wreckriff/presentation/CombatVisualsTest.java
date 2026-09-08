package game.wreckriff.presentation;

import com.jme3.asset.DesktopAssetManager;
import com.jme3.math.*;
import com.jme3.scene.*;
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
