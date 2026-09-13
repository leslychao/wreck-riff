package game.wreckriff.diagnostics;

import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EnvironmentLightingAssetsTest {
    @Test void compressedColourDefinitionKeepsEveryPinnedPhongTechniqueAndOnlyChangesItsDataContract()throws Exception {
        var assets=new ArrayList<VerifyAssets.Asset>();
        VerifyAssets.verifyEnvironmentLighting(assets);
        assertEquals(3,assets.size());
        assertTrue(assets.stream().anyMatch(asset->asset.path().equals("materials/EnvironmentLighting.j3md")));
    }
}
