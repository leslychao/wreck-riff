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
class ArenaShortcutBombTest {
    @Test void dueBombOpensTheAuthoredShortcutBeforeTheSameTickEnvironmentAndAdvancesOnlyOnce() {
        var definition=ArenaRegistry.load().definition("construction_17");
        var session=new MatchSession(32,definition,MatchSession.Mode.ARENA,Configs.load("combat",CombatRules.class),
                UUID.randomUUID(),false,0,"spark");
        session.phase=MatchSession.Phase.ARENA_COMBAT;
        for(var state:session.vehicles){state.protectionTicks=0;if(state.id>0)state.hp=0;}
        var arena=new ArenaSystems(session,definition);var combat=new CombatSystem(session,session.combatRules);
        combat.configureArenaDamage(arena::damageTargets,arena::damageObject);
        var panel=definition.destructibles().stream().filter(o->o.id().equals("warehouse-service-gate")).findFirst().orElseThrow();
        assertTrue(definition.edges().stream().anyMatch(edge->edge.type()==ArenaDefinition.Transition.OPENABLE&&edge.objectId().equals(panel.id())),
                "The destroyed panel must open an authored shortcut");
        var box=definition.boxes().stream().filter(b->b.id().equals(panel.geometryId())).findFirst().orElseThrow();
        var world=new BombWorld(box.center().vector().add(box.rotation().mult(new Vector3f(-box.size().x()/2-.8f,-box.size().y()/2,0))));
        long explosions=0;
        int fuseTicks=CombatRules.ticks(SpecialRules.BOMB_FUSE);
        for(int tick=0;tick<=fuseTicks+2;tick++) {
            boolean plant=tick==0;
            var command=plant?new VehicleCommand(0,0,0,false,false,false,false,null,0,false,false,AbilityId.SPECIAL):VehicleCommand.NONE;
            combat.beginTick(Map.of(0,command),world);combat.advanceProjectiles(world);
            combat.prepareArenaDamage(world);
            if(tick<fuseTicks-1)assertFalse(arena.objectState(panel.id()).open(),"A live fuse cannot remove collision early");
            if(tick>=fuseTicks) {
                assertTrue(arena.objectState(panel.id()).open(),"Environment synchronization sees the open route in the due bomb tick");
                assertTrue(arena.damageTargets().stream().noneMatch(target->target.geometryId().equals(panel.geometryId())));
            }
            arena.updateHazard(world,(id,amount,cause,event)->fail("No hazard is active during the short fuse"));
            combat.resolveDamage(world);combat.resolveArenaDamage();
            explosions+=combat.drainEvents().stream().filter(e->e.type()==GameEvent.Type.EXPLOSION&&e.kind().equals("special-bomb")).count();
            session.tick++;
        }
        assertEquals(1,explosions,"Prepare and final damage resolution cannot advance a due bomb twice");
        assertEquals(1,arena.drainEvents().stream().filter(e->e.type()==GameEvent.Type.ARENA_OBJECT_DESTROYED).count());
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
