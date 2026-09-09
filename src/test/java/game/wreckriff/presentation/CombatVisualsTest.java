package game.wreckriff.presentation;

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
        try(CombatVisuals visuals=new CombatVisuals(PresentationTestAssets.shared(),scene,world())) {
            MatchSession session=new MatchSession(42,180);
            for(int i=0;i<500;i++)visuals.accept(List.of(
                    event(GameEvent.Type.EXPLOSION,i,"power",6),event(GameEvent.Type.FREEZE,i,"freeze",2),
                    event(GameEvent.Type.SHOT,i,"machine-gun",8)));
            assertTrue(visuals.effectCount()<=CombatVisuals.PARTICLE_LIMIT+CombatVisuals.SHARD_LIMIT+CombatVisuals.FLARE_LIMIT+CombatVisuals.SHOT_LIMIT);
            visuals.update(List.of(),List.of(),List.of(),session,.016f);
            int[] draws={0};
            scene.depthFirstTraversal(s->{if(s instanceof Geometry geometry) {
                draws[0]++;
                var vertices=(java.nio.FloatBuffer)geometry.getMesh().getBuffer(VertexBuffer.Type.Position).getData();
                for(int i=0;i<vertices.limit();i++)assertTrue(Float.isFinite(vertices.get(i)));
                assertEquals(vertices.limit()/3,geometry.getMesh().getVertexCount());
                var colors=(java.nio.FloatBuffer)geometry.getMesh().getBuffer(VertexBuffer.Type.Color).getData();
                assertEquals(vertices.limit()/3*4,colors.limit());
                if(geometry.getName().equals("particles-and-tracers")) {
                    assertTrue(vertices.limit()/3<=CombatVisuals.PARTICLE_LIMIT*6+CombatVisuals.SHOT_LIMIT*12);
                    for(var kind:List.of(VertexBuffer.Type.TexCoord,VertexBuffer.Type.TexCoord2)) {
                        var data=(java.nio.FloatBuffer)geometry.getMesh().getBuffer(kind).getData();
                        assertEquals(vertices.limit()/3*2,data.limit());
                        for(int i=0;i<data.limit();i++)assertTrue(Float.isFinite(data.get(i)));
                    }
                }
            }});
            assertEquals(4,draws[0]);
            for(int frame=0;frame<200;frame++)visuals.update(List.of(),List.of(),List.of(),session,.02f);
            assertEquals(0,visuals.effectCount());
        }
        assertEquals(0,scene.getQuantity());
    }
    @Test void lowHpSmokeStopsAfterDestructionAndRetryOwnsNoGlobalEffects() {
        Node scene=new Node();MatchSession session=new MatchSession(1,180);session.vehicle(0).hp=59;
        for(int retry=0;retry<20;retry++) {
            try(CombatVisuals visuals=new CombatVisuals(PresentationTestAssets.shared(),scene,world())) {
                session.vehicle(0).hp=59;visuals.update(List.of(),List.of(),List.of(),session,.016f);
                assertTrue(visuals.effectCount()>0);
                session.vehicle(0).hp=0;
                for(int frame=0;frame<80;frame++)visuals.update(List.of(),List.of(),List.of(),session,.02f);
                assertEquals(0,visuals.effectCount());
            }
            assertEquals(0,scene.getQuantity());
        }
    }
    @Test void damagedCarSmokeUsesSingleSoftSpriteWithBoundedRadiusAndOpacity() {
        Node scene=new Node();MatchSession session=new MatchSession(1,180);session.vehicle(0).hp=40;
        try(CombatVisuals visuals=new CombatVisuals(PresentationTestAssets.shared(),scene,world())) {
            for(int frame=0;frame<90;frame++)visuals.update(List.of(),List.of(),List.of(),session,1f/60);
            Geometry geometry=(Geometry)((Node)scene.getChild("combat-visuals")).getChild("particles-and-tracers");
            Mesh mesh=geometry.getMesh();
            var positions=(java.nio.FloatBuffer)mesh.getBuffer(VertexBuffer.Type.Position).getData();
            var shapes=(java.nio.FloatBuffer)mesh.getBuffer(VertexBuffer.Type.TexCoord2).getData();
            var colors=(java.nio.FloatBuffer)mesh.getBuffer(VertexBuffer.Type.Color).getData();
            assertTrue(mesh.getVertexCount()>0);assertEquals(0,mesh.getVertexCount()%6);
            for(int vertex=0;vertex<mesh.getVertexCount();vertex++) {
                assertTrue(shapes.get(vertex*2)<=.55f,"Smoke radius must never grow into metre-scale walls");
                assertEquals(1,shapes.get(vertex*2+1));
                assertTrue(colors.get(vertex*4+3)<=.62f,"Critical smoke remains translucent even at its dense centre");
                int first=(vertex/6)*6;
                for(int axis=0;axis<3;axis++)assertEquals(positions.get(first*3+axis),positions.get(vertex*3+axis),
                        "All six vertices use one world centre; billboard offsets are camera-facing in the shader");
            }
            assertEquals(com.jme3.material.RenderState.BlendMode.Alpha,geometry.getMaterial().getAdditionalRenderState().getBlendMode());
            assertFalse(geometry.getMaterial().getAdditionalRenderState().isDepthWrite());
        }
    }
    @Test void criticalSmokeFormsVisibleBoundedVerticalPlumeAboveBonnet() {
        Node scene=new Node();MatchSession session=new MatchSession(1,180);session.vehicle(0).hp=session.vehicle(0).maximumHp*.25f;
        try(CombatVisuals visuals=new CombatVisuals(PresentationTestAssets.shared(),scene,world())) {
            for(int frame=0;frame<90;frame++)visuals.update(List.of(),List.of(),List.of(),session,1f/60);
            Geometry geometry=batch(scene,"particles-and-tracers");Mesh mesh=geometry.getMesh();
            assertTrue(mesh.getVertexCount()/6>=15,"The former six tiny sprites were invisible against the arena floor");
            assertTrue(mesh.getVertexCount()/6<=20,"Critical smoke remains a narrow bounded plume");
            var positions=mesh.getFloatBuffer(VertexBuffer.Type.Position);var colors=mesh.getFloatBuffer(VertexBuffer.Type.Color);
            float highest=0,peakOpacity=0;
            for(int vertex=0;vertex<mesh.getVertexCount();vertex+=6) {
                Vector3f centre=point(positions,vertex);highest=Math.max(highest,centre.y);
                assertTrue(Math.abs(centre.x)<.3f&&Math.abs(centre.z-1.05f)<.3f,"Smoke stays over the bonnet rather than becoming arena fog");
                peakOpacity=Math.max(peakOpacity,colors.get(vertex*4+3));
            }
            assertTrue(highest>2.8f,"Rising smoke remains visible above the roof, not hidden inside the model");
            assertTrue(peakOpacity>.45f,"Fresh billows need enough alpha to contrast with gray concrete");
            assertEquals(com.jme3.material.RenderState.BlendMode.Alpha,geometry.getMaterial().getAdditionalRenderState().getBlendMode(),
                    "Dark smoke must alpha-blend; additive particles cannot darken a background");
        }
    }
    @Test void transparentParticleBufferIsSortedForActualCameraIncludingRearView() {
        Node scene=new Node();
        try(CombatVisuals visuals=new CombatVisuals(PresentationTestAssets.shared(),scene,world())) {
            visuals.accept(List.of(new GameEvent(GameEvent.Type.EXPLOSION,1,0,0,new Vector3f(0,1,4),"power",6),
                    new GameEvent(GameEvent.Type.EXPLOSION,2,0,0,new Vector3f(0,1,18),"power",6)));
            visuals.update(List.of(),List.of(),List.of(),new MatchSession(1,180),.016f);
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
    @Test void persistentFireUsesOnlyAuthoritativeSupportedPointsAndClearsOnRemoval() {
        Node scene=new Node();MatchSession session=new MatchSession(1,180);
        var fire=new game.wreckriff.combat.CombatSystem.FireZoneView(1,0,new Vector3f(0,6,0),Vector3f.UNIT_Y,5,240,
                List.of(new Vector3f(2,6,2),new Vector3f(3,6,2)));
        var mine=new game.wreckriff.combat.CombatSystem.MineView(2,0,new Vector3f(2,6,2),Vector3f.UNIT_Y,true,3);
        try(CombatVisuals visuals=new CombatVisuals(PresentationTestAssets.shared(),scene,world())) {
            visuals.update(List.of(),List.of(mine),List.of(fire),session,.016f);
            Node root=(Node)scene.getChild("combat-visuals");
            Geometry fields=(Geometry)root.getChild("ground-fire");
            var positions=(java.nio.FloatBuffer)fields.getMesh().getBuffer(VertexBuffer.Type.Position).getData();
            assertEquals(48,fields.getMesh().getVertexCount());
            for(int i=0;i<positions.limit();i+=3) {
                assertEquals(6.028f,positions.get(i+1),.0001f,"Scorch stays on the supported floor");
                assertTrue(positions.get(i)>=1.44f && positions.get(i)<=3.56f,"No whole-radius disc through unsupported walls or edges");
            }
            assertTrue(((Geometry)root.getChild("rocket-models")).getMesh().getVertexCount()>0,"Persistent mine is visible");
            visuals.update(List.of(),List.of(),List.of(),session,.016f);
            assertEquals(0,fields.getMesh().getVertexCount());
            assertEquals(0,((Geometry)root.getChild("rocket-models")).getMesh().getVertexCount());
        }
    }
    @Test void everyThirdShotPerCarHasTravellingShortTracerClampedToAuthoritativeEndpoint() {
        Node scene=new Node();
        try(CombatVisuals visuals=new CombatVisuals(PresentationTestAssets.shared(),scene,world())) {
            Vector3f left=new Vector3f(-.53f,1.47f,2.28f),right=new Vector3f(.53f,1.47f,2.28f);
            Vector3f end=left.add(0,0,18);
            visuals.accept(List.of(shot(1,0,left,end),shot(2,0,right,end)));
            visuals.update(List.of(),List.of(),List.of(),null,.01f);
            assertTrue(visuals.tracerSegments().isEmpty());
            visuals.accept(List.of(shot(3,0,left,end),shot(4,1,right,end)));
            visuals.update(List.of(),List.of(),List.of(),null,.01f);
            var tracer=visuals.tracerSegments().getFirst();
            assertEquals(1,visuals.tracerSegments().size(),"Tracer cadence is independent for each vehicle");
            assertEquals(3,tracer.id());assertTrue(left.add(0,0,1.8f).distance(tracer.to())<.0001f);
            assertEquals(1.5f,tracer.from().distance(tracer.to()),.0001f);
            visuals.update(List.of(),List.of(),List.of(),null,.095f);
            tracer=visuals.tracerSegments().getFirst();
            assertEquals(end,tracer.to(),"Head must stop at the recorded contact, even across a long render frame");
            assertTrue(tracer.from().distance(tracer.to())<=1.50001f);
            for(int frame=0;frame<10;frame++)visuals.update(List.of(),List.of(),List.of(),null,.05f);
            assertEquals(0,visuals.effectCount(),"A miss has no impact or particles after its flash/tracer expires");
        }
    }
    @Test void muzzleFlashUsesActualAlternatingOriginsAndVeryCloseTracerCannotCrossTheWall() {
        Node scene=new Node();
        try(CombatVisuals visuals=new CombatVisuals(PresentationTestAssets.shared(),scene,world())) {
            Vector3f left=new Vector3f(-.53f,1.47f,2.28f),right=new Vector3f(.53f,1.47f,2.28f);
            Vector3f end=left.add(0,0,.4f);
            visuals.accept(List.of(shot(1,0,left,end),shot(2,0,right,end),shot(3,0,left,end)));
            visuals.update(List.of(),List.of(),List.of(),null,0);
            var positions=batch(scene,"particles-and-tracers").getMesh().getFloatBuffer(VertexBuffer.Type.Position);
            for(int flash=0;flash<3;flash++)assertEquals(flash==1?right:left,point(positions,flash*6));
            visuals.update(List.of(),List.of(),List.of(),null,.003f);
            var tracer=visuals.tracerSegments().getFirst();assertEquals(left,tracer.from());assertEquals(end,tracer.to());
            assertTrue(tracer.from().distance(tracer.to())<.401f);
        }
    }
    @Test void impactAndShieldFlareWaitForVisualArrivalAndDedupeKeepsDifferentEventTypes() {
        Node scene=new Node();
        try(CombatVisuals visuals=new CombatVisuals(PresentationTestAssets.shared(),scene,world())) {
            Vector3f start=new Vector3f(0,1,0),end=new Vector3f(0,1,36),normal=new Vector3f(0,0,-1);
            var shot=shot(3,0,start,end);
            var hit=new GameEvent(GameEvent.Type.IMPACT,3,1,0,end,"machine-gun",8,start,normal);
            var shield=new GameEvent(GameEvent.Type.SHIELD_HIT,3,1,0,end,"machine-gun",8,start,normal);
            // Deliberately unordered transport: the shot prepass still pairs contact cosmetics.
            visuals.accept(List.of(shield,hit,shot,hit,shield,shot));
            visuals.update(List.of(),List.of(),List.of(),null,.1f);
            assertEquals(0,batch(scene,"impact-fragments").getMesh().getVertexCount());
            assertEquals(0,batch(scene,"particles-and-tracers").getMesh().getVertexCount());
            visuals.update(List.of(),List.of(),List.of(),null,.1f);
            assertEquals(6*6,batch(scene,"particles-and-tracers").getMesh().getVertexCount());
            assertEquals(16*3,batch(scene,"impact-fragments").getMesh().getVertexCount());
            var positions=batch(scene,"particles-and-tracers").getMesh().getFloatBuffer(VertexBuffer.Type.Position);
            for(int spark=0;spark<6;spark++)assertEquals(end.add(normal.mult(.02f)),point(positions,spark*6));
            int count=visuals.effectCount();visuals.accept(List.of(shot,hit,shield));assertEquals(count,visuals.effectCount());
            for(int frame=0;frame<30;frame++)visuals.update(List.of(),List.of(),List.of(),null,.02f);
            visuals.accept(List.of(shot,hit,shield));assertEquals(0,visuals.effectCount(),"Duplicate delivery after expiry must not restart an old effect");
        }
    }
    @Test void destroyingTargetCancelsPendingContactsButPreservesLaunchedTracerAndMuzzleFlash() {
        Node scene=new Node(),baselineScene=new Node();
        try(CombatVisuals visuals=new CombatVisuals(PresentationTestAssets.shared(),scene,world());
            CombatVisuals baseline=new CombatVisuals(PresentationTestAssets.shared(),baselineScene,world())) {
            Vector3f start=new Vector3f(0,1,0),end=new Vector3f(0,1,90);
            List<GameEvent> launches=List.of(shot(1,0,start,end),shot(2,0,start,end),shot(3,0,start,end));
            visuals.accept(launches);baseline.accept(launches);
            visuals.accept(List.of(new GameEvent(GameEvent.Type.IMPACT,3,1,0,end,"machine-gun",8,start,Vector3f.UNIT_Z),
                    new GameEvent(GameEvent.Type.SHIELD_HIT,3,1,0,end,"machine-gun",8,start,Vector3f.UNIT_Z)));
            visuals.update(List.of(),List.of(),List.of(),null,.02f);baseline.update(List.of(),List.of(),List.of(),null,.02f);
            assertEquals(3*6+12,batch(scene,"particles-and-tracers").getMesh().getVertexCount(),"All muzzle flashes and the third tracer remain visible");
            var death=new GameEvent(GameEvent.Type.DESTROYED,9,1,0,end,"machine-gun",0);
            visuals.accept(List.of(death));baseline.accept(List.of(death));
            for(int frame=0;frame<7;frame++) {
                visuals.update(List.of(),List.of(),List.of(),null,.1f);baseline.update(List.of(),List.of(),List.of(),null,.1f);
                if(frame<4)assertEquals(1,visuals.tracerSegments().size(),"Target death must not erase the launched tracer");
                assertSameEffects(scene,baselineScene);
                assertEquals(baseline.effectCount(),visuals.effectCount(),"Dead targets may not receive late sparks or shield flares");
            }
        }
    }
    @Test void contactsAfterDeathInSameOrLaterBatchStayCancelledButOtherTargetsStillReceiveHits() {
        Node scene=new Node(),baselineScene=new Node();
        try(CombatVisuals visuals=new CombatVisuals(PresentationTestAssets.shared(),scene,world());
            CombatVisuals baseline=new CombatVisuals(PresentationTestAssets.shared(),baselineScene,world())) {
            Vector3f start=new Vector3f(0,1,0),end=new Vector3f(0,1,36);
            var death=new GameEvent(GameEvent.Type.DESTROYED,10,1,0,end,"power",0);var launch=shot(1,0,start,end);
            var hit=new GameEvent(GameEvent.Type.IMPACT,1,1,0,end,"machine-gun",8,start,Vector3f.UNIT_Z);
            var shield=new GameEvent(GameEvent.Type.SHIELD_HIT,1,1,0,end,"machine-gun",8,start,Vector3f.UNIT_Z);
            visuals.accept(List.of(death,launch,hit,shield));baseline.accept(List.of(death,launch));
            // A new event ID after death must also be rejected; deduplication alone is insufficient.
            var later=shot(2,0,start,end);visuals.accept(List.of(later,new GameEvent(GameEvent.Type.IMPACT,2,1,0,end,"machine-gun",8,start,Vector3f.UNIT_Z),
                    new GameEvent(GameEvent.Type.SHIELD_HIT,2,1,0,end,"machine-gun",8,start,Vector3f.UNIT_Z)));baseline.accept(List.of(later));
            var validLaunch=shot(3,1,start,end);var validHit=new GameEvent(GameEvent.Type.IMPACT,3,2,1,end,"machine-gun",8,start,Vector3f.UNIT_Z);
            visuals.accept(List.of(validLaunch,validHit));baseline.accept(List.of(validLaunch,validHit));
            for(int frame=0;frame<4;frame++) {
                visuals.update(List.of(),List.of(),List.of(),null,.1f);baseline.update(List.of(),List.of(),List.of(),null,.1f);
                assertSameEffects(scene,baselineScene);assertEquals(baseline.effectCount(),visuals.effectCount());
            }
        }
    }
    private static void assertSameEffects(Node actual,Node expected) {
        for(String name:List.of("particles-and-tracers","impact-fragments")) {
            Mesh a=batch(actual,name).getMesh(),b=batch(expected,name).getMesh();assertEquals(b.getVertexCount(),a.getVertexCount());
            for(var kind:List.of(VertexBuffer.Type.Position,VertexBuffer.Type.Color)) {
                var ap=a.getFloatBuffer(kind);var bp=b.getFloatBuffer(kind);assertEquals(bp.limit(),ap.limit());
                for(int i=0;i<ap.limit();i++)assertEquals(bp.get(i),ap.get(i));
            }
        }
    }
    @Test void staticContactProducesDustWhileMissProducesNoFakeImpact() {
        Node scene=new Node();
        try(CombatVisuals visuals=new CombatVisuals(PresentationTestAssets.shared(),scene,world())) {
            Vector3f start=new Vector3f(0,1,0),end=new Vector3f(0,1,18);
            visuals.accept(List.of(shot(1,0,start,end),new GameEvent(GameEvent.Type.IMPACT,1,-1,0,end,"machine-gun",8,start,Vector3f.UNIT_Z.negate())));
            visuals.update(List.of(),List.of(),List.of(),null,.1f);
            Mesh mesh=batch(scene,"particles-and-tracers").getMesh();assertEquals(4*6,mesh.getVertexCount());
            var shape=mesh.getFloatBuffer(VertexBuffer.Type.TexCoord2);
            for(int vertex=0;vertex<mesh.getVertexCount();vertex++)assertEquals(1,shape.get(vertex*2+1),"Static surfaces emit soft dust, not metal sparks");
        }
    }
    @Test void freezeBreakupAndShieldContactFreezeOnPauseThenExpireAndCloseClears() {
        Node scene=new Node();
        CombatVisuals visuals=new CombatVisuals(PresentationTestAssets.shared(),scene,world());
        visuals.accept(List.of(event(GameEvent.Type.FREEZE,1,"freeze",2),event(GameEvent.Type.CONTROL_ENDED,2,"freeze",0),
                event(GameEvent.Type.SHIELD_HIT,3,"homing",0)));
        visuals.update(List.of(),List.of(),List.of(),null,.016f);
        Mesh mesh=batch(scene,"impact-fragments").getMesh();int count=visuals.effectCount();
        float[] before=new float[mesh.getFloatBuffer(VertexBuffer.Type.Position).limit()];
        mesh.getFloatBuffer(VertexBuffer.Type.Position).duplicate().rewind().get(before);assertTrue(before.length>0);
        for(int frame=0;frame<100;frame++)visuals.update(List.of(),List.of(),List.of(),null,0);
        float[] after=new float[mesh.getFloatBuffer(VertexBuffer.Type.Position).limit()];mesh.getFloatBuffer(VertexBuffer.Type.Position).duplicate().rewind().get(after);
        assertArrayEquals(before,after);assertEquals(count,visuals.effectCount());
        for(int frame=0;frame<100;frame++)visuals.update(List.of(),List.of(),List.of(),null,.02f);
        assertEquals(0,visuals.effectCount());assertEquals(0,mesh.getVertexCount());
        visuals.close();visuals.accept(List.of(event(GameEvent.Type.FREEZE,9,"freeze",2)));assertEquals(0,visuals.effectCount());assertEquals(0,scene.getQuantity());
    }
    @Test void criticalSmokeBeginsAtExactlyQuarterHpAndDoesNotEmitWhilePaused() {
        Node scene=new Node();MatchSession session=new MatchSession(1,180);
        try(CombatVisuals visuals=new CombatVisuals(PresentationTestAssets.shared(),scene,world())) {
            session.vehicle(0).hp=session.vehicle(0).maximumHp*.2501f;
            visuals.update(List.of(),List.of(),List.of(),session,.02f);assertEquals(0,visuals.effectCount());
            session.vehicle(0).hp=session.vehicle(0).maximumHp*.25f;
            visuals.update(List.of(),List.of(),List.of(),session,0);assertEquals(0,visuals.effectCount());
            visuals.update(List.of(),List.of(),List.of(),session,.02f);assertEquals(1,visuals.effectCount());
            for(int frame=0;frame<100;frame++)visuals.update(List.of(),List.of(),List.of(),session,0);
            assertEquals(1,visuals.effectCount());
        }
    }
    private static GameEvent shot(long id,int source,Vector3f origin,Vector3f end) {
        return new GameEvent(GameEvent.Type.SHOT,id,source,source,end,"machine-gun",8,origin,Vector3f.ZERO);
    }
    private static Geometry batch(Node scene,String name) {return (Geometry)((Node)scene.getChild("combat-visuals")).getChild(name);}
    private static Vector3f point(java.nio.FloatBuffer positions,int vertex) {return new Vector3f(positions.get(vertex*3),positions.get(vertex*3+1),positions.get(vertex*3+2));}
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
            public Vector3f closestHullPoint(int id,Vector3f from){return position(id);}
            public void impulse(int id,Vector3f linear,Vector3f torque,float cap){throw new AssertionError("Visuals must not mutate physics");}
        };
    }
}
