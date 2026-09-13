package game.wreckriff.simulation;

import game.wreckriff.ai.AiRules;
import game.wreckriff.ai.BotController;
import game.wreckriff.arena.ArenaRegistry;
import game.wreckriff.combat.CombatRules;
import game.wreckriff.config.Configs;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class BossProtocolTest {
    @ParameterizedTest @ValueSource(strings={"construction_17","neon_zero","euphoria_park"})
    void presentationReadsOnlyTheRegisteredBossWithoutGuessingItsParticipantId(String arenaId) {
        var arena=ArenaRegistry.load().definition(arenaId);
        var session=new MatchSession(91,arena,MatchSession.Mode.BOSS_DUEL,Configs.load("combat",CombatRules.class));
        int bossId=session.registerBoss(arena.bosses().getFirst()).id;
        session.phase=MatchSession.Phase.BOSS_COMBAT;
        var bots=new BotController(session,arena,AiRules.load(),List::of);
        assertTrue(bots.bossAction(99).isEmpty());assertTrue(bots.bossAction(0).isEmpty());
        assertTrue(bots.bossAction(bossId).isPresent());
    }
}
