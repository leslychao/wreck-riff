package game.wreckriff.presentation;

import com.jme3.asset.AssetManager;
import com.jme3.font.BitmapText;
import jme3tools.optimize.GeometryBatchFactory;
import com.jme3.material.Material;
import com.jme3.math.*;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.*;
import com.jme3.util.BufferUtils;
import com.jme3.util.mikktspace.MikktspaceTangentGenerator;
import java.util.*;

/** Authored Rivet coupe, built from local parameterized surfaces; +Z front, +X right. */
public final class VehicleVisual {
    private static final ColorRGBA[] PAINT = {
        new ColorRGBA(.85f,.19f,.065f,1), new ColorRGBA(.62f,.54f,.84f,1),
        new ColorRGBA(.92f,.65f,.11f,1), new ColorRGBA(.11f,.69f,.7f,1),
        new ColorRGBA(.35f,.77f,.19f,1)
    };
    private VehicleVisual() {}

    public static Node create(AssetManager assets, int livery) {
        if (livery<0 || livery>=5) throw new IllegalArgumentException("Rivet livery 0..4 required");
        Node root=new Node("Rivet-"+livery);
        SurfaceMaterials surfaces=new SurfaceMaterials(assets);
        Material paint=surfaces.paint(PAINT[livery]), steel=surfaces.material("steel");
        Material dark=surfaces.rubber();
        Material glass=SurfaceMaterials.lit(assets,new ColorRGBA(.035f,.09f,.13f,1),64,.8f);
        Material markings=SurfaceMaterials.lit(assets,new ColorRGBA(.88f,.84f,.72f,1),15,.08f);
        Material lamps=unlit(assets,new ColorRGBA(1,.77f,.33f,1));
        Material tail=unlit(assets,new ColorRGBA(.95f,.09f,.025f,1));
        Builder body=new Builder(), metal=new Builder(), black=new Builder(), windows=new Builder(), ink=new Builder();
        Builder frontLights=new Builder(), rearLights=new Builder();
        // The long swept bonnet, short raked cab and kicked rear distinguish this from a box chassis.
        body.loft(new float[][] {
            {-2.3f,.79f,-.19f,.19f}, {-1.85f,1.02f,-.21f,.33f},
            {-.9f,1.03f,-.21f,.38f}, {.5f,1.00f,-.21f,.39f},
            {1.65f,.96f,-.19f,.26f}, {2.3f,.79f,-.13f,.14f}
        });
        body.loft(new float[][] {
            {-1.36f,.78f,.31f,.42f}, {-.91f,.67f,.33f,.99f},
            {-.2f,.64f,.33f,1.01f}, {.47f,.77f,.33f,.43f}
        });
        // Windshield and rear glass follow the raked roof, with deliberately thick pillars.
        windows.quad(v(-.70f,.456f,.43f),v(.70f,.456f,.43f),v(.59f,1.018f,-.17f),v(-.59f,1.018f,-.17f));
        windows.quad(v(.72f,.464f,-1.31f),v(-.72f,.464f,-1.31f),v(-.62f,.956f,-.935f),v(.62f,.956f,-.935f));
        for (int side:new int[]{-1,1}) {
            float x=side;
            if(side>0) windows.quad(v(x*.786f,.44f,-1.23f),v(x*.687f,.94f,-.88f),
                    v(x*.66f,.94f,-.24f),v(x*.77f,.455f,.35f));
            else windows.quad(v(x*.77f,.455f,.35f),v(x*.66f,.94f,-.24f),
                    v(x*.687f,.94f,-.88f),v(x*.786f,.44f,-1.23f));
            // Armoured sill and curved wheel-arch lips follow the actual wheel silhouette.
            black.box(x*1.012f,-.03f,-.15f,.038f,.14f,.87f);
            metal.box(x*1.055f,-.09f,-.1f,.035f,.045f,1.93f);
            for (float z:new float[]{-1.4f,1.4f}) {
                body.arch(x*1.06f,-.20f,z,.43f,.56f,.16f,20);
                metal.arch(x*1.22f,-.20f,z,.47f,.51f,.025f,20);
            }
            metal.box(x*.95f,.37f,-.13f,.10f,.025f,.16f); // door handle
            metal.box(x*.83f,.50f,.32f,.16f,.055f,.09f); // bracket mirror
            // Front weapon brackets are exposed and mechanically distinct from lamps.
            metal.box(x*.53f,.37f,1.45f,.16f,.07f,.43f);
            black.box(x*.53f,.47f,1.65f,.095f,.095f,.43f);
            metal.cylinderZ(x*.53f,.47f,2.09f,.063f,.17f,10);
            black.cylinderZ(x*.53f,.47f,2.27f,.041f,.013f,10);
            frontLights.box(x*.61f,.16f,2.261f,.145f,.058f,.012f);
            rearLights.box(x*.6f,.19f,-2.25f,.15f,.042f,.023f);
            // Twin low exhausts, rear-facing, for the turbo effect anchors.
            metal.cylinderZ(x*.72f,-.11f,-2.33f,.09f,.14f,10);
            black.cylinderZ(x*.72f,-.11f,-2.48f,.067f,.008f,10);
            Node exhaust=new Node("exhaust-"+(side<0?"left":"right"));
            exhaust.setLocalTranslation(x*.72f,-.11f,-2.49f); root.attachChild(exhaust);
        }
        metal.box(0,-.055f,2.32f,.81f,.072f,.075f);
        metal.box(0,-.055f,-2.32f,.81f,.075f,.075f);
        black.box(0,.065f,2.305f,.27f,.055f,.015f);
        for (int i=-3;i<=3;i++) metal.box(i*.071f,.065f,2.324f,.01f,.05f,.008f);
        // Rear amplifier pack: two speakers in a braced cabinet, cooling fins and a carrying rail.
        black.box(0,.52f,-1.79f,.55f,.22f,.27f);
        metal.box(0,.76f,-1.80f,.58f,.025f,.29f);
        for (float x:new float[]{-.27f,.27f}) {
            metal.cylinderZ(x,.53f,-2.075f,.16f,.014f,14);
            black.cylinderZ(x,.53f,-2.096f,.12f,.016f,14);
            metal.cylinderZ(x,.53f,-2.115f,.043f,.008f,10);
        }
        for (int i=-3;i<=3;i++) metal.box(i*.115f,.535f,-1.508f,.019f,.145f,.015f);
        // Fastener heads, bonnet vent slats and scratches use the shared metal batch.
        for (float x:new float[]{-.81f,.81f}) for (float z:new float[]{-.72f,.65f,1.20f})
            metal.box(x,.405f-(z>0?z*.065f:0),z,.028f,.014f,.028f);
        for (int i=0;i<5;i++) black.box(0,.392f-i*.008f,.61f+i*.12f,.25f,.012f,.027f);
        pattern(ink,livery);
        body.attach(root,"paint",paint); metal.attach(root,"steel",steel); black.attach(root,"rubber-trim",dark);
        windows.attach(root,"glass",glass); ink.attach(root,"livery-markings",markings);
        frontLights.attach(root,"headlights",lamps); rearLights.attach(root,"taillights",tail);
        for (int wheel=0;wheel<4;wheel++) {
            Node node=wheel(wheel,steel,dark);
            node.setLocalTranslation(wheel%2==0?-1.06f:1.06f,-.20f,wheel<2?1.4f:-1.4f);
            root.attachChild(node);
        }
        addNumbers(root,assets,livery);
        root.setShadowMode(RenderQueue.ShadowMode.CastAndReceive);
        root.setUserData("livery",livery); root.setUserData("assetOrigin","original-java-procedural");
        return root;
    }

    private static Node wheel(int id,Material metal,Material tyre) {
        Node node=new Node("wheel-"+id);
        Builder rubber=new Builder(),steel=new Builder();
        rubber.tyre(.38f,.30f,32,8);
        for (float x:new float[]{-.153f,.153f}) {
            steel.cylinderX(x,0,0,.228f,.016f,12);
            rubber.cylinderX(x*1.07f,0,0,.17f,.01f,12);
            steel.cylinderX(x*1.15f,0,0,.071f,.024f,10);
            for (int spoke=0;spoke<5;spoke++) {
                double a=spoke*Math.PI*2/5;
                // Faceted spokes rotate with the wheel; both outside faces are fully modeled.
                steel.box(x*1.12f,(float)Math.cos(a)*.11f,(float)Math.sin(a)*.11f,.009f,.027f,.027f);
            }
        }
        // Rounded shoulders and recessed tread retain shape even at close range.
        for (int i=0;i<32;i++) {
            double a=i*Math.PI*2/32, b=a+.025;
            rubber.quad(v(-.12f,(float)Math.cos(a)*.388f,(float)Math.sin(a)*.388f),
                    v(.12f,(float)Math.cos(a)*.388f,(float)Math.sin(a)*.388f),
                    v(.12f,(float)Math.cos(b)*.388f,(float)Math.sin(b)*.388f),
                    v(-.12f,(float)Math.cos(b)*.388f,(float)Math.sin(b)*.388f));
        }
        rubber.attach(node,"tyre",tyre); steel.attach(node,"hub",metal);
        return node;
    }
    private static void addNumbers(Node root,AssetManager assets,int livery) {
        Node numbers=new Node("race-numbers");
        for(int face=0;face<3;face++) {
            BitmapText number=new BitmapText(assets.loadFont("fonts/wreck-bold.fnt"));number.setText(Integer.toString(livery));
            number.setSize(face==2?.64f:.43f);number.setColor(new ColorRGBA(.9f,.87f,.74f,1));
            if(face==2) {
                number.rotate(-FastMath.HALF_PI,0,0);number.setLocalTranslation(-number.getLineWidth()*.5f,1.032f,-.72f);
            } else {
                float side=face==0?-1:1;number.rotate(0,side*FastMath.HALF_PI,0);
                number.setLocalTranslation(side*1.044f,.32f,-.10f+side*number.getLineWidth()*.5f);
            }
            number.updateLogicalState(0);number.setQueueBucket(RenderQueue.Bucket.Transparent);
            number.setShadowMode(RenderQueue.ShadowMode.Off);numbers.attachChild(number);
        }
        numbers.updateGeometricState();GeometryBatchFactory.optimize(numbers,false);root.attachChild(numbers);
    }
    private static void pattern(Builder ink,int livery) {
        // Hood patterns remain visibly distinct under colour-vision deficiencies.
        switch(livery) {
            case 0 -> { for(float x:new float[]{-.37f,.37f}) ink.hoodStrip(x,.095f,.61f,2.13f); }
            case 1 -> { for(int i=0;i<5;i++) ink.hoodStrip(-.65f+i*.29f,.06f,1.40f+(i%2)*.15f,2.10f); }
            case 2 -> {
                for(int i=0;i<5;i++) ink.hoodStrip(-.57f+i*.27f,.08f,.92f+i*.12f,1.17f+i*.12f);
                ink.hoodStrip(0,.48f,1.99f,2.10f);
            }
            case 3 -> { for(int i=0;i<4;i++) ink.hoodStrip(0,.19f+i*.12f,.94f+i*.28f,1.04f+i*.28f); }
            case 4 -> {
                for(int row=0;row<4;row++) for(int col=0;col<4;col++) if((row+col)%2==0)
                    ink.hoodStrip(-.48f+col*.32f,.145f,1.2f+row*.21f,1.38f+row*.21f);
            }
            default -> throw new IllegalArgumentException();
        }
    }
    private static Material unlit(AssetManager assets,ColorRGBA color) {
        Material material=new Material(assets,"Common/MatDefs/Misc/Unshaded.j3md");
        material.setColor("Color",color); material.setColor("GlowColor",color.mult(.5f)); return material;
    }
    private static Vector3f v(float x,float y,float z) { return new Vector3f(x,y,z); }

    private static final class Builder {
        final List<Float> points=new ArrayList<>(),normals=new ArrayList<>(),uvs=new ArrayList<>();
        void triangle(Vector3f a,Vector3f b,Vector3f c) {
            Vector3f n=b.subtract(a).cross(c.subtract(a)).normalizeLocal();
            for(Vector3f p:List.of(a,b,c)) { Collections.addAll(points,p.x,p.y,p.z); Collections.addAll(normals,n.x,n.y,n.z);
                float[] uv=SurfaceMesh.uv(p,n,1.5f);Collections.addAll(uvs,uv[0],uv[1]); }
        }
        void quad(Vector3f a,Vector3f b,Vector3f c,Vector3f d) { triangle(a,b,c);triangle(a,c,d); }
        void box(float x,float y,float z,float hx,float hy,float hz) {
            Vector3f a=v(x-hx,y-hy,z-hz),b=v(x+hx,y-hy,z-hz),c=v(x+hx,y+hy,z-hz),d=v(x-hx,y+hy,z-hz);
            Vector3f e=v(x-hx,y-hy,z+hz),f=v(x+hx,y-hy,z+hz),g=v(x+hx,y+hy,z+hz),h=v(x-hx,y+hy,z+hz);
            quad(a,d,c,b);quad(e,f,g,h);quad(a,e,h,d);quad(b,c,g,f);quad(d,h,g,c);quad(a,b,f,e);
        }
        void loft(float[][] rings) {
            for(int i=0;i<rings.length-1;i++) {
                Vector3f[] a=ring(rings[i]),b=ring(rings[i+1]);
                for(int face=0;face<8;face++) quad(a[face],a[(face+1)%8],b[(face+1)%8],b[face]);
            }
            Vector3f[] start=ring(rings[0]),end=ring(rings[rings.length-1]);
            for(int i=1;i<7;i++){triangle(start[0],start[i+1],start[i]);triangle(end[0],end[i],end[i+1]);}
        }
        Vector3f[] ring(float[] r) {
            float bevel=Math.min(.12f,(r[3]-r[2])*.24f),x=r[1],bottom=r[2],top=r[3],z=r[0];
            return new Vector3f[]{v(-x+bevel,bottom,z),v(x-bevel,bottom,z),v(x,bottom+bevel,z),
                    v(x,top-bevel,z),v(x-bevel,top,z),v(-x+bevel,top,z),v(-x,top-bevel,z),v(-x,bottom+bevel,z)};
        }
        void arch(float x,float y,float z,float inner,float outer,float half,int count) {
            for(int i=0;i<count;i++) {
                double a=-.15+i*(Math.PI+.3)/count,b=-.15+(i+1)*(Math.PI+.3)/count;
                for(float side:new float[]{-1,1}) {
                    float face=x+side*half;
                    Vector3f p=v(face,y+(float)Math.sin(a)*inner,z+(float)Math.cos(a)*inner),
                            q=v(face,y+(float)Math.sin(a)*outer,z+(float)Math.cos(a)*outer),
                            r=v(face,y+(float)Math.sin(b)*outer,z+(float)Math.cos(b)*outer),
                            t=v(face,y+(float)Math.sin(b)*inner,z+(float)Math.cos(b)*inner);
                    if(side>0)quad(p,t,r,q);else quad(p,q,r,t);
                }
                quad(v(x-half,y+(float)Math.sin(a)*outer,z+(float)Math.cos(a)*outer),
                        v(x+half,y+(float)Math.sin(a)*outer,z+(float)Math.cos(a)*outer),
                        v(x+half,y+(float)Math.sin(b)*outer,z+(float)Math.cos(b)*outer),
                        v(x-half,y+(float)Math.sin(b)*outer,z+(float)Math.cos(b)*outer));
            }
        }
        void tyre(float radius,float width,int segments,int sections) {
            // Smooth vertex normals follow the rounded axial shoulder profile; UVs unwrap circumference.
            float[][] profile={{-width*.5f,.22f},{-width*.55f,.30f},{-width*.44f,.36f},{-width*.28f,radius},
                    {width*.28f,radius},{width*.44f,.36f},{width*.55f,.30f},{width*.5f,.22f}};
            for(int i=0;i<segments;i++)for(int j=0;j<sections-1;j++) {
                double a=i*Math.PI*2/segments,b=(i+1)*Math.PI*2/segments;
                Vector3f p=radial(0,0,0,profile[j][1],profile[j][0],a,true),q=radial(0,0,0,profile[j][1],profile[j][0],b,true);
                Vector3f r=radial(0,0,0,profile[j+1][1],profile[j+1][0],b,true),t=radial(0,0,0,profile[j+1][1],profile[j+1][0],a,true);
                Vector3f[] vertices={p,q,r,t},normal={tyreNormal(profile,j,a),tyreNormal(profile,j,b),tyreNormal(profile,j+1,b),tyreNormal(profile,j+1,a)};
                float[][] uv={{i*3f/segments,j/(float)(sections-1)},{(i+1)*3f/segments,j/(float)(sections-1)},
                        {(i+1)*3f/segments,(j+1)/(float)(sections-1)},{i*3f/segments,(j+1)/(float)(sections-1)}};
                for(int vertex:new int[]{0,1,2,0,2,3}) {
                    Vector3f point=vertices[vertex],n=normal[vertex];
                    Collections.addAll(points,point.x,point.y,point.z);Collections.addAll(normals,n.x,n.y,n.z);
                    Collections.addAll(uvs,uv[vertex][0],uv[vertex][1]);
                }
            }
        }
        Vector3f tyreNormal(float[][] profile,int section,double angle) {
            float[] before=profile[Math.max(0,section-1)],after=profile[Math.min(profile.length-1,section+1)];
            float nx=before[1]-after[1],radial=after[0]-before[0];
            return v(nx,radial*(float)Math.cos(angle),radial*(float)Math.sin(angle)).normalizeLocal();
        }
        void cylinderZ(float x,float y,float z,float radius,float length,int count) {
            cylinder(x,y,z,radius,length,count,false);
        }
        void cylinderX(float x,float y,float z,float radius,float length,int count) {
            cylinder(x,y,z,radius,length,count,true);
        }
        void cylinder(float x,float y,float z,float radius,float length,int count,boolean alongX) {
            for(int i=0;i<count;i++) {
                double a=i*Math.PI*2/count,b=(i+1)*Math.PI*2/count;
                Vector3f p=radial(x,y,z,radius,-length*.5f,a,alongX),q=radial(x,y,z,radius,-length*.5f,b,alongX);
                Vector3f r=radial(x,y,z,radius,length*.5f,b,alongX),s=radial(x,y,z,radius,length*.5f,a,alongX);
                quad(p,q,r,s);
                triangle(radial(x,y,z,0,-length*.5f,0,alongX),q,p);
                triangle(radial(x,y,z,0,length*.5f,0,alongX),s,r);
            }
        }
        Vector3f radial(float x,float y,float z,float radius,float axis,double angle,boolean alongX) {
            float a=(float)Math.cos(angle)*radius,b=(float)Math.sin(angle)*radius;
            return alongX?v(x+axis,y+a,z+b):v(x+a,y+b,z+axis);
        }
        void hoodStrip(float x,float halfWidth,float near,float far) {
            // Follow the bonnet crease instead of a chord that would cut through the paint.
            if(near<1.65f && far>1.65f) { hoodStrip(x,halfWidth,near,1.65f);hoodStrip(x,halfWidth,1.65f,far);return; }
            float y1=hoodY(near),y2=hoodY(far);
            quad(v(x-halfWidth,y1,near),v(x-halfWidth,y2,far),v(x+halfWidth,y2,far),v(x+halfWidth,y1,near));
        }
        float hoodY(float z) { return z<1.65f?.39f-(z-.5f)*(.13f/1.15f)+.012f:.26f-(z-1.65f)*(.12f/.65f)+.012f; }
        void attach(Node parent,String name,Material material) {
            if(points.isEmpty())return;
            float[] p=new float[points.size()],n=new float[normals.size()],uv=new float[uvs.size()];
            for(int i=0;i<p.length;i++){p[i]=points.get(i);n[i]=normals.get(i);}
            for(int i=0;i<uv.length;i++)uv[i]=uvs.get(i);
            Mesh mesh=new Mesh(); mesh.setBuffer(VertexBuffer.Type.Position,3,BufferUtils.createFloatBuffer(p));
            mesh.setBuffer(VertexBuffer.Type.Normal,3,BufferUtils.createFloatBuffer(n));
            mesh.setBuffer(VertexBuffer.Type.TexCoord,2,BufferUtils.createFloatBuffer(uv));
            mesh.updateBound();MikktspaceTangentGenerator.generate(mesh);mesh.setStatic();
            Geometry geometry=new Geometry(name,mesh);geometry.setMaterial(material);parent.attachChild(geometry);
        }
    }
}
