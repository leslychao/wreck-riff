package game.wreckriff.arena;

import com.jme3.math.Vector3f;
import game.wreckriff.simulation.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ArenaRulesTest {
    private final ArenaDefinition arena=ArenaDefinition.load();
    @Test void configuredMaximumHealthAndRepairFractionAreAuthoritativeForBothDrivers() {
        var json=game.wreckriff.config.Configs.gson().toJsonTree(game.wreckriff.config.Configs.load("combat",game.wreckriff.combat.CombatRules.class)).getAsJsonObject();
        json.getAsJsonObject("health").addProperty("playerMaximumHp",1000);
        json.getAsJsonObject("health").addProperty("botMaximumHp",600);
        json.getAsJsonObject("health").addProperty("repairFraction",.2);
        var rules=game.wreckriff.config.Configs.gson().fromJson(json,game.wreckriff.combat.CombatRules.class);
        for(int id:new int[]{0,1}) {
            MatchSession session=new MatchSession(42,360,rules);TestWorld world=new TestWorld();ArenaSystems systems=new ArenaSystems(session,arena);
            assertEquals(id==0?1000:600,session.vehicle(id).maximumHp);
            session.vehicle(id).hp=100;world.positions[id]=new Vector3f(-44,.8f,22);
            systems.collectPickups(world);assertEquals(id==0?300:220,session.vehicle(id).hp,.001f);
        }
    }

    @Test void authoredGraphHasBothSurfaceCorrectEntrancesAndConnectedPickups() {
        NavGraph graph=new NavGraph(arena);
        assertTrue(graph.nodes().size()<=48);
        assertEquals(graph.nodes().size(),graph.reachable(0).size());
        List<Integer> southern=graph.path(30,33,false,8,150),northern=graph.path(36,33,false,8,150);
        assertTrue(southern.containsAll(List.of(31,32)));
        assertTrue(northern.containsAll(List.of(34,35)));
        assertEquals(33,graph.nearest(new Vector3f(54,6.8f,0)));
        assertNotEquals(33,graph.nearest(new Vector3f(54,.8f,0)));
        for (var spawn:arena.spawns()) for (var pickup:arena.pickups()) {
            assertFalse(graph.path(graph.nearest(spawn.position().vector()),graph.nearest(pickup.position().vector()),false,8,150).isEmpty());
        }
        assertEquals(arena.shuffledSpawns(42),arena.shuffledSpawns(42));
        assertEquals(5,new HashSet<>(arena.shuffledSpawns(42)).size());
    }
    @Test void activeHazardChangesRouteWithoutInventingFloorConnections() {
        NavGraph graph=new NavGraph(arena);
        assertTrue(graph.path(17,25,false,8,150).contains(39));
        assertFalse(graph.path(17,25,true,8,150).contains(39));
        assertTrue(graph.crossesHazard(new Vector3f(0,0,-36),new Vector3f(0,0,-12)));
        assertFalse(graph.crossesHazard(new Vector3f(0,6,-36),new Vector3f(0,6,-12)));
        assertFalse(graph.crossesHazard(new Vector3f(-20,0,-36),new Vector3f(-20,0,-12)));
    }
    @Test void geometryUsesExactSharedRampDeckTopAndWideOpenGarage() {
        var deck=arena.boxes().stream().filter(b->b.id().equals("upper-deck")).findFirst().orElseThrow();
        float top=deck.center().y()+deck.size().y()/2;
        assertEquals(6,top);
        assertEquals(top,arena.ramps().getFirst().endY());
        assertEquals(top,arena.ramps().getLast().startY());
        for (var ramp:arena.ramps()) assertTrue(ramp.maxX()-ramp.minX()>=10);
        // Neither the garage interior nor the under-deck drive is represented by a solid enclosing box.
        for (Vector3f point:List.of(new Vector3f(-44,1,22),new Vector3f(54,1,0))) {
            assertTrue(arena.boxes().stream().filter(ArenaDefinition.BoxPart::collision).noneMatch(b->inside(point,b)));
        }
    }
    private static boolean inside(Vector3f p,ArenaDefinition.BoxPart b) {
        return Math.abs(p.x-b.center().x())<b.size().x()/2 && Math.abs(p.y-b.center().y())<b.size().y()/2
                && Math.abs(p.z-b.center().z())<b.size().z()/2;
    }
    @Test void repairIsAtomicAndTieBreaksByVehicleId() {
        MatchSession session=new MatchSession(42,360);
        ArenaSystems systems=new ArenaSystems(session,arena); TestWorld world=new TestWorld();
        world.positions[0]=new Vector3f(-44,.8f,22); world.positions[1]=world.positions[0].clone();
        session.vehicle(0).hp=100; session.vehicle(1).hp=100;
        systems.collectPickups(world); systems.collectPickups(world);
        assertEquals(300,session.vehicle(0).hp); assertEquals(100,session.vehicle(1).hp);
        assertFalse(systems.active("repair-garage"));
        var events=systems.drainEvents(); assertEquals(1,events.size()); assertEquals("repair",events.getFirst().kind());
        assertTrue(systems.drainEvents().isEmpty());
        session.tick=2999; systems.collectPickups(world); assertEquals(300,session.vehicle(0).hp);
        session.tick=3000; systems.collectPickups(world); assertEquals(500,session.vehicle(0).hp);
    }
    @Test void fullDeadProtectedOccludedAndWrongFloorVehiclesCannotCollect() {
        for (String situation:List.of("full","dead","protected","wall","floor")) {
            MatchSession session=new MatchSession(1,360); ArenaSystems systems=new ArenaSystems(session,arena); TestWorld world=new TestWorld();
            world.positions[0]=new Vector3f(54,6.8f,0); session.vehicle(0).hp=100;
            switch(situation) {
                case "full" -> session.vehicle(0).hp=session.vehicle(0).maximumHp;
                case "dead" -> session.vehicle(0).hp=0;
                case "protected" -> session.vehicle(0).protectionTicks=1;
                case "wall" -> world.wall=true;
                case "floor" -> world.positions[0].y=.8f;
            }
            systems.collectPickups(world);
            assertTrue(systems.active("repair-deck"),situation);
            assertTrue(systems.drainEvents().isEmpty(),situation);
        }
    }
    @Test void hazardNeedsACompleteContinuousIntervalAndDoesNotTouchUpperFloor() {
        MatchSession session=new MatchSession(7,360); ArenaSystems systems=new ArenaSystems(session,arena); TestWorld world=new TestWorld();
        world.positions[0]=new Vector3f(0,.8f,-20); world.positions[1]=new Vector3f(0,6.8f,-20);
        List<Integer> targets=new ArrayList<>(); List<Long> ids=new ArrayList<>();
        ArenaSystems.DamageSink sink=(id,amount,cause,event)-> { targets.add(id);ids.add(event);assertEquals(5,amount);assertEquals("hazard",cause); };
        session.tick=1019; assertEquals(ArenaSystems.HazardPhase.OFF,systems.hazardPhase());
        session.tick=1020; assertEquals(ArenaSystems.HazardPhase.WARNING,systems.hazardPhase());
        for (long tick=1200;tick<1229;tick++) { session.tick=tick; systems.updateHazard(world,sink); }
        assertTrue(targets.isEmpty()); session.tick=1229; systems.updateHazard(world,sink); systems.updateHazard(world,sink);
        assertEquals(List.of(0),targets); assertEquals(Long.MIN_VALUE+1229*16,ids.getFirst());
        world.positions[0].x=20; session.tick=1230; systems.updateHazard(world,sink);
        world.positions[0].x=0;
        for (long tick=1231;tick<1260;tick++) { session.tick=tick; systems.updateHazard(world,sink); }
        assertEquals(1,targets.size()); session.tick=1260; systems.updateHazard(world,sink); assertEquals(2,targets.size());
        session.tick=1440; assertEquals(ArenaSystems.HazardPhase.OFF,systems.hazardPhase());
    }
}
