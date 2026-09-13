package game.wreckriff.diagnostics;

import game.wreckriff.combat.AbilityId;
import game.wreckriff.combat.WeaponType;
import game.wreckriff.config.ProgressStore;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UiReviewFixturesTest {
    @Test void unlockFixtureKeepsMeasuredResultsEmptyAndFreshStateDistinct() {
        var fresh=UiReviewFixtures.fresh();var unlocked=SoakProfile.unlockedSnapshot();
        assertEquals(1,fresh.campaign().unlockedArenaIds().size());assertTrue(fresh.campaign().completedArenaIds().isEmpty());
        assertEquals(ProgressStore.CAMPAIGN_ARENAS.size(),unlocked.campaign().unlockedArenaIds().size());
        assertEquals(0,unlocked.stats().wins());assertEquals(0,unlocked.stats().completedMatches());assertTrue(unlocked.records().isEmpty());
    }
    @Test void allProfilesExposeEveryWeaponStateAndAbilityPhaseWithoutAWorld() {
        for(String profile:new String[]{"rivet","grinder","spark"})for(int variant=0;variant<6;variant++) {
            var view=UiReviewFixtures.hud(profile,variant);assertEquals(WeaponType.values()[variant],view.selectedWeapon());
            assertEquals(6,view.weapons().size());assertEquals(0,view.weapons().get(view.selectedWeapon()).ammo());
            var ability=view.abilities().get(AbilityId.SPECIAL);
            assertEquals(variant%3==1,ability.activeSeconds()>0);assertEquals(variant%3==2,ability.cooldownSeconds()>0);
            assertTrue(view.radarTargets().stream().anyMatch(t->!t.alive()));assertTrue(view.radarTargets().stream().anyMatch(t->t.boss()));
            if(variant==5)assertTrue(view.weapons().values().stream().allMatch(w->w.ammo()==0));
        }
    }
}
