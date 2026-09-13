package game.wreckriff.presentation;

import com.jme3.math.*;
import com.jme3.texture.*;
import com.jme3.texture.image.ColorSpace;
import com.jme3.util.BufferUtils;
import game.wreckriff.config.VehicleProfile;
import java.nio.ByteBuffer;
import java.util.*;

/** Bounded per-car canonical UV history. Uploaded only when an actual hit/repair changes the mask. */
final class VehicleDamageMarks {
    static final int SIZE=512,LIMIT=32;
    record Mark(Vector3f point,Vector3f normal,float strength,int channel,long id) {}
    private final VehicleProfile profile;
    private final ArrayDeque<Mark> marks=new ArrayDeque<>();
    private final ByteBuffer pixels=BufferUtils.createByteBuffer(SIZE*SIZE*4);
    private final Image image=new Image(Image.Format.RGBA8,SIZE,SIZE,pixels,ColorSpace.Linear);
    private boolean dirty;
    final Texture2D texture=new Texture2D(image);
    VehicleDamageMarks(VehicleProfile profile){this.profile=profile;texture.setMagFilter(Texture.MagFilter.Bilinear);texture.setMinFilter(Texture.MinFilter.BilinearNoMipMaps);texture.setWrap(Texture.WrapMode.EdgeClamp);}
    void hit(Vector3f point,Vector3f normal,float amount,boolean fire,boolean glass,long id) {
        if(marks.size()==LIMIT)marks.removeFirst();marks.add(new Mark(point.clone(),normal.clone(),Math.clamp(amount,.12f,1),fire?1:glass?2:0,id));dirty=true;
    }
    void repair(float ratio) {
        if(ratio>=.999f)marks.clear();else {var retained=new ArrayDeque<Mark>();for(Mark m:marks)if(m.strength*(1-ratio)>.07f)retained.add(new Mark(m.point,m.normal,m.strength*(1-ratio),m.channel,m.id));marks.clear();marks.addAll(retained);}dirty=true;
    }
    private void rebuild() {
        for(int i=0;i<pixels.capacity();i++)pixels.put(i,(byte)0);
        for(Mark mark:marks) {
            Vector2f uv=uv(mark.point,mark.normal,profile);int cx=Math.round(uv.x*(SIZE-1)),cy=Math.round(uv.y*(SIZE-1));
            int radius=3+Math.round(mark.strength*9);long seed=mark.id;
            for(int y=Math.max(0,cy-radius);y<=Math.min(SIZE-1,cy+radius);y++)for(int x=Math.max(0,cx-radius);x<=Math.min(SIZE-1,cx+radius);x++) {
                float dx=(x-cx)/(float)radius,dy=(y-cy)/(float)radius,r=(float)Math.sqrt(dx*dx+dy*dy);
                float noise=.65f+.35f*(float)Math.sin((x*13+y*31+(seed&65535))*.31);
                float intensity=Math.max(0,1-r)*mark.strength*noise;
                if(mark.channel==2){float a=(float)Math.atan2(dy,dx);intensity*=Math.pow(Math.abs(Math.cos(a*5+seed%7)),14);}
                int offset=(y*SIZE+x)*4+mark.channel;pixels.put(offset,(byte)Math.max(pixels.get(offset)&255,Math.round(intensity*255)));
            }
        }
        image.setUpdateNeeded();
    }
    static Vector2f uv(Vector3f p,Vector3f normal,VehicleProfile profile) {
        int axis=Math.abs(normal.x)>Math.abs(normal.y)?0:1;if(Math.abs(normal.z)>(axis==0?Math.abs(normal.x):Math.abs(normal.y)))axis=2;
        float n=axis==0?normal.x:axis==1?normal.y:normal.z;int face=axis*2+(n>0?1:0);
        float u=axis==0?p.z/profile.length()+.5f:p.x/profile.width()+.5f;
        float height=profile.id().equals("rivet")?1.07f:profile.height();float v=axis==1?p.z/profile.length()+.5f:p.y/height+.08f;
        return new Vector2f((face%3+.06f+Math.clamp(u,0,1)*.88f)/3,(face/3+.06f+Math.clamp(v,0,1)*.88f)/2);
    }
    int count(){return marks.size();}
    void flush(){if(dirty){rebuild();dirty=false;}}
    void close(){marks.clear();image.dispose();}
    byte[] snapshot(){flush();byte[] result=new byte[pixels.capacity()];pixels.duplicate().clear().get(result);return result;}
}
