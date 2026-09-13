package game.wreckriff.presentation;

import com.jme3.material.Material;
import com.jme3.material.MaterialDef;
import com.jme3.math.Vector3f;
import com.jme3.scene.*;
import com.jme3.shader.VarType;
import game.wreckriff.simulation.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WeaponMountVisualTest {
    private static final List<String> MOUNTS=List.of("mount-machine-gun-0","mount-machine-gun-1","mount-weapon");
    private static Node fixture(){
        Node vehicle=new Node();MaterialDef definition=new MaterialDef(null,"heat-test");definition.addMaterialParam(VarType.Float,"Heat",0f);
        for(int level=0;level<3;level++){Node lod=new Node("lod"+level);vehicle.attachChild(lod);for(String mount:MOUNTS)for(String suffix:List.of("","-bolt","-barrel")){Geometry geometry=new Geometry(mount+suffix,new Mesh());geometry.setMaterial(new Material(definition));lod.attachChild(geometry);}}
        return vehicle;
    }
    private static GameEvent shot(String socket,String kind){return new GameEvent(GameEvent.Type.SHOT,1,0,0,Vector3f.ZERO,kind,0).withEmission(new ShotEmission(socket,Vector3f.UNIT_Z,Vector3f.ZERO));}
    @Test void deliveredShotImmediatelyMovesOnlyItsAuthoredMechanismOnEveryLod(){
        Node vehicle=fixture();WeaponMountVisual visual=new WeaponMountVisual(vehicle);visual.accept(shot("machine-gun-muzzle-1","machine-gun"));
        for(int level=0;level<3;level++){Node lod=(Node)vehicle.getChild("lod"+level);assertEquals(-.055f,lod.getChild("mount-machine-gun-1-bolt").getLocalTranslation().z,.0001f);assertEquals(0,lod.getChild("mount-machine-gun-0-bolt").getLocalTranslation().z);assertEquals(Vector3f.ZERO,lod.getChild("mount-machine-gun-1").getLocalTranslation());}
    }
    @Test void carrierReleaseUnknownAndMissingSocketsCannotAnimateAVehicleMount(){
        Node vehicle=fixture();WeaponMountVisual visual=new WeaponMountVisual(vehicle);
        visual.accept(shot("carrier-release","ballistic-fall"));visual.accept(shot("unknown-socket","cannon"));visual.accept(new GameEvent(GameEvent.Type.SHOT,2,0,0,Vector3f.ZERO,"cannon",0));visual.update(.01f);
        for(String mount:MOUNTS){assertEquals(0,visual.recoil(mount));Geometry barrel=(Geometry)vehicle.getChild(mount+"-barrel");assertEquals(Vector3f.ZERO,barrel.getLocalTranslation());assertEquals(0f,(Float)barrel.getMaterial().getParam("Heat").getValue());}
    }
    @Test void zeroTimeHoldsTheDeliveredPoseAndALongNextFrameDoesNotExtendTheShot(){
        Node vehicle=fixture();WeaponMountVisual visual=new WeaponMountVisual(vehicle);visual.accept(shot("machine-gun-muzzle-0","machine-gun"));Geometry bolt=(Geometry)vehicle.getChild("mount-machine-gun-0-bolt");
        assertEquals(-.055f,bolt.getLocalTranslation().z,.0001f);visual.update(0);assertEquals(-.055f,bolt.getLocalTranslation().z,.0001f);
        visual.update(.1f);assertEquals(0,bolt.getLocalTranslation().z,.0001f);assertEquals(0,visual.recoil("mount-machine-gun-0"));
    }
}
