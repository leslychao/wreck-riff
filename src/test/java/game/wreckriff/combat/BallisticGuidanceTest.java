package game.wreckriff.combat;

import com.jme3.math.Vector3f;
import game.wreckriff.input.VehicleCommand;
import game.wreckriff.simulation.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BallisticGuidanceTest {
    private final NewArsenalTest fixture=new NewArsenalTest();
    private final MatchSession session=fixture.session;
    private final CombatSystem combat=fixture.combat;
    private final NewArsenalTest.TestWorld world=fixture.world;
    private void fire() {fixture.tick(NewArsenalTest.fire(WeaponType.BALLISTIC));}
    private List<GameEvent> tick() {return fixture.tick(VehicleCommand.NONE);}
    private ProjectileState firstDrop() {
        for(int i=0;i<400;i++) {
            tick();
            var drop=combat.projectiles().stream().filter(p->p.kind().equals("ballistic-fall")).findFirst();
            if(drop.isPresent())return drop.get();
        }
        throw new AssertionError("No falling charge");
    }

    @Test void immediateShotSelectsNearestVisibleFrontCarIncludingCloseAndOffAxisTargets() {
        world.positions[1].set(0,1,40);world.positions[2].set(8,1,7);
        world.positions[3].set(0,1,-3);world.positions[4].set(0,1,5);world.hidden.add(4);
        fire();
        assertEquals(-1,combat.lockTarget(0),"Fixture fires before any lock can complete");
        assertEquals(2,combat.projectiles().getFirst().targetId());
        assertEquals(2,combat.ballisticTarget(0),"HUD and shot use the same immediate selection");
        assertEquals(1,session.vehicle(0).weapon(WeaponType.BALLISTIC).ammo);
    }

    @Test void deadHiddenRearAndOutOfRangeCarsCannotBeSelectedAndEqualDistancesUseStableId() {
        world.positions[1].set(-10,1,20);world.positions[2].set(10,1,20);
        fire();assertEquals(1,combat.projectiles().getFirst().targetId());
        combat.clear();session.vehicle(0).weapon(WeaponType.BALLISTIC).cooldownTicks=0;
        session.vehicle(1).hp=0;world.hidden.add(2);world.positions[3].set(0,1,-5);world.positions[4].set(0,1,71);
        fire();assertEquals(-1,combat.projectiles().getFirst().targetId());
    }

    @Test void allFourDropsKeepTheOriginallySelectedTargetEvenWhenAnotherCarBecomesCloser() {
        world.floor=true;world.positions[1].set(0,1,40);fire();world.positions[2].set(0,1,8);
        Set<Long> seen=new HashSet<>();
        for(int i=0;i<750;i++) {
            tick();
            for(var p:combat.projectiles())if(p.kind().equals("ballistic-fall")) {
                seen.add(p.id());assertEquals(1,p.targetId());
            }
        }
        assertEquals(4,seen.size());assertTrue(combat.ballisticWarnings().isEmpty());
        assertEquals(0,combat.occupiedProjectileSlots());
    }

    @Test void releasedDropsTurnAtABoundedRateAndWarningsMoveWithTheirTrajectory() {
        world.floor=true;world.positions[1].set(0,1,40);fire();ProjectileState drop=firstDrop();
        assertEquals(1,drop.targetId());Vector3f initialWarning=warning(drop.id());
        world.positions[1].x=22;
        float largestWarningShift=0;
        for(int i=0;i<600&&!drop.exploded;i++) {
            Vector3f before=drop.velocity().normalizeLocal();tick();
            Vector3f steered=drop.velocity().addLocal(0,18*MatchSession.DT,0).normalizeLocal();
            double angle=Math.acos(Math.clamp(before.dot(steered),-1,1));
            assertTrue(angle<=Math.toRadians(45)*MatchSession.DT+.00015,"Instant turn: "+angle);
            if(!drop.exploded) {
                var marker=combat.ballisticWarnings().stream().filter(w->w.id()==drop.id()).findFirst();
                if(marker.isPresent())largestWarningShift=Math.max(largestWarningShift,marker.get().point().distance(initialWarning));
            }
        }
        assertTrue(drop.position().x>6,"Charge must leave the removed six-metre area limit: "+drop.position());
        assertTrue(largestWarningShift>6,"The old warning must not remain fixed under a homing charge");
    }

    @Test void movingTargetReceivesDamageAfterDrivingOutsideTheOldSixMetreArea() {
        world.floor=true;world.positions[1].set(0,1,40);world.velocities[1].set(6,0,0);fire();
        List<GameEvent> explosions=new ArrayList<>();
        for(int i=0;i<750;i++) {
            world.positions[1].addLocal(world.velocities[1].mult(MatchSession.DT));
            for(var event:tick())if(event.type()==GameEvent.Type.EXPLOSION&&event.kind().equals("ballistic")) {
                explosions.add(event);assertTrue(event.position().x>6);
            }
        }
        assertEquals(4,explosions.size());assertTrue(session.vehicle(1).hp<400,"A steady moving target should be hit");
        assertTrue(combat.fireZones().isEmpty());
    }

    @Test void lostVisibilityOrDeathPermanentlyStopsGuidanceWithoutChoosingAnotherTarget() {
        for(boolean dies:new boolean[]{false,true}) {
            var f=new NewArsenalTest();f.world.floor=true;f.world.positions[1].set(0,1,40);
            f.tick(NewArsenalTest.fire(WeaponType.BALLISTIC));ProjectileState drop=null;
            for(int i=0;i<400&&drop==null;i++) {
                f.tick(VehicleCommand.NONE);
                drop=f.combat.projectiles().stream().filter(p->p.kind().equals("ballistic-fall")).findFirst().orElse(null);
            }
            assertNotNull(drop);assertEquals(1,drop.targetId());
            if(dies)f.session.vehicle(1).hp=0;else f.world.hidden.add(1);
            f.tick(VehicleCommand.NONE);assertEquals(-1,drop.targetId());
            f.world.hidden.clear();f.world.positions[2].set(0,1,40);
            Vector3f velocity=drop.velocity();
            for(int i=0;i<30&&!drop.exploded;i++) {
                f.tick(VehicleCommand.NONE);assertEquals(-1,drop.targetId());
                assertEquals(velocity.x,drop.velocity().x,.00001f);assertEquals(velocity.z,drop.velocity().z,.00001f);
            }
            f.combat.clear();assertTrue(f.combat.ballisticWarnings().isEmpty());assertEquals(0,f.combat.occupiedProjectileSlots());
        }
    }

    @Test void aLateSharpDodgeCanEscapeAnAlreadyFallingCharge() {
        world.floor=true;world.positions[1].set(0,1,40);fire();ProjectileState drop=firstDrop();
        for(int i=0;i<105;i++)tick();
        world.positions[1].x=30;float health=session.vehicle(1).hp;
        while(!drop.exploded)tick();
        assertTrue(drop.position().distance(world.positions[1])>session.combatRules.ballistic().radius()+1);
        assertEquals(health,session.vehicle(1).hp,"Guidance must not guarantee a hit after a sudden late dodge");
    }

    private Vector3f warning(long id) {
        return combat.ballisticWarnings().stream().filter(w->w.id()==id).findFirst().orElseThrow().point();
    }
}
