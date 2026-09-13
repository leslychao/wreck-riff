package game.wreckriff.diagnostics;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CombatVfxAssetsVerifierTest {
    @Test void packagedCompressedAssetsAndLosslessSourceProvenanceAgreeWithoutAnEncoder() throws Exception {
        var assets=new ArrayList<VerifyAssets.Asset>();CombatVfxAssetsVerifier.verify(assets);
        for(String family:List.of("smoke","flame","blast","dust")) {
            assertTrue(assets.stream().anyMatch(a->a.path().equals("textures/vfx/"+family+".dds")));
            assertTrue(assets.stream().anyMatch(a->a.path().equals("src/tools/assets/combat-vfx/"+family+".png")));
            assertFalse(assets.stream().anyMatch(a->a.path().equals("textures/vfx/"+family+".png")));
        }
    }
}
