package game.wreckriff.presentation;

import com.jme3.math.*;
import com.jme3.renderer.Camera;
import com.jme3.scene.*;
import game.wreckriff.config.VehicleProfile;
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
            VehicleVisual.updatePresentation(car,.06f,null);assertEquals(1,paint.getMorphState()[1],.001f);
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
        VehicleVisual.acceptPresented(car,shot);VehicleVisual.updatePresentation(car,.01f,null);var mount=car.getChild("mount-machine-gun-0");float recoil=mount.getLocalTranslation().z;assertTrue(recoil<0);
        VehicleVisual.updatePresentation(car,0,null);assertEquals(recoil,mount.getLocalTranslation().z);assertEquals(socket,car.getChild("machine-gun-muzzle-0").getLocalTransform());
        VehicleVisual.updatePresentation(car,.15f,null);assertEquals(0,mount.getLocalTranslation().z,.00001f);VehicleVisual.close(car);
    }
    @Test void lodUsesScreenCoverageWithHysteresisAndStatusSharesActiveDamageMesh() {
        Node car=car();Camera camera=new Camera(1920,1080);camera.setFrustumPerspective(60,1920f/1080,.1f,1000);camera.setLocation(new Vector3f(0,0,-100));camera.lookAt(Vector3f.ZERO,Vector3f.UNIT_Y);
        car.updateGeometricState();VehicleVisual.updatePresentation(car,0,camera);assertEquals(2,(Integer)car.getUserData("vehicleLod"));
        camera.setLocation(new Vector3f(0,0,-30));VehicleVisual.updatePresentation(car,0,camera);assertEquals(1,(Integer)car.getUserData("vehicleLod"));
        camera.setLocation(new Vector3f(0,0,-10));VehicleVisual.updatePresentation(car,0,camera);assertEquals(0,(Integer)car.getUserData("vehicleLod"));
        VehicleVisual.updateDamage(car,.5f);VehicleVisual.updateEffects(car,true,true);VehicleVisual.updatePresentation(car,.06f,null);
        Geometry paint=(Geometry)car.getChild("paint"),frost=(Geometry)car.getChild("frost-paint");assertSame(paint.getMesh(),frost.getMesh());assertArrayEquals(paint.getMorphState(),frost.getMorphState());VehicleVisual.close(car);
    }
    @Test void boundedMarkHistoryBakesOldMarksInsteadOfErasingThemAndFullRepairClearsMask() {
        var marks=new VehicleDamageMarks(VehicleProfile.rivet());Vector2f first=new Vector2f(.05f,.05f);
        marks.hit(Vector3f.ZERO,Vector3f.UNIT_Y,first,1,false,false,1);
        for(int i=0;i<80;i++)marks.hit(Vector3f.ZERO,Vector3f.UNIT_Y,new Vector2f(.15f+(i%9)*.08f,.15f+(i/9)*.08f),.7f,false,false,i+2);
        assertEquals(32,marks.count());byte[] data=marks.snapshot();int at=(Math.round(first.y*511)*512+Math.round(first.x*511))*4;assertTrue((data[at]&255)>0);
        marks.repair(1);assertEquals(0,marks.count());for(byte value:marks.snapshot())assertEquals(0,value);marks.close();
    }
    private static float[] points(Mesh mesh){var b=mesh.getFloatBuffer(VertexBuffer.Type.Position);float[] result=new float[b.limit()];for(int i=0;i<result.length;i++)result[i]=b.get(i);return result;}
}
