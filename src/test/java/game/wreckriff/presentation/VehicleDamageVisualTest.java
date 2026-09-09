package game.wreckriff.presentation;

import com.jme3.material.Material;
import com.jme3.math.*;
import com.jme3.scene.*;
import org.junit.jupiter.api.Test;
import java.nio.FloatBuffer;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class VehicleDamageVisualTest {
    @Test void exactHpBoundariesSelectFivePrebuiltStagesAndRepairRestoresOriginalMeshes() {
        Node car=VehicleVisual.create(PresentationTestAssets.shared(),0);
        Geometry paint=(Geometry)car.getChild("paint");Mesh intact=paint.getMesh();
        Map<Integer,Mesh> stages=new HashMap<>();stages.put(0,intact);
        float[] fractions={1,.7501f,.75f,.5001f,.5f,.2501f,.25f,.0001f,0};
        int[] expected={0,0,1,1,2,2,3,3,4};
        for(int i=0;i<fractions.length;i++) {
            VehicleVisual.updateDamage(car,fractions[i]);assertEquals(expected[i],(Integer)car.getUserData("damageStage"));
            assertEquals(expected[i]<2?Spatial.CullHint.Always:Spatial.CullHint.Inherit,car.getChild("glass-cracks").getLocalCullHint(),"Cracked glass begins at medium damage");
            if(stages.containsKey(expected[i]))assertSame(stages.get(expected[i]),paint.getMesh(),"No mesh allocation or mutation within a stage");
            else stages.put(expected[i],paint.getMesh());
        }
        assertEquals(5,new HashSet<>(stages.values()).size());
        for(int stage=3;stage>=0;stage--) {
            VehicleVisual.updateDamage(car,new float[]{1,.75f,.5f,.25f}[stage]);assertSame(stages.get(stage),paint.getMesh());
        }
        assertSame(intact,paint.getMesh());
        assertEquals(Spatial.CullHint.Always,car.getChild("glass-cracks").getCullHint());
        assertEquals(Spatial.CullHint.Always,car.getChild("body-wear").getCullHint());
        assertThrows(IllegalArgumentException.class,()->VehicleVisual.updateDamage(car,Float.NaN));
    }
    @Test void dentsAreBoundedAllBuffersFiniteAndPhysicalWheelNodesStayUntouched() {
        Node car=VehicleVisual.create(PresentationTestAssets.shared(),1);
        List<Geometry> body=new ArrayList<>();Map<Geometry,float[]> intact=new IdentityHashMap<>();
        for(Spatial child:car.getChildren())if(child instanceof Geometry geometry && !child.getName().equals("glass-cracks")&&!child.getName().equals("body-wear")) {
            body.add(geometry);intact.put(geometry,positions(geometry.getMesh()));
        }
        Map<Geometry,Mesh> wheels=new IdentityHashMap<>();Map<Spatial,Transform> wheelTransforms=new IdentityHashMap<>();
        for(int id=0;id<4;id++)car.getChild("wheel-"+id).depthFirstTraversal(spatial->{
            wheelTransforms.put(spatial,spatial.getLocalTransform().clone());if(spatial instanceof Geometry geometry)wheels.put(geometry,geometry.getMesh());
        });
        for(float hp:new float[]{.75f,.5f,.25f,0,1}) {
            VehicleVisual.updateDamage(car,hp);boolean changed=false;
            for(Geometry geometry:body) {
                float[] before=intact.get(geometry),after=positions(geometry.getMesh());assertEquals(before.length,after.length);
                for(int vertex=0;vertex<before.length;vertex+=3) {
                    float distance=new Vector3f(before[vertex],before[vertex+1],before[vertex+2]).distance(new Vector3f(after[vertex],after[vertex+1],after[vertex+2]));
                    assertTrue(distance<=.18001f,"Visual dents may not exceed the approved collider allowance");changed|=distance>.005f;
                }
                for(var type:List.of(VertexBuffer.Type.Position,VertexBuffer.Type.Normal,VertexBuffer.Type.Tangent)) {
                    var buffer=geometry.getMesh().getFloatBuffer(type);if(buffer!=null)for(int i=0;i<buffer.limit();i++)assertTrue(Float.isFinite(buffer.get(i)));
                }
            }
            assertEquals(hp<1,changed);wheels.forEach((geometry,mesh)->assertSame(mesh,geometry.getMesh()));
            wheelTransforms.forEach((spatial,transform)->assertEquals(transform,spatial.getLocalTransform()));
            assertEquals(new Vector3f(1,1,1),car.getLocalScale(),"Wreck does not shrink the collision or visual footprint");
        }
    }
    @Test void damageMaterialsAreOwnedByEachCarAndBrokenLampProgressionIsReversible() {
        var assets=PresentationTestAssets.shared();Node first=VehicleVisual.create(assets,0),second=VehicleVisual.create(assets,0);
        List<Material> firstMaterials=new ArrayList<>(),secondMaterials=new ArrayList<>();
        first.depthFirstTraversal(spatial->{if(spatial instanceof Geometry geometry)firstMaterials.add(geometry.getMaterial());});
        second.depthFirstTraversal(spatial->{if(spatial instanceof Geometry geometry)secondMaterials.add(geometry.getMaterial());});
        for(Material a:firstMaterials)for(Material b:secondMaterials)assertNotSame(a,b,"Even font and intact car materials must be isolated");
        Geometry firstPaint=(Geometry)first.getChild("paint"),secondPaint=(Geometry)second.getChild("paint");
        ColorRGBA original=((ColorRGBA)secondPaint.getMaterial().getParam("Diffuse").getValue()).clone();
        VehicleVisual.updateDamage(first,.5f);
        assertEquals(original,secondPaint.getMaterial().getParam("Diffuse").getValue());
        assertNotEquals(original,firstPaint.getMaterial().getParam("Diffuse").getValue());
        Geometry lamp=(Geometry)first.getChild("headlights");var points=lamp.getMesh().getFloatBuffer(VertexBuffer.Type.Position);var colors=lamp.getMesh().getFloatBuffer(VertexBuffer.Type.Color);
        for(int vertex=0;vertex<lamp.getMesh().getVertexCount();vertex++)assertEquals(points.get(vertex*3)<0?.025f:1,colors.get(vertex*4));
        VehicleVisual.updateDamage(first,1);colors=lamp.getMesh().getFloatBuffer(VertexBuffer.Type.Color);
        for(int vertex=0;vertex<lamp.getMesh().getVertexCount();vertex++)assertEquals(1,colors.get(vertex*4));
    }
    @Test void bodyFollowingFrostAndShellRespectStatusRepairDeathAndActiveGeometryBudget() {
        Node car=VehicleVisual.create(PresentationTestAssets.shared(),0);
        Node frost=(Node)car.getChild("frost-overlay"),shield=(Node)car.getChild("shield-shell");
        assertEquals(Spatial.CullHint.Always,frost.getCullHint());assertEquals(Spatial.CullHint.Always,shield.getCullHint());
        for(float hp:new float[]{1,.75f,.5f,.25f}) {
            VehicleVisual.updateDamage(car,hp);VehicleVisual.updateEffects(car,true,false);
            Geometry paint=(Geometry)car.getChild("paint"),ice=(Geometry)frost.getChild("frost-paint");
            assertEquals(paint.getMesh().getVertexCount(),ice.getMesh().getVertexCount());
            assertEquals(Spatial.CullHint.Inherit,frost.getLocalCullHint());
            float[] body=positions(paint.getMesh()),overlay=positions(ice.getMesh());
            for(int vertex=0;vertex<body.length;vertex+=3)assertEquals(.010f,new Vector3f(body[vertex],body[vertex+1],body[vertex+2]).distance(new Vector3f(overlay[vertex],overlay[vertex+1],overlay[vertex+2])),.0001f);
            assertTrue((float)ice.getMaterial().getParam("Shininess").getValue()>0);
            // No controlUpdate timer: the overlay remains for the entire authoritative status, including pause.
            car.updateLogicalState(30);assertEquals(Spatial.CullHint.Inherit,frost.getLocalCullHint());
            assertVisibleBudget(car);
            VehicleVisual.updateEffects(car,false,true);assertEquals(Spatial.CullHint.Always,frost.getCullHint());
            assertEquals(Spatial.CullHint.Inherit,shield.getLocalCullHint());assertVisibleBudget(car);
            assertTrue(((Geometry)shield.getChild("shield-paint")).getMesh().getTriangleCount()>100,"Shield is a body-sized shell, not a status ring");
        }
        VehicleVisual.updateDamage(car,0);VehicleVisual.updateEffects(car,true,true);
        assertEquals(Spatial.CullHint.Always,frost.getCullHint());assertEquals(Spatial.CullHint.Always,shield.getCullHint());
        VehicleVisual.updateDamage(car,1);VehicleVisual.updateEffects(car,false,false);assertVisibleBudget(car);
        assertEquals(Spatial.CullHint.Always,frost.getCullHint());assertEquals(Spatial.CullHint.Always,shield.getCullHint());
    }
    @Test void rebuildingCarForRetryReusesImmutableTextureImagesWithoutSharingDamageMaterials() {
        var assets=PresentationTestAssets.shared();
        Node original=VehicleVisual.create(assets,0);
        for(int retry=0;retry<3;retry++) {
            Node rebuilt=VehicleVisual.create(assets,retry);
            for(String part:List.of("paint","steel","rubber-trim")) {
                Material first=((Geometry)original.getChild(part)).getMaterial(),next=((Geometry)rebuilt.getChild(part)).getMaterial();
                assertNotSame(first,next);
                for(String map:List.of("DiffuseMap","NormalMap","SpecularMap"))if(first.getParam(map)!=null) {
                    var firstImage=((com.jme3.texture.Texture)first.getParam(map).getValue()).getImage();
                    var nextImage=((com.jme3.texture.Texture)next.getParam(map).getValue()).getImage();
                    assertSame(firstImage,nextImage,"Retry must reuse cached decoded images for "+part+"/"+map);
                }
            }
            VehicleVisual.updateDamage(rebuilt,0);assertEquals(0,(Integer)original.getUserData("damageStage"));
        }
    }
    private static float[] positions(Mesh mesh) {FloatBuffer buffer=mesh.getFloatBuffer(VertexBuffer.Type.Position).duplicate().rewind();float[] values=new float[buffer.limit()];buffer.get(values);return values;}
    private static void assertVisibleBudget(Node car) {
        int[] triangles={0},draws={0};
        car.depthFirstTraversal(spatial->{if(spatial instanceof Geometry geometry) {
            for(Spatial current=geometry;current!=null;current=current.getParent())if(current.getLocalCullHint()==Spatial.CullHint.Always)return;
            triangles[0]+=geometry.getMesh().getTriangleCount();draws[0]++;
        }});
        assertTrue(triangles[0]<=8000,"Active damage/status vehicle triangle budget: "+triangles[0]);
        assertTrue(draws[0]<=20,"Prepared damage overlays have at most four extra draws: "+draws[0]);
    }
}
