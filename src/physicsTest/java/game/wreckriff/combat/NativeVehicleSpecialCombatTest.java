package game.wreckriff.combat;

import com.jme3.bullet.collision.shapes.BoxCollisionShape;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import game.wreckriff.config.Configs;
import game.wreckriff.config.VehicleDefinition;
import game.wreckriff.config.VehicleRules;
import game.wreckriff.input.VehicleCommand;
import game.wreckriff.simulation.GameEvent;
import game.wreckriff.simulation.MatchSession;
import game.wreckriff.simulation.PhysicsWorld;
import game.wreckriff.simulation.VehicleState;
import game.wreckriff.vehicle.VehicleController;
import org.junit.jupiter.api.Test;
import java.util.*;
import static game.wreckriff.combat.CombatRules.ticks;
import static org.junit.jupiter.api.Assertions.*;

/** Real Bullet collision/visibility/constraints plus the common fixed-step damage pipeline. */
class NativeVehicleSpecialCombatTest {
    private static final VehicleRules VEHICLES=VehicleRules.load();
    private static final CombatRules COMBAT=Configs.load("combat",CombatRules.class);
    private static final VehicleCommand GAS=new VehicleCommand(1,0,0,false,false,false,false,null,0,false,false,AbilityId.NONE);
    private static final VehicleCommand TURBO=new VehicleCommand(1,0,0,false,true,false,false,null,0,false,false,AbilityId.NONE);

    @Test void balancedNativeRosterUsesTheSameFullHpAndMassForEveryDriver() {
        for(var definition:VehicleDefinition.values())try(var fight=new Fight(definition.id(),definition.id())) {
            fight.place(0,0,0,0);fight.place(1,0,10,0);fight.settle();
            for(int id=0;id<2;id++) {
                assertEquals(definition.maximumHp(),fight.session.vehicle(id).hp);
                assertEquals(definition.profile(VEHICLES).mass(),fight.world.mass(id));
                assertEquals(4,fight.world.supportedWheelContacts(id));
            }
        }
    }

    @Test void pulseHitsRealHullsWithExactMassResponseAndShieldReductionAfterWindup() {
        for(var definition:VehicleDefinition.values())for(boolean shield:List.of(false,true))
            try(var fight=new Fight("rivet",definition.id())) {
                fight.place(0,0,0,0);fight.place(1,0,9,0);fight.settle();
                float initialHp=fight.target().hp;if(shield)fight.target().shieldTicks=200;
                fight.tick(Map.of(0,ability(AbilityId.SPECIAL)));fight.idle(34);
                assertEquals(initialHp,fight.target().hp);
                fight.tick();assertEquals(initialHp-(shield?18:60),fight.target().hp,.01f);
                var delta=fight.world.velocity(1).subtract(fight.velocityBeforeDamage.get(1));
                float expected=Math.min(8800/fight.world.mass(1),9)*(shield?.3f:1);
                assertEquals(expected,delta.clone().setY(0).length(),.015f,definition+" shield="+shield);
                assertEquals(0,delta.y,.001f);assertEquals(1645,fight.player().abilityCooldown(AbilityId.SPECIAL));
            }
    }

    @Test void pulseCannotDamageThroughAWallOrAnUpperDeckInsideItsRange() {
        for(boolean deck:List.of(false,true))try(var fight=new Fight("rivet","rivet")) {
            fight.place(0,0,0,0);
            if(deck) {
                fight.box("upper-deck",new Vector3f(5,.25f,5),new Vector3f(0,2.75f,9));
                fight.place(1,0,9,3);
            } else {
                fight.place(1,0,9,0);
                fight.box("wall",new Vector3f(5,3,.2f),new Vector3f(0,3,5));
            }
            fight.settle();
            Vector3f intake=fight.world.position(0).add(fight.world.rotation(0).mult(fight.world.profile(0).grinderIntake()));
            assertTrue(fight.world.distanceToHull(1,intake)<12,"Blocked target must be in range");
            fight.tick(Map.of(0,ability(AbilityId.SPECIAL)));fight.idle(35);
            assertEquals(800,fight.target().hp,deck?"Deck blocks pulse":"Wall blocks pulse");
            assertTrue(fight.damage("pulse",1)==0);assertFalse(fight.player().specialActive());
            assertTrue(fight.player().abilityCooldown(AbilityId.SPECIAL)>0);
        }
    }

    @Test void nativeGrinderHoldsForFullOnePointFiveSecondsDeals120AndKeepsTurboDisabled() {
        try(var fight=new Fight("grinder","rivet")) {
            fight.prepareCapture();fight.capture();
            assertEquals(1,fight.world.grabCount());float turbo=fight.player().turbo;
            for(int tick=1;tick<180;tick++) {
                assertEquals(0,fight.target().grabbedBy,"Capture tick "+tick);
                assertTrue(fight.world.touchingVehicles(0,1),"Native contact tick "+tick);
                fight.tick(Map.of(0,TURBO));
                assertTrue(fight.world.velocity(0).clone().setY(0).length()<=12.1f);
            }
            assertEquals(120,fight.damage("grinder",1),.02f);
            assertFalse(fight.player().specialActive());assertEquals(-1,fight.target().grabbedBy);
            assertEquals(0,fight.world.grabCount());assertEquals(360,fight.target().controlImmunityTicks);
            assertFalse(fight.controllers.get(0).turboActive());assertEquals(turbo,fight.player().turbo,.001f);
            float delivered=fight.damage("grinder",1);fight.idle(20);
            assertEquals(delivered,fight.damage("grinder",1));
        }
    }

    @Test void grinderRoofGunsActuallyDamageTheCapturedHullAndVictimCanStillFire() {
        try(var fight=new Fight("grinder","rivet")) {
            fight.prepareCapture();fight.capture();
            assertTrue(fight.world.muzzle(0).y>fight.world.position(1).y+fight.world.profile(1).hullBounds().maxY(),
                    "This fixture exercises aiming down from above the captured car");
            fight.tick(Map.of(0,fire(true,true),1,fire(true,true)));
            for(int tick=0;tick<30&&fight.damage("power",1)==0;tick++)fight.tick(Map.of(0,GAS));
            assertTrue(fight.damage("machine-gun",1)>0,"Roof machine gun must hit the real captured hull");
            assertTrue(fight.damage("power",1)>=50,"Selected Power must hit the real captured hull");
            for(int id=0;id<2;id++) {
                final int driver=id;
                assertTrue(fight.events.stream().anyMatch(e->e.type()==GameEvent.Type.SHOT&&e.sourceId()==driver&&e.kind().equals("machine-gun")));
                assertTrue(fight.events.stream().anyMatch(e->e.type()==GameEvent.Type.SHOT&&e.sourceId()==driver&&e.kind().equals("power")));
                assertEquals(COMBAT.power().initialAmmo()-1,fight.session.vehicle(id).weapon(WeaponType.POWER).ammo);
            }
            assertTrue(fight.damage("grinder",1)<=120.02f);
        }
    }

    @Test void shieldReleaseRemovesTheNativeJointBeforeThisTicksContactDamage() {
        try(var fight=new Fight("grinder","rivet")) {
            fight.prepareCapture();fight.capture();float damage=fight.damage("grinder",1);
            fight.tick(Map.of(0,GAS,1,ability(AbilityId.SHIELD)));
            assertEquals(0,fight.world.grabCount());assertEquals(-1,fight.target().grabbedBy);
            assertFalse(fight.player().specialActive());assertEquals(damage,fight.damage("grinder",1));
            assertEquals(360,fight.target().controlImmunityTicks);assertTrue(fight.target().shieldTicks>0);
            assertTrue(fight.player().abilityCooldown(AbilityId.SPECIAL)>0);
        }
    }

    @Test void realFreezeBoltCannotStackOnCaptureAndFrozenHullCannotBeCaptured() {
        for(boolean freezeFirst:List.of(false,true))try(var fight=new Fight("grinder","rivet","rivet")) {
            fight.prepareCapture();
            fight.place(2,0,18,0,new Quaternion().fromAngleAxis((float)Math.PI,Vector3f.UNIT_Y));fight.settle();
            if(freezeFirst) {
                fight.tick(Map.of(2,ability(AbilityId.FREEZE)));
                for(int tick=0;tick<60&&fight.target().frozenTicks==0;tick++)fight.tick();
                assertTrue(fight.target().frozenTicks>0,"Freeze bolt must strike the native victim hull");
                assertEquals(1,fight.world.immobilizerCount());
                fight.tick(Map.of(0,ability(AbilityId.SPECIAL)));fight.idle(71);
                for(int tick=0;tick<90&&fight.damage("grinder",1)==0;tick++)fight.tick(Map.of(0,GAS));
                assertTrue(fight.damage("grinder",1)>0,"Teeth still damage frozen hull on native contact");
                assertEquals(-1,fight.target().grabbedBy);assertEquals(0,fight.world.grabCount());
                assertTrue(fight.target().frozenTicks>0);
            } else {
                fight.capture();fight.tick(Map.of(0,GAS,2,ability(AbilityId.FREEZE)));
                for(int tick=0;tick<60&&fight.combat.projectiles().stream().anyMatch(p->p.kind().equals("freeze"));tick++)fight.tick(Map.of(0,GAS));
                assertTrue(fight.events.stream().anyMatch(e->e.type()==GameEvent.Type.IMPACT&&e.kind().equals("freeze")&&e.subjectId()==1),
                        "Freeze must hit the held hull rather than miss");
                assertEquals(0,fight.target().frozenTicks);assertEquals(0,fight.target().grabbedBy);
                assertEquals(0,fight.world.immobilizerCount());assertEquals(1,fight.world.grabCount());
            }
        }
    }

    @Test void sparkBombUsesRoadFalloffAndHalfSelfDamageWhileWallsAndDeckBlockOtherHulls() {
        try(var fight=new Fight("spark","rivet","rivet","rivet")) {
            fight.box("deck",new Vector3f(5,.25f,5),new Vector3f(0,2.25f,0));
            fight.box("wall",new Vector3f(5,1.2f,.1f),new Vector3f(0,1.2f,2.5f));
            fight.box("dash-stop",new Vector3f(.1f,1,1.5f),new Vector3f(-1.6f,1,0));
            fight.place(0,0,0,0);fight.place(1,3,0,0);fight.place(2,0,0,2.5f);fight.place(3,0,5,0);fight.settle();
            fight.tick(Map.of(0,ability(AbilityId.SPECIAL)));assertEquals(1,fight.combat.specialBombs().size());
            var bomb=fight.combat.specialBombs().getFirst();var center=bomb.position().add(bomb.normal().mult(.08f));
            assertEquals(0,bomb.position().y,.02f,"Bomb is supported by the lower road");
            fight.idle(82);for(var state:fight.session.vehicles)assertEquals(state.maximumHp,state.hp);
            assertTrue(fight.world.distanceToHull(2,center)<4,"Upper car would be damaged without deck occlusion");
            assertTrue(fight.world.distanceToHull(3,center)<4,"Wall must block an in-range hull");
            fight.blastCenter=center;fight.tick();
            float own=70*Math.max(0,1-fight.distanceBeforeDamage.get(0)/4)*.5f;
            float nearby=70*Math.max(0,1-fight.distanceBeforeDamage.get(1)/4);
            assertTrue(own>0,"Stopped dodge remains within its own explosion");assertTrue(nearby>0);
            assertEquals(own,fight.damage("special-bomb",0),.02f);assertEquals(nearby,fight.damage("special-bomb",1),.02f);
            assertEquals(800,fight.session.vehicle(2).hp);assertEquals(800,fight.session.vehicle(3).hp);
            assertTrue(fight.combat.specialBombs().isEmpty());assertFalse(fight.world.dashActive(0));
            assertTrue(fight.player().abilityCooldown(AbilityId.SPECIAL)>0);
        }
    }

    private static VehicleCommand ability(AbilityId id){return new VehicleCommand(0,0,0,false,false,false,false,null,0,false,false,id);}
    private static VehicleCommand fire(boolean machineGun,boolean weapon){return new VehicleCommand(1,0,0,false,false,machineGun,weapon,WeaponType.POWER,0,false,false,AbilityId.NONE);}

    private static final class Fight implements AutoCloseable {
        final MatchSession session;final CombatSystem combat;final PhysicsWorld world;
        final Map<Integer,VehicleController> controllers=new LinkedHashMap<>();final List<GameEvent> events=new ArrayList<>();
        final Map<Integer,Vector3f> velocityBeforeDamage=new HashMap<>();final Map<Integer,Float> distanceBeforeDamage=new HashMap<>();
        Vector3f blastCenter;
        Fight(String... profiles) {
            session=MatchSession.balanced(991,List.of(profiles),COMBAT);combat=new CombatSystem(session,COMBAT);world=new PhysicsWorld(VEHICLES);
            box("road",new Vector3f(200,.5f,200),new Vector3f(0,-.5f,0));
            for(var state:session.vehicles) {
                var profile=VehicleDefinition.forId(state.profileId).profile(VEHICLES);
                world.addVehicle(state.id,new Vector3f(80+state.id*12,profile.roadOffset()+.4f,80),new Quaternion(),profile);
                controllers.put(state.id,new VehicleController(world,state,VEHICLES));
            }
        }
        VehicleState player(){return session.vehicle(0);}VehicleState target(){return session.vehicle(1);}
        void box(String name,Vector3f half,Vector3f center){world.addStatic(name,new BoxCollisionShape(half),center,new Quaternion());}
        void place(int id,float x,float z,float roadY){place(id,x,z,roadY,new Quaternion());}
        void place(int id,float x,float z,float roadY,Quaternion rotation){world.teleport(id,new Vector3f(x,roadY+world.profile(id).roadOffset()+.4f,z),rotation);}
        void settle(){for(int tick=0;tick<360;tick++)world.step();for(var state:session.vehicles)assertEquals(4,world.supportedWheelContacts(state.id));}
        void prepareCapture(){place(0,0,0,0);place(1,0,6.5f,0);settle();}
        void capture() {
            assertFalse(world.touchingVehicles(0,1));tick(Map.of(0,ability(AbilityId.SPECIAL)));idle(ticks(SpecialRules.GRINDER_WINDUP)-1);
            for(int tick=0;tick<240&&target().grabbedBy<0;tick++)tick(Map.of(0,GAS));
            assertEquals(0,target().grabbedBy,"Truck must drive into an actual successful capture");
            assertTrue(world.touchingVehicles(0,1));assertTrue(world.grabIntact(0,1));
        }
        void idle(int count){for(int tick=0;tick<count;tick++)tick();}
        void tick(){tick(Map.of());}
        void tick(Map<Integer,VehicleCommand> commands) {
            combat.beginTick(commands,world);
            for(var state:session.vehicles)controllers.get(state.id).drive(commands.getOrDefault(state.id,VehicleCommand.NONE));
            world.step();velocityBeforeDamage.clear();distanceBeforeDamage.clear();
            for(var state:session.vehicles) {
                velocityBeforeDamage.put(state.id,world.velocity(state.id));
                if(blastCenter!=null)distanceBeforeDamage.put(state.id,world.distanceToHull(state.id,blastCenter));
            }
            combat.advanceProjectiles(world);
            for(var ram:world.rams())combat.queueRam(ram.first(),ram.second(),ram.closingSpeed(),ram.point(),ram.normal());
            combat.advanceSpecials(world);combat.resolveDamage(world);
            events.addAll(combat.drainEvents());session.tick++;
        }
        float damage(String cause,int target){return (float)events.stream().filter(e->e.type()==GameEvent.Type.DAMAGE&&e.subjectId()==target&&e.kind().equals(cause)).mapToDouble(GameEvent::value).sum();}
        @Override public void close(){combat.clear();world.close();}
    }
}
