package game.wreckriff.ai;

import com.jme3.math.Vector3f;
import game.wreckriff.arena.*;
import game.wreckriff.input.VehicleCommand;
import game.wreckriff.simulation.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BotControllerTest {
    private final ArenaDefinition arena=ArenaDefinition.load();
    private final AiRules rules=AiRules.load();

    @Test void hiddenOpponentOnlyExposesOldObservationThenExpires() {
        MatchSession session=new MatchSession(42,360); TestWorld world=new TestWorld();
        world.positions[0]=new Vector3f(0,.8f,-45); world.positions[1]=new Vector3f(0,.8f,-15);
        BotController bots=new BotController(session,arena,rules);
        bots.commands(world); assertEquals(1,bots.targetId(0));
        Vector3f old=bots.observation(0).visible(1).position().clone();
        world.hidden.add(1); world.positions[1]=new Vector3f(45,.8f,-10);
        session.tick=12; VehicleCommand command=bots.commands(world).get(0);
        assertNull(bots.observation(0).visible(1)); assertEquals(old,bots.observation(0).known(1).position());
        assertFalse(command.machineGun()); assertFalse(command.selectedWeapon());
        session.tick=252; bots.commands(world); assertNull(bots.observation(0).known(1));
    }
    @Test void lowHpRoutesToUpperRepairThroughRampAndUsesNormalCommands() {
        MatchSession session=new MatchSession(42,360); TestWorld world=new TestWorld();
        world.positions[0]=new Vector3f(54,.8f,-41); session.vehicle(0).hp=60;
        BotController bots=new BotController(session,arena,rules);
        VehicleCommand command=bots.commands(world).get(0);
        assertEquals(BotController.State.SEEK_PICKUP,bots.state(0));
        assertTrue(bots.route(0).containsAll(List.of(31,32,33)));
        assertTrue(command.throttle()>0); assertFalse(command.recover());
        assertEquals(new Vector3f(54,.8f,-41),world.positions[0]);
    }
    @Test void reactionIsNotInstantAndPureDecisionsRepeatForTheSeed() {
        MatchSession a=new MatchSession(987,360),b=new MatchSession(987,360); TestWorld world=new TestWorld();
        world.positions[0]=new Vector3f(0,.8f,-45); world.positions[1]=new Vector3f(0,.8f,-15);
        BotController first=new BotController(a,arena,rules),second=new BotController(b,arena,rules);
        boolean fired=false;
        for (int tick=0;tick<72;tick++) {
            a.tick=tick;b.tick=tick;
            Map<Integer,VehicleCommand> ca=first.commands(world),cb=second.commands(world);
            assertEquals(ca,cb);
            if (tick<rules.reactionMinTicks()) assertFalse(ca.get(0).machineGun());
            fired|=ca.get(0).machineGun();
        }
        assertTrue(fired);
    }
    @Test void replanDoesNotTurnBackTowardTheClosestNodeAlreadyPassed() {
        MatchSession session=new MatchSession(42,360); TestWorld world=new TestWorld();
        world.positions[0]=new Vector3f(-69,.8f,-17); session.vehicle(0).hp=60;
        BotController bots=new BotController(session,arena,rules);
        VehicleCommand command=bots.commands(world).get(0);
        assertEquals(BotController.State.SEEK_PICKUP,bots.state(0));
        assertTrue(Math.abs(command.steer())<.3f,"Next corridor is ahead, even though the nearest node is behind");
        assertFalse(command.handbrake());
    }
    @Test void sustainedBlockerTriggersReverseBeforeCostlyRecovery() {
        MatchSession session=new MatchSession(42,360); TestWorld world=new TestWorld();
        world.positions[0]=new Vector3f(54,.8f,-41); world.blockSweeps=true; session.vehicle(0).hp=60;
        BotController bots=new BotController(session,arena,rules);
        boolean reversed=false,recovered=false;
        for (int tick=0;tick<850;tick++) {
            session.tick=tick; VehicleCommand command=bots.commands(world).get(0);
            if (bots.state(0)==BotController.State.RECOVER && command.brakeReverse()>0 && !command.recover()) reversed=true;
            if (command.recover()) { assertTrue(tick>600); recovered=true;break; }
        }
        assertTrue(reversed);assertTrue(recovered);
        assertEquals(60,session.vehicle(0).hp,"The AI requests recovery; only the shared recovery system charges it");
    }
    @Test void invisiblePickupAvailabilityDoesNotGiveAIHiddenState() {
        MatchSession session=new MatchSession(3,360); TestWorld world=new TestWorld();
        world.positions[0]=new Vector3f(54,.8f,-41); session.vehicle(0).hp=60;world.wall=true;
        BotController bots=new BotController(session,arena,rules,List::of);
        bots.commands(world);
        assertEquals(BotController.State.SEEK_PICKUP,bots.state(0));
        assertTrue(bots.route(0).contains(33));
        world.wall=false; session.tick=12; bots.commands(world);
        assertFalse(bots.route(0).contains(33),"A visibly unavailable upper repair must no longer be the destination");
    }
    @Test void actualRouteProgressAfterReverseCancelsStaleRecoveryBeforeTheNextSamplingWindow() {
        MatchSession session=new MatchSession(42,360);TestWorld world=new TestWorld();
        world.positions[0]=new Vector3f(54,.8f,-41);world.blockSweeps=true;session.vehicle(0).hp=60;
        BotController bots=new BotController(session,arena,rules);
        for(int tick=0;tick<620;tick++) {
            // Two unsuccessful reverse attempts, then the blocker clears and the car
            // actually advances two metres towards its route before the next 180-tick sample.
            // Traffic blocks it again afterwards; that must start a new stuck episode.
            if(tick==530)world.blockSweeps=false;
            if(tick==560)world.blockSweeps=true;
            if(tick>=540&&tick<560)world.positions[0].z+=.1f;
            session.tick=tick;VehicleCommand command=bots.commands(world).get(0);
            assertFalse(command.recover(),"Recovered despite demonstrated route progress at tick "+tick);
        }
        assertEquals(0,bots.metrics(0).reverseAttempts());
    }
    @Test void lowTurboSeeksCellsAfterRepairAndAmmoAndKeepsAnUsefulRouteDuringRegen() {
        MatchSession session=new MatchSession(2,360);TestWorld world=new TestWorld();
        world.positions[0]=new Vector3f(0,.8f,-36);session.vehicle(0).turbo=19;
        BotController bots=new BotController(session,arena,rules);
        bots.commands(world);
        assertEquals(new Vector3f(0,0,-20),bots.metrics(0).destination());
        session.vehicle(0).turbo=21;session.tick=12;bots.commands(world);
        assertEquals(new Vector3f(0,0,-20),bots.metrics(0).destination());
        session.vehicle(0).hp=60;session.tick=24;bots.commands(world);
        assertEquals(BotController.State.SEEK_PICKUP,bots.state(0));
        assertTrue(arena.pickups().stream().anyMatch(p->p.type()==ArenaDefinition.PickupType.REPAIR
                && p.position().vector().equals(bots.metrics(0).destination())));
    }
}
