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

    @Test void immediateShotSelectsNearestVisibleCarOnlyInsideThirtyDegreeCone() {
        world.positions[1].set(0,1,40);world.positions[2].set(8,1,7);
        world.positions[3].set(0,1,-3);world.positions[4].set(0,1,5);world.hidden.add(4);
        fire();
        assertEquals(-1,combat.lockTarget(0),"Fixture fires before any lock can complete");
        assertEquals(1,combat.projectiles().getFirst().targetId(),"The nearer off-axis car is outside the aiming cone");
        assertEquals(1,combat.ballisticTarget(0),"HUD and shot use the same immediate selection");
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
                seen.add(p.id());assertTrue(p.targetId()==1||p.targetId()==-1,"Guidance may finish, but must never switch targets");
            }
        }
        assertEquals(4,seen.size());assertTrue(combat.ballisticWarnings().isEmpty());
        assertEquals(0,combat.occupiedProjectileSlots());
    }

    @Test void releasedDropsOnlyTurnHorizontallyAndStayWithinFiveMetresOfTheirFreeTrajectory() {
        world.floor=true;world.positions[1].set(0,1,40);world.velocities[1].set(18,0,0);fire();
        for(int wait=0;wait<400&&combat.ballisticWarnings().isEmpty();wait++) {world.positions[1].addLocal(world.velocities[1].mult(MatchSession.DT));tick();}
        assertFalse(combat.ballisticWarnings().isEmpty(),"The launched carrier must publish its warning");
        ProjectileState drop=firstDrop();
        assertEquals(1,drop.targetId());Vector3f initialWarning=warning(drop.id());
        world.positions[1].x=-22;world.velocities[1].set(-18,0,0);
        Vector3f initialVelocity=drop.originalVelocity.clone();
        for(int i=0;i<600&&!drop.exploded;i++) {
            Vector3f before=drop.velocity();tick();
            assertEquals(before.y-36*MatchSession.DT,drop.velocity().y,.0001,"Guidance must not change falling speed");
            Vector3f oldHorizontal=before.setY(0).normalizeLocal(),newHorizontal=drop.velocity().setY(0).normalizeLocal();
            double angle=Math.acos(Math.clamp(oldHorizontal.dot(newHorizontal),-1,1));
            assertTrue(angle<=Math.toRadians(24)*MatchSession.DT+.0003,"Instant turn: "+angle);
            if(!drop.exploded) {
                Vector3f free=drop.launchPosition.add(initialVelocity.mult(drop.ageTicks*MatchSession.DT));
                assertTrue(drop.position().subtract(free).setY(0).length()<=5.001,"Correction exceeded the free-flight envelope");
                var marker=combat.ballisticWarnings().stream().filter(w->w.id()==drop.id()).findFirst();
                if(marker.isPresent())assertTrue(marker.get().point().distance(initialWarning)<=5.1,"Warning movement exceeded the trajectory envelope");
            }
        }
    }

    @Test void eachChargeReaimsAtTheOriginalTargetWhenItsWarningIsPlanned() {
        world.floor=true;world.positions[1].set(0,1,40);fire();
        Set<Long> seen=new HashSet<>();
        float[] expectedX={-2,6,14,18};List<Long> planTicks=new ArrayList<>();
        for(int i=0;i<750;i++) {
            for(var marker:combat.ballisticWarnings())if(seen.add(marker.id())) {
                int index=seen.size()-1;
                assertEquals(expectedX[index],marker.point().x,.05f,"Each warning must use the latest position of the original target");
                planTicks.add(session.tick);world.positions[1].x=(index+1)*6;
                world.positions[2].set(0,1,10);
            }
            tick();
        }
        assertEquals(4,seen.size());
        for(int i=1;i<4;i++)assertEquals(36,planTicks.get(i)-planTicks.get(i-1));
        assertTrue(combat.fireZones().isEmpty());
    }

    @Test void eachWarningUsesPointEightSecondsOfLeadCappedAtTwelveMetres() {
        for(float speed:new float[]{5,100}) {
            var f=new NewArsenalTest();f.world.floor=true;f.world.positions[1].set(0,1,40);f.world.velocities[1].x=speed;
            f.tick(NewArsenalTest.fire(WeaponType.BALLISTIC));
            for(int i=0;i<400&&f.combat.ballisticWarnings().isEmpty();i++)f.tick(VehicleCommand.NONE);
            assertFalse(f.combat.ballisticWarnings().isEmpty());
            assertEquals(Math.min(speed*.8f,12)-2,f.combat.ballisticWarnings().getFirst().point().x,.05f);
        }
    }

    @Test void visibilityLostBetweenPlanningTicksStopsTheSalvoPermanently() {
        world.floor=true;world.positions[1].set(0,1,40);fire();
        for(int wait=0;wait<400&&combat.ballisticWarnings().isEmpty();wait++)tick();
        assertFalse(combat.ballisticWarnings().isEmpty());
        world.hidden.add(1);tick();world.hidden.clear();world.positions[1].x=20;
        Set<Long> released=new HashSet<>();
        for(int i=0;i<600;i++) {
            tick();
            for(var drop:combat.projectiles())if(drop.kind().equals("ballistic-fall")&&released.add(drop.id()))
                assertEquals(-1,drop.targetId(),"The carrier must remember even a brief loss between charge plans");
        }
        assertEquals(4,released.size());assertEquals(0,combat.occupiedProjectileSlots());
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

    @Test void guidanceEndsPermanentlyAfterTheFirstPointSevenFiveSecondsOfFalling() {
        world.floor=true;world.positions[1].set(0,1,40);fire();ProjectileState drop=firstDrop();
        while(drop.ageTicks<=90&&!drop.exploded)tick();
        assertFalse(drop.exploded);assertEquals(-1,drop.targetId());Vector3f velocity=drop.velocity();
        world.positions[1].x=4;
        for(int i=0;i<30&&!drop.exploded;i++) {
            tick();assertEquals(-1,drop.targetId());
            assertEquals(velocity.x,drop.velocity().x,.00001);assertEquals(velocity.z,drop.velocity().z,.00001);
        }
    }

    @Test void approachingAnEarlierRoofEndsGuidanceBeforeTheMaximumGuidedLifetime() {
        world.floor=true;world.positions[1].set(0,1,40);fire();ProjectileState drop=firstDrop();
        // A fresh roof prediction reaches its terminal window while the charge is still young.
        world.roof=true;world.roofHeight=18;world.positions[1].y=19;
        for(int i=0;i<90&&!drop.exploded&&drop.targetId()>=0;i++)tick();
        assertEquals(-1,drop.targetId());
        assertTrue(drop.ageTicks<90,"The roof must end guidance earlier than the maximum guided lifetime");
    }

    private Vector3f warning(long id) {
        return combat.ballisticWarnings().stream().filter(w->w.id()==id).findFirst().orElseThrow().point();
    }
}
