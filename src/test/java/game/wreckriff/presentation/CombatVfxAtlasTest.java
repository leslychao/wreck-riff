package game.wreckriff.presentation;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CombatVfxAtlasTest {
    @Test void authoredAtlasHasBoundedResidentCostAndAllWeaponRecipes() {
        var atlas=CombatVfxAtlas.load(PresentationTestAssets.shared());
        assertTrue(atlas.residentBytes()<128L*1024*1024);
        for(String kind:new String[]{"homing","power","napalm","cannon","mine","ballistic","destroyed","special-bomb"}) {
            var recipe=atlas.explosion(kind);
            assertTrue(recipe.smokeSeconds()>recipe.flameSeconds());
            assertTrue(recipe.flashSeconds()>0&&recipe.flashSeconds()<=.1f);
        }
        assertThrows(IllegalArgumentException.class,()->atlas.explosion("missing"));
    }
    @Test void frameProgressionCannotCrossTheAtlasOrRestartAfterExpiry() {
        assertEquals(0,CombatVfxAtlas.frame(0,1));
        assertEquals(31.5f,CombatVfxAtlas.frame(.5f,1));
        assertEquals(63,CombatVfxAtlas.frame(2,1));
        assertEquals(0,CombatVfxAtlas.frame(-1,1));
    }
    @Test void softCoverageUsesEveryMsaaSampleInsteadOfAveragingDepth() {
        assertEquals(.5f,CombatVfxFilter.coverage(4,new float[]{3,3,9,9},1),.0001f);
        assertEquals(0,CombatVfxFilter.coverage(4,new float[]{3,3,3,3},1));
        assertEquals(.5f,CombatVfxFilter.coverage(4,new float[]{4.5f},1),.0001f);
    }
    @Test void threeBlastVariantsKeepInterpolationInsideTheirOwnTwentyOneFrames() {
        for(int variant=0;variant<3;variant++) {
            float variation=(variant+.5f)/3;
            assertEquals(variant*21,CombatVfxAtlas.blastFrame(0,1,variation));
            assertEquals(variant*21+10,CombatVfxAtlas.blastFrame(.5f,1,variation));
            assertEquals(variant*21+20,CombatVfxAtlas.blastFrame(2,1,variation));
        }
    }
}
