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
    @Test void externalPresentationClockKeepsTraceAlignedAndResultsTailExpires() {
        Node scene=new Node();
        try(var visuals=new CombatVisuals(PresentationTestAssets.shared(),scene,world())) {
            visuals.setPresentationTime(10);
            for(int i=1;i<=3;i++)visuals.accept(List.of(shot(i,0,new Vector3f(0,1,0),new Vector3f(0,1,36)).atTick(1200,i)));
            visuals.setPresentationTime(10.1);visuals.update(List.of(),List.of(),List.of(),List.of(),null,1f/120);
            assertEquals(18,visuals.tracerSegments().getFirst().to().z,.0001);
            visuals.setPresentationTime(10.3);visuals.update(List.of(),List.of(),List.of(),List.of(),null,1f/120);
            assertTrue(visuals.tracerSegments().isEmpty(),"The monotonic Results presentation clock must retire the final launched trace");
            for(int frame=0;frame<100;frame++)visuals.update(List.of(),List.of(),List.of(),List.of(),null,.02f);
            assertEquals(0,visuals.effectCount());assertEquals(3,visuals.statistics().get("peakShots"));
        }
    }
    @Test void preparedDetachedPanelPreservesAuthoredGeometryWithinOneLitBatch() {
        Node scene=new Node();var triangle=new Mesh();
        triangle.setBuffer(VertexBuffer.Type.Position,3,new float[]{0,0,0,.4f,0,0,0,.2f,.6f});triangle.updateBound();
        try(var visuals=new CombatVisuals(PresentationTestAssets.shared(),scene,world())) {
            visuals.detachPanel(new Vector3f(2,1,4),Quaternion.IDENTITY,Vector3f.ZERO,new Vector3f(.4f,.2f,.6f),triangle,ContactSurface.METAL,0);
            visuals.update(List.of(),List.of(),List.of(),List.of(),null,0);
            var mesh=batch(scene,"impact-fragments").getMesh();assertEquals(3,mesh.getVertexCount());
            assertEquals(new Vector3f(2.4f,1,4),point(mesh.getFloatBuffer(VertexBuffer.Type.Position),1));
            assertEquals(3,triangle.getVertexCount());assertEquals(0,triangle.getFloatBuffer(VertexBuffer.Type.Position).get(0));
            assertNotNull(mesh.getBuffer(VertexBuffer.Type.Normal));
        }
    }
    @Test void freezeContactUsesColdVapourAndOutwardIceInsteadOfHotMaterialSparks() {
        Node scene=new Node();Vector3f hit=new Vector3f(0,2,8),normal=Vector3f.UNIT_X;
        try(var visuals=new CombatVisuals(PresentationTestAssets.shared(),scene,world())) {
            var impact=new GameEvent(GameEvent.Type.IMPACT,17,1,0,hit,"freeze",0,Vector3f.ZERO,normal).withContact(ContactSurface.METAL,null);
            visuals.acceptPresented(List.of(impact));visuals.update(List.of(),List.of(),List.of(),List.of(),null,.04f);
            Mesh vapour=batch(scene,"particles-and-tracers").getMesh(),sparks=batch(scene,"sparks-and-tracers").getMesh();
            assertTrue(vapour.getVertexCount()>0,"Cryogenic contact needs a soft cold vapour layer");
            for(Mesh mesh:List.of(vapour,sparks)) {
                var colors=mesh.getFloatBuffer(VertexBuffer.Type.Color);var positions=mesh.getFloatBuffer(VertexBuffer.Type.Position);
                for(int vertex=0;vertex<mesh.getVertexCount();vertex++) {
                    assertTrue(colors.get(vertex*4+2)>=colors.get(vertex*4),"Freeze must not inherit orange metal sparks");
                    assertTrue(point(positions,vertex).subtract(hit).dot(normal)>=0,"Cold contact remains outside the struck surface");
                }
            }
            Mesh fragments=batch(scene,"impact-fragments").getMesh();assertTrue(fragments.getVertexCount()>0);
            float[] before=floats(fragments,VertexBuffer.Type.Position);int effects=visuals.effectCount();
            visuals.acceptPresented(List.of(impact));visuals.update(List.of(),List.of(),List.of(),List.of(),null,0);
            assertEquals(effects,visuals.effectCount());assertArrayEquals(before,floats(fragments,VertexBuffer.Type.Position));
            visuals.accept(List.of(new GameEvent(GameEvent.Type.FREEZE,17,1,0,hit,"freeze",3)));
            assertEquals(effects,visuals.effectCount(),"The applied control event must not repeat its already presented projectile contact");
        }
    }
    @Test void freezeExpiryReleasesIceAcrossTheHullAndNotOnlyAtTheOldContact() {
        Node scene=new Node();Vector3f centre=new Vector3f(0,2,8);
        try(var visuals=new CombatVisuals(PresentationTestAssets.shared(),scene,world())) {
            visuals.accept(List.of(new GameEvent(GameEvent.Type.CONTROL_ENDED,21,1,1,centre,"freeze",0)));
            visuals.update(List.of(),List.of(),List.of(),List.of(),null,0);
            Mesh fragments=batch(scene,"impact-fragments").getMesh();var positions=fragments.getFloatBuffer(VertexBuffer.Type.Position);
            float left=0,right=0,rear=0,front=0;
            for(int vertex=0;vertex<fragments.getVertexCount();vertex++) {
                Vector3f point=point(positions,vertex).subtractLocal(centre);
                left=Math.min(left,point.x);right=Math.max(right,point.x);rear=Math.min(rear,point.z);front=Math.max(front,point.z);
            }
            assertTrue(left<-.6f&&right>.6f&&rear<-1&&front>1,"Breaking frost must read over the whole car");
            assertTrue(batch(scene,"particles-and-tracers").getMesh().getVertexCount()>0,"Thaw releases soft powder as well as solid chips");
        }
    }
    @Test void freezeFlightHasVisibleCoreSoftWakeAndIceThatStopOnPauseAndExpire() {
        Node scene=new Node();
        try(var visuals=new CombatVisuals(PresentationTestAssets.shared(),scene,world())) {
            var start=game.wreckriff.combat.ProjectilePresentationFixtures.state(4,"freeze",new Vector3f(0,2,3),Vector3f.UNIT_Z);
            var moved=game.wreckriff.combat.ProjectilePresentationFixtures.state(4,"freeze",new Vector3f(0,2,4.3f),Vector3f.UNIT_Z);
            visuals.update(List.of(start),List.of(),List.of(),List.of(),null,1f/60);
            visuals.update(List.of(moved),List.of(),List.of(),List.of(),null,1f/60);
            Mesh wake=batch(scene,"particles-and-tracers").getMesh(),core=batch(scene,"sparks-and-tracers").getMesh();
            assertTrue(wake.getVertexCount()>0,"A cold soft wake must accompany the tiny fast projectile");
            assertTrue(core.getVertexCount()>0);assertTrue(batch(scene,"impact-fragments").getMesh().getVertexCount()>0);
            var shapes=core.getFloatBuffer(VertexBuffer.Type.TexCoord2);float radius=0;
            for(int vertex=0;vertex<core.getVertexCount();vertex++) {
                radius=Math.max(radius,shapes.get(vertex*2));
                assertEquals(9,shapes.get(vertex*2+1),"Cold cores must use the auxiliary silhouette without the atlas's baked orange RGB");
                var colors=core.getFloatBuffer(VertexBuffer.Type.Color);
                assertTrue(colors.get(vertex*4+2)>colors.get(vertex*4));
            }
            assertTrue(radius>=.08f,"The former 4.5 cm points were not a readable projectile trail");
            int effects=visuals.effectCount();float[] before=floats(wake,VertexBuffer.Type.Position);
            for(int frame=0;frame<60;frame++)visuals.update(List.of(moved),List.of(),List.of(),List.of(),null,0);
            assertEquals(effects,visuals.effectCount());assertArrayEquals(before,floats(wake,VertexBuffer.Type.Position));
            for(int frame=0;frame<80;frame++)visuals.update(List.of(),List.of(),List.of(),List.of(),null,.02f);
            assertEquals(0,visuals.effectCount());assertEquals(0,visuals.projectileCount());
        }
    }
    @Test void freezeFlightReleasesIceAtThirtySixtyAndOneHundredTwentyRenderFrames() {
        for(int fps:List.of(30,60,120)) {
            Node scene=new Node();
            try(var visuals=new CombatVisuals(PresentationTestAssets.shared(),scene,world())) {
                for(int frame=0;frame<=fps/10;frame++) {
                    var projectile=game.wreckriff.combat.ProjectilePresentationFixtures.state(4,"freeze",new Vector3f(0,2,3+frame*80f/fps),Vector3f.UNIT_Z);
                    visuals.update(List.of(projectile),List.of(),List.of(),List.of(),null,1f/fps);
                }
                assertTrue(batch(scene,"impact-fragments").getMesh().getVertexCount()>0,"Ice emission must not reset its cadence at every render frame: "+fps);
                assertTrue(batch(scene,"particles-and-tracers").getMesh().getVertexCount()>0);
            }
        }
    }
    @Test void effectsAreBoundedFiniteAndRemovedAfterExpiryAndClose() {
        Node scene=new Node();
        try(CombatVisuals visuals=new CombatVisuals(PresentationTestAssets.shared(),scene,world())) {
            MatchSession session=new MatchSession(42,180);
            for(int i=0;i<500;i++)visuals.accept(List.of(
                    event(GameEvent.Type.EXPLOSION,i,"power",6),event(GameEvent.Type.FREEZE,i,"freeze",2),
                    event(GameEvent.Type.SHOT,i,"machine-gun",8)));
            assertTrue(visuals.effectCount()<=CombatVisuals.PARTICLE_LIMIT+CombatVisuals.SHARD_LIMIT+CombatVisuals.FLARE_LIMIT+CombatVisuals.SHOT_LIMIT);
            visuals.update(List.of(),List.of(),List.of(),List.of(),session,.016f);
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
                    for(var kind:List.of(VertexBuffer.Type.TexCoord,VertexBuffer.Type.TexCoord2,VertexBuffer.Type.TexCoord3)) {
                        var data=(java.nio.FloatBuffer)geometry.getMesh().getBuffer(kind).getData();
                        assertEquals(vertices.limit()/3*2,data.limit());
                        for(int i=0;i<data.limit();i++)assertTrue(Float.isFinite(data.get(i)));
                    }
                }
            }});
            assertEquals(4,draws[0],"Four effect batches; ordnance geometry exists only for live IDs");
            for(int frame=0;frame<200;frame++)visuals.update(List.of(),List.of(),List.of(),List.of(),session,.02f);
            assertEquals(0,visuals.effectCount());
        }
        assertEquals(0,scene.getQuantity());
    }
    @Test void lowHpSmokeStopsAfterDestructionAndRetryOwnsNoGlobalEffects() {
        Node scene=new Node();MatchSession session=new MatchSession(1,180);session.vehicle(0).hp=59;
        for(int retry=0;retry<20;retry++) {
            try(CombatVisuals visuals=new CombatVisuals(PresentationTestAssets.shared(),scene,world())) {
                session.vehicle(0).hp=59;visuals.update(List.of(),List.of(),List.of(),List.of(),session,.016f);
                assertTrue(visuals.effectCount()>0);
                session.vehicle(0).hp=0;
                for(int frame=0;frame<100;frame++)visuals.update(List.of(),List.of(),List.of(),List.of(),session,.02f);
                assertEquals(0,visuals.effectCount());
            }
            assertEquals(0,scene.getQuantity());
        }
    }
    @Test void damagedCarSmokeUsesSingleSoftSpriteWithBoundedRadiusAndOpacity() {
        Node scene=new Node();MatchSession session=new MatchSession(1,180);session.vehicle(0).hp=40;
        try(CombatVisuals visuals=new CombatVisuals(PresentationTestAssets.shared(),scene,world())) {
            for(int frame=0;frame<150;frame++)visuals.update(List.of(),List.of(),List.of(),List.of(),session,1f/60);
            Geometry geometry=(Geometry)((Node)scene.getChild("combat-visuals")).getChild("particles-and-tracers");
            Mesh mesh=geometry.getMesh();
            var positions=(java.nio.FloatBuffer)mesh.getBuffer(VertexBuffer.Type.Position).getData();
            var shapes=(java.nio.FloatBuffer)mesh.getBuffer(VertexBuffer.Type.TexCoord2).getData();
            var colors=(java.nio.FloatBuffer)mesh.getBuffer(VertexBuffer.Type.Color).getData();
            assertTrue(mesh.getVertexCount()>0);assertEquals(0,mesh.getVertexCount()%6);
            for(int vertex=0;vertex<mesh.getVertexCount();vertex++) {
                assertTrue(shapes.get(vertex*2)<=.55f,"Smoke radius must never grow into metre-scale walls");
                assertEquals(3,shapes.get(vertex*2+1));
                assertTrue(colors.get(vertex*4+3)<=.38f,"Overlapping smoke billows remain translucent");
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
            for(int frame=0;frame<150;frame++)visuals.update(List.of(),List.of(),List.of(),List.of(),session,1f/60);
            Geometry geometry=batch(scene,"particles-and-tracers");Mesh mesh=geometry.getMesh();
            assertTrue(mesh.getVertexCount()/6>=14,"Ten emissions per second maintain a readable rising plume");
            assertTrue(mesh.getVertexCount()/6<=16,"Separate billows avoid a dense opaque pipe");
            var positions=mesh.getFloatBuffer(VertexBuffer.Type.Position);var colors=mesh.getFloatBuffer(VertexBuffer.Type.Color);
            float highest=0,peakOpacity=0;
            for(int vertex=0;vertex<mesh.getVertexCount();vertex+=6) {
                Vector3f centre=point(positions,vertex);highest=Math.max(highest,centre.y);
                assertTrue(Math.abs(centre.x)<.9f&&Math.abs(centre.z-1.05f)<.5f,"A small lateral drift separates rising billows without becoming arena fog");
                peakOpacity=Math.max(peakOpacity,colors.get(vertex*4+3));
            }
            assertTrue(highest>3.3f,"Rising smoke remains visible above the roof, not hidden inside the model");
            assertTrue(peakOpacity>.3f&&peakOpacity<=.38f,"Fresh billows retain detail while transmitting the scene behind them");
            assertEquals(com.jme3.material.RenderState.BlendMode.Alpha,geometry.getMaterial().getAdditionalRenderState().getBlendMode(),
                    "Dark smoke must alpha-blend; additive particles cannot darken a background");
        }
    }
    @Test void transparentParticleBufferIsSortedForActualCameraIncludingRearView() {
        Node scene=new Node();
        try(CombatVisuals visuals=new CombatVisuals(PresentationTestAssets.shared(),scene,world())) {
            visuals.accept(List.of(new GameEvent(GameEvent.Type.EXPLOSION,1,0,0,new Vector3f(0,1,4),"power",6),
                    new GameEvent(GameEvent.Type.EXPLOSION,2,0,0,new Vector3f(0,1,18),"power",6)));
            visuals.update(List.of(),List.of(),List.of(),List.of(),new MatchSession(1,180),.016f);
            Geometry geometry=(Geometry)((Node)scene.getChild("combat-visuals")).getChild("particles-and-tracers");
            Camera camera=new Camera(1280,720);camera.setLocation(new Vector3f(0,2,0));
            for(Vector3f look:List.of(new Vector3f(0,2,20),new Vector3f(0,2,-20))) {
                camera.lookAt(look,Vector3f.UNIT_Y);
                scene.updateGeometricState();
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
            visuals.update(List.of(),List.of(mine),List.of(fire),List.of(),session,.016f);
            Node root=(Node)scene.getChild("combat-visuals");
            Geometry fields=(Geometry)root.getChild("ground-fire");
            var positions=(java.nio.FloatBuffer)fields.getMesh().getBuffer(VertexBuffer.Type.Position).getData();
            assertEquals(12,fields.getMesh().getVertexCount(),"Only supported ground patches; no grid outline");
            for(int i=0;i<positions.limit();i+=3) {
                assertEquals(6.028f,positions.get(i+1),.0001f,"Scorch stays on the supported floor");
                assertTrue(positions.get(i)>=1.5f && positions.get(i)<=3.5f,"No whole-radius disc through unsupported walls or edges");
            }
            assertEquals(1,((Node)root.getChild("ordnance-models")).getQuantity(),"Persistent mine is visible");
            visuals.update(List.of(),List.of(),List.of(),List.of(),session,.016f);
            assertEquals(0,fields.getMesh().getVertexCount());
            assertEquals(0,((Node)root.getChild("ordnance-models")).getQuantity());
        }
    }
    @Test void everyThirdShotPerCarHasTravellingShortTracerClampedToAuthoritativeEndpoint() {
        Node scene=new Node();
        try(CombatVisuals visuals=new CombatVisuals(PresentationTestAssets.shared(),scene,world())) {
            Vector3f left=new Vector3f(-.53f,1.47f,2.28f),right=new Vector3f(.53f,1.47f,2.28f);
            Vector3f end=left.add(0,0,18);
            visuals.accept(List.of(shot(1,0,left,end),shot(2,0,right,end)));
            visuals.update(List.of(),List.of(),List.of(),List.of(),null,.01f);
            assertTrue(visuals.tracerSegments().isEmpty());
            visuals.accept(List.of(shot(3,0,left,end),shot(4,1,right,end)));
            visuals.update(List.of(),List.of(),List.of(),List.of(),null,.01f);
            var tracer=visuals.tracerSegments().getFirst();
            assertEquals(1,visuals.tracerSegments().size(),"Tracer cadence is independent for each vehicle");
            assertEquals(3,tracer.id());assertTrue(left.add(0,0,1.8f).distance(tracer.to())<.0001f);
            assertEquals(1.5f,tracer.from().distance(tracer.to()),.0001f);
            visuals.update(List.of(),List.of(),List.of(),List.of(),null,.095f);
            tracer=visuals.tracerSegments().getFirst();
            assertEquals(end,tracer.to(),"Head must stop at the recorded contact, even across a long render frame");
            assertTrue(tracer.from().distance(tracer.to())<=1.50001f);
            for(int frame=0;frame<10;frame++)visuals.update(List.of(),List.of(),List.of(),List.of(),null,.05f);
            for(int frame=0;frame<10;frame++)visuals.update(List.of(),List.of(),List.of(),List.of(),null,.05f);
            assertEquals(0,visuals.effectCount(),"A miss leaves no impact after its flash, smoke and casing expire");
        }
    }
    @Test void muzzleFlashUsesActualAlternatingOriginsAndVeryCloseTracerCannotCrossTheWall() {
        Node scene=new Node();
        try(CombatVisuals visuals=new CombatVisuals(PresentationTestAssets.shared(),scene,world())) {
            Vector3f left=new Vector3f(-.53f,1.47f,2.28f),right=new Vector3f(.53f,1.47f,2.28f);
            Vector3f end=left.add(0,0,.4f);
            visuals.accept(List.of(shot(1,0,left,end),shot(2,0,right,end),shot(3,0,left,end)));
            visuals.update(List.of(),List.of(),List.of(),List.of(),null,0);
            var positions=batch(scene,"sparks-and-tracers").getMesh().getFloatBuffer(VertexBuffer.Type.Position);
            var shapes=batch(scene,"sparks-and-tracers").getMesh().getFloatBuffer(VertexBuffer.Type.TexCoord2);
            int flash=0;for(int vertex=0;vertex<positions.limit()/3;vertex+=6)if(shapes.get(vertex*2+1)==2) {
                assertEquals(flash==1?right:left,point(positions,vertex));flash++;
            }
            assertEquals(3,flash);
            visuals.update(List.of(),List.of(),List.of(),List.of(),null,.003f);
            var tracer=visuals.tracerSegments().getFirst();assertEquals(left,tracer.from());assertEquals(end,tracer.to());
            assertTrue(tracer.from().distance(tracer.to())<.401f);
        }
    }
    @Test void presentedContactHasNoSecondDelayAndDedupeKeepsDifferentEventTypes() {
        Node scene=new Node();
        try(CombatVisuals visuals=new CombatVisuals(PresentationTestAssets.shared(),scene,world())) {
            Vector3f start=new Vector3f(0,1,0),end=new Vector3f(0,1,36),normal=new Vector3f(0,0,-1);
            var shot=shot(3,0,start,end);
            var hit=new GameEvent(GameEvent.Type.IMPACT,3,1,0,end,"machine-gun",8,start,normal);
            var shield=new GameEvent(GameEvent.Type.SHIELD_HIT,3,1,0,end,"machine-gun",8,start,normal);
            // Raw contacts are consumed only by the shared timeline.
            visuals.accept(List.of(shield,hit,hit,shield));
            visuals.update(List.of(),List.of(),List.of(),List.of(),null,0);
            assertEquals(0,batch(scene,"impact-fragments").getMesh().getVertexCount());
            assertEquals(0,batch(scene,"sparks-and-tracers").getMesh().getVertexCount());
            visuals.acceptPresented(List.of(hit,shield));
            visuals.update(List.of(),List.of(),List.of(),List.of(),null,0);
            assertEquals(12*6,batch(scene,"sparks-and-tracers").getMesh().getVertexCount());
            assertEquals(16*3+3*36,batch(scene,"impact-fragments").getMesh().getVertexCount());
            var positions=batch(scene,"sparks-and-tracers").getMesh().getFloatBuffer(VertexBuffer.Type.Position);
            for(int spark=0;spark<12;spark++)assertEquals(end.add(normal.mult(.025f)),point(positions,spark*6));
            int count=visuals.effectCount();visuals.acceptPresented(List.of(hit,shield));assertEquals(count,visuals.effectCount());
            for(int frame=0;frame<30;frame++)visuals.update(List.of(),List.of(),List.of(),List.of(),null,.02f);
            visuals.acceptPresented(List.of(hit,shield));assertEquals(0,visuals.effectCount(),"Duplicate delivery after expiry must not restart an old effect");
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
            visuals.update(List.of(),List.of(),List.of(),List.of(),null,.02f);baseline.update(List.of(),List.of(),List.of(),List.of(),null,.02f);
            assertEquals(3*6+12,batch(scene,"sparks-and-tracers").getMesh().getVertexCount(),"Muzzle flashes and third tracer remain visible");
            var death=new GameEvent(GameEvent.Type.DESTROYED,9,1,0,end,"machine-gun",0);
            visuals.accept(List.of(death));baseline.accept(List.of(death));
            for(int frame=0;frame<7;frame++) {
                visuals.update(List.of(),List.of(),List.of(),List.of(),null,.1f);baseline.update(List.of(),List.of(),List.of(),List.of(),null,.1f);
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
            visuals.acceptPresented(List.of(hit,shield));
            // A new event ID after death must also be rejected; deduplication alone is insufficient.
            var later=shot(2,0,start,end);visuals.accept(List.of(later,new GameEvent(GameEvent.Type.IMPACT,2,1,0,end,"machine-gun",8,start,Vector3f.UNIT_Z),
                    new GameEvent(GameEvent.Type.SHIELD_HIT,2,1,0,end,"machine-gun",8,start,Vector3f.UNIT_Z)));baseline.accept(List.of(later));
            var validLaunch=shot(3,1,start,end);var validHit=new GameEvent(GameEvent.Type.IMPACT,3,2,1,end,"machine-gun",8,start,Vector3f.UNIT_Z);
            visuals.accept(List.of(validLaunch,validHit));baseline.accept(List.of(validLaunch,validHit));
            visuals.acceptPresented(List.of(validHit));baseline.acceptPresented(List.of(validHit));
            for(int frame=0;frame<4;frame++) {
                visuals.update(List.of(),List.of(),List.of(),List.of(),null,.1f);baseline.update(List.of(),List.of(),List.of(),List.of(),null,.1f);
                assertSameEffects(scene,baselineScene);assertEquals(baseline.effectCount(),visuals.effectCount());
            }
        }
    }
    private static void assertSameEffects(Node actual,Node expected) {
        for(String name:List.of("particles-and-tracers","sparks-and-tracers","impact-fragments")) {
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
            visuals.acceptPresented(List.of(new GameEvent(GameEvent.Type.IMPACT,1,-1,0,end,"machine-gun",8,start,Vector3f.UNIT_Z.negate()).withContact(ContactSurface.CONCRETE,null)));
            visuals.update(List.of(),List.of(),List.of(),List.of(),null,0);
            Mesh mesh=batch(scene,"particles-and-tracers").getMesh();assertEquals(8*6,mesh.getVertexCount());
            var shape=mesh.getFloatBuffer(VertexBuffer.Type.TexCoord2);
            for(int vertex=0;vertex<mesh.getVertexCount();vertex++)assertEquals(7,shape.get(vertex*2+1),"Concrete emits the baked dust family");
        }
    }
    @Test void freezeBreakupAndShieldContactFreezeOnPauseThenExpireAndCloseClears() {
        Node scene=new Node();
        CombatVisuals visuals=new CombatVisuals(PresentationTestAssets.shared(),scene,world());
        visuals.accept(List.of(event(GameEvent.Type.FREEZE,1,"freeze",2),event(GameEvent.Type.CONTROL_ENDED,2,"freeze",0),
                event(GameEvent.Type.SHIELD_HIT,3,"homing",0)));
        visuals.acceptPresented(List.of(event(GameEvent.Type.SHIELD_HIT,3,"homing",0)));
        visuals.update(List.of(),List.of(),List.of(),List.of(),null,.016f);
        Mesh mesh=batch(scene,"impact-fragments").getMesh();int count=visuals.effectCount();
        float[] before=new float[mesh.getFloatBuffer(VertexBuffer.Type.Position).limit()];
        mesh.getFloatBuffer(VertexBuffer.Type.Position).duplicate().rewind().get(before);assertTrue(before.length>0);
        for(int frame=0;frame<100;frame++)visuals.update(List.of(),List.of(),List.of(),List.of(),null,0);
        float[] after=new float[mesh.getFloatBuffer(VertexBuffer.Type.Position).limit()];mesh.getFloatBuffer(VertexBuffer.Type.Position).duplicate().rewind().get(after);
        assertArrayEquals(before,after);assertEquals(count,visuals.effectCount());
        for(int frame=0;frame<100;frame++)visuals.update(List.of(),List.of(),List.of(),List.of(),null,.02f);
        assertEquals(0,visuals.effectCount());assertEquals(0,mesh.getVertexCount());
        visuals.close();visuals.accept(List.of(event(GameEvent.Type.FREEZE,9,"freeze",2)));assertEquals(0,visuals.effectCount());assertEquals(0,scene.getQuantity());
    }
    @Test void criticalSmokeBeginsAtExactlyQuarterHpAndDoesNotEmitWhilePaused() {
        Node scene=new Node();MatchSession session=new MatchSession(1,180);
        try(CombatVisuals visuals=new CombatVisuals(PresentationTestAssets.shared(),scene,world())) {
            session.vehicle(0).hp=session.vehicle(0).maximumHp*.2501f;
            visuals.update(List.of(),List.of(),List.of(),List.of(),session,.02f);assertEquals(0,visuals.effectCount());
            session.vehicle(0).hp=session.vehicle(0).maximumHp*.25f;
            visuals.update(List.of(),List.of(),List.of(),List.of(),session,0);assertEquals(0,visuals.effectCount());
            visuals.update(List.of(),List.of(),List.of(),List.of(),session,.02f);assertEquals(1,visuals.effectCount());
            for(int frame=0;frame<100;frame++)visuals.update(List.of(),List.of(),List.of(),List.of(),session,0);
            assertEquals(1,visuals.effectCount());
        }
    }
    @Test void ballisticWarningUsesDeclaredRoofPointAndRemovesWithAuthoritativeSnapshot() {
        Node scene=new Node();var warning=new game.wreckriff.combat.CombatSystem.BallisticWarningView(7,1,new Vector3f(4,6,8),Vector3f.UNIT_Y,6,60);
        try(CombatVisuals visuals=new CombatVisuals(PresentationTestAssets.shared(),scene,world())) {
            visuals.update(List.of(),List.of(),List.of(),List.of(warning),null,.02f);
            Mesh fields=batch(scene,"ground-fire").getMesh();assertEquals(48*6+12,fields.getVertexCount());
            var points=fields.getFloatBuffer(VertexBuffer.Type.Position);
            for(int i=0;i<points.limit();i+=3){assertEquals(6.045f,points.get(i+1),.0001f);assertTrue(new Vector3f(points.get(i)-4,0,points.get(i+2)-8).length()<=6.001f);}
            visuals.update(List.of(),List.of(),List.of(),List.of(),null,0);assertEquals(0,fields.getVertexCount());
        }
    }
    @Test void ballisticWarningFollowsRampPlaneAndSurvivesSaturatedFireBuffer() {
        Node scene=new Node();Vector3f contact=new Vector3f(4,6,8),normal=new Vector3f(-.4f,1,.25f).normalizeLocal();
        var warning=new game.wreckriff.combat.CombatSystem.BallisticWarningView(7,1,contact,normal,6,60);
        List<Vector3f> support=new ArrayList<>();for(int i=0;i<81;i++)support.add(new Vector3f(50+i%9,0,50+i/9));
        List<game.wreckriff.combat.CombatSystem.FireZoneView> fires=new ArrayList<>();
        for(int i=0;i<48;i++)fires.add(new game.wreckriff.combat.CombatSystem.FireZoneView(i,1,new Vector3f(50,0,50),Vector3f.UNIT_Y,5,240,support));
        try(CombatVisuals visuals=new CombatVisuals(PresentationTestAssets.shared(),scene,world())) {
            visuals.update(List.of(),List.of(),fires,List.of(warning),null,0);
            Mesh fields=batch(scene,"ground-fire").getMesh();assertTrue(fields.getVertexCount()<=20000);assertEquals(0,fields.getVertexCount()%3);
            assertTrue(fields.getVertexCount()>19000,"Regression fills the existing field buffer with lower-priority geometry");
            var points=fields.getFloatBuffer(VertexBuffer.Type.Position);
            float lowest=Float.POSITIVE_INFINITY,highest=Float.NEGATIVE_INFINITY;
            for(int vertex=fields.getVertexCount()-(48*6+12);vertex<fields.getVertexCount();vertex++) {
                Vector3f point=point(points,vertex);Vector3f offset=point.subtract(contact);
                assertEquals(.045f,offset.dot(normal),.00001f,"Every warning ring/cross vertex lies above the actual support plane");
                assertTrue(offset.length()<=6.001f,"All warning vertices survive and draw after the saturated fire entries");
                lowest=Math.min(lowest,point.y);highest=Math.max(highest,point.y);
            }
            assertTrue(highest-lowest>3,"The warning tilts with the ramp instead of staying horizontal");
        }
    }
    @Test void allProjectileSlotsCanWarnTogetherWithoutScorchObscuringTheirRadii() {
        Node scene=new Node();
        List<game.wreckriff.combat.CombatSystem.BallisticWarningView> warnings=new ArrayList<>();
        for(int i=0;i<64;i++)warnings.add(new game.wreckriff.combat.CombatSystem.BallisticWarningView(i,1,new Vector3f(i*15,0,0),Vector3f.UNIT_Y,6,60));
        var fire=new game.wreckriff.combat.CombatSystem.FireZoneView(1,0,Vector3f.ZERO,Vector3f.UNIT_Y,5,120,List.of(Vector3f.ZERO));
        try(CombatVisuals visuals=new CombatVisuals(PresentationTestAssets.shared(),scene,world())) {
            visuals.setFlashIntensity(0);visuals.update(List.of(),List.of(),List.of(fire),warnings,null,0);
            Mesh mesh=batch(scene,"ground-fire").getMesh();
            assertTrue(mesh.getVertexCount()<=20000);
            var positions=mesh.getFloatBuffer(VertexBuffer.Type.Position);
            var shape=mesh.getFloatBuffer(VertexBuffer.Type.TexCoord2);
            assertEquals(5,shape.get(1),"Scorch renders before transparent warning lines");
            boolean[] outerRadius=new boolean[64],thinInnerRadius=new boolean[64];
            for(int vertex=6;vertex<mesh.getVertexCount();vertex++) {
                assertEquals(6,shape.get(vertex*2+1),"Warnings occupy the final draw range");
                Vector3f point=point(positions,vertex);int index=Math.round(point.x/15);
                Vector3f offset=point.subtract(warnings.get(index).point());offset.y=0;
                float radius=offset.length();
                outerRadius[index]|=Math.abs(radius-6)<.0001f;
                thinInnerRadius[index]|=Math.abs(radius-5.925f)<.0001f;
            }
            for(int index=0;index<64;index++)assertTrue(outerRadius[index]&&thinInnerRadius[index],"Every declared impact keeps its full, thin warning radius");
        }
    }
    @Test void ballisticScorchFollowsWallAndSteepImpactPlanes() {
        for(Vector3f normal:List.of(Vector3f.UNIT_X,new Vector3f(.3f,.2f,1).normalizeLocal())) {
            Node scene=new Node();Vector3f contact=new Vector3f(4,6,8);
            try(CombatVisuals visuals=new CombatVisuals(PresentationTestAssets.shared(),scene,world())) {
                visuals.accept(List.of(new GameEvent(GameEvent.Type.EXPLOSION,1,-1,0,contact,"ballistic",34,Vector3f.ZERO,normal)));
                visuals.update(List.of(),List.of(),List.of(),List.of(),null,0);
                Mesh mesh=batch(scene,"ground-fire").getMesh();assertEquals(6,mesh.getVertexCount());
                var positions=mesh.getFloatBuffer(VertexBuffer.Type.Position);
                for(int vertex=0;vertex<mesh.getVertexCount();vertex++)
                    assertEquals(.035f,point(positions,vertex).subtract(contact).dot(normal),.00001f,"Cosmetic scorch stays on the actual impact plane");
            }
        }
    }
    @Test void ballisticCosmeticFireHasSixteenPatchLimitFreezesAndExpiresWithoutChangingHp() {
        Node scene=new Node();MatchSession session=new MatchSession(7,180);float hp=session.vehicle(0).hp;
        try(CombatVisuals visuals=new CombatVisuals(PresentationTestAssets.shared(),scene,world())) {
            for(int i=0;i<30;i++)visuals.accept(List.of(new GameEvent(GameEvent.Type.EXPLOSION,i,-1,0,new Vector3f(i,0,4),"ballistic",34,Vector3f.ZERO,Vector3f.UNIT_Y)));
            assertEquals(CombatVisuals.COSMETIC_FIRE_LIMIT,visuals.cosmeticFireCount());
            visuals.update(List.of(),List.of(),List.of(),List.of(),session,.1f);int count=visuals.effectCount();
            for(int i=0;i<50;i++)visuals.update(List.of(),List.of(),List.of(),List.of(),session,0);
            assertEquals(count,visuals.effectCount());assertEquals(16,visuals.cosmeticFireCount());
            assertEquals(hp,session.vehicle(0).hp,"Presentation fire cannot apply DOT");
            for(int i=0;i<21;i++)visuals.update(List.of(),List.of(),List.of(),List.of(),session,.1f);
            assertEquals(0,visuals.cosmeticFireCount());assertEquals(0,visuals.effectCount());assertEquals(0,batch(scene,"ground-fire").getMesh().getVertexCount());
        }
    }
    @Test void closeBlastsUseAtMostTwoBriefLightsPausePreservesThemAndCloseRemovesBoth() {
        Node scene=new Node();CombatVisuals visuals=new CombatVisuals(PresentationTestAssets.shared(),scene,world());
        assertEquals(2,scene.getLocalLightList().size());
        visuals.accept(List.of(new GameEvent(GameEvent.Type.EXPLOSION,1,-1,1,new Vector3f(200,1,8),"power",6)));
        for(var light:scene.getLocalLightList())assertFalse(light.isEnabled(),"Distant blasts do not spend a light");
        for(int i=2;i<7;i++)visuals.accept(List.of(event(GameEvent.Type.EXPLOSION,i,"power",6)));
        for(var light:scene.getLocalLightList())assertTrue(light.isEnabled());
        visuals.update(List.of(),List.of(),List.of(),List.of(),null,0);
        for(var light:scene.getLocalLightList())assertTrue(light.isEnabled());
        for(int i=0;i<3;i++)visuals.update(List.of(),List.of(),List.of(),List.of(),null,.1f);
        for(var light:scene.getLocalLightList())assertFalse(light.isEnabled());
        visuals.close();assertEquals(0,scene.getLocalLightList().size());assertEquals(0,scene.getQuantity());
    }
    @Test void saturatedPoolsPreservePlayerHitOverRepeatedDistantImpactsAndTails() {
        Node scene=new Node();
        try(CombatVisuals visuals=new CombatVisuals(PresentationTestAssets.shared(),scene,world())) {
            for(int i=0;i<40;i++)visuals.accept(List.of(new GameEvent(GameEvent.Type.EXPLOSION,i,2,2,new Vector3f(220,0,0),"power",6)));
            visuals.accept(List.of(new GameEvent(GameEvent.Type.EXPLOSION,100,1,0,new Vector3f(0,1,8),"cannon",65)));
            for(int i=101;i<141;i++)visuals.accept(List.of(new GameEvent(GameEvent.Type.EXPLOSION,i,2,2,new Vector3f(220,0,0),"power",6)));
            visuals.update(List.of(),List.of(),List.of(),List.of(),null,0);
            Mesh mesh=batch(scene,"particles-and-tracers").getMesh();var points=mesh.getFloatBuffer(VertexBuffer.Type.Position);int near=0;
            for(int vertex=0;vertex<mesh.getVertexCount();vertex+=6)if(point(points,vertex).distance(new Vector3f(0,1,8))<2)near++;
            assertTrue(near>=24,"Player hit survives far-effect pressure");assertTrue(mesh.getVertexCount()<=CombatVisuals.PARTICLE_LIMIT*6);
            Mesh fragments=batch(scene,"impact-fragments").getMesh();assertTrue(fragments.getVertexCount()<=CombatVisuals.SHARD_LIMIT*36);
            assertTrue(fragments.getVertexCount()>0);
        }
    }
    @Test void ramRequiresClosingSpeedAndExplosionProfilesHaveDistinctDebrisAndSmokeLifetimes() {
        Node scene=new Node();
        try(CombatVisuals visuals=new CombatVisuals(PresentationTestAssets.shared(),scene,world())) {
            visuals.accept(List.of(event(GameEvent.Type.RAM,1,"ram",2)));assertEquals(0,visuals.effectCount());
            visuals.accept(List.of(event(GameEvent.Type.RAM,2,"ram",12)));visuals.update(List.of(),List.of(),List.of(),List.of(),null,.01f);
            assertTrue(batch(scene,"impact-fragments").getMesh().getVertexCount()>0);
        }
        Set<Integer> profiles=new HashSet<>();
        for(String kind:List.of("power","mine","cannon","ballistic")) {
            Node one=new Node();
            try(CombatVisuals visuals=new CombatVisuals(PresentationTestAssets.shared(),one,world())) {
                visuals.accept(List.of(event(GameEvent.Type.EXPLOSION,1,kind,34)));
                visuals.update(List.of(),List.of(),List.of(),List.of(),null,.1f);
                profiles.add(Arrays.hashCode(floats(batch(one,"particles-and-tracers").getMesh(),VertexBuffer.Type.Position)));
                for(int i=0;i<7;i++)visuals.update(List.of(),List.of(),List.of(),List.of(),null,.1f);
                Mesh smoke=batch(one,"particles-and-tracers").getMesh();assertTrue(smoke.getVertexCount()>0,"Smoke outlasts the 0.6s flash");
                for(int i=0;i<24;i++)visuals.update(List.of(),List.of(),List.of(),List.of(),null,.1f);
                assertEquals(0,visuals.effectCount());
            }
        }
        assertEquals(4,profiles.size(),"Weapon-specific expansion directions produce distinct visible geometry, even when two recipes share a particle count");
    }
    private static GameEvent shot(long id,int source,Vector3f origin,Vector3f end) {
        return new GameEvent(GameEvent.Type.SHOT,id,source,source,end,"machine-gun",8,origin,Vector3f.ZERO);
    }
    @Test void onlyPickupOwnerStartsTypedBurstAndEveryTypeExpiresWithoutLooping() {
        Set<ColorRGBA> colors=new HashSet<>();
        for(String kind:List.of("homing-ammo","power-ammo","mine-ammo","napalm-ammo","ballistic-ammo","cannon-ammo","repair","turbo")) {
            Node scene=new Node();
            try(CombatVisuals visuals=new CombatVisuals(PresentationTestAssets.shared(),scene,world())) {
                visuals.accept(List.of(event(GameEvent.Type.PICKUP,11,kind,1)));
                assertEquals(0,visuals.effectCount(),"Generic event dispatch cannot duplicate the pickup owner's burst");
                visuals.pickupBurst(kind,new Vector3f(2,1.1f,4));
                assertEquals(10,visuals.effectCount());
                visuals.update(List.of(),List.of(),List.of(),List.of(),null,0);
                var mesh=batch(scene,"sparks-and-tracers").getMesh();var buffer=mesh.getFloatBuffer(VertexBuffer.Type.Color);
                colors.add(new ColorRGBA(buffer.get(0),buffer.get(1),buffer.get(2),buffer.get(3)));
                assertEquals(60,mesh.getVertexCount());
                for(int frame=0;frame<5;frame++)visuals.update(List.of(),List.of(),List.of(),List.of(),null,.1f);
                assertEquals(0,visuals.effectCount());
            }
        }
        assertEquals(8,colors.size(),"Pickup families have authored individual burst colours");
    }
    @Test void spriteVariationIsStableOnPauseAndDoesNotAddGeometry() {
        Node scene=new Node();
        try(CombatVisuals visuals=new CombatVisuals(PresentationTestAssets.shared(),scene,world())) {
            visuals.accept(List.of(event(GameEvent.Type.EXPLOSION,19,"power",5)));
            visuals.update(List.of(),List.of(),List.of(),List.of(),null,0);
            Mesh mesh=batch(scene,"particles-and-tracers").getMesh();
            float[] before=floats(mesh,VertexBuffer.Type.TexCoord3);Set<Float> angles=new HashSet<>();
            for(int i=0;i<before.length;i+=12)angles.add(before[i]);
            assertTrue(angles.size()>10,"Sprites rotate their masks independently instead of repeating the same stamp");
            for(int frame=0;frame<50;frame++)visuals.update(List.of(),List.of(),List.of(),List.of(),null,0);
            assertArrayEquals(before,floats(mesh,VertexBuffer.Type.TexCoord3));
            assertEquals(mesh.getVertexCount()*2,before.length);
        }
    }
    @Test void minimumFlashDisablesBlastLightsButPreservesTheWarningAndContactConfirmation() {
        Node scene=new Node();
        var warning=new game.wreckriff.combat.CombatSystem.BallisticWarningView(7,1,new Vector3f(4,6,8),Vector3f.UNIT_Y,6,60);
        try(CombatVisuals visuals=new CombatVisuals(PresentationTestAssets.shared(),scene,world())) {
            visuals.update(List.of(),List.of(),List.of(),List.of(warning),null,0);
            float[] positions=floats(batch(scene,"ground-fire").getMesh(),VertexBuffer.Type.Position);
            float[] colors=floats(batch(scene,"ground-fire").getMesh(),VertexBuffer.Type.Color);
            visuals.setFlashIntensity(0);visuals.accept(List.of(event(GameEvent.Type.EXPLOSION,1,"power",5)));
            visuals.update(List.of(),List.of(),List.of(),List.of(warning),null,0);
            assertTrue(visuals.effectCount()>0);assertEquals(0f,batch(scene,"particles-and-tracers").getMaterial().getParam("FlashIntensity").getValue());
            for(var light:scene.getLocalLightList())assertFalse(light.isEnabled());
            assertArrayEquals(positions,floats(batch(scene,"ground-fire").getMesh(),VertexBuffer.Type.Position));
            assertArrayEquals(colors,floats(batch(scene,"ground-fire").getMesh(),VertexBuffer.Type.Color));
            assertThrows(IllegalArgumentException.class,()->visuals.setFlashIntensity(Float.NaN));
            assertThrows(IllegalArgumentException.class,()->visuals.setFlashIntensity(2));
        }
    }
    @Test void cannonRicochetCreatesShortMetalFanWithoutExplosionSmoke() {
        Node scene=new Node();
        try(CombatVisuals visuals=new CombatVisuals(PresentationTestAssets.shared(),scene,world())) {
            visuals.accept(List.of(new GameEvent(GameEvent.Type.EXPLOSION,9,-1,0,new Vector3f(0,1,8),"cannon-ricochet",0,new Vector3f(0,1,7),Vector3f.UNIT_Z.negate())));
            visuals.update(List.of(),List.of(),List.of(),List.of(),null,.06f);
            Mesh mesh=batch(scene,"sparks-and-tracers").getMesh();var shape=mesh.getFloatBuffer(VertexBuffer.Type.TexCoord2);
            assertTrue(mesh.getVertexCount()>0);
            for(int vertex=0;vertex<mesh.getVertexCount();vertex++)assertEquals(2,shape.get(vertex*2+1),"Ricochet emits sparks, not flame or smoke");
            for(var light:scene.getLocalLightList())assertFalse(light.isEnabled());
            for(int frame=0;frame<4;frame++)visuals.update(List.of(),List.of(),List.of(),List.of(),null,.1f);
            assertEquals(0,visuals.effectCount());
        }
    }
    @Test void softFireFollowsClippedRampCellsAndStaysStableAtMinimumFlash() {
        Node scene=new Node();Vector3f a=new Vector3f(4,6,8),normal=new Vector3f(-.4f,1,.25f).normalizeLocal();
        Vector3f b=a.add(1,.4f,0),separate=a.add(4,1.6f,0);
        var fire=new game.wreckriff.combat.CombatSystem.FireZoneView(1,0,a,normal,5,120,List.of(a,b,separate));
        try(CombatVisuals visuals=new CombatVisuals(PresentationTestAssets.shared(),scene,world())) {
            visuals.setFlashIntensity(0);visuals.update(List.of(),List.of(),List.of(fire),List.of(),null,0);
            Mesh mesh=batch(scene,"ground-fire").getMesh();var positions=mesh.getFloatBuffer(VertexBuffer.Type.Position);var colors=mesh.getFloatBuffer(VertexBuffer.Type.Color);
            assertEquals(18,mesh.getVertexCount(),"Three supported patches without decorative boundary geometry");
            var shape=mesh.getFloatBuffer(VertexBuffer.Type.TexCoord2);
            var masks=mesh.getFloatBuffer(VertexBuffer.Type.TexCoord3);
            for(int vertex=0;vertex<mesh.getVertexCount();vertex++) {
                Vector3f offset=point(positions,vertex).subtract(a);
                assertEquals(.028f,offset.dot(normal),.00001f);
                float x=offset.x-normal.x*.028f;
                assertTrue(x<=1.501f||x>=3.499f,"No geometry bridges unsupported cells");
                assertEquals(0,shape.get(vertex*2),"Ground geometry never billboards with the camera");
                assertEquals(5,shape.get(vertex*2+1),"Soft ground mask replaces opaque polygon fans");
                assertTrue(colors.get(vertex*4+3)>.25f,"Persistent fire is independent of flash reduction");
            }
            assertEquals(0,((int)masks.get(0))&1,"Shared +X edge does not feather to a visible seam");
            assertEquals(0,((int)masks.get(12))&2,"Shared -X edge does not feather to a visible seam");
            assertEquals(255,masks.get(24),"Isolated support fades on all edges and corners");
            float[] before=floats(mesh,VertexBuffer.Type.TexCoord3);
            visuals.update(List.of(),List.of(),List.of(fire),List.of(),null,0);
            assertArrayEquals(before,floats(mesh,VertexBuffer.Type.TexCoord3),"Paused frames keep stable ground patterns");
        }
    }
    @Test void unchangedFireSnapshotsReusePreparedTopologyAndGeometry() {
        Node scene=new Node();Vector3f origin=new Vector3f(4,6,8);
        List<Vector3f> support=List.of(origin,origin.add(1,0,0),origin.add(0,0,1));
        try(CombatVisuals visuals=new CombatVisuals(PresentationTestAssets.shared(),scene,world())) {
            visuals.update(List.of(),List.of(),List.of(cachedFire(9,origin,Vector3f.UNIT_Y,240,support)),List.of(),null,0);
            Mesh mesh=batch(scene,"ground-fire").getMesh();
            Map<VertexBuffer.Type,float[]> initial=new EnumMap<>(VertexBuffer.Type.class);
            for(var type:List.of(VertexBuffer.Type.Position,VertexBuffer.Type.Color,VertexBuffer.Type.TexCoord,VertexBuffer.Type.TexCoord2,VertexBuffer.Type.TexCoord3))
                initial.put(type,floats(mesh,type));
            for(int frame=0;frame<120;frame++) {
                // CombatSystem publishes fresh views/vectors and a decreasing TTL every frame.
                visuals.update(List.of(),List.of(),List.of(cachedFire(9,origin,Vector3f.UNIT_Y,240-frame,support)),List.of(),null,0);
            }
            assertEquals(1,visuals.fireTopologyBuildCount());
            assertEquals(3,visuals.cachedFirePointCount());
            initial.forEach((type,data)->assertArrayEquals(data,floats(mesh,type),"Cache hits preserve all rendered attributes"));
        }
    }
    @Test void changedFireSupportPoseAndLifecycleInvalidateOnlyTheirCachedGeometry() {
        Node scene=new Node();Vector3f origin=new Vector3f(4,6,8);
        var first=cachedFire(1,origin,Vector3f.UNIT_Y,240,List.of(origin));
        var second=cachedFire(2,origin,Vector3f.UNIT_Y,240,List.of(origin.add(4,0,0)));
        CombatVisuals visuals=new CombatVisuals(PresentationTestAssets.shared(),scene,world());
        try {
            visuals.update(List.of(),List.of(),List.of(first,second),List.of(),null,0);
            assertEquals(2,visuals.fireTopologyBuildCount());
            var changed=cachedFire(1,origin,Vector3f.UNIT_Y,239,List.of(origin,origin.add(1,0,0)));
            visuals.update(List.of(),List.of(),List.of(changed,second),List.of(),null,0);
            assertEquals(3,visuals.fireTopologyBuildCount());
            var mesh=batch(scene,"ground-fire").getMesh();
            assertEquals(0,((int)mesh.getFloatBuffer(VertexBuffer.Type.TexCoord3).get(0))&1,"Added neighbour removes the shared feathered edge");
            changed=cachedFire(1,origin.add(.2f,0,0),new Vector3f(-.4f,1,0).normalizeLocal(),238,List.of(origin,origin.add(1,.4f,0)));
            visuals.update(List.of(),List.of(),List.of(changed,second),List.of(),null,0);
            assertEquals(4,visuals.fireTopologyBuildCount());
            // A caller mutating a previously published vector cannot mutate the stored cache key.
            changed.surfacePoints().getFirst().y+=.1f;
            visuals.update(List.of(),List.of(),List.of(changed,second),List.of(),null,0);
            assertEquals(5,visuals.fireTopologyBuildCount());
            visuals.update(List.of(),List.of(),List.of(second),List.of(),null,0);
            assertEquals(1,visuals.cachedFirePointCount());assertEquals(5,visuals.fireTopologyBuildCount());
            visuals.update(List.of(),List.of(),List.of(),List.of(),null,0);
            assertEquals(0,visuals.cachedFirePointCount());
            visuals.update(List.of(),List.of(),List.of(first),List.of(),null,0);
            assertEquals(6,visuals.fireTopologyBuildCount(),"Reusing an ID after expiry creates a new entry");
        } finally {visuals.close();}
        assertEquals(0,visuals.cachedFirePointCount());
        try(CombatVisuals retry=new CombatVisuals(PresentationTestAssets.shared(),scene,world())) {
            assertEquals(0,retry.cachedFirePointCount());assertEquals(0,retry.fireTopologyBuildCount());
        }
    }
    @Test void fireTopologyRetentionIsBoundedByTheGroundVertexBudget() {
        Node scene=new Node();List<Vector3f> support=new ArrayList<>();
        for(int i=0;i<81;i++)support.add(new Vector3f(i%9,0,i/9));
        try(CombatVisuals visuals=new CombatVisuals(PresentationTestAssets.shared(),scene,world())) {
            for(int frame=0;frame<5;frame++) {
                List<game.wreckriff.combat.CombatSystem.FireZoneView> fires=new ArrayList<>();
                for(int i=0;i<64;i++)fires.add(cachedFire(frame*64L+i,Vector3f.ZERO,Vector3f.UNIT_Y,240,support));
                visuals.update(List.of(),List.of(),fires,List.of(),null,0);
                assertTrue(visuals.cachedFirePointCount()<=20000/6,"Retained geometry never exceeds the existing field budget");
                assertTrue(batch(scene,"ground-fire").getMesh().getVertexCount()>19000,"The retention limit does not hide drawable fields");
            }
            visuals.update(List.of(),List.of(),List.of(),List.of(),null,0);
            assertEquals(0,visuals.cachedFirePointCount());
        }
    }
    private static game.wreckriff.combat.CombatSystem.FireZoneView cachedFire(long id,Vector3f origin,Vector3f normal,int ticks,List<Vector3f> support) {
        return new game.wreckriff.combat.CombatSystem.FireZoneView(id,0,origin,normal,5,ticks,support);
    }
    private static float[] floats(Mesh mesh,VertexBuffer.Type type) {
        var values=mesh.getFloatBuffer(type).duplicate().rewind();float[] result=new float[values.remaining()];values.get(result);return result;
    }
    private static Geometry batch(Node scene,String name) {return (Geometry)((Node)scene.getChild("combat-visuals")).getChild(name);}
    private static Vector3f point(java.nio.FloatBuffer positions,int vertex) {return new Vector3f(positions.get(vertex*3),positions.get(vertex*3+1),positions.get(vertex*3+2));}
    private static GameEvent event(GameEvent.Type type,long id,String kind,float value) {
        return new GameEvent(type,id,0,0,new Vector3f(0,1,8),kind,value);
    }
    private static WorldQuery world() {
        return world(null,false);
    }
    private static WorldQuery world(int[] queryCount,boolean wall) {
        return new WorldQuery() {
            public Vector3f position(int id){return new Vector3f(id*5,1,0);}
            public Vector3f velocity(int id){return Vector3f.ZERO;}
            public Quaternion rotation(int id){return Quaternion.IDENTITY;}
            public boolean grounded(int id){return true;}
            public float mass(int id){return 1100;}
            public Hit ray(Vector3f a,Vector3f b,int id){return null;}
            public Hit sweep(Vector3f a,Vector3f b,float r,int id,float stepStart,float stepEnd){return null;}
            public Hit staticSweep(Vector3f a,Vector3f b,float r){
                if(queryCount!=null)queryCount[0]++;
                return wall?new Hit(-1,a.clone().interpolateLocal(b,.5f),Vector3f.UNIT_Y,.5f):null;
            }
            public boolean visible(Vector3f a,Vector3f b,int id){return true;}
            public float distanceToHull(int id,Vector3f p){return 0;}
            public Vector3f closestHullPoint(int id,Vector3f from){return position(id);}
            public void impulse(int id,Vector3f linear,Vector3f torque,float cap){throw new AssertionError("Visuals must not mutate physics");}
        };
    }
    @Test void staticSteelAndGlassUseTheirActualMaterialInsteadOfVehicleIdentity() {
        for(ContactSurface surface:List.of(ContactSurface.METAL,ContactSurface.GLASS)) {
            Node scene=new Node();
            try(var visuals=new CombatVisuals(PresentationTestAssets.shared(),scene,world())) {
                var hit=new GameEvent(GameEvent.Type.IMPACT,44,-1,0,new Vector3f(0,1,2),"machine-gun",4,Vector3f.ZERO,Vector3f.UNIT_Z.negate()).withContact(surface,null);
                visuals.acceptPresented(List.of(hit));visuals.update(List.of(),List.of(),List.of(),List.of(),null,0);
                Mesh fragments=batch(scene,"impact-fragments").getMesh();
                assertTrue(fragments.getVertexCount()>0);assertNotNull(fragments.getBuffer(VertexBuffer.Type.Normal));
                if(surface==ContactSurface.GLASS)assertEquals(0,batch(scene,"sparks-and-tracers").getMesh().getVertexCount(),"Glass chips do not create metal sparks");
                else assertEquals(12*6,batch(scene,"sparks-and-tracers").getMesh().getVertexCount());
            }
        }
    }
    @Test void detachedPanelsHaveFiniteLitGeometryAndBoundedReadOnlySurfaceQueries() {
        int[] calls={0};Node scene=new Node();
        try(var visuals=new CombatVisuals(PresentationTestAssets.shared(),scene,world(calls,true))) {
            for(int i=0;i<300;i++)visuals.detachPanel(new Vector3f(0,1,2),Quaternion.IDENTITY,new Vector3f(3,-2,1),new Vector3f(.4f,.025f,.6f),ContactSurface.METAL,0);
            visuals.update(List.of(),List.of(),List.of(),List.of(),null,.016f);
            assertTrue(calls[0]<=CombatVisuals.DEBRIS_QUERY_LIMIT);
            assertEquals(calls[0],visuals.debrisQueriesLastFrame());
            assertEquals(CombatVisuals.SHARD_LIMIT*36,batch(scene,"impact-fragments").getMesh().getVertexCount());
            for(int i=0;i<35;i++)visuals.update(List.of(),List.of(),List.of(),List.of(),null,.1f);
            assertEquals(0,visuals.effectCount());
        }
    }
}
