package game.wreckriff.presentation;

import com.jme3.math.*;
import com.jme3.scene.*;
import game.wreckriff.config.*;
import java.nio.FloatBuffer;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BossVehicleVisualTest {
    private static final VehicleRules RULES=VehicleRules.load();

    @Test void canonicalRivetProfileBuildsRepeatableMeshesAndWheels() {
        Node old=VehicleVisual.create(PresentationTestAssets.shared(),game.wreckriff.config.VehicleProfile.rivet(),2);
        Node profiled=VehicleVisual.create(PresentationTestAssets.shared(),VehicleProfile.rivet(RULES),2);
        Map<String,Geometry> original=geometries(old),actual=geometries(profiled);
        assertEquals(original.keySet(),actual.keySet());
        original.forEach((name,geometry)->assertArrayEquals(positions(geometry.getMesh()),positions(actual.get(name).getMesh()),name));
        for(int wheel=0;wheel<4;wheel++)assertEquals(old.getChild("wheel-"+wheel).getLocalTransform(),profiled.getChild("wheel-"+wheel).getLocalTransform());
    }
    @Test void bossesHaveDistinctAuthoredBodiesRealAxlesSocketsAndBoundedFullSilhouettes() {
        Set<Integer> paintHashes=new HashSet<>();
        for(var profile:VehicleProfile.bosses(RULES)) {
            Node model=VehicleVisual.create(PresentationTestAssets.shared(),profile,7);
            assertEquals(Vector3f.UNIT_XYZ,model.getLocalScale());
            assertEquals(profile.id(),model.getUserData("profileId"));
            assertEquals("original-java-procedural",model.getUserData("assetOrigin"));
            Geometry paint=(Geometry)model.getChild("paint");
            paintHashes.add(Arrays.hashCode(positions(paint.getMesh())));
            assertTrue(paint.getMesh().getTriangleCount()>450,"Body requires authored panel topology");
            assertNotNull(model.getChild("boss-panels"));
            for(int wheel=0;wheel<4;wheel++) {
                Node axle=(Node)model.getChild("wheel-"+wheel);
                assertEquals(profile.wheelConnection(wheel).add(0,-profile.suspensionRestLength(),0),axle.getLocalTranslation());
                assertEquals(profile.wheelRadius()/.38f,axle.getLocalScale().x);
            }
            for(int barrel=0;barrel<2;barrel++)assertEquals(profile.machineGunMuzzle(barrel),model.getChild("machine-gun-muzzle-"+barrel).getLocalTranslation());
            assertEquals(profile.weaponBase(),model.getChild("weapon-base").getLocalTranslation());
            assertEquals(profile.muzzle(),model.getChild("weapon-muzzle").getLocalTranslation());
            model.updateGeometricState();
            var envelope=profile.fullBounds();
            model.depthFirstTraversal(spatial->{if(spatial instanceof Geometry geometry&&visible(geometry)) {
                FloatBuffer vertices=geometry.getMesh().getFloatBuffer(VertexBuffer.Type.Position);
                for(int i=0;i<vertices.limit();i+=3) {
                    Vector3f p=geometry.localToWorld(new Vector3f(vertices.get(i),vertices.get(i+1),vertices.get(i+2)),null);
                    assertTrue(p.x>=envelope.minX()-.12f&&p.x<=envelope.maxX()+.12f,profile.id()+" width "+p);
                    assertTrue(p.y>=envelope.minY()-.12f&&p.y<=envelope.maxY()+.12f,profile.id()+" height "+p);
                    assertTrue(p.z>=envelope.minZ()-.12f&&p.z<=envelope.maxZ()+.12f,profile.id()+" length "+p);
                }
            }});
            assertBudgetAndFinite(model);
        }
        assertEquals(5,paintHashes.size(),"Each boss has independently authored panel coordinates");
    }
    @Test void allBossDamageStagesUseOwnMeshesAndStatusSurfacesWithoutMovingWeaponsOrWheels() {
        for(var profile:VehicleProfile.bosses(RULES)) {
            Node model=VehicleVisual.create(PresentationTestAssets.shared(),profile,1);
            Geometry paint=(Geometry)model.getChild("paint");Mesh intact=paint.getMesh();
            Map<String,Geometry> before=geometries(model);
            Map<String,Mesh> wheels=new HashMap<>();
            before.forEach((key,value)->{if(key.contains("wheel-"))wheels.put(key,value.getMesh());});
            Vector3f gun=model.getChild("machine-gun-muzzle-0").getLocalTranslation().clone();
            Set<Mesh> stages=Collections.newSetFromMap(new IdentityHashMap<>());
            stages.add(intact);
            for(float hp:new float[]{.75f,.5f,.25f,0}) {
                VehicleVisual.updateDamage(model,hp);VehicleVisual.updateEffects(model,true,true);stages.add(paint.getMesh());
                float[] original=positions(intact),damaged=positions(paint.getMesh());boolean dented=false;
                for(int i=0;i<original.length;i+=3) {
                    float difference=new Vector3f(original[i],original[i+1],original[i+2]).distance(new Vector3f(damaged[i],damaged[i+1],damaged[i+2]));
                    assertTrue(difference<=.35001f);dented|=difference>.005f;
                }
                assertTrue(dented,profile.id()+" damage must affect the actual large panels");
                assertEquals(gun,model.getChild("machine-gun-muzzle-0").getLocalTranslation());
                wheels.forEach((key,mesh)->assertSame(mesh,before.get(key).getMesh()));
                if(hp>0) {
                    Geometry ice=(Geometry)((Node)model.getChild("frost-overlay")).getChild("frost-paint");
                    assertEquals(paint.getMesh().getVertexCount(),ice.getMesh().getVertexCount());
                    assertEquals(Spatial.CullHint.Inherit,model.getChild("shield-shell").getLocalCullHint());
                } else assertEquals(Spatial.CullHint.Always,model.getChild("frost-overlay").getLocalCullHint());
                assertBudgetAndFinite(model);
            }
            assertEquals(5,stages.size());
            VehicleVisual.updateDamage(model,1);assertSame(intact,paint.getMesh());
            assertEquals(Spatial.CullHint.Inherit,model.getChild("boss-panels").getLocalCullHint());
        }
    }
    @Test void bossPresentationFollowsAuthoritativePhaseAndVulnerabilityIndependentlyOfDamage() {
        for(String id:List.of("boss_emcee","boss_ash_shepherd","boss_director")) {
            Node model=VehicleVisual.create(PresentationTestAssets.shared(),VehicleProfile.boss(id,RULES),0);
            for(int phase:new int[]{0,1,2,0}) {
                VehicleVisual.updateBossPhase(model,phase,phase==1);
                assertEquals(phase==2?Spatial.CullHint.Always:Spatial.CullHint.Inherit,model.getChild("boss-panels").getLocalCullHint());
                assertEquals(phase==1?Spatial.CullHint.Inherit:Spatial.CullHint.Always,model.getChild("service-core").getLocalCullHint());
                assertEquals(phase==1?Spatial.CullHint.Always:Spatial.CullHint.Inherit,model.getChild("service-cover").getLocalCullHint());
                assertEquals(phase,(int)model.getUserData("bossVisualPhase"));
            }
        }
    }
    private static Map<String,Geometry> geometries(Node node) {
        Map<String,Geometry> result=new LinkedHashMap<>();
        node.depthFirstTraversal(spatial->{if(spatial instanceof Geometry geometry) {
            String path=geometry.getName();for(Spatial parent=geometry.getParent();parent!=node;parent=parent.getParent())path=parent.getName()+"/"+path;
            result.put(path,geometry);
        }});return result;
    }
    private static float[] positions(Mesh mesh) {
        FloatBuffer buffer=mesh.getFloatBuffer(VertexBuffer.Type.Position).duplicate().rewind();
        float[] result=new float[buffer.limit()];buffer.get(result);return result;
    }
    private static boolean visible(Spatial spatial) {
        for(var part=spatial;part!=null;part=part.getParent())if(part.getLocalCullHint()==Spatial.CullHint.Always)return false;
        return true;
    }
    private static void assertBudgetAndFinite(Node model) {
        int[] draws={0},triangles={0};
        model.depthFirstTraversal(spatial->{if(spatial instanceof Geometry geometry) {
            if(visible(geometry)){draws[0]++;triangles[0]+=geometry.getMesh().getTriangleCount();}
            for(var type:List.of(VertexBuffer.Type.Position,VertexBuffer.Type.Normal,VertexBuffer.Type.Tangent)) {
                FloatBuffer values=geometry.getMesh().getFloatBuffer(type);
                if(values!=null)for(int i=0;i<values.limit();i++)assertTrue(Float.isFinite(values.get(i)),geometry.getName()+" "+type);
            }
        }});
        assertTrue(draws[0]<=22,"One active boss including simultaneous frost/shield: "+draws[0]);
        assertTrue(triangles[0]<=12000,"One active boss with status shells: "+triangles[0]);
    }
}
