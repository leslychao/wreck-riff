package game.wreckriff.ui;

import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import game.wreckriff.arena.ArenaRegistry;
import game.wreckriff.combat.CombatRules;
import game.wreckriff.combat.WeaponType;
import game.wreckriff.config.Configs;
import game.wreckriff.simulation.MatchSession;
import game.wreckriff.simulation.RoadContext;
import game.wreckriff.simulation.WorldQuery;
import java.lang.reflect.Proxy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;

class MatchHudPresenterTest {
    @ParameterizedTest
    @CsvSource({"HOMING,60", "POWER,84", "MINE,84", "NAPALM,108", "CANNON,168", "BALLISTIC,360"})
    void weaponHudUsesApprovedDurationAndRemainingSimulationTicks(WeaponType type, int intervalTicks) {
        var session = new MatchSession(12, 360);
        var slot = session.vehicle(0).weapon(type);
        slot.cooldownTicks = intervalTicks / 2;
        var presenter = new MatchHudPresenter();
        var snapshot = presenter.snapshot(session, observations(2),
                new MatchHudPresenter.Targeting(-1, -1, -1), "RMB", "", 0);
        var status = snapshot.weapons().get(type);
        assertEquals(intervalTicks / 120f, status.cooldownDurationSeconds(), .0001f);
        assertEquals(intervalTicks / 240f, status.cooldownSeconds(), .0001f);
        assertEquals(slot.ammo, status.ammo());
        assertEquals(intervalTicks / 2, slot.cooldownTicks, "Rendering must not advance reload time");
    }

    private WorldQuery observations(float airborneHeight) {
        return (WorldQuery)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{WorldQuery.class},(proxy,method,args)->{
            int id=(Integer)args[0];
            return switch(method.getName()) {
                case "position"->new Vector3f(id*2,id==1?airborneHeight:1,10);
                case "rotation"->new Quaternion();
                case "roadContext"->id==1?new RoadContext("upper",1,1,RoadContext.Motion.AIRBORNE,"","",1):new RoadContext("lower",0,1,RoadContext.Motion.ROAD,"","",0);
                default->throw new AssertionError("HUD must use read-only pose/road observations: "+method.getName());
            };
        });
    }
    @Test void launchingTargetKeepsConfirmedFloorAcrossApexAndHudDoesNotSpendResources() {
        var session=new MatchSession(12,360);var presenter=new MatchHudPresenter();
        var targeting=new MatchHudPresenter.Targeting(1,-1,-1);
        int ammo=session.vehicle(0).weapon(WeaponType.HOMING).ammo;
        var first=presenter.snapshot(session,observations(2),targeting,"RMB","",0);
        var apex=presenter.snapshot(session,observations(40),targeting,"RMB","",1);
        assertEquals(1,first.radarTargets().getFirst().roadLevel());assertEquals(1,apex.radarTargets().getFirst().roadLevel());
        assertEquals(0,session.tick);assertEquals(ammo,session.vehicle(0).weapon(WeaponType.HOMING).ammo);
        assertEquals(800,session.vehicle(0).hp);
        assertEquals("",presenter.snapshot(session,observations(2),targeting,"RMB","",2).selectionHint());
        assertTrue(presenter.snapshot(session,observations(2),targeting,"RB","",3).selectionHint().endsWith("RB"));
    }
    @Test void campaignRosterBeyondFourUsesActualAliveCountAndHasNoLegacyTimer() {
        var arena=ArenaRegistry.load().definition("construction_17");
        var session=new MatchSession(12,arena,MatchSession.Mode.ARENA,Configs.load("combat",CombatRules.class));
        session.phase=MatchSession.Phase.ARENA_COMBAT;
        var presenter=new MatchHudPresenter();var targeting=new MatchHudPresenter.Targeting(-1,-1,-1);
        var first=presenter.snapshot(session,observations(2),targeting,"RMB","",0);
        assertEquals(6,first.radarTargets().size());assertEquals("6 RIVALS",first.objective());
        session.vehicle(5).hp=0;
        var next=presenter.snapshot(session,observations(2),targeting,"RMB","",1);
        assertEquals(5,next.radarTargets().size());assertEquals("5 RIVALS",next.objective());
    }
}
