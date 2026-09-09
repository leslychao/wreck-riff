package game.wreckriff.simulation;

import game.wreckriff.arena.ArenaDefinition;
import game.wreckriff.combat.*;
import game.wreckriff.config.Configs;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MatchPhaseTest {
    private final CombatRules rules=Configs.load("combat",CombatRules.class);
    private final ArenaDefinition arena=fixture();
    private ArenaDefinition fixture() {
        var yard=ArenaDefinition.load();
        var boss=new ArenaDefinition.Boss("boss_foreman","Бригадир","boss_foreman",4000,WeaponType.POWER,WeaponType.CANNON,
                yard.spawns().subList(0,2),List.of(),List.of("Смена началась."));
        var meta=new ArenaDefinition.Metadata("Тест",ArenaDefinition.Theme.CONSTRUCTION,4,0,0,"","normal","boss");
        return new ArenaDefinition(2,"construction_17",meta,yard.bounds(),yard.boxes(),yard.ramps(),yard.spawns(),yard.pickups(),
                yard.hazards(),yard.nodes(),yard.edges(),yard.surfaces(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of(boss));
    }
    private MatchSession session() { return new MatchSession(42,arena,MatchSession.Mode.CAMPAIGN,rules); }
    @Test void introDoesNotConsumeCombatTimeAndNewArenasHaveNoTimeLimit() {
        var session=session();session.vehicle(0).weapon(WeaponType.HOMING).cooldownTicks=120;
        for(int tick=0;tick<360;tick++)MatchRuntime.finishTick(session);
        assertEquals(MatchSession.Phase.ARENA_COMBAT,session.phase);assertEquals(0,session.activeTicks);
        assertEquals(120,session.vehicle(0).weapon(WeaponType.HOMING).cooldownTicks);
        session.tick=10L*60*60*120;MatchRuntime.finishTick(session);
        assertEquals(MatchSession.Outcome.NONE,session.outcome);
    }
    @Test void lastRivalRepairsOnceAndRequestsCheckpointWithoutEndingMatch() {
        var session=session();session.phase=MatchSession.Phase.ARENA_COMBAT;session.vehicle(0).hp=100;
        for(var v:session.vehicles)if(!v.player)v.hp=0;
        int ammo=session.vehicle(0).weapon(WeaponType.POWER).ammo;
        MatchRuntime.finishTick(session);
        assertEquals(520,session.vehicle(0).hp);assertTrue(session.checkpointRequested);
        assertEquals(MatchSession.Phase.BOSS_ENTRY,session.phase);assertEquals(MatchSession.Outcome.NONE,session.outcome);
        session.vehicle(0).hp=490;MatchRuntime.finishTick(session);
        assertEquals(490,session.vehicle(0).hp);assertEquals(ammo,session.vehicle(0).weapon(WeaponType.POWER).ammo);
    }
    @Test void simultaneousPlayerAndLastRivalDeathIsDefeatWithoutRepairOrBossEntry() {
        var session=session();session.phase=MatchSession.Phase.ARENA_COMBAT;session.vehicles.forEach(v->v.hp=0);
        MatchRuntime.finishTick(session);
        assertEquals(MatchSession.Outcome.DEFEAT,session.outcome);
        assertFalse(session.preBossRepairApplied);assertFalse(session.checkpointRequested);assertEquals(-1,session.bossParticipantId);
    }
    @Test void simultaneousBossPlayerDeathWinsAndRepeatedTickCannotResolveAgain() {
        var session=session();session.phase=MatchSession.Phase.BOSS_COMBAT;
        session.registerBoss(arena.bosses().getFirst());session.vehicle(0).hp=0;session.vehicle(session.bossParticipantId).hp=0;
        MatchRuntime.finishTick(session);
        assertEquals(MatchSession.Outcome.VICTORY,session.outcome);assertTrue(session.outcomeReason.contains("Машина уничтожена"));
        long tick=session.tick;MatchRuntime.finishTick(session);assertEquals(tick,session.tick);
    }
    @Test void bossIsAppendedWithItsOwnProfileAndLargeHitSkipsPhaseWithoutRefilling() {
        var session=session();var view=session.vehicles;var boss=session.registerBoss(arena.bosses().getFirst());
        assertEquals(6,view.size());assertSame(boss,view.getLast());assertEquals("boss_foreman",boss.profileId);
        assertTrue(boss.boss);assertEquals(4000,boss.maximumHp);assertThrows(UnsupportedOperationException.class,()->view.removeLast());
        var combat=new CombatSystem(session,rules);
        assertThrows(IllegalArgumentException.class,()->combat.registerParticipant(boss));
        session.phase=MatchSession.Phase.BOSS_COMBAT;boss.hp=900;boss.weapon(WeaponType.CANNON).ammo=0;
        MatchRuntime.finishTick(session);assertEquals(3,session.bossMode);assertEquals(900,boss.hp);assertEquals(0,boss.weapon(WeaponType.CANNON).ammo);
        boss.hp=3000;MatchRuntime.finishTick(session);assertEquals(3,session.bossMode);
    }
    @Test void entryLastsThreeSecondsAndDoesNotCountAsActiveCombat() {
        var session=new MatchSession(42,arena,MatchSession.Mode.BOSS_DUEL,rules);
        session.registerBoss(arena.bosses().getFirst());
        for(int i=0;i<359;i++)MatchRuntime.finishTick(session);
        assertEquals(MatchSession.Phase.BOSS_ENTRY,session.phase);assertEquals(0,session.activeTicks);
        MatchRuntime.finishTick(session);assertEquals(MatchSession.Phase.BOSS_COMBAT,session.phase);
        MatchRuntime.finishTick(session);assertEquals(1,session.activeTicks);
    }
}
