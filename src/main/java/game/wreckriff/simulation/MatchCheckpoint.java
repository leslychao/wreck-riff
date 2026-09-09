package game.wreckriff.simulation;

import game.wreckriff.arena.*;
import game.wreckriff.combat.*;
import game.wreckriff.config.ProgressStore;
import java.util.*;
import java.util.stream.Collectors;

/** Immutable persistence mapping at the existing simulation boundary; never performs file I/O. */
public final class MatchCheckpoint {
    private MatchCheckpoint() {}
    public static ProgressStore.PlayerResources player(VehicleState state) {
        Map<String,ProgressStore.WeaponResource> weapons=new LinkedHashMap<>();
        for(var type:WeaponType.values()) {
            var slot=state.weapon(type);weapons.put(type.id(),new ProgressStore.WeaponResource(slot.ammo,slot.cooldownTicks));
        }
        Map<String,Long> abilities=new LinkedHashMap<>();
        for(var ability:List.of(AbilityId.FREEZE,AbilityId.SHIELD,AbilityId.SPECIAL))abilities.put(ability.name().toLowerCase(Locale.ROOT),(long)state.abilityCooldown(ability));
        return new ProgressStore.PlayerResources(state.hp,state.turbo,state.selectedWeapon.id(),weapons,abilities,
                new ProgressStore.ResourceTimers(state.machineGunCooldown,state.turboQuietTicks,state.recoveryCooldown,
                        state.protectionTicks,state.frozenTicks,state.shieldTicks,state.controlImmunityTicks,state.impactStabilizerTicks));
    }
    public static void restorePlayer(VehicleState state,ProgressStore.PlayerResources data) {
        // Validate everything before mutating live resources.
        if(data.hp()>state.maximumHp)throw new IllegalArgumentException("Checkpoint HP exceeds this profile");
        for(var type:WeaponType.values())if(data.weapons().get(type.id()).ammunition()>state.weapon(type).maximumAmmo)
            throw new IllegalArgumentException("Checkpoint ammunition exceeds capacity: "+type);
        state.hp=data.hp();state.turbo=data.turbo();state.selectedWeapon=WeaponType.valueOf(data.selectedWeapon().toUpperCase(Locale.ROOT));
        for(var type:WeaponType.values()) {
            var resource=data.weapons().get(type.id());var slot=state.weapon(type);
            slot.ammo=resource.ammunition();slot.cooldownTicks=Math.toIntExact(resource.cooldownTicks());
        }
        for(var ability:List.of(AbilityId.FREEZE,AbilityId.SHIELD,AbilityId.SPECIAL))state.abilityCooldown(ability,Math.toIntExact(data.abilityCooldownTicks().get(ability.name().toLowerCase(Locale.ROOT))));
        var timer=data.timers();
        state.machineGunCooldown=Math.toIntExact(timer.machineGunCooldown());state.turboQuietTicks=Math.toIntExact(timer.turboQuietTicks());
        state.recoveryCooldown=Math.toIntExact(timer.recoveryCooldown());state.protectionTicks=Math.toIntExact(timer.protectionTicks());
        state.frozenTicks=Math.toIntExact(timer.frozenTicks());state.shieldTicks=Math.toIntExact(timer.shieldTicks());
        state.controlImmunityTicks=Math.toIntExact(timer.controlImmunityTicks());state.impactStabilizerTicks=Math.toIntExact(timer.impactStabilizerTicks());
        state.heavyImpactPending=false;state.clearSpecial();state.grabbedBy=-1;
    }
    public static void validateReferences(ArenaRegistry registry,ProgressStore.Checkpoint checkpoint) {
        var arena=registry.definition(checkpoint.arenaId());
        if(arena.surfaces().stream().noneMatch(s->s.id().equals(checkpoint.safePose().surfaceId()))
                ||arena.nodes().stream().noneMatch(n->("node-"+n.id()).equals(checkpoint.safePose().anchorId())
                    &&n.surfaceId().equals(checkpoint.safePose().surfaceId())))throw new IllegalArgumentException("Unknown checkpoint road anchor");
        sameKeys(checkpoint.arena().pickups(),arena.pickups().stream().map(ArenaDefinition.Pickup::id).collect(Collectors.toSet()),"pickup");
        sameKeys(checkpoint.arena().objects(),arena.destructibles().stream().map(ArenaDefinition.Destructible::id).collect(Collectors.toSet()),"object");
        sameKeys(checkpoint.arena().hazards(),arena.hazards().stream().map(ArenaDefinition.Hazard::id).collect(Collectors.toSet()),"hazard");
        if(!arena.bounds().contains(new com.jme3.math.Vector3f((float)checkpoint.safePose().x(),(float)checkpoint.safePose().y(),(float)checkpoint.safePose().z())))
            throw new IllegalArgumentException("Checkpoint pose outside arena");
        for(var object:arena.destructibles())if(checkpoint.arena().objects().get(object.id()).hp()>object.maximumHp())
            throw new IllegalArgumentException("Checkpoint object HP exceeds maximum");
    }
    private static void sameKeys(Map<String,?> data,Set<String> expected,String type) {
        if(!data.keySet().equals(expected))throw new IllegalArgumentException("Checkpoint "+type+" references differ from this arena");
    }
}
