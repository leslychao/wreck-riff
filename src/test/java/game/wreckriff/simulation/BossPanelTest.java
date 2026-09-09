package game.wreckriff.simulation;

import com.jme3.math.Vector3f;
import game.wreckriff.arena.*;
import game.wreckriff.combat.CombatRules;
import game.wreckriff.config.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BossPanelTest {
    @Test void windowIsBoundedByRealBossActionAndRearPanelDoesNotCoverTheWholeHull() {
        var arena=ArenaRegistry.load().definition("construction_17");
        var session=new MatchSession(42,arena,MatchSession.Mode.BOSS_DUEL,Configs.load("combat",CombatRules.class));
        var boss=session.registerBoss(arena.bosses().getFirst());var profile=VehicleProfile.boss(boss.profileId,VehicleRules.load());
        var systems=new ArenaSystems(session,arena);systems.configureBoss(profile);
        systems.completeBossAction(ArenaSystems.BossAction.RAM_MISSED);assertFalse(systems.bossVulnerable());
        session.phase=MatchSession.Phase.BOSS_COMBAT;
        systems.completeBossAction(ArenaSystems.BossAction.RAM_MISSED);assertTrue(systems.bossVulnerable());
        var rear=new Vector3f(0,profile.hullBoxes().getFirst().y(),-profile.length()/2);
        assertEquals(1.25f,systems.directHitMultiplier(boss.id,rear));
        assertEquals(1,systems.directHitMultiplier(boss.id,new Vector3f(0,rear.y,profile.length()/2)));
        assertEquals(1,systems.directHitMultiplier(0,rear));assertEquals(1,systems.directHitMultiplier(boss.id,null));
        session.tick=239;assertTrue(systems.bossVulnerable());session.tick=240;assertFalse(systems.bossVulnerable());
        assertEquals(1,systems.directHitMultiplier(boss.id,rear));
    }
}
