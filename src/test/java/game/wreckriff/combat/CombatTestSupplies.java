package game.wreckriff.combat;

import game.wreckriff.simulation.MatchSession;

/** Explicit collected-ammunition fixture for combat tests; production participants start empty. */
final class CombatTestSupplies {
    private CombatTestSupplies() { }
    static void halfLoad(MatchSession session) {
        session.vehicles.forEach(vehicle->vehicle.weapons().forEach(slot->slot.ammo=slot.maximumAmmo/2));
    }
}
