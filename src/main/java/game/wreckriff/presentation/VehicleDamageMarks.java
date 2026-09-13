package game.wreckriff.presentation;

import com.jme3.math.*;
import com.jme3.asset.AssetManager;
import com.jme3.texture.image.ImageRaster;
import com.jme3.texture.*;
import com.jme3.texture.image.ColorSpace;
import com.jme3.util.BufferUtils;
import java.nio.ByteBuffer;
import java.util.*;

/** Bounded per-car canonical UV history. Uploaded only when an actual hit/repair changes the mask. */
final class VehicleDamageMarks {
    static final int SIZE=512,LIMIT=32;
    record Mark(Vector3f point,Vector3f normal,Vector2f uv,float strength,int channel,long id,int owner,int stamp) {}
    private static final Map<AssetManager,byte[]> ATLASES=new WeakHashMap<>();
    private final byte[] atlas,ownership;
    private final ArrayDeque<Mark> marks=new ArrayDeque<>();
    private final ByteBuffer pixels=BufferUtils.createByteBuffer(SIZE*SIZE*4);
    private final ByteBuffer accumulated=ByteBuffer.allocate(SIZE*SIZE*4);
    private final Image image=new Image(Image.Format.RGBA8,SIZE,SIZE,pixels,ColorSpace.Linear);
    private boolean dirty;
    final Texture2D texture=new Texture2D(image);
    VehicleDamageMarks(AssetManager assets,String profile){synchronized(ATLASES){atlas=ATLASES.computeIfAbsent(assets,key->readPixels(assets,"textures/vehicles/shared/damage-atlas.png"));}ownership=readPixels(assets,"textures/vehicles/"+profile+"/damage-ownership.png");texture.setMagFilter(Texture.MagFilter.Bilinear);texture.setMinFilter(Texture.MinFilter.BilinearNoMipMaps);texture.setWrap(Texture.WrapMode.EdgeClamp);}
    void hit(Vector3f point,Vector3f normal,Vector2f uv,float amount,boolean fire,boolean glass,long id,int owner,int stamp) {
        int channel=fire?1:glass?2:0;Mark nearby=null;for(Mark mark:marks)if(mark.channel==channel&&mark.owner==owner&&mark.uv.distanceSquared(uv)<.00016f){nearby=mark;break;}
        if(nearby!=null){marks.remove(nearby);amount=Math.min(1,nearby.strength+amount*.6f);}
        if(marks.size()==LIMIT)draw(marks.removeFirst(),accumulated);
        marks.add(new Mark(point.clone(),normal.clone(),uv.clone(),Math.clamp(amount,.12f,1),channel,id,owner,glass?6+(int)(id&1):stamp+(int)(id&1)));dirty=true;
    }
    void repair(float ratio) {
        for(int i=0;i<accumulated.capacity();i++)accumulated.put(i,(byte)Math.round((accumulated.get(i)&255)*(1-ratio)));
        if(ratio>=.999f)marks.clear();else {var retained=new ArrayDeque<Mark>();for(Mark m:marks)if(m.strength*(1-ratio)>.07f)retained.add(new Mark(m.point,m.normal,m.uv,m.strength*(1-ratio),m.channel,m.id,m.owner,m.stamp));marks.clear();marks.addAll(retained);}dirty=true;
    }
    private void rebuild() {
        pixels.put(0,accumulated,0,pixels.capacity());
        for(Mark mark:marks)draw(mark,pixels);
        image.setUpdateNeeded();
    }
    private void draw(Mark mark,ByteBuffer output) {
            Vector2f uv=mark.uv;int cx=Math.round(uv.x*(SIZE-1)),cy=Math.round(uv.y*(SIZE-1));
            int radius=3+Math.round(mark.strength*9);long seed=mark.id;
            for(int y=Math.max(0,cy-radius);y<=Math.min(SIZE-1,cy+radius);y++)for(int x=Math.max(0,cx-radius);x<=Math.min(SIZE-1,cx+radius);x++) {
                if(mark.owner>0&&(ownership[(y*SIZE+x)*2]&255)!=mark.owner)continue;
                float dx=(x-cx)/(float)radius,dy=(y-cy)/(float)radius,intensity,cavity=0;
                if(mark.channel==1){float r=(float)Math.sqrt(dx*dx+dy*dy);float noise=.65f+.35f*(float)Math.sin((x*13+y*31+(seed&65535))*.31);intensity=Math.max(0,1-r)*mark.strength*noise;}
                else {
                    int ax=Math.clamp(Math.round((dx+1)*63.5f),0,127)+mark.stamp%4*128,ay=Math.clamp(Math.round((dy+1)*63.5f),0,127)+mark.stamp/4*128;
                    int pixel=(ay*512+ax)*2;intensity=(atlas[pixel]&255)/255f*mark.strength;cavity=(atlas[pixel+1]&255)/255f*mark.strength;
                }
                int offset=(y*SIZE+x)*4+mark.channel;output.put(offset,(byte)Math.max(output.get(offset)&255,Math.round(intensity*255)));
                int ao=(y*SIZE+x)*4+3;output.put(ao,(byte)Math.max(output.get(ao)&255,Math.round(cavity*255)));
            }
    }
    private static byte[] readPixels(AssetManager assets,String path){Image image=new SurfaceMaterials.TextureUse("DamageMap",path,false).load(assets).getImage();ImageRaster raster=ImageRaster.create(image);byte[] result=new byte[image.getWidth()*image.getHeight()*2];ColorRGBA pixel=new ColorRGBA();for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++){raster.getPixel(x,y,pixel);int index=(y*image.getWidth()+x)*2;result[index]=(byte)Math.round(pixel.r*255);result[index+1]=(byte)Math.round(pixel.g*255);}return result;}
    int count(){return marks.size();}
    void flush(){if(dirty){rebuild();dirty=false;}}
    void close(){marks.clear();image.dispose();}
    byte[] snapshot(){flush();byte[] result=new byte[pixels.capacity()];pixels.duplicate().clear().get(result);return result;}
}
