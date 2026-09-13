package game.wreckriff.combat;

import com.jme3.math.Vector3f;
import game.wreckriff.input.VehicleCommand;
import game.wreckriff.simulation.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class FreezeGuidanceTest {
    private final NewArsenalTest fixture=new NewArsenalTest();
    private final MatchSession session=fixture.session;
    private final CombatSystem combat=fixture.combat;
    private final NewArsenalTest.TestWorld world=fixture.world;
    private static final VehicleCommand FREEZE=new VehicleCommand(0,0,0,false,false,false,false,null,0,false,false,AbilityId.FREEZE);
    private ProjectileState fire() {fixture.tick(FREEZE);return combat.projectiles().getFirst();}
    private void tick() {fixture.tick(VehicleCommand.NONE);}
    private void place(int id,float degrees,float distance) {
        double angle=Math.toRadians(degrees);
        world.positions[id].set(world.muzzle(0).add((float)Math.sin(angle)*distance,0,(float)Math.cos(angle)*distance));
    }

    @ParameterizedTest @EnumSource(WeaponType.class)
    void instantAcquisitionUsesAimBeforeDistanceRegardlessOfSelectedWeapon(WeaponType selected) {
        session.vehicle(0).selectedWeapon=selected;place(1,10,15);place(2,2,45);
        var bolt=fire();
        assertEquals(-1,combat.lockTarget(0),"Homing still requires its normal acquisition time");
        assertEquals(2,bolt.targetId());assertTrue(bolt.direction().x>0);
        assertEquals(1440,session.vehicle(0).abilityCooldown(AbilityId.FREEZE));
        assertEquals(selected,session.vehicle(0).selectedWeapon);
    }

    @Test void equalAimChoosesNearestThenStableParticipantId() {
        place(1,0,40);place(2,0,25);place(3,0,25);
        assertEquals(2,fire().targetId());
    }

    @ParameterizedTest @ValueSource(floats={-18,18})
    void acquisitionIncludesBothConeEdges(float angle) {
        place(1,angle,40);assertEquals(1,fire().targetId());
    }

    @Test void acquisitionIncludesSixtyMetresButRejectsUnreachableRearHiddenAndDeadTargets() {
        place(1,0,60);assertEquals(1,fire().targetId());
        combat.clear();session.vehicle(0).abilityCooldown(AbilityId.FREEZE,0);
        place(1,0,60.01f);place(2,18.01f,20);place(3,0,30);place(4,0,25);
        world.hidden.add(3);session.vehicle(4).hp=0;
        assertEquals(-1,fire().targetId());
        combat.clear();session.vehicle(0).abilityCooldown(AbilityId.FREEZE,0);
        place(1,180,10);assertEquals(-1,fire().targetId());
    }

    @Test void movingTargetIsFollowedAtTheSharedHomingTurnRate() {
        place(1,0,55);var bolt=fire();
        for(int i=0;i<30;i++) {
            world.positions[1].set(bolt.position().add(10,0,35));
            Vector3f before=bolt.direction(),position=bolt.position();tick();
            double turn=Math.acos(Math.clamp(before.dot(bolt.direction()),-1,1));
            assertTrue(turn<=Math.toRadians(80)*MatchSession.DT+.0001,"Freeze exceeded Homing's turn limit");
            assertEquals(80*MatchSession.DT,position.distance(bolt.position()),.0001f);
            assertEquals(1,bolt.targetId());
        }
        assertTrue(bolt.direction().x>.2f,"The bolt must actually correct its heading");
    }

    @Test void exactlyThirtySixOccludedTicksAreAllowedAndVisibilityResetsTheGrace() {
        place(1,0,55);var bolt=fire();world.hidden.add(1);
        for(int i=0;i<36;i++) {world.positions[1].set(bolt.position().add(0,0,35));tick();assertEquals(1,bolt.targetId());}
        world.hidden.clear();tick();assertEquals(1,bolt.targetId());
        world.hidden.add(1);
        for(int i=0;i<36;i++) {world.positions[1].set(bolt.position().add(0,0,35));tick();assertEquals(1,bolt.targetId());}
        tick();assertEquals(-1,bolt.targetId());
        world.hidden.clear();world.positions[2].set(bolt.position().add(0,0,10));
        tick();assertEquals(-1,bolt.targetId(),"Lost targets must never be reacquired");
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void deathOrLeavingRetentionConePermanentlyDisablesGuidance(boolean dies) {
        place(1,0,45);var bolt=fire();
        if(dies)session.vehicle(1).hp=0;else world.positions[1].set(bolt.position().add(30,0,10));
        tick();assertEquals(-1,bolt.targetId());Vector3f direction=bolt.direction();
        place(2,1,30);
        for(int i=0;i<20;i++) {tick();assertEquals(-1,bolt.targetId());assertEquals(direction,bolt.direction());}
    }

    @Test void unacquiredBoltNeverLocksLaterAndExpiresAfterExactlyNinetySteps() {
        var bolt=fire();Vector3f origin=bolt.launchPosition.clone();place(1,5,40);
        for(int i=1;i<89;i++) {tick();assertEquals(-1,bolt.targetId());}
        assertEquals(1,bolt.remainingTicks());assertFalse(combat.projectiles().isEmpty());
        tick();assertTrue(combat.projectiles().isEmpty());
        assertEquals(60,bolt.position().distance(origin),.001f);
        assertFalse(session.vehicle(1).controlled());
    }

    @Test void obstacleHitAndBlockedMuzzleCannotFreezeTheAcquiredTargetThroughTheWall() {
        for(boolean muzzle:List.of(false,true)) {
            var f=new NewArsenalTest();f.world.positions[1].set(f.world.muzzle(0).add(0,0,20));
            f.world.blockMuzzle=muzzle;
            if(!muzzle)f.world.hits.add(new WorldQuery.Hit(-1,f.world.muzzle(0).add(0,0,.2f),Vector3f.UNIT_Z.negate(),.3f));
            var events=f.tick(FREEZE);
            assertTrue(f.combat.projectiles().isEmpty());assertFalse(f.session.vehicle(1).controlled());
            assertTrue(events.stream().anyMatch(e->e.type()==GameEvent.Type.IMPACT&&e.kind().equals("freeze")));
            assertTrue(events.stream().noneMatch(e->e.type()==GameEvent.Type.FREEZE));
        }
    }
}
