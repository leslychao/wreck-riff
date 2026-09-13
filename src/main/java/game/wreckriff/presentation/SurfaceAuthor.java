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
import game.wreckriff.config.VehicleProfile;
import java.util.*;

public final class SurfaceAuthor {
        final List<Float> points=new ArrayList<>(),normals=new ArrayList<>(),uvs=new ArrayList<>();
        void triangle(Vector3f a,Vector3f b,Vector3f c) {
            Vector3f n=b.subtract(a).cross(c.subtract(a)).normalizeLocal();
            for(Vector3f p:List.of(a,b,c)) { Collections.addAll(points,p.x,p.y,p.z); Collections.addAll(normals,n.x,n.y,n.z);
                float[] uv=SurfaceMesh.uv(p,n,1.5f);Collections.addAll(uvs,uv[0],uv[1]); }
        }
        void quad(Vector3f a,Vector3f b,Vector3f c,Vector3f d) { triangle(a,b,c);triangle(a,c,d); }
        void panel(Vector3f a,Vector3f b,Vector3f c,Vector3f d,int across,int along) {
            // Extra local vertices let prepared dents crease a panel instead of only moving its corners.
            for(int u=0;u<across;u++)for(int v=0;v<along;v++) {
                float u0=u/(float)across,u1=(u+1)/(float)across,v0=v/(float)along,v1=(v+1)/(float)along;
                quad(panelPoint(a,b,c,d,u0,v0),panelPoint(a,b,c,d,u1,v0),
                        panelPoint(a,b,c,d,u1,v1),panelPoint(a,b,c,d,u0,v1));
            }
        }
        Vector3f panelPoint(Vector3f a,Vector3f b,Vector3f c,Vector3f d,float u,float v) {
            return new Vector3f().interpolateLocal(new Vector3f().interpolateLocal(a,b,u),new Vector3f().interpolateLocal(d,c,u),v);
        }
        void bumper(float z,float height) {
            // One material draw, but a segmented beam that bends locally without moving the wheels.
            Vector3f a=v(-.81f,-.055f-height,z-.075f),b=v(.81f,-.055f-height,z-.075f),
                    c=v(.81f,-.055f+height,z-.075f),d=v(-.81f,-.055f+height,z-.075f),
                    e=v(-.81f,-.055f-height,z+.075f),f=v(.81f,-.055f-height,z+.075f),
                    g=v(.81f,-.055f+height,z+.075f),h=v(-.81f,-.055f+height,z+.075f);
            panel(a,d,c,b,1,8);panel(e,f,g,h,8,1);quad(a,e,h,d);quad(b,c,g,f);
            panel(d,h,g,c,1,8);panel(a,b,f,e,8,1);
        }
        void box(float x,float y,float z,float hx,float hy,float hz) {
            Vector3f a=v(x-hx,y-hy,z-hz),b=v(x+hx,y-hy,z-hz),c=v(x+hx,y+hy,z-hz),d=v(x-hx,y+hy,z-hz);
            Vector3f e=v(x-hx,y-hy,z+hz),f=v(x+hx,y-hy,z+hz),g=v(x+hx,y+hy,z+hz),h=v(x-hx,y+hy,z+hz);
            quad(a,d,c,b);quad(e,f,g,h);quad(a,e,h,d);quad(b,c,g,f);quad(d,h,g,c);quad(a,b,f,e);
        }
        void loft(float[][] rings) {
            for(int i=0;i<rings.length-1;i++) {
                Vector3f[] a=ring(rings[i]),b=ring(rings[i+1]);
                for(int face=0;face<8;face++) {
                    int across=face==4?6:face==2||face==6?2:1;
                    int along=face==4||face==2||face==6?3:1;
                    panel(a[face],a[(face+1)%8],b[(face+1)%8],b[face],across,along);
                }
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
            panel(v(x-halfWidth,y1,near),v(x-halfWidth,y2,far),v(x+halfWidth,y2,far),v(x+halfWidth,y1,near),4,2);
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
        static Node wheel(int id,Material metal,Material tyre) {
        Node node=new Node("wheel-"+id);
        SurfaceAuthor rubber=new SurfaceAuthor(),steel=new SurfaceAuthor();
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
    private static Vector3f v(float x,float y,float z) {return new Vector3f(x,y,z);}
}
