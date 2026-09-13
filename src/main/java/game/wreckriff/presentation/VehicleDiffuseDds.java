package game.wreckriff.presentation;

import com.jme3.asset.AssetManager;
import com.jme3.asset.TextureKey;
import com.jme3.texture.Texture;
import com.jme3.texture.image.ColorSpace;
import java.io.IOException;
import java.nio.*;
import java.nio.charset.StandardCharsets;

/** Pinned DX10 BC7 sRGB opaque vehicle maps, including the complete offline mip chain. */
public record VehicleDiffuseDds(int size,int levels,long payloadBytes) {
    public static VehicleDiffuseDds read(byte[] bytes,int expectedSize)throws IOException {
        if(new String(bytes,0,Math.min(bytes.length,64),StandardCharsets.US_ASCII).startsWith("version https://git-lfs.github.com/spec/"))throw new IOException("Unresolved vehicle DDS Git LFS pointer");
        if(expectedSize<4||expectedSize>2048||Integer.bitCount(expectedSize)!=1)throw new IOException("Unsupported vehicle diffuse size");
        int levels=Integer.numberOfTrailingZeros(expectedSize)+1;long payload=mipBytes(expectedSize);
        if(bytes.length!=148+payload)throw new IOException("Incomplete vehicle DDS mip chain");
        var data=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if(data.getInt(0)!=0x20534444||data.getInt(4)!=124||data.getInt(12)!=expectedSize||data.getInt(16)!=expectedSize
                ||data.getInt(28)!=levels||data.getInt(76)!=32||data.getInt(80)!=4||data.getInt(84)!=0x30315844
                ||data.getInt(112)!=0||(data.getInt(108)&0x401008)!=0x401008||data.getInt(24)<0||data.getInt(24)>1
                ||data.getInt(20)!=(long)expectedSize*expectedSize||data.getInt(128)!=99||data.getInt(132)!=3
                ||data.getInt(136)!=0||data.getInt(140)!=1||data.getInt(144)!=0)
            throw new IOException("Expected one DX10 BC7_UNORM_SRGB 2D vehicle diffuse with full mips, no arrays/cubemaps");
        return new VehicleDiffuseDds(expectedSize,levels,payload);
    }
    public static long mipBytes(int size){long bytes=0;for(;;){long blocks=Math.max(1,(size+3)/4);bytes+=blocks*blocks*16;if(size==1)return bytes;size=Math.max(1,size/2);}}
    public static Texture load(AssetManager assets,String path) {
        // DDSLoader cannot flip BC7 blocks; the preparation encoder flips the original PNG once.
        var key=new TextureKey(path,false);key.setGenerateMips(false);Texture texture=assets.loadTexture(key);
        // jME 3.8.1's explicit BC7_UNORM_SRGB enum already maps to GL_COMPRESSED_SRGB_ALPHA_BPTC_UNORM.
        // Its separate sRGB lookup has no entry. Linear here avoids a second conversion/lookup.
        texture.getImage().setColorSpace(ColorSpace.Linear);texture.setWrap(Texture.WrapMode.Repeat);
        texture.setMinFilter(Texture.MinFilter.Trilinear);texture.setMagFilter(Texture.MagFilter.Bilinear);texture.setAnisotropicFilter(8);return texture;
    }
}
