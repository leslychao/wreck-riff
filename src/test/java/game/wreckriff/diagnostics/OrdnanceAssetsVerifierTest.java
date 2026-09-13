package game.wreckriff.diagnostics;

import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.util.*;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import static org.junit.jupiter.api.Assertions.*;

class OrdnanceAssetsVerifierTest {
    @Test void generatedSpecularAtlasesStoreLinearSamplesInOneEightBitChannel()throws Exception {
        var loader=new com.jme3.texture.plugins.AWTLoader();
        for(String atlas:List.of("projectiles","mines")) {
            String path="textures/ordnance/"+atlas+"-specular.png";
            byte[] bytes;
            try(var stream=getClass().getClassLoader().getResourceAsStream(path)){bytes=Objects.requireNonNull(stream).readAllBytes();}
            assertEquals(8,Byte.toUnsignedInt(bytes[24]),path);
            assertEquals(0,Byte.toUnsignedInt(bytes[25]),"Specular PNG must be scalar L8: "+path);
            var image=ImageIO.read(new java.io.ByteArrayInputStream(bytes));
            var decoded=loader.load(new java.io.ByteArrayInputStream(bytes),true);
            assertEquals(com.jme3.texture.Image.Format.Luminance8,decoded.getFormat());
            var data=decoded.getData(0).duplicate();data.clear();
            assertEquals(2048*2048,data.remaining());
            for(int y=0;y<2048;y++)for(int x=0;x<2048;x++)
                if(Byte.toUnsignedInt(data.get())!=image.getRaster().getSample(x,2047-y,0))
                    fail("AWT loader changed a scalar sample: "+path+" at "+x+","+y);
        }
    }
    @Test void specularVerifierRejectsAnRgbAtlasEvenIfItsChannelsAreEqual()throws Exception {
        var image=new BufferedImage(2048,2048,BufferedImage.TYPE_INT_RGB);
        for(int y=0;y<2048;y++)for(int x=0;x<2048;x++) {
            int value=(x+y)&255;image.setRGB(x,y,(value<<16)|(value<<8)|value);
        }
        var bytes=new ByteArrayOutputStream();ImageIO.write(image,"png",bytes);
        assertThrows(java.io.IOException.class,()->OrdnanceAssetsVerifier.verifyTexture("textures/ordnance/mines-specular.png",bytes.toByteArray()));
    }
    @Test void exportedFamiliesHaveExactSourceProvenanceModelsAndSixDetailed2kTextureMaps()throws Exception {
        List<VerifyAssets.Asset> assets=new ArrayList<>();OrdnanceAssetsVerifier.verify(assets);
        assertEquals(8,assets.stream().filter(asset->asset.category().equals("ordnance-model")).count());
        assertEquals(6,assets.stream().filter(asset->asset.category().equals("ordnance-texture")).count());
        assertEquals(2,assets.stream().filter(asset->asset.category().equals("historical-source")).count());
    }
    @Test void flatAndWrongSizePlaceholderTexturesAreRejected()throws Exception {
        for(int size:List.of(512,2048)) {
            var image=new BufferedImage(size,size,BufferedImage.TYPE_INT_RGB);var bytes=new ByteArrayOutputStream();ImageIO.write(image,"png",bytes);
            assertThrows(java.io.IOException.class,()->OrdnanceAssetsVerifier.verifyTexture("flat.png",bytes.toByteArray()));
        }
    }
}
