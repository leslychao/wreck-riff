package game.wreckriff.ai;

import com.jme3.math.*;
import game.wreckriff.arena.*;
import game.wreckriff.combat.*;
import game.wreckriff.input.VehicleCommand;
import game.wreckriff.simulation.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Perception/command tests only. Native trajectories and road contact remain physicsTest responsibilities. */
class BotArsenalAndWarningsTest {
    private final ArenaDefinition arena=ArenaDefinition.load();
    private final AiRules rules=AiRules.load();

    @Test void allSixWeaponsAreActuallyRequestedWhenUsefulAndReady() {
        MatchSession session=new MatchSession(42,360);World world=new World();
        world.positions[1]=new Vector3f(0,.8f,-15);
        world.positions[2]=new Vector3f(-7,.8f,-54);world.velocities[2]=new Vector3f(0,0,6);
        BotController bots=new BotController(session,arena,rules);EnumSet<WeaponType> used=EnumSet.noneOf(WeaponType.class);
        for(int tick=0;tick<120;tick++) {
            session.tick=tick;VehicleCommand command=bots.commands(world).get(0);
            if(command.directWeapon()!=null)session.vehicle(0).selectedWeapon=command.directWeapon();
            if(command.selectedWeapon()) {
                WeaponType selected=session.vehicle(0).selectedWeapon;WeaponSlot slot=session.vehicle(0).weapon(selected);
                assertTrue(slot.ammo>0);assertEquals(0,slot.cooldownTicks);
                used.add(selected);slot.ammo--;slot.cooldownTicks=240;
            }
        }
        assertEquals(EnumSet.allOf(WeaponType.class),used);
    }
    @Test void cooldownAndEmptyAmmoCannotBlockAnotherReadyWeaponOrRequestAnInvalidShot() {
        MatchSession session=new MatchSession(42,360);World world=new World();world.positions[1]=new Vector3f(0,.8f,-15);
        for(WeaponType weapon:WeaponType.values())session.vehicle(0).weapon(weapon).cooldownTicks=240;
        BotController bots=new BotController(session,arena,rules);
        for(int tick=0;tick<72;tick++) {session.tick=tick;assertFalse(bots.commands(world).get(0).selectedWeapon());}
        session.vehicle(0).weapon(WeaponType.CANNON).cooldownTicks=0;session.tick=72;
        VehicleCommand cannon=bots.commands(world).get(0);assertTrue(cannon.selectedWeapon());assertEquals(WeaponType.CANNON,cannon.directWeapon());
        session.vehicle(0).weapon(WeaponType.CANNON).ammo=0;session.tick=73;
        assertFalse(bots.commands(world).get(0).selectedWeapon());
    }
    @Test void napalmUsesAssistRangeAndConeWithoutTheRemovedOneSecondLeadOrFixedDistanceBand() {
        for(float distance:new float[]{5,15,30,60})assertTrue(napalmRequested(distance,0,new Vector3f(25,0,0)),"Assist distance "+distance);
        assertTrue(napalmRequested(30,11,Vector3f.ZERO));
        assertFalse(napalmRequested(4.5f,0,Vector3f.ZERO));
        assertFalse(napalmRequested(61,0,Vector3f.ZERO));
        assertFalse(napalmRequested(30,13,Vector3f.ZERO));
    }
    @Test void ballisticNeedsVisibleRangeLockAndOpenCarrierClearance() {
        assertTrue(ballisticRequested(35,false,false));
        assertFalse(ballisticRequested(15,false,false));
        assertFalse(ballisticRequested(72,false,false));
        assertFalse(ballisticRequested(35,true,false));
        assertFalse(ballisticRequested(35,false,true));
    }
    @Test void exhaustedNewAmmoFindsTheCorrectAuthoredPickupThroughTheExistingGraph() {
        for(WeaponType weapon:List.of(WeaponType.BALLISTIC,WeaponType.CANNON)) {
            MatchSession session=new MatchSession(42,360);World world=new World();
            session.vehicle(0).weapon(weapon).ammo=0;BotController bots=new BotController(session,arena,rules);
            bots.commands(world);
            ArenaDefinition.PickupType type=weapon==WeaponType.BALLISTIC?ArenaDefinition.PickupType.BALLISTIC_AMMO:ArenaDefinition.PickupType.CANNON_AMMO;
            assertEquals(BotController.State.SEEK_PICKUP,bots.state(0));
            assertTrue(arena.pickups().stream().anyMatch(p->p.type()==type&&p.position().vector().equals(bots.metrics(0).destination())));
        }
    }
    @Test void visibleSameFloorWarningUsesAnOutsideGraphDestinationAndImminentShieldThenExpires() {
        MatchSession session=new MatchSession(42,360);World world=new World();BotController bots=new BotController(session,arena,rules);
        Vector3f impact=world.position(0).add(0,-.8f,0);
        List<CombatSystem.BallisticWarningView> warnings=new ArrayList<>();
        warnings.add(new CombatSystem.BallisticWarningView(1,1,impact,Vector3f.UNIT_Y,6,18));bots.observeBallisticWarnings(()->warnings);
        VehicleCommand command=bots.commands(world).get(0);
        assertEquals(BotController.State.EVADE_HAZARD,bots.state(0));
        assertTrue(horizontal(bots.metrics(0).destination(),impact)>=9);
        assertEquals(AbilityId.SHIELD,command.ability());
        assertEquals(new Vector3f(0,.8f,-45),world.position(0),"AI can issue only driver commands");
        warnings.clear();session.tick=12;bots.commands(world);
        assertNotEquals(BotController.State.EVADE_HAZARD,bots.state(0));
    }
    @Test void wallsRoofsOtherFloorsAndExpiredWarningsCannotRevealDangerOrTriggerShield() {
        for(String hidden:List.of("wall","roof","other-floor","expired","behind")) {
            MatchSession session=new MatchSession(42,360);World world=new World();BotController bots=new BotController(session,arena,rules);
            Vector3f point=world.position(0).add(0,-.8f,0);int ticks=18;
            if(hidden.equals("wall"))world.wall=true;
            if(hidden.equals("roof")) {point.y=1.8f;world.wall=true;}
            if(hidden.equals("other-floor"))point.y=6;
            if(hidden.equals("expired"))ticks=0;
            if(hidden.equals("behind"))point.addLocal(0,0,-7);
            var warning=new CombatSystem.BallisticWarningView(1,1,point,Vector3f.UNIT_Y,6,ticks);
            bots.observeBallisticWarnings(()->List.of(warning));VehicleCommand command=bots.commands(world).get(0);
            assertNotEquals(BotController.State.EVADE_HAZARD,bots.state(0),hidden);
            assertNotEquals(AbilityId.SHIELD,command.ability(),hidden);
        }
    }
    @Test void aCarAlreadyLeavingBeforeImpactKeepsItsShield() {
        MatchSession session=new MatchSession(42,360);World world=new World();world.velocities[0]=new Vector3f(0,0,28);
        BotController bots=new BotController(session,arena,rules);
        bots.observeBallisticWarnings(()->List.of(new CombatSystem.BallisticWarningView(1,1,new Vector3f(0,0,-45),Vector3f.UNIT_Y,6,60)));
        assertNotEquals(AbilityId.SHIELD,bots.commands(world).get(0).ability());
    }
    private boolean napalmRequested(float distance,float degrees,Vector3f velocity) {
        MatchSession session=new MatchSession(42,360);World world=new World();
        float angle=(float)Math.toRadians(degrees);
        world.positions[1]=world.muzzle(0).add((float)Math.sin(angle)*distance,0,(float)Math.cos(angle)*distance);
        world.velocities[1]=velocity;
        for(WeaponType weapon:WeaponType.values())if(weapon!=WeaponType.NAPALM)session.vehicle(0).weapon(weapon).cooldownTicks=240;
        return requested(session,world,WeaponType.NAPALM);
    }
    private boolean ballisticRequested(float distance,boolean ceiling,boolean hidden) {
        MatchSession session=new MatchSession(42,360);World world=new World();world.positions[1]=world.muzzle(0).add(0,0,distance);
        world.ceiling=ceiling;world.hideTarget=hidden;
        for(WeaponType weapon:WeaponType.values())if(weapon!=WeaponType.BALLISTIC)session.vehicle(0).weapon(weapon).cooldownTicks=240;
        return requested(session,world,WeaponType.BALLISTIC);
    }
    private boolean requested(MatchSession session,World world,WeaponType expected) {
        BotController bots=new BotController(session,arena,rules);
        for(int tick=0;tick<110;tick++) {
            session.tick=tick;VehicleCommand command=bots.commands(world).get(0);
            if(command.directWeapon()!=null)session.vehicle(0).selectedWeapon=command.directWeapon();
            if(command.selectedWeapon()) {assertEquals(expected,session.vehicle(0).selectedWeapon);return true;}
        }
        return false;
    }
    private static float horizontal(Vector3f a,Vector3f b) {return new Vector3f(a.x-b.x,0,a.z-b.z).length();}
    private static final class World implements WorldQuery {
        final Vector3f[] positions=new Vector3f[5],velocities=new Vector3f[5];
        boolean wall,ceiling,hideTarget;
        World() {for(int id=0;id<5;id++){positions[id]=new Vector3f(1000+id*100,.8f,1000);velocities[id]=new Vector3f();}positions[0]=new Vector3f(0,.8f,-45);}
        public Vector3f position(int id){return positions[id].clone();}
        public Vector3f velocity(int id){return velocities[id].clone();}
        public Quaternion rotation(int id){return new Quaternion();}
        public boolean grounded(int id){return true;}
        public float mass(int id){return 1100;}
        public Hit ray(Vector3f from,Vector3f to,int ignored) {
            if(from.y>0&&to.y<=0&&Math.abs(from.x-to.x)<.001f&&Math.abs(from.z-to.z)<.001f) {
                float fraction=from.y/(from.y-to.y);return new Hit(-1,from.clone().interpolateLocal(to,fraction),Vector3f.UNIT_Y,fraction);
            }
            if(wall||hideTarget&&to.distanceSquared(positions[1])<.01f)return new Hit(-1,from.clone().interpolateLocal(to,.5f),Vector3f.UNIT_Z,.5f);
            return null;
        }
        public Hit sweep(Vector3f from,Vector3f to,float radius,int ignored,float stepStart,float stepEnd){return null;}
        public Hit staticSweep(Vector3f from,Vector3f to,float radius){return ceiling?new Hit(-1,from.add(0,2,0),Vector3f.UNIT_Y.negate(),.2f):null;}
        public boolean visible(Vector3f from,Vector3f to,int target){return !wall&&!(hideTarget&&target==1);}
        public float distanceToHull(int id,Vector3f point){return Math.max(0,positions[id].distance(point)-1);}
        public void impulse(int id,Vector3f linear,Vector3f torque,float cap){}
        public Vector3f closestHullPoint(int id,Vector3f from){return position(id);}
    }
}
