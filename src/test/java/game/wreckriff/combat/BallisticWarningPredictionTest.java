package game.wreckriff.combat;

import game.wreckriff.input.VehicleCommand;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BallisticWarningPredictionTest {
    @Test void missingPredictedSurfaceHidesTheOldMarkerWithoutCancellingTheCharge() {
        var fixture=new NewArsenalTest();
        fixture.world.floor=true;fixture.world.positions[1].set(0,1,40);
        fixture.tick(NewArsenalTest.fire(WeaponType.BALLISTIC));
        ProjectileState charge=null;
        for(int tick=0;tick<400&&charge==null;tick++) {
            fixture.tick(VehicleCommand.NONE);
            charge=fixture.combat.projectiles().stream().filter(p->p.kind().equals("ballistic-fall")).findFirst().orElse(null);
        }
        assertNotNull(charge);long id=charge.id();
        assertTrue(fixture.combat.ballisticWarnings().stream().anyMatch(w->w.id()==id));
        fixture.world.floor=false;
        for(int tick=0;tick<13;tick++)fixture.tick(VehicleCommand.NONE);
        assertTrue(fixture.combat.projectiles().stream().anyMatch(p->p.id()==id),"A hidden marker must not cancel the flying charge");
        assertTrue(fixture.combat.ballisticWarnings().stream().noneMatch(w->w.id()==id),"Do not display a stale impact point when no surface is predicted");
        fixture.world.floor=true;
        for(int tick=0;tick<13;tick++)fixture.tick(VehicleCommand.NONE);
        assertTrue(fixture.combat.ballisticWarnings().stream().anyMatch(w->w.id()==id),"A valid prediction must restore the same charge marker");
        fixture.combat.clear();
        assertTrue(fixture.combat.ballisticWarnings().isEmpty());assertEquals(0,fixture.combat.occupiedProjectileSlots());
    }
}
