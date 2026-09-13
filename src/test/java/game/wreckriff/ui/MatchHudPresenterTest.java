package game.wreckriff.ui;

import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import game.wreckriff.arena.ArenaRegistry;
import game.wreckriff.combat.CombatRules;
import game.wreckriff.combat.WeaponType;
import game.wreckriff.combat.AbilityId;
import game.wreckriff.combat.SpecialRules;
import game.wreckriff.simulation.VehicleState;
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
    @Test void airborneHudStopsUsingTheOldYawBranchOnceTheCarIsUprightWithoutRecovery() {
        var session=new MatchSession(12,360);var presenter=new MatchHudPresenter();var rotation=new Quaternion();
        WorldQuery world=(WorldQuery)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{WorldQuery.class},(proxy,method,args)->switch(method.getName()) {
            case "position"->new Vector3f();case "rotation"->rotation.clone();
            case "roadContext"->new RoadContext("lower",0,1,RoadContext.Motion.AIRBORNE,"","",0);
            default->throw new AssertionError(method.getName());
        });
        var targeting=new MatchHudPresenter.Targeting(-1,-1,-1);
        assertEquals(1,presenter.snapshot(session,world,targeting,"RMB","",0).observer().heading().z());
        for(float[] pose:new float[][]{{89,0},{89.8f,0},{89.8f,180},{89,180},{60,180},{0,180}}) {
            rotation.set(new Quaternion().fromAngleAxis(pose[1]*(float)Math.PI/180,Vector3f.UNIT_Y)
                    .mult(new Quaternion().fromAngleAxis(pose[0]*(float)Math.PI/180,Vector3f.UNIT_X)));
            var observed=presenter.snapshot(session,world,targeting,"RMB","",1).observer().heading();
            assertEquals(pose[0]<=60?-1:1,observed.z(),.001f,"pitch="+pose[0]+", yaw="+pose[1]);
        }
        assertEquals(0,session.vehicle(0).recoveries);assertEquals(0,session.tick);
    }
    @Test void airbornePitchKeepsRadarHeadingButGroundedTurnAndRecoveryUseTheNewPhysicalPose() {
        var session=new MatchSession(12,360);var presenter=new MatchHudPresenter();
        var rotation=new Quaternion();boolean[] flying={false};
        WorldQuery world=(WorldQuery)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{WorldQuery.class},(proxy,method,args)->switch(method.getName()) {
            case "position"->new Vector3f();case "rotation"->rotation.clone();
            case "roadContext"->new RoadContext("lower",0,1,flying[0]?RoadContext.Motion.AIRBORNE:RoadContext.Motion.ROAD,"","",0);
            default->throw new AssertionError(method.getName());
        });
        var targeting=new MatchHudPresenter.Targeting(-1,-1,-1);
        assertEquals(1,presenter.snapshot(session,world,targeting,"RMB","",0).observer().heading().z());
        flying[0]=true;
        for(int degrees=0;degrees<=180;degrees++) {
            rotation.fromAngleAxis(degrees*(float)Math.PI/180,Vector3f.UNIT_X);
            assertEquals(1,presenter.snapshot(session,world,targeting,"RMB","",1).observer().heading().z(),.001f);
        }
        rotation.fromAngles(0,(float)Math.PI,0);session.vehicle(0).recoveries++;
        assertEquals(-1,presenter.snapshot(session,world,targeting,"RMB","",2).observer().heading().z(),.001f);
        flying[0]=false;rotation.loadIdentity();
        assertEquals(1,presenter.snapshot(session,world,targeting,"RMB","",3).observer().heading().z(),.001f);
        assertEquals(0,session.tick,"Presentation never advances simulation");
    }
    @ParameterizedTest
    @CsvSource({"rivet,14", "grinder,20", "spark,12"})
    void playerSpecialReadsProfileCooldownAndActiveStateWithoutAdvancingEither(String profileId,float duration) {
        var session=new MatchSession(12,ArenaRegistry.load().definition("construction_17"),MatchSession.Mode.ARENA,
                Configs.load("combat",CombatRules.class),java.util.UUID.randomUUID(),false,0,profileId);
        var player=session.vehicle(0);player.specialPhase=VehicleState.SpecialPhase.DASH;player.specialTicks=30;
        player.abilityCooldown(AbilityId.SPECIAL,600);
        var snapshot=new MatchHudPresenter().snapshot(session,observations(2),new MatchHudPresenter.Targeting(-1,-1,-1),"RMB","",0);
        var special=snapshot.abilities().get(AbilityId.SPECIAL);
        assertEquals(duration,SpecialRules.cooldownSeconds(profileId));assertEquals(duration,special.cooldownDurationSeconds());
        assertEquals(.25f,special.activeSeconds());assertEquals(5,special.cooldownSeconds());
        assertEquals(30,player.specialTicks);assertEquals(600,player.abilityCooldown(AbilityId.SPECIAL));
    }
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
