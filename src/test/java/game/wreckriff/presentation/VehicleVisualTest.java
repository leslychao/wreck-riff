package game.wreckriff.presentation;

import com.jme3.math.*;
import com.jme3.scene.*;
import game.wreckriff.config.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VehicleVisualTest {
    @Test void authoredProfilesHaveNativeSocketsIndependentWheelsAndPreparedDetail() {
        List<VehicleProfile> profiles=new ArrayList<>(VehicleProfile.bosses(VehicleRules.load()));for(var id:VehicleDefinition.values())profiles.add(VehicleProfile.player(id.id(),VehicleRules.load()));
        for(var profile:profiles) {
            Node model=VehicleVisual.create(PresentationTestAssets.shared(),profile,0);assertEquals("ORIGINAL_BLENDER_CONTENT",model.getUserData("assetOrigin"));assertEquals(Vector3f.UNIT_XYZ,model.getLocalScale());
            for(int wheel=0;wheel<4;wheel++)assertEquals(profile.wheelConnection(wheel).add(0,-profile.suspensionRestLength(),0),model.getChild("wheel-"+wheel).getLocalTranslation());
            for(int barrel=0;barrel<2;barrel++)assertEquals(profile.machineGunMuzzle(barrel),model.getChild("machine-gun-muzzle-"+barrel).getLocalTranslation());assertEquals(profile.muzzle(),model.getChild("weapon-muzzle").getLocalTranslation());
            Geometry paint=(Geometry)model.getChild("paint");assertTrue(paint.getMesh().getTriangleCount()>1000);assertNotNull(paint.getMaterial().getParam("NormalMap"));assertNotNull(paint.getMaterial().getParam("SpecularMap"));
            for(String panel:List.of("panel-hood","panel-trunk","panel-door-left","panel-door-right"))assertNotNull(model.getChild(panel));
            if(profile.id().equals("grinder")){var roller=model.getChild("grinder-roller-left");roller.rotate(.7f,0,0);var before=roller.getLocalTransform().clone();VehicleVisual.updateDamage(model,.25f);VehicleVisual.updatePresentation(model,.12f,null);assertEquals(before,roller.getLocalTransform());}
            VehicleVisual.close(model);
        }
    }
    @Test void allBossLevelsPreserveAuthoritativePhaseAndWeakPoint() {
        for(var profile:VehicleProfile.bosses(VehicleRules.load())) {
            Node model=VehicleVisual.create(PresentationTestAssets.shared(),profile,0);
            for(int phase=0;phase<3;phase++){VehicleVisual.updateBossPhase(model,phase,phase==1);assertEquals(phase==2?Spatial.CullHint.Always:Spatial.CullHint.Inherit,model.getChild("boss-panels").getLocalCullHint());assertEquals(phase==1?Spatial.CullHint.Inherit:Spatial.CullHint.Always,model.getChild("service-core").getLocalCullHint());}
            VehicleVisual.close(model);
        }
    }
}
