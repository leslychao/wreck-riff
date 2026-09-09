package game.wreckriff.presentation;

import com.jme3.math.Vector3f;
import com.jme3.scene.*;
import game.wreckriff.config.VehicleProfile;
import game.wreckriff.config.VehicleRules;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlayerVehicleVisualTest {
    @Test void heavyAndLightCarsUseOriginalBodyMeshesProfileWheelsAndRealWeaponMounts() {
        Set<Integer> shapes=new HashSet<>();
        for(String id:List.of("grinder","spark")) {
            var profile=VehicleProfile.player(id,VehicleRules.load());
            Node model=VehicleVisual.create(PresentationTestAssets.shared(),profile,0);
            assertEquals(id,model.getUserData("profileId"));assertEquals(Vector3f.UNIT_XYZ,model.getLocalScale());
            assertNull(model.getChild("boss-panels"),"Player chassis do not reuse a boss body or its phase equipment");
            assertEquals(profile.muzzle(),model.getChild("weapon-muzzle").getLocalTranslation());
            for(int barrel=0;barrel<2;barrel++)assertEquals(profile.machineGunMuzzle(barrel),model.getChild("machine-gun-muzzle-"+barrel).getLocalTranslation());
            for(int wheel=0;wheel<4;wheel++) {
                var node=model.getChild("wheel-"+wheel);
                assertEquals(profile.wheelConnection(wheel).add(0,-profile.suspensionRestLength(),0),node.getLocalTranslation());
                assertEquals(profile.wheelRadius()/.38f,node.getLocalScale().x);
            }
            if(id.equals("grinder"))assertEquals(profile.grinderIntake(),model.getChild("grinder-intake").getLocalTranslation());
            var mesh=((Geometry)model.getChild("paint")).getMesh();shapes.add(Arrays.hashCode(points(mesh)));
            model.updateGeometricState();var bounds=profile.fullBounds();
            model.depthFirstTraversal(spatial->{if(spatial instanceof Geometry geometry&&visible(geometry)) {
                var vertices=geometry.getMesh().getFloatBuffer(VertexBuffer.Type.Position);
                for(int i=0;i<vertices.limit();i+=3) {
                    Vector3f point=geometry.localToWorld(new Vector3f(vertices.get(i),vertices.get(i+1),vertices.get(i+2)),null);
                    assertTrue(point.x>=bounds.minX()-.10f&&point.x<=bounds.maxX()+.10f,id+" width "+point);
                    assertTrue(point.y>=bounds.minY()-.10f&&point.y<=bounds.maxY()+.10f,id+" height "+point);
                    assertTrue(point.z>=bounds.minZ()-.10f&&point.z<=bounds.maxZ()+.10f,id+" length "+point);
                }
            }});
            budget(model,16);
        }
        assertEquals(2,shapes.size());
    }
    @Test void allDamageStagesAndCombinedStatusesFitOrdinaryCarBudgetAndRepairRestoresMesh() {
        for(String id:List.of("grinder","spark")) {
            Node model=VehicleVisual.create(PresentationTestAssets.shared(),VehicleProfile.player(id,VehicleRules.load()),0);
            Geometry body=(Geometry)model.getChild("paint");Mesh intact=body.getMesh();Set<Mesh> stages=new HashSet<>();
            float[] original=points(intact);
            for(float hp:new float[]{1,.75f,.5f,.25f,0}) {
                VehicleVisual.updateDamage(model,hp);VehicleVisual.updateEffects(model,true,true);stages.add(body.getMesh());
                float[] damaged=points(body.getMesh());boolean changed=false;
                for(int i=0;i<original.length;i+=3) {
                    float distance=new Vector3f(original[i],original[i+1],original[i+2]).distance(new Vector3f(damaged[i],damaged[i+1],damaged[i+2]));
                    changed|=distance>.001f;assertTrue(distance<=.35001f);
                }
                assertEquals(hp<1,changed,id+" damage follows the actual panels");budget(model,20);
            }
            assertEquals(5,stages.size());VehicleVisual.updateDamage(model,1);assertSame(intact,body.getMesh());
        }
    }
    private static void budget(Node model,int maxDraws) {
        int[] totals={0,0};model.depthFirstTraversal(spatial->{if(spatial instanceof Geometry geometry&&visible(geometry)) {
            totals[0]++;totals[1]+=geometry.getMesh().getTriangleCount();
            for(var kind:List.of(VertexBuffer.Type.Position,VertexBuffer.Type.Normal,VertexBuffer.Type.Tangent)) {
                var buffer=geometry.getMesh().getFloatBuffer(kind);if(buffer!=null)for(int i=0;i<buffer.limit();i++)assertTrue(Float.isFinite(buffer.get(i)));
            }
        }});
        assertTrue(totals[0]<=maxDraws,"Draw budget: "+totals[0]);assertTrue(totals[1]<=8000,"Triangle budget: "+totals[1]);
    }
    private static boolean visible(Spatial spatial) {
        for(var node=spatial;node!=null;node=node.getParent())if(node.getLocalCullHint()==Spatial.CullHint.Always)return false;
        return true;
    }
    private static float[] points(Mesh mesh) {
        var vertices=mesh.getFloatBuffer(VertexBuffer.Type.Position).duplicate().rewind();float[] result=new float[vertices.remaining()];vertices.get(result);return result;
    }
}
