package game.wreckriff.presentation;

import com.jme3.math.*;
import com.jme3.material.RenderState;
import com.jme3.renderer.Camera;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.*;
import game.wreckriff.config.VehicleProfile;
import game.wreckriff.config.VehicleRules;
import game.wreckriff.simulation.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VehicleDamageVisualTest {
    private static Node car(){Node car=VehicleVisual.create(PresentationTestAssets.shared(),VehicleProfile.rivet(),0);VehicleVisual.configureMaximumHp(car,800);return car;}
    private static GameEvent hit(long id,float damage){return new GameEvent(GameEvent.Type.DAMAGE,id,0,1,new Vector3f(1,.2f,0),"machine-gun",damage).withContact(ContactSurface.METAL,new VehicleContact(new Vector3f(1,.2f,0),Vector3f.UNIT_X)).withHealthChange(new HealthChange(800,800-damage));}
    @Test void fiveStagesUseExactlyTwoGpuTargetsAndKeepImmutableBaseAndWheelPose() {
        Node car=car();Geometry paint=(Geometry)car.getChild("paint");Mesh mesh=paint.getMesh();float[] original=points(mesh);var wheel=car.getChild("wheel-0").getLocalTransform().clone();
        for(float hp:new float[]{1,.75f,.5f,.25f,0}) {
            VehicleVisual.updateDamage(car,hp);VehicleVisual.updatePresentation(car,.06f,null);
            assertEquals(VehicleDamageVisual.stage(hp),(Integer)car.getUserData("damageStage"));assertSame(mesh,paint.getMesh());assertEquals(2,mesh.getMorphTargets().length);
            assertArrayEquals(original,points(mesh));assertEquals(wheel,car.getChild("wheel-0").getLocalTransform());
            if(hp<1)assertEquals(.5f,paint.getMorphState()[1],.001f);
            VehicleVisual.updatePresentation(car,.06f,null);if(hp<1)assertEquals(1,paint.getMorphState()[1],.001f);
        }
        assertEquals(Spatial.CullHint.Always,car.getChild("frost-overlay").getLocalCullHint());VehicleVisual.close(car);
    }
    @Test void localDamageIsDeliveredOnceAndFullRepairClearsItUsingParticipantMaximumHp() {
        Node car=car(),other=car();var damage=car.getControl(VehicleDamageVisual.class);GameEvent hit=hit(1,160);
        VehicleVisual.acceptPresented(car,hit);VehicleVisual.acceptPresented(car,hit);VehicleVisual.updateDamage(car,.8f);VehicleVisual.updatePresentation(car,.12f,null);
        assertEquals(1,damage.markCount());assertTrue(damage.regionDamage(3)>0);assertEquals(0,other.getControl(VehicleDamageVisual.class).markCount());
        VehicleVisual.acceptPresented(car,new GameEvent(GameEvent.Type.REPAIRED,2,0,0,Vector3f.ZERO,"repair",160).withHealthChange(new HealthChange(640,800)));
        VehicleVisual.updateDamage(car,1);VehicleVisual.updatePresentation(car,.15f,null);
        assertEquals(0,damage.markCount());assertEquals(0,damage.regionDamage(3));assertEquals(1,damage.repairRevision());assertEquals(.5f,((Geometry)car.getChild("paint")).getMorphState()[1],.001f);
        VehicleVisual.close(car);VehicleVisual.close(other);
    }
    @Test void repairRevisionDoesNotEraseNewerHitInSameFrameAndPartialRepairKeepsPanelDetached() {
        Node car=car();VehicleVisual.updateDamage(car,.5f);VehicleVisual.acceptPresented(car,hit(10,100));VehicleVisual.updatePresentation(car,.12f,null);
        assertEquals(1,VehicleVisual.drainDetached(car).size());assertEquals(Spatial.CullHint.Always,car.getChild("panel-door-right").getLocalCullHint());
        VehicleVisual.acceptPresented(car,new GameEvent(GameEvent.Type.REPAIRED,11,0,0,Vector3f.ZERO,"repair",200).withHealthChange(new HealthChange(400,600)));
        VehicleVisual.acceptPresented(car,hit(12,20));VehicleVisual.updateDamage(car,.725f);VehicleVisual.updatePresentation(car,.12f,null);
        assertTrue(car.getControl(VehicleDamageVisual.class).markCount()>0);assertTrue(car.getControl(VehicleDamageVisual.class).regionDamage(3)>.325f);
        assertEquals(Spatial.CullHint.Always,car.getChild("panel-door-right").getLocalCullHint());VehicleVisual.close(car);
    }
    @Test void preparedMountRecoilNeverMovesSimulationSocketAndPauseFreezesIt() {
        Node car=car();var socket=car.getChild("machine-gun-muzzle-0").getLocalTransform().clone();
        GameEvent shot=new GameEvent(GameEvent.Type.SHOT,9,0,0,Vector3f.ZERO,"machine-gun",0).withEmission(new ShotEmission("machine-gun-muzzle-0",Vector3f.UNIT_Z,Vector3f.ZERO));
        VehicleVisual.acceptPresented(car,shot);VehicleVisual.updatePresentation(car,.01f,null);var mount=car.getChild("mount-machine-gun-0-bolt");assertEquals(Vector3f.ZERO,car.getChild("mount-machine-gun-0").getLocalTranslation());float recoil=mount.getLocalTranslation().z;assertTrue(recoil<0);
        VehicleVisual.updatePresentation(car,0,null);assertEquals(recoil,mount.getLocalTranslation().z);assertEquals(socket,car.getChild("machine-gun-muzzle-0").getLocalTransform());
        VehicleVisual.updatePresentation(car,.15f,null);assertEquals(0,mount.getLocalTranslation().z,.00001f);VehicleVisual.close(car);
    }
    @Test void lodUsesScreenCoverageWithHysteresisAndStatusSharesActiveDamageMesh() {
        Node car=car();Camera camera=new Camera(1920,1080);camera.setFrustumPerspective(60,1920f/1080,.1f,1000);camera.setLocation(new Vector3f(0,0,-100));camera.lookAt(Vector3f.ZERO,Vector3f.UNIT_Y);
        car.updateGeometricState();VehicleVisual.updatePresentation(car,0,camera);assertEquals(2,(Integer)car.getUserData("vehicleLod"));
        camera.setLocation(new Vector3f(0,0,-12));VehicleVisual.updatePresentation(car,0,camera);assertEquals(1,(Integer)car.getUserData("vehicleLod"));
        camera.setLocation(new Vector3f(0,0,-3));VehicleVisual.updatePresentation(car,0,camera);assertEquals(0,(Integer)car.getUserData("vehicleLod"));
        VehicleVisual.updateDamage(car,.5f);VehicleVisual.updateEffects(car,true,true);VehicleVisual.updatePresentation(car,.06f,null);
        Geometry paint=(Geometry)car.getChild("paint"),frost=(Geometry)car.getChild("frost-paint");assertSame(paint.getMesh(),frost.getMesh());assertArrayEquals(paint.getMorphState(),frost.getMorphState());VehicleVisual.close(car);
    }
    @Test void frostUsesBoundedTransparentCrystalsAndExistingDamageMaskOnEveryVehicleAndLod() {
        VehicleRules rules=VehicleRules.load();
        for(String id:List.of("rivet","spark","grinder")) {
            VehicleProfile profile=VehicleProfile.player(id,rules);
            Node car=VehicleVisual.create(PresentationTestAssets.shared(),profile,2);
            try {
                VehicleVisual.updateEffects(car,true,false);VehicleVisual.updateDamage(car,.5f);VehicleVisual.updatePresentation(car,.06f,null);
                for(int level=0;level<3;level++) {
                    Node body=(Node)car.getChild("lod"+level),frost=(Node)car.getChild("frost-lod"+level);
                    for(Spatial spatial:frost.getChildren()) {
                        Geometry ice=(Geometry)spatial,source=(Geometry)body.getChild(ice.getName().substring("frost-".length()));
                        var material=ice.getMaterial();assertEquals("VehicleFrost",material.getMaterialDef().getName());
                        assertSame(source.getMesh(),ice.getMesh());assertArrayEquals(source.getMorphState(),ice.getMorphState());
                        assertSame(source.getMaterial().getParam("DamageMap").getValue(),material.getParam("DamageMap").getValue());
                        assertTrue((Float)material.getParam("MaxOpacity").getValue()<=.65f,"Base paint and damage must remain visible through ice");
                        assertEquals(RenderState.BlendMode.Alpha,material.getAdditionalRenderState().getBlendMode());assertFalse(material.getAdditionalRenderState().isDepthWrite());
                        assertEquals(RenderQueue.Bucket.Transparent,ice.getQueueBucket());assertEquals(RenderQueue.ShadowMode.Off,ice.getShadowMode());
                    }
                }
            } finally {VehicleVisual.close(car);}
        }
    }
    @Test void frostFollowsAuthoritativeStatusThroughPauseCleanseExpiryAndDeath() {
        Node car=car();Node frost=(Node)car.getChild("frost-overlay");
        try {
            VehicleVisual.acceptPresented(car,new GameEvent(GameEvent.Type.FREEZE,20,0,1,Vector3f.ZERO,"freeze",4));
            assertEquals(Spatial.CullHint.Always,frost.getLocalCullHint(),"Feedback events must not create a separate status timer");
            VehicleVisual.updateEffects(car,true,false);VehicleVisual.updatePresentation(car,0,null);
            assertEquals(Spatial.CullHint.Inherit,frost.getLocalCullHint());
            VehicleVisual.updatePresentation(car,5,null);assertEquals(Spatial.CullHint.Inherit,frost.getLocalCullHint(),"Only the simulation ends Freeze");
            VehicleVisual.updateEffects(car,true,true);assertEquals(Spatial.CullHint.Always,frost.getLocalCullHint(),"Shield cleanse cannot retain a frost shell");
            VehicleVisual.updateEffects(car,true,false);VehicleVisual.updateEffects(car,false,false);assertEquals(Spatial.CullHint.Always,frost.getLocalCullHint());
            VehicleVisual.updateEffects(car,true,false);VehicleVisual.acceptPresented(car,new GameEvent(GameEvent.Type.DESTROYED,21,0,1,Vector3f.ZERO,"power",0));
            VehicleVisual.updateEffects(car,true,false);assertEquals(Spatial.CullHint.Always,frost.getLocalCullHint(),"Dead cars cannot regain frost from a stale status");
        } finally {VehicleVisual.close(car);}
    }
    @Test void boundedMarkHistoryBakesOldMarksInsteadOfErasingThemAndFullRepairClearsMask() {
        var marks=new VehicleDamageMarks(PresentationTestAssets.shared(),"rivet");Vector2f first=new Vector2f(.05f,.05f);
        marks.hit(Vector3f.ZERO,Vector3f.UNIT_Y,first,1,false,false,1,0,2);
        for(int i=0;i<80;i++)marks.hit(Vector3f.ZERO,Vector3f.UNIT_Y,new Vector2f(.15f+(i%9)*.08f,.15f+(i/9)*.08f),.7f,false,false,i+2,0,2);
        assertEquals(32,marks.count());byte[] data=marks.snapshot();int at=(Math.round(first.y*511)*512+Math.round(first.x*511))*4;assertTrue((data[at]&255)>0);
        marks.repair(1);assertEquals(0,marks.count());for(byte value:marks.snapshot())assertEquals(0,value);marks.close();
    }
    @Test void sustainedFireAddsSootWithoutImpactDentOrDetachedPanels() {
        Node car=car();VehicleVisual.updateDamage(car,.25f);
        for(int i=0;i<180;i++)VehicleVisual.acceptPresented(car,new GameEvent(GameEvent.Type.DAMAGE,1000+i,0,1,Vector3f.ZERO,"napalm-fire",1)
                .withContact(ContactSurface.METAL,new VehicleContact(new Vector3f(1,.2f,0),Vector3f.UNIT_X)));
        VehicleVisual.updatePresentation(car,.12f,null);
        for(int i=0;i<8;i++)assertEquals(0,car.getControl(VehicleDamageVisual.class).regionDamage(i),"Thermal exposure must not become an impact");
        assertTrue(car.getControl(VehicleDamageVisual.class).markCount()>0);assertTrue(VehicleVisual.drainDetached(car).isEmpty());VehicleVisual.close(car);
    }
    @Test void contactRefinementPreservesAuthoritativeWorldTraceAndUsesCanonicalUv() {
        Node car=car();car.setLocalTranslation(100,20,-60);car.updateGeometricState();GameEvent original=hit(80,20);
        GameEvent refined=VehicleVisual.refineContact(car,original);
        assertEquals(original.position(),refined.position());assertEquals(original.normal(),refined.normal());assertEquals(original.origin(),refined.origin());
        VehicleVisual.acceptPresented(car,refined);assertEquals(1,car.getControl(VehicleDamageVisual.class).markCount());VehicleVisual.close(car);
    }
    @Test void batchedLocalHitDoesNotRewindAnUnaffectedPanelTransition() {
        Node car=car();Geometry left=(Geometry)car.getChild("panel-door-left");
        VehicleVisual.updateDamage(car,.5f);VehicleVisual.updatePresentation(car,.06f,null);assertEquals(.5f,left.getMorphState()[1],.001f);
        VehicleVisual.acceptPresented(car,hit(2000,20));VehicleVisual.updatePresentation(car,.03f,null);
        assertEquals(.75f,left.getMorphState()[1],.001f,"An unrelated dirty region cannot reset another panel's transition clock");VehicleVisual.close(car);
    }
    @Test void reusedGpuMorphBufferIsMarkedDirtyAfterAnotherDamageComposite() {
        Node car=car();Geometry paint=(Geometry)car.getChild("paint");
        VehicleVisual.updateDamage(car,.5f);VehicleVisual.updatePresentation(car,.12f,null);
        var data=paint.getMesh().getMorphTargets()[1].getBuffer(VertexBuffer.Type.Position);
        paint.getMesh().setBuffer(VertexBuffer.Type.MorphTarget3,3,data);var gpu=paint.getMesh().getBuffer(VertexBuffer.Type.MorphTarget3);gpu.clearUpdateNeeded();
        VehicleVisual.acceptPresented(car,hit(2100,20));VehicleVisual.updatePresentation(car,.12f,null);
        assertSame(data,paint.getMesh().getMorphTargets()[1].getBuffer(VertexBuffer.Type.Position));assertTrue(gpu.isUpdateNeeded(),"In-place composites must be uploaded even when jME reuses the same FloatBuffer");VehicleVisual.close(car);
    }
    @Test void detailedDamageStampCannotPaintAnotherPackedPart() {
        var assets=PresentationTestAssets.shared();var data=VehicleModelData.load(assets,"rivet");var door=data.lods.getFirst().stream().filter(p->p.name().equals("panel-door-right")).findFirst().orElseThrow();
        var uv=door.stages()[0].getFloatBuffer(VertexBuffer.Type.TexCoord);Vector2f center=new Vector2f();float greatest=0;
        for(int i=0;i<uv.limit();i+=6){float area=Math.abs((uv.get(i+2)-uv.get(i))*(uv.get(i+5)-uv.get(i+1))-(uv.get(i+4)-uv.get(i))*(uv.get(i+3)-uv.get(i+1)));if(area>greatest){greatest=area;center.set((uv.get(i)+uv.get(i+2)+uv.get(i+4))/3,(uv.get(i+1)+uv.get(i+3)+uv.get(i+5))/3);}}
        var marks=new VehicleDamageMarks(assets,"rivet");marks.hit(Vector3f.ZERO,Vector3f.UNIT_X,center,1,false,false,99,door.islandId(),2);byte[] mask=marks.snapshot();
        var ownership=com.jme3.texture.image.ImageRaster.create(new SurfaceMaterials.TextureUse("DamageMap","textures/vehicles/rivet/damage-ownership.png",false).load(assets).getImage());ColorRGBA pixel=new ColorRGBA();int painted=0;
        for(int y=0;y<512;y++)for(int x=0;x<512;x++)if((mask[(y*512+x)*4]&255)>0){ownership.getPixel(x,y,pixel);assertEquals(door.islandId(),Math.round(pixel.r*255));painted++;}
        assertTrue(painted>0,"Prepared stamp must reach its own canonical island");marks.close();
    }
    @Test void fullRepairAcrossHpStageUsesTheWholeThreeHundredMillisecondTransition() {
        Node car=car();VehicleVisual.updateDamage(car,.5f);VehicleVisual.updatePresentation(car,.12f,null);
        VehicleVisual.acceptPresented(car,new GameEvent(GameEvent.Type.REPAIRED,3000,0,0,Vector3f.ZERO,"repair",400).withHealthChange(new HealthChange(400,800)));
        VehicleVisual.updateDamage(car,1);VehicleVisual.updatePresentation(car,.15f,null);
        assertEquals(.5f,((Geometry)car.getChild("paint")).getMorphState()[1],.001f);VehicleVisual.close(car);
    }
    @Test void hitCrossingIntoModerateDamageDetachesWithoutWaitingForAnotherHit() {
        Node car=car();VehicleVisual.updateDamage(car,.55f);VehicleVisual.updatePresentation(car,.12f,null);
        VehicleVisual.acceptPresented(car,hit(3100,100).withHealthChange(new HealthChange(440,340)));VehicleVisual.updateDamage(car,340f/800);VehicleVisual.updatePresentation(car,.12f,null);
        assertEquals(1,VehicleVisual.drainDetached(car).size());VehicleVisual.close(car);
    }
    @Test void lodSwitchKeepsTheVisibleDamageTransitionPhase() {
        Node car=car();VehicleVisual.updateDamage(car,.5f);VehicleVisual.updatePresentation(car,.06f,null);
        Camera camera=new Camera(1920,1080);camera.setFrustumPerspective(60,1920f/1080,.1f,1000);camera.setLocation(new Vector3f(0,0,-100));camera.lookAt(Vector3f.ZERO,Vector3f.UNIT_Y);car.updateGeometricState();
        VehicleVisual.updatePresentation(car,0,camera);assertEquals(2,(Integer)car.getUserData("vehicleLod"));
        assertEquals(.5f,((Geometry)((Node)car.getChild("lod2")).getChild("paint")).getMorphState()[1],.001f);VehicleVisual.close(car);
    }
    @Test void presentedContactFollowsCurrentMorphWhileTheAuthoritativeTraceIsPreserved() {
        Node car=car();GameEvent original=hit(3200,20),canonical=VehicleVisual.refineContact(car,original);
        VehicleVisual.acceptPresented(car,canonical);VehicleVisual.updateDamage(car,.5f);VehicleVisual.updatePresentation(car,.06f,null);
        GameEvent half=VehicleVisual.presentContact(car,canonical);Vector3f before=canonical.vehicleContact().localPoint(),middle=half.vehicleContact().localPoint();
        assertTrue(before.distance(middle)>.01f);assertEquals(original.position(),half.position());assertEquals(original.normal(),half.normal());assertEquals(original.origin(),half.origin());
        VehicleVisual.updatePresentation(car,.06f,null);GameEvent complete=VehicleVisual.presentContact(car,canonical);
        assertTrue(before.distance(complete.vehicleContact().localPoint())>before.distance(middle));assertEquals(1,complete.vehicleContact().localNormal().length(),.001f);VehicleVisual.close(car);
    }
    @Test void terminalDamageReachesBodyGlassMetalAndWheelsWithoutLeakingIntoAnotherCar() {
        Node wreck=car(),intact=car();
        try {
            VehicleVisual.updateDamage(wreck,0);VehicleVisual.updatePresentation(wreck,.12f,null);
            for(String part:List.of("paint","glass","steel","wheel-0-hub","wheel-0-tyre")) {
                assertEquals(1f,(Float)((Geometry)wreck.getChild(part)).getMaterial().getParam("Damage").getValue(),part);
                assertEquals(0f,(Float)((Geometry)intact.getChild(part)).getMaterial().getParam("Damage").getValue(),part+" must remain instance-local");
            }
            VehicleVisual.acceptPresented(wreck,new GameEvent(GameEvent.Type.REPAIRED,3300,0,0,Vector3f.ZERO,"repair",800).withHealthChange(new HealthChange(0,800)));
            VehicleVisual.updateDamage(wreck,1);VehicleVisual.updatePresentation(wreck,.3f,null);
            assertEquals(0f,(Float)((Geometry)wreck.getChild("wheel-0-hub")).getMaterial().getParam("Damage").getValue());
        } finally {VehicleVisual.close(wreck);VehicleVisual.close(intact);}
    }
    @Test void oneLampHitStaysLocalThroughLodChangesAndReusesItsColorBuffer() {
        Node car=car();Geometry front=(Geometry)car.getChild("headlights");Mesh mesh=front.getMesh();
        var color=mesh.getBuffer(VertexBuffer.Type.Color).getData();Vector3f contact=null;
        for(int triangle=0;triangle<mesh.getTriangleCount();triangle++) {
            Vector3f a=new Vector3f(),b=new Vector3f(),c=new Vector3f();mesh.getTriangle(triangle,a,b,c);
            Vector3f center=a.add(b).addLocal(c).divideLocal(3),normal=b.subtract(a).cross(c.subtract(a)).normalizeLocal();
            if(center.x<0&&normal.z>.9f){contact=center;break;}
        }
        assertNotNull(contact);
        try {
            GameEvent hit=new GameEvent(GameEvent.Type.DAMAGE,3400,0,1,contact,"machine-gun",40).withContact(ContactSurface.METAL,new VehicleContact(contact,Vector3f.UNIT_Z));
            VehicleVisual.acceptPresented(car,hit);VehicleVisual.updatePresentation(car,.12f,null);
            assertTrue(lampEnergy(front,true)<.5f);assertEquals(1,lampEnergy(front,false),.001f);
            assertEquals(1,lampEnergy((Geometry)car.getChild("taillights"),true),.001f,"A headlight impact cannot break the rear lamp");
            assertSame(color,mesh.getBuffer(VertexBuffer.Type.Color).getData(),"Local lamp updates must reuse the instance buffer");
            Camera camera=new Camera(1920,1080);camera.setFrustumPerspective(60,1920f/1080,.1f,1000);camera.setLocation(new Vector3f(0,0,-100));camera.lookAt(Vector3f.ZERO,Vector3f.UNIT_Y);car.updateGeometricState();
            VehicleVisual.updatePresentation(car,0,camera);
            assertTrue(lampEnergy((Geometry)((Node)car.getChild("lod2")).getChild("headlights"),true)<.5f);
            VehicleVisual.acceptPresented(car,new GameEvent(GameEvent.Type.REPAIRED,3401,0,0,Vector3f.ZERO,"repair",40).withHealthChange(new HealthChange(760,800)));
            VehicleVisual.updatePresentation(car,.3f,camera);
            assertEquals(1,lampEnergy((Geometry)((Node)car.getChild("lod2")).getChild("headlights"),true),.001f);
        } finally {VehicleVisual.close(car);}
    }
    private static float lampEnergy(Geometry geometry,boolean left){var position=geometry.getMesh().getFloatBuffer(VertexBuffer.Type.Position);var color=geometry.getMesh().getFloatBuffer(VertexBuffer.Type.Color);float sum=0;int count=0;for(int vertex=0;vertex<geometry.getVertexCount();vertex++)if((position.get(vertex*3)<0)==left){sum+=color.get(vertex*4);count++;}return sum/count;}
    private static float[] points(Mesh mesh){var b=mesh.getFloatBuffer(VertexBuffer.Type.Position);float[] result=new float[b.limit()];for(int i=0;i<result.length;i++)result[i]=b.get(i);return result;}
}
