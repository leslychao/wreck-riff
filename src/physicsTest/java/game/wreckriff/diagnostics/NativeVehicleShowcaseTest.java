package game.wreckriff.diagnostics;

import com.jme3.math.*;
import com.jme3.renderer.Camera;
import game.wreckriff.arena.*;
import game.wreckriff.combat.CombatRules;
import game.wreckriff.config.*;
import game.wreckriff.simulation.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Executes the entire staging script on the real arena. Real-window capture is still a separate requirement. */
class NativeVehicleShowcaseTest {
    @Test void allThreeSpecialsProduceRealRuntimeDamageAndNativeMovement() {
        var arena=ArenaRegistry.load().definition("dead-air-yard");var rules=VehicleRules.load();
        var session=new MatchSession(42,arena,MatchSession.Mode.LEGACY,Configs.load("combat",CombatRules.class));
        try(var world=new PhysicsWorld(rules)) {
            var content=new ArenaFactory(NativeArenaAssets.MANAGER).build(arena);
            for(var body:content.bodies())world.addStatic(body.id(),body.shape(),body.position(),body.rotation());
            var spawns=arena.shuffledSpawns(42);
            for(var state:session.vehicles) {
                var spawn=spawns.get(state.id);var profile=VehicleDefinition.forId(state.profileId).profile(rules);
                world.addVehicle(state.id,spawn.position().vector().add(0,profile.roadOffset(),0),
                        new Quaternion().fromAngleAxis(spawn.yawDegrees()*FastMath.DEG_TO_RAD,Vector3f.UNIT_Y),profile);
            }
            try(var runtime=new MatchRuntime(session,world,arena,content.graph(),rules)) {
                for(int tick=0;tick<360;tick++)world.step();
                var showcase=new VehicleShowcase(session,world,runtime,arena);var camera=new Camera(1280,720);
                Set<String> captures=new LinkedHashSet<>();List<GameEvent> actual=new ArrayList<>();
                for(int tick=0;tick<VehicleShowcase.SECONDS*MatchSession.TICKS_PER_SECOND;tick++) {
                    var events=runtime.tick(showcase.commands(),false);showcase.accept(events);actual.addAll(events);
                    if(tick%2==0) {String capture=showcase.frame(camera);if(capture!=null)assertTrue(captures.add(capture),"Captures must be unique");}
                    assertEquals(MatchSession.Outcome.NONE,session.outcome,()->showcase.evidence().toString());
                }
                assertTrue(showcase.complete());assertTrue(showcase.demonstrated(),()->showcase.evidence().toString());
                assertTrue(captures.containsAll(Set.of("vehicle-rivet-exterior","vehicle-grinder-exterior","vehicle-spark-exterior",
                        "vehicle-rivet-pulse-hit","vehicle-grinder-capture","vehicle-grinder-weapon-hit","vehicle-spark-dash","vehicle-spark-bomb-hit")),captures.toString());
                assertEquals(3,actual.stream().filter(e->e.type()==GameEvent.Type.SPECIAL_STARTED).count());
                assertTrue(actual.stream().filter(e->e.type()==GameEvent.Type.SPECIAL_STARTED||e.type()==GameEvent.Type.GRAB_STARTED)
                        .allMatch(e->e.sessionId().equals(session.sessionId)));
                assertTrue(actual.stream().anyMatch(e->e.type()==GameEvent.Type.GRAB_STARTED));
                assertTrue(actual.stream().anyMatch(e->e.type()==GameEvent.Type.DAMAGE&&e.kind().equals("special-bomb")&&e.value()>0));
                assertTrue(showcase.label().contains("постановочные"));
            }
        }
    }
}
