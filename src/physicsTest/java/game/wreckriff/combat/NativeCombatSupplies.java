package game.wreckriff.combat;

import game.wreckriff.simulation.MatchSession;

/** Native geometry tests provide rounds explicitly; live matches obtain them from map pickups. */
final class NativeCombatSupplies {
    private NativeCombatSupplies() { }
    static void halfLoad(MatchSession session) {
        session.vehicles.forEach(vehicle->vehicle.weapons().forEach(slot->slot.ammo=slot.maximumAmmo/2));
    }
}
