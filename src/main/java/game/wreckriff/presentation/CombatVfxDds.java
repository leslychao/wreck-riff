package game.wreckriff.presentation;

import java.io.IOException;
import java.nio.*;
import java.nio.charset.StandardCharsets;

/** Strict offline BC3/DXT5 container and base-level decoder for asset verification.
 * Rendering uses jME's DDSLoader and native compressed textures, never this decoder.
 */
public final class CombatVfxDds {
    private final byte[] bytes;
    private final int size,levels;
    private CombatVfxDds(byte[] bytes,int size,int levels){this.bytes=bytes;this.size=size;this.levels=levels;}
    public static CombatVfxDds read(byte[] bytes,int expectedSize)throws IOException {
        if(new String(bytes,0,Math.min(bytes.length,64),StandardCharsets.US_ASCII).startsWith("version https://git-lfs.github.com/spec/"))
            throw new IOException("Unresolved Git LFS pointer in VFX DDS; checkout prepared assets before Gradle");
        if(expectedSize<4||expectedSize>2048||Integer.bitCount(expectedSize)!=1)throw new IOException("Unsupported VFX atlas dimensions");
        int levels=Integer.numberOfTrailingZeros(expectedSize)+1;
        if(bytes.length!=128+mipBytes(expectedSize))throw new IOException("Missing, truncated or oversized VFX DDS mip payload");
        var data=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if(data.getInt(0)!=0x20534444||data.getInt(4)!=124||data.getInt(12)!=expectedSize||data.getInt(16)!=expectedSize
                ||data.getInt(28)!=levels||data.getInt(76)!=32||data.getInt(80)!=4||data.getInt(84)!=0x35545844
                ||data.getInt(112)!=0||(data.getInt(108)&0x401008)!=0x401008||data.getInt(24)<0||data.getInt(24)>1
                ||data.getInt(20)!=(long)expectedSize*expectedSize)
            throw new IOException("Expected legacy 2D DXT5 VFX DDS with complete offline mip chain (no DX10/array/cubemap)");
        return new CombatVfxDds(bytes,expectedSize,levels);
    }
    public int size(){return size;}
    public int levels(){return levels;}
    public long payloadBytes(){return bytes.length-128L;}
    public static long mipBytes(int size) {
        long result=0;
        for(;;){long blocks=Math.max(1,(size+3)/4);result+=blocks*blocks*16;if(size==1)return result;size=Math.max(1,size/2);}
    }
    public int argb(int x,int y) {
        if(x<0||y<0||x>=size||y>=size)throw new IndexOutOfBoundsException();
        int offset=128+((y/4)*(size/4)+x/4)*16,pixel=(y%4)*4+x%4;
        int a0=bytes[offset]&255,a1=bytes[offset+1]&255;
        long alphaBits=0;for(int i=0;i<6;i++)alphaBits|=(bytes[offset+2+i]&255L)<<(i*8);
        int index=(int)(alphaBits>>>(pixel*3))&7;
        int alpha=index==0?a0:index==1?a1:a0>a1?((8-index)*a0+(index-1)*a1)/7:index==6?0:index==7?255:((6-index)*a0+(index-1)*a1)/5;
        int c0=(bytes[offset+8]&255)|((bytes[offset+9]&255)<<8),c1=(bytes[offset+10]&255)|((bytes[offset+11]&255)<<8);
        int rgb0=rgb565(c0),rgb1=rgb565(c1),selector=((bytes[offset+12+pixel/4]&255)>>>((pixel%4)*2))&3;
        int color=selector==0?rgb0:selector==1?rgb1:interpolate(rgb0,rgb1,selector==2?2:1);
        return alpha<<24|color;
    }
    private static int rgb565(int value) {
        int r=(value>>>11)&31,g=(value>>>5)&63,b=value&31;
        return ((r<<3|r>>>2)<<16)|((g<<2|g>>>4)<<8)|(b<<3|b>>>2);
    }
    private static int interpolate(int a,int b,int firstWeight) {
        int result=0;for(int shift=0;shift<=16;shift+=8)result|=((firstWeight*(a>>>shift&255)+(3-firstWeight)*(b>>>shift&255))/3)<<shift;
        return result;
    }
}
