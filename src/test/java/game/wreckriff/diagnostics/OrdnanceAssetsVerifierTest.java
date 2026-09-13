package game.wreckriff.diagnostics;

import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.util.*;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import static org.junit.jupiter.api.Assertions.*;

class OrdnanceAssetsVerifierTest {
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
