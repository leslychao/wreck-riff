package game.wreckriff.simulation;

import game.wreckriff.arena.ArenaRegistry;
import game.wreckriff.combat.*;
import game.wreckriff.config.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MatchCheckpointTest {
    private final CombatRules rules=Configs.load("combat",CombatRules.class);
    @Test void snapshotIsImmutableAndRestoresAllExistingResourcesWithoutRestoringAttemptMetrics() {
        VehicleState source=new VehicleState(0,"Rivet",true,rules);
        source.hp=520;source.turbo=37;source.selectedWeapon=WeaponType.CANNON;
        for(var type:WeaponType.values()) {source.weapon(type).ammo=0;source.weapon(type).cooldownTicks=17+type.ordinal();}
        source.machineGunCooldown=9;source.turboQuietTicks=101;source.recoveryCooldown=21;source.protectionTicks=7;
        source.frozenTicks=18;source.shieldTicks=22;source.controlImmunityTicks=24;source.impactStabilizerTicks=4;
        source.abilityCooldown(AbilityId.FREEZE,91);source.abilityCooldown(AbilityId.SHIELD,123);
        source.abilityCooldown(AbilityId.SPECIAL,881);
        var saved=MatchCheckpoint.player(source);
        source.hp=1;source.weapon(WeaponType.CANNON).ammo=2;
        VehicleState restored=new VehicleState(0,"Rivet",true,rules);
        MatchCheckpoint.restorePlayer(restored,saved);
        assertEquals(saved,MatchCheckpoint.player(restored));
        assertEquals(520,restored.hp);assertEquals(0,restored.weapon(WeaponType.CANNON).ammo);
        assertEquals(0,restored.damageDealt);assertEquals(0,restored.eliminations);
        assertThrows(UnsupportedOperationException.class,()->saved.weapons().clear());
    }
    @Test void overCapacityResourceCannotPartiallyMutateTheLivePlayer() {
        var source=new VehicleState(0,"Rivet",true,rules);var data=MatchCheckpoint.player(source);
        Map<String,ProgressStore.WeaponResource> bad=new HashMap<>(data.weapons());
        bad.put("cannon",new ProgressStore.WeaponResource(999,0));
        var corrupt=new ProgressStore.PlayerResources(12,1,"cannon",bad,data.abilityCooldownTicks(),data.timers());
        assertThrows(IllegalArgumentException.class,()->MatchCheckpoint.restorePlayer(source,corrupt));
        assertEquals(data,MatchCheckpoint.player(source));
    }
    @Test void continuationDiscardsSavedAmmunitionButPreservesOtherResourcesAndDoesNotMutateTheSnapshot() {
        var player=new VehicleState(0,"Rivet",true,rules);player.hp=340;player.turbo=41;
        for(var type:WeaponType.values()) {player.weapon(type).ammo=player.weapon(type).maximumAmmo;player.weapon(type).cooldownTicks=37;}
        player.machineGunCooldown=9;player.abilityCooldown(AbilityId.FREEZE,81);
        var saved=MatchCheckpoint.player(player);var expected=MatchCheckpoint.withoutAmmunition(saved);
        var restored=new VehicleState(0,"Rivet",true,rules);MatchCheckpoint.restorePlayer(restored,saved);
        assertEquals(expected,MatchCheckpoint.player(restored));
        assertTrue(saved.weapons().values().stream().allMatch(slot->slot.ammunition()>0));
        assertTrue(expected.weapons().values().stream().allMatch(slot->slot.ammunition()==0&&slot.cooldownTicks()==37));
        assertEquals(340,restored.hp);assertEquals(41,restored.turbo);assertEquals(9,restored.machineGunCooldown);
        assertEquals(81,restored.abilityCooldown(AbilityId.FREEZE));
    }
    @Test void allFreshParticipantsStartEmptyAndLiveBossEntryKeepsThePlayersCollectedResources() {
        var legacy=new MatchSession(12,360);assertEmpty(legacy);
        var registry=ArenaRegistry.load();
        for(var id:registry.campaignIds()) {
            var arena=registry.definition(id);
            for(var mode:List.of(MatchSession.Mode.ARENA,MatchSession.Mode.CAMPAIGN,MatchSession.Mode.BOSS_DUEL)) {
                var match=new MatchSession(4,arena,mode,rules);assertEmpty(match);
                var player=match.vehicle(0);player.weapon(WeaponType.POWER).ammo=2;
                match.registerBoss(arena.bosses().getFirst());
                assertTrue(match.vehicle(match.bossParticipantId).weapons().stream().allMatch(slot->slot.ammo==0));
                assertEquals(2,player.weapon(WeaponType.POWER).ammo);
            }
        }
    }
    private static void assertEmpty(MatchSession match) {
        assertTrue(match.vehicles.stream().allMatch(vehicle->vehicle.weapons().stream().allMatch(slot->slot.ammo==0)));
    }
    @Test void bossCheckpointConstructsOnlyPlayerAndRetainsCampaignIdentity() {
        var arena=ArenaRegistry.load().definition("construction_17");UUID attempt=UUID.randomUUID();
        var restored=new MatchSession(4,arena,MatchSession.Mode.CAMPAIGN,rules,attempt,true,3);
        assertEquals(1,restored.vehicles.size());assertEquals(MatchSession.Phase.BOSS_ENTRY,restored.phase);
        assertEquals(MatchSession.Mode.CAMPAIGN,restored.mode);assertEquals(attempt,restored.sessionId);
        assertEquals(3,restored.vehicle(0).liveryId);assertTrue(restored.preBossRepairApplied);
        assertEquals(0,restored.activeTicks);assertEquals(-1,restored.bossParticipantId);
    }
}
