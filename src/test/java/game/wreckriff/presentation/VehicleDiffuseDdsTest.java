package game.wreckriff.presentation;

import com.jme3.asset.DesktopAssetManager;
import com.jme3.asset.plugins.FileLocator;
import com.jme3.texture.Image;
import com.jme3.texture.image.ColorSpace;
import java.io.IOException;
import java.nio.*;
import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VehicleDiffuseDdsTest {
    @Test void rejectsLinearWrongSizedIncompleteAndPointerContainers() throws Exception {
        byte[] valid=fixture();assertEquals(3,VehicleDiffuseDds.read(valid,4).levels());
        assertEquals(48,VehicleDiffuseDds.read(valid,4).payloadBytes());
        byte[] linear=valid.clone();ByteBuffer.wrap(linear).order(ByteOrder.LITTLE_ENDIAN).putInt(128,98);
        assertThrows(IOException.class,()->VehicleDiffuseDds.read(linear,4));
        assertThrows(IOException.class,()->VehicleDiffuseDds.read(valid,8));
        assertThrows(IOException.class,()->VehicleDiffuseDds.read(java.util.Arrays.copyOf(valid,valid.length-1),4));
        assertThrows(IOException.class,()->VehicleDiffuseDds.read("version https://git-lfs.github.com/spec/v1".getBytes(),4));
    }
    @Test void everyDiffuseUsesExplicitSrgbBc7OfflineMipsAndNoRetiredPng() throws Exception {
        var assets=new DesktopAssetManager(true);assets.registerLocator(Path.of("src/main/resources").toAbsolutePath().toString(),FileLocator.class);
        try {for(String profile:List.of("rivet","grinder","spark","boss_foreman","boss_prefect","boss_emcee","shared")) {
            String base="textures/vehicles/"+profile+"/"+(profile.equals("shared")?"metal-":"")+"diffuse";
            assertFalse(Files.exists(Path.of("src/main/resources",base+".png")),base);
            var texture=VehicleDiffuseDds.load(assets,base+".dds");
            assertFalse(((com.jme3.asset.TextureKey)texture.getKey()).isFlipY());
            assertFalse(((com.jme3.asset.TextureKey)texture.getKey()).isGenerateMips());
            assertEquals(Image.Format.BC7_UNORM_SRGB,texture.getImage().getFormat());
            assertEquals(ColorSpace.Linear,texture.getImage().getColorSpace(),"DX10 format already selects GL sRGB decoding");
            assertEquals(profile.equals("shared")?11:12,texture.getImage().getMipMapSizes().length);
            var material=VehicleMaterials.create(assets,profile.equals("shared")?"rivet":profile,profile.equals("shared")?"steel":"paint");
            assertSame(texture.getImage(),material.getTextureParam("DiffuseMap").getTextureValue().getImage());
            assertEquals(ColorSpace.Linear,((com.jme3.material.MatParamTexture)material.getMaterialDef().getMaterialParam("DiffuseMap")).getColorSpace());
        }} finally {assets.clearCache();}
    }
    private static byte[] fixture(){byte[] bytes=new byte[148+48];var b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);b.putInt(0,0x20534444).putInt(4,124).putInt(12,4).putInt(16,4).putInt(20,16).putInt(28,3).putInt(76,32).putInt(80,4).putInt(84,0x30315844).putInt(108,0x401008).putInt(128,99).putInt(132,3).putInt(140,1);return bytes;}
}
