package game.wreckriff.combat;

import com.jme3.math.*;
import game.wreckriff.arena.*;
import game.wreckriff.config.*;
import game.wreckriff.input.VehicleCommand;
import game.wreckriff.simulation.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real combat/schedule transaction ordering; geometry is the explicit WorldQuery fixture. */
class ArenaShieldBombTest {
    record Result(boolean shieldDestroyed,ArenaSystems.HazardPhase phase,List<Float> hazardDamage,long explosions) {}

    @Test void aDueBombDestroysTheControlShieldBeforeTheSameTickElectricDamageIsChosen() {
        var baseline=run(false);
        assertFalse(baseline.shieldDestroyed());assertEquals(ArenaSystems.HazardPhase.ACTIVE,baseline.phase());
        assertEquals(List.of(32f),baseline.hazardDamage(),"The control run must reach the exact electric impact tick");
        var bomb=run(true);
        assertTrue(bomb.shieldDestroyed());assertEquals(ArenaSystems.HazardPhase.OFF,bomb.phase());
        assertTrue(bomb.hazardDamage().isEmpty(),"The bomb must cancel the pending hazard before its damage is queued");
        assertEquals(1,bomb.explosions(),"Prepare and final damage resolution must advance a due bomb only once");
    }

    private Result run(boolean placeBomb) {
        var definition=ArenaRegistry.load().definition("doomsday_arena");
        var session=new MatchSession(32,definition,MatchSession.Mode.ARENA,Configs.load("combat",CombatRules.class),
                UUID.randomUUID(),false,0,"spark");
        session.phase=MatchSession.Phase.ARENA_COMBAT;
        for(var state:session.vehicles){state.protectionTicks=0;if(state.id>1)state.hp=0;}
        var arena=new ArenaSystems(session,definition);var combat=new CombatSystem(session,session.combatRules);
        combat.configureArenaDamage(arena::damageTargets,arena::damageObject);
        var shield=definition.destructibles().stream().filter(o->o.id().equals("control-shield-south")).findFirst().orElseThrow();
        var box=definition.boxes().stream().filter(b->b.id().equals(shield.geometryId())).findFirst().orElseThrow();
        var world=new BombWorld(box.center().vector().setY(0).add(-box.size().x()/2-.8f,0,0));
        arena.damageObject(shield.geometryId(),0,shield.maximumHp()-1,"fixture-prior-damage",-1);
        // Let the shipped fixed-tick scheduler choose and warn about the electrical sector.
        for(int tick=0;tick<30000&&arena.hazardPhase("show-electric")!=ArenaSystems.HazardPhase.ACTIVE;tick++) {
            arena.updateHazard(world,(id,amount,cause,event)->fail("No participant is inside a sector yet"));session.tick++;
        }
        assertEquals(ArenaSystems.HazardPhase.ACTIVE,arena.hazardPhase("show-electric"));
        var hazard=definition.hazards().stream().filter(h->h.id().equals("show-electric")).findFirst().orElseThrow();
        world.victim=new Vector3f((hazard.minX()+hazard.maxX())/2,.5f,(hazard.minZ()+hazard.maxZ())/2);
        List<Float> damage=new ArrayList<>();long explosions=0;
        int fuseTicks=CombatRules.ticks(SpecialRules.BOMB_FUSE);
        for(int exposure=1;exposure<=hazard.damageIntervalTicks();exposure++) {
            boolean plant=placeBomb&&exposure==hazard.damageIntervalTicks()-fuseTicks+1;
            var command=plant?new VehicleCommand(0,0,0,false,false,false,false,null,0,false,false,AbilityId.SPECIAL):VehicleCommand.NONE;
            combat.beginTick(Map.of(0,command),world);combat.advanceProjectiles(world);
            combat.prepareArenaDamage(world);
            arena.updateHazard(world,(id,amount,cause,event)->damage.add(amount));
            combat.resolveDamage(world);combat.resolveArenaDamage();
            explosions+=combat.drainEvents().stream().filter(e->e.type()==GameEvent.Type.EXPLOSION&&e.kind().equals("special-bomb")).count();
            session.tick++;
        }
        return new Result(arena.objectState(shield.id()).destroyed(),arena.hazardPhase(hazard.id()),List.copyOf(damage),explosions);
    }

    static final class BombWorld implements WorldQuery {
        final VehicleProfile spark=VehicleProfile.player("spark",VehicleRules.load());
        final Vector3f support;Vector3f victim=new Vector3f(1000,0,1000);
        BombWorld(Vector3f support){this.support=support;}
        public Vector3f position(int id){return id==0?support.add(0,.5f,0):id==1?victim.clone():new Vector3f(1000+100*id,0,1000);}
        public Vector3f velocity(int id){return new Vector3f();}public Quaternion rotation(int id){return new Quaternion();}
        public boolean grounded(int id){return true;}public float mass(int id){return 1100;}
        public VehicleProfile profile(int id){return id==0?spark:VehicleProfile.rivet();}
        public Hit ray(Vector3f from,Vector3f to,int ignored){return null;}
        public Hit sweep(Vector3f a,Vector3f b,float radius,int id,float start,float end){return null;}
        public Hit staticSweep(Vector3f a,Vector3f b,float radius){return null;}
        public boolean visible(Vector3f from,Vector3f to,int id){return true;}
        public float distanceToHull(int id,Vector3f point){return position(id).distance(point);}
        public Vector3f closestHullPoint(int id,Vector3f point){return position(id);}
        public void impulse(int id,Vector3f linear,Vector3f torque,float maximum){}
        public Support support(Vector3f from,float depth){return new Support(0,support,Vector3f.UNIT_Y);}
    }
}
