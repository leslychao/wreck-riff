package game.wreckriff.presentation;

import com.jme3.asset.AssetManager;
import com.jme3.material.*;
import com.jme3.math.*;
import com.jme3.renderer.*;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.*;
import com.jme3.scene.control.AbstractControl;
import com.jme3.util.BufferUtils;
import com.jme3.util.mikktspace.MikktspaceTangentGenerator;
import java.nio.FloatBuffer;
import java.util.*;

/** Five immutable mesh/material stages, selected reversibly without touching vehicle physics or wheel nodes. */
final class VehicleDamageVisual extends AbstractControl {
    static final int STAGES=5;
    static final float MAX_DENT=.35f;
    private static final float[] DENT_STRENGTH={0,.28f,.58f,.85f,1};
    private record Part(Geometry geometry,Mesh[] meshes,Material[] materials) {}
    private final List<Part> parts=new ArrayList<>();
    private final List<Part> frostParts=new ArrayList<>(),shieldParts=new ArrayList<>();
    private final Node frost=new Node("frost-overlay"),shield=new Node("shield-shell");
    private final Geometry cracks,wear;
    private final Material scratchMaterial,sootMaterial;
    private final Mesh[] crackStages=new Mesh[STAGES],wearStages=new Mesh[STAGES];
    private int stage;

    static void install(AssetManager assets,Node root) { root.addControl(new VehicleDamageVisual(assets,root)); }
    private VehicleDamageVisual(AssetManager assets,Node root) {
        IdentityHashMap<Material,Material[]> materials=new IdentityHashMap<>();
        for(Spatial child:List.copyOf(root.getChildren())) {
            if(child.getName().startsWith("wheel-")||child.getName().startsWith("exhaust-"))continue;
            child.depthFirstTraversal(spatial->{if(spatial instanceof Geometry geometry) {
                boolean lamp=geometry.getName().equals("headlights")||geometry.getName().equals("taillights");
                Mesh[] meshes=new Mesh[STAGES];meshes[0]=lamp?geometry.getMesh().deepClone():geometry.getMesh();
                for(int damage=1;damage<STAGES;damage++)meshes[damage]=deform(meshes[0],damage);
                if(lamp)for(int damage=0;damage<STAGES;damage++)lampColors(meshes[damage],damage);
                Material[] colors=materials.computeIfAbsent(geometry.getMaterial(),VehicleDamageVisual::materialStages);
                if(lamp)for(Material color:colors)color.setBoolean("VertexColor",true);
                // Even intact instances own their mutable material; font assets and sibling cars remain untouched.
                geometry.setMesh(meshes[0]);geometry.setMaterial(colors[0]);parts.add(new Part(geometry,meshes,colors));
                if(geometry.getName().equals("paint")||geometry.getName().equals("glass")) {
                    Mesh[] overlays=new Mesh[STAGES];for(int damage=0;damage<STAGES;damage++)overlays[damage]=offset(meshes[damage],.010f);
                    Geometry overlay=new Geometry("frost-"+geometry.getName(),overlays[0]);
                    Material ice=SurfaceMaterials.lit(assets,new ColorRGBA(.42f,.78f,.95f,.56f),85,.2f);
                    if(geometry.getMaterial().getParam("DiffuseMap")!=null)
                        ice.setTexture("DiffuseMap",(com.jme3.texture.Texture)geometry.getMaterial().getParam("DiffuseMap").getValue());
                    ice.getAdditionalRenderState().setBlendMode(RenderState.BlendMode.Alpha);
                    ice.getAdditionalRenderState().setDepthWrite(false);overlay.setMaterial(ice);
                    overlay.setQueueBucket(RenderQueue.Bucket.Transparent);overlay.setShadowMode(RenderQueue.ShadowMode.Off);
                    frost.attachChild(overlay);frostParts.add(new Part(overlay,overlays,null));
                    Mesh[] shells=new Mesh[STAGES];for(int damage=0;damage<STAGES;damage++)shells[damage]=offset(meshes[damage],.065f);
                    Geometry shell=new Geometry("shield-"+geometry.getName(),shells[0]);
                    shell.setMaterial(translucent(assets,new ColorRGBA(.08f,.52f,1,.23f)));
                    shell.setQueueBucket(RenderQueue.Bucket.Transparent);shell.setShadowMode(RenderQueue.ShadowMode.Off);
                    shield.attachChild(shell);shieldParts.add(new Part(shell,shells,null));
                }
            }});
        }
        for(int damage=1;damage<STAGES;damage++) {crackStages[damage]=cracks(damage);wearStages[damage]=damage<3?scratches(damage):soot(damage);}
        cracks=new Geometry("glass-cracks",crackStages[1]);cracks.setMaterial(translucent(assets,new ColorRGBA(.67f,.79f,.85f,.65f)));
        scratchMaterial=translucent(assets,new ColorRGBA(.55f,.50f,.41f,.66f));
        sootMaterial=translucent(assets,new ColorRGBA(.025f,.018f,.013f,.67f));
        wear=new Geometry("body-wear",wearStages[1]);wear.setMaterial(scratchMaterial);
        for(Geometry detail:List.of(cracks,wear)) {
            detail.setQueueBucket(RenderQueue.Bucket.Transparent);detail.setShadowMode(RenderQueue.ShadowMode.Off);
            detail.setCullHint(Spatial.CullHint.Always);root.attachChild(detail);
        }
        frost.setCullHint(Spatial.CullHint.Always);shield.setCullHint(Spatial.CullHint.Always);
        root.attachChild(frost);root.attachChild(shield);root.setUserData("damageStage",0);
    }
    static int stage(float hpFraction) {
        if(!Float.isFinite(hpFraction))throw new IllegalArgumentException("Finite HP fraction required");
        return hpFraction<=0?4:hpFraction<=.25f?3:hpFraction<=.5f?2:hpFraction<=.75f?1:0;
    }
    void damage(float hpFraction) {
        int next=stage(hpFraction);if(next==stage)return;stage=next;
        for(Part part:parts) {part.geometry.setMesh(part.meshes[stage]);part.geometry.setMaterial(part.materials[stage]);}
        for(Part part:frostParts)part.geometry.setMesh(part.meshes[stage]);
        for(Part part:shieldParts)part.geometry.setMesh(part.meshes[stage]);
        cracks.setCullHint(stage<2?Spatial.CullHint.Always:Spatial.CullHint.Inherit);
        wear.setCullHint(stage==0?Spatial.CullHint.Always:Spatial.CullHint.Inherit);
        if(stage>0){cracks.setMesh(crackStages[stage]);wear.setMesh(wearStages[stage]);wear.setMaterial(stage<3?scratchMaterial:sootMaterial);}
        if(stage==4)effects(false,false);spatial.setUserData("damageStage",stage);
    }
    void effects(boolean frozen,boolean shielded) {
        frost.setCullHint(frozen&&stage<4?Spatial.CullHint.Inherit:Spatial.CullHint.Always);
        shield.setCullHint(shielded&&stage<4?Spatial.CullHint.Inherit:Spatial.CullHint.Always);
    }
    private static void lampColors(Mesh mesh,int stage) {
        FloatBuffer points=mesh.getFloatBuffer(VertexBuffer.Type.Position);float[] colors=new float[mesh.getVertexCount()*4];
        for(int vertex=0;vertex<mesh.getVertexCount();vertex++) {
            boolean left=points.get(vertex*3)<0;
            float brightness=switch(stage){case 0,1->1;case 2->left?.025f:1;case 3->left?.015f:.075f;default->.015f;};
            colors[vertex*4]=brightness;colors[vertex*4+1]=brightness;colors[vertex*4+2]=brightness;colors[vertex*4+3]=1;
        }
        mesh.setBuffer(VertexBuffer.Type.Color,4,BufferUtils.createFloatBuffer(colors));
    }
    private static Material[] materialStages(Material original) {
        Material[] result=new Material[STAGES];float[] shade={1,.93f,.78f,.52f,.24f};
        for(int stage=0;stage<STAGES;stage++) {
            Material material=original.clone();result[stage]=material;
            for(String name:List.of("Diffuse","Ambient","Color","GlowColor"))if(material.getMaterialDef().getMaterialParam(name)!=null) {
                var parameter=original.getParam(name);if(name.equals("GlowColor")&&parameter==null)continue;
                ColorRGBA base=parameter==null?ColorRGBA.White:(ColorRGBA)parameter.getValue();
                ColorRGBA tint=base.mult(shade[stage]);
                if(stage==4&&!name.equals("GlowColor")) {
                    float charcoal=(base.r*.2126f+base.g*.7152f+base.b*.0722f)*.15f;
                    tint.set(charcoal*1.04f,charcoal,charcoal*.94f,base.a);
                }
                tint.a=base.a;material.setColor(name,tint);
            }
            if(material.getParam("Specular")!=null) {
                ColorRGBA base=(ColorRGBA)material.getParam("Specular").getValue();material.setColor("Specular",base.mult(1-stage*.19f));
            }
        }
        return result;
    }
    private static Vector3f dent(Vector3f point,int stage) {
        if(point.y>=.36f&&point.z>1.79f&&Math.abs(Math.abs(point.x)-.53f)<.19f)return point.clone();
        float strength=DENT_STRENGTH[stage];
        // Broad folds cross local topology; sharper ridges catch light in addition to changing the outline.
        // The weapon barrels sit above this sheet-metal envelope and keep their authored muzzle position.
        float sheet=Math.clamp((.45f-point.y)/.15f,0,1);
        float hood=patch(point,-.15f,.23f,1.38f,.83f,.6f,.85f)*sheet;
        float ridge=patch(point,-.15f,.23f,.91f,.83f,.6f,.23f)*sheet;
        float right=patch(point,1.02f,.08f,-.14f,.40f,.6f,.92f);
        float left=patch(point,-1.02f,.08f,.30f,.35f,.5f,.65f);
        float fender=patch(point,1.12f,.19f,1.43f,.42f,.46f,.48f);
        float front=patch(point,-.28f,-.04f,2.32f,.85f,.34f,.31f);
        float rear=patch(point,.32f,-.02f,-2.31f,.78f,.43f,.43f);
        Vector3f displacement=new Vector3f(-.34f*right+.24f*left-.10f*fender,
                -.33f*hood+.19f*ridge-.24f*fender-.10f*front-.11f*rear,
                -.32f*front+.23f*rear-.09f*hood).multLocal(strength);
        if(displacement.length()>MAX_DENT)displacement.normalizeLocal().multLocal(MAX_DENT);
        return point.add(displacement);
    }
    private static float patch(Vector3f p,float x,float y,float z,float rx,float ry,float rz) {
        float dx=(p.x-x)/rx,dy=(p.y-y)/ry,dz=(p.z-z)/rz;
        return Math.max(0,1-dx*dx-dy*dy-dz*dz);
    }
    private static Mesh deform(Mesh original,int stage) {
        Mesh mesh=original.deepClone();FloatBuffer positions=mesh.getFloatBuffer(VertexBuffer.Type.Position);
        for(int vertex=0;vertex<mesh.getVertexCount();vertex++) {
            Vector3f changed=dent(new Vector3f(positions.get(vertex*3),positions.get(vertex*3+1),positions.get(vertex*3+2)),stage);
            positions.put(vertex*3,changed.x);positions.put(vertex*3+1,changed.y);positions.put(vertex*3+2,changed.z);
        }
        mesh.getBuffer(VertexBuffer.Type.Position).updateData(positions);
        if(mesh.getBuffer(VertexBuffer.Type.Normal)!=null) {
            float[] normals=new float[mesh.getVertexCount()*3];var indices=mesh.getIndicesAsList();
            Vector3f a=new Vector3f(),b=new Vector3f(),c=new Vector3f();
            for(int triangle=0;triangle<mesh.getTriangleCount();triangle++) {
                mesh.getTriangle(triangle,a,b,c);Vector3f n=b.subtract(a).cross(c.subtract(a)).normalizeLocal();
                for(int corner=0;corner<3;corner++){int i=indices.get(triangle*3+corner)*3;normals[i]+=n.x;normals[i+1]+=n.y;normals[i+2]+=n.z;}
            }
            for(int i=0;i<normals.length;i+=3){Vector3f n=new Vector3f(normals[i],normals[i+1],normals[i+2]).normalizeLocal();normals[i]=n.x;normals[i+1]=n.y;normals[i+2]=n.z;}
            mesh.setBuffer(VertexBuffer.Type.Normal,3,BufferUtils.createFloatBuffer(normals));
            if(mesh.getBuffer(VertexBuffer.Type.TexCoord)!=null)MikktspaceTangentGenerator.generate(mesh);
        }
        mesh.updateBound();mesh.setStatic();return mesh;
    }
    private static Mesh offset(Mesh original,float distance) {
        Mesh mesh=original.deepClone();FloatBuffer positions=mesh.getFloatBuffer(VertexBuffer.Type.Position),normals=mesh.getFloatBuffer(VertexBuffer.Type.Normal);
        for(int i=0;i<positions.limit();i++)positions.put(i,positions.get(i)+normals.get(i)*distance);
        mesh.getBuffer(VertexBuffer.Type.Position).updateData(positions);mesh.updateBound();return mesh;
    }
    private static Mesh cracks(int stage) {
        List<Vector3f> vertices=new ArrayList<>();
        for(int cluster=0;cluster<(stage==1?1:2);cluster++) {
            float u=cluster==0?.30f:.73f,v=cluster==0?.53f:.34f;
            for(int spoke=0;spoke<5+stage;spoke++) {
                float angle=spoke*FastMath.TWO_PI/(5+stage),reach=.12f+stage*.065f;
                Vector3f a=glassPoint(u,v),b=glassPoint(Math.clamp(u+FastMath.cos(angle)*reach,0,1),Math.clamp(v+FastMath.sin(angle)*reach,0,1));
                ribbon(vertices,dent(a,stage),dent(b,stage),.0025f+stage*.0012f);
            }
        }
        return SurfaceMesh.triangles(vertices,1);
    }
    private static Vector3f glassPoint(float u,float v) {
        float width=FastMath.interpolateLinear(v,.70f,.59f);
        return new Vector3f((u*2-1)*width,FastMath.interpolateLinear(v,.456f,1.018f)+.005f,FastMath.interpolateLinear(v,.43f,-.17f)+.005f);
    }
    private static Mesh scratches(int stage) {
        List<Vector3f> vertices=new ArrayList<>();
        for(int scratch=0;scratch<4+stage*3;scratch++) {
            float x=-.66f+(scratch%4)*.31f,z=.95f+(scratch/4)*.42f;
            Vector3f a=new Vector3f(x,hoodOrTrunkY(z),z),b=new Vector3f(x+.04f+(scratch%2)*.055f,0,z+.13f+stage*.04f);
            b.y=hoodOrTrunkY(b.z);ribbon(vertices,dent(a,stage),dent(b,stage),.003f+stage*.0015f);
        }
        return SurfaceMesh.triangles(vertices,1);
    }
    private static Mesh soot(int stage) {
        List<Vector3f> vertices=new ArrayList<>();
        for(int patch=0;patch<stage;patch++) {
            float x=-.48f+(patch%2)*.71f,z=patch<2?1.6f:-1.7f,radius=.12f+stage*.07f;
            Vector3f centre=new Vector3f(x,hoodOrTrunkY(z),z);
            for(int segment=0;segment<12;segment++) {
                float a=segment*FastMath.TWO_PI/12,b=(segment+1)*FastMath.TWO_PI/12;
                Vector3f p=new Vector3f(x+FastMath.cos(a)*radius,0,z+FastMath.sin(a)*radius*.65f);
                Vector3f q=new Vector3f(x+FastMath.cos(b)*radius,0,z+FastMath.sin(b)*radius*.65f);
                p.y=hoodOrTrunkY(p.z);q.y=hoodOrTrunkY(q.z);
                Collections.addAll(vertices,dent(centre,stage),dent(q,stage),dent(p,stage));
            }
        }
        return SurfaceMesh.triangles(vertices,1);
    }
    private static float hoodOrTrunkY(float z) {return (z<0?(z< -1.85f?.19f+(z+2.3f)*(.14f/.45f):.33f+(z+1.85f)*(.05f/.95f)):z<1.65f?.39f-(z-.5f)*(.13f/1.15f):.26f-(z-1.65f)*(.12f/.65f))+.013f;}
    private static void ribbon(List<Vector3f> out,Vector3f a,Vector3f b,float width) {
        Vector3f sideways=b.subtract(a).cross(new Vector3f(0,.73f,.68f)).normalizeLocal().multLocal(width);
        Collections.addAll(out,a.subtract(sideways),b.subtract(sideways),b.add(sideways),a.subtract(sideways),b.add(sideways),a.add(sideways));
    }
    private static Material translucent(AssetManager assets,ColorRGBA color) {
        Material material=new Material(assets,"Common/MatDefs/Misc/Unshaded.j3md");material.setColor("Color",color);
        material.getAdditionalRenderState().setBlendMode(RenderState.BlendMode.Alpha);material.getAdditionalRenderState().setDepthWrite(false);
        material.getAdditionalRenderState().setFaceCullMode(RenderState.FaceCullMode.Off);return material;
    }
    @Override protected void controlUpdate(float tpf) { }
    @Override protected void controlRender(RenderManager manager,ViewPort viewport) { }
}
