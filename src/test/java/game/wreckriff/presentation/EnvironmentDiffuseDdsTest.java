package game.wreckriff.presentation;

import com.jme3.asset.DesktopAssetManager;
import com.jme3.asset.TextureKey;
import com.jme3.asset.plugins.FileLocator;
import com.jme3.texture.Image;
import com.jme3.texture.image.ColorSpace;
import game.wreckriff.diagnostics.EnvironmentDiffuseAssetsVerifier;
import java.io.IOException;
import java.nio.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EnvironmentDiffuseDdsTest {
    @Test void rejectsWrongFormatTruncationAndLfsPointers()throws Exception {
        byte[] bytes=Files.readAllBytes(Path.of("src/main/resources/textures/materials/asphalt_02/diffuse.dds"));
        assertEquals(5_592_432,EnvironmentDiffuseDds.read(bytes).payloadBytes());
        assertThrows(IOException.class,()->EnvironmentDiffuseDds.read(java.util.Arrays.copyOf(bytes,bytes.length-1)));
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(128,98);
        assertThrows(IOException.class,()->EnvironmentDiffuseDds.read(bytes));
        assertThrows(IOException.class,()->EnvironmentDiffuseDds.read("version https://git-lfs.github.com/spec/v1".getBytes()));
    }
    @Test void allThirteenJmeLoadsHaveExplicitSrgbAndOfflineMipOrientation()throws Exception {
        var assets=new DesktopAssetManager(true);assets.registerLocator(Path.of("src/main/resources").toAbsolutePath().toString(),FileLocator.class);
        try{for(String name:EnvironmentDiffuseDds.MATERIALS){
            var texture=EnvironmentDiffuseDds.load(assets,"textures/materials/"+name+"/diffuse.dds");var key=(TextureKey)texture.getKey();
            assertFalse(key.isFlipY());assertFalse(key.isGenerateMips());assertEquals(Image.Format.BC7_UNORM_SRGB,texture.getImage().getFormat());assertEquals(ColorSpace.Linear,texture.getImage().getColorSpace());
            assertEquals(2048,texture.getImage().getWidth());assertEquals(2048,texture.getImage().getHeight());assertEquals(12,texture.getImage().getMipMapSizes().length);
        }}finally{assets.clearCache();}
        assertEquals(14,EnvironmentDiffuseAssetsVerifier.verify().size());
    }
}
