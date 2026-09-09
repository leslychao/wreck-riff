package game.wreckriff.simulation;

import com.jme3.math.*;
import com.jme3.renderer.Camera;
import game.wreckriff.arena.*;
import game.wreckriff.combat.*;
import game.wreckriff.config.*;
import game.wreckriff.diagnostics.ArtShowcase;
import game.wreckriff.presentation.ArenaPresentation;
import com.jme3.scene.Node;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Verifies scripted drives against the real roads. It does not substitute for the required real-window capture. */
class NativeArtShowcaseTest {
    @ParameterizedTest
    @ValueSource(strings={"dead-air-yard","construction_17","neon_zero","euphoria_park","ash_necropolis","doomsday_arena"})
    void allEightPickupsComeFromActualRuntimeDrivesAndLaunchesUseNativePhysics(String arenaId) {
        var arena=ArenaRegistry.load().definition(arenaId);var rules=VehicleRules.load();
        var session=new MatchSession(42,arena,arena.bosses().isEmpty()?MatchSession.Mode.LEGACY:MatchSession.Mode.ARENA,
                Configs.load("combat",CombatRules.class));
        try(var world=new PhysicsWorld(rules)) {
            var content=new ArenaFactory(NativeArenaAssets.MANAGER).build(arena);
            for(var body:content.bodies())world.addStatic(body.id(),body.shape(),body.position(),body.rotation());
            var spawns=arena.shuffledSpawns(42);
            for(var state:session.vehicles) {
                var spawn=spawns.get(state.id);var profile=VehicleProfile.rivet(rules);
                world.addVehicle(state.id,spawn.position().vector().add(0,profile.roadOffset(),0),
                        new Quaternion().fromAngleAxis(spawn.yawDegrees()*FastMath.DEG_TO_RAD,Vector3f.UNIT_Y),profile);
            }
            try(var runtime=new MatchRuntime(session,world,arena,content.graph(),rules)) {
                for(int tick=0;tick<360;tick++)world.step();
                var showcase=new ArtShowcase(session,world,runtime,arena);List<GameEvent> pickups=new ArrayList<>();
                var presentation=ArenaPresentation.attach(NativeArenaAssets.MANAGER,content.visual(),session,arena,runtime.arenaSystems());
                boolean sawCompression=false,sawLaunch=false;
                Camera camera=new Camera(1280,720);
                for(int tick=0;tick<ArtShowcase.SECONDS*MatchSession.TICKS_PER_SECOND&&!showcase.complete();tick++) {
                    var events=runtime.tick(showcase.commands(),false);showcase.accept(events);
                    presentation.update(0);
                    for(var pad:arena.launchPads()) {
                        Node marker=(Node)content.visual().getChild("launch-pad-"+pad.id());
                        String phase=marker.getUserData("launchPhase");
                        sawCompression|="COMPRESSING".equals(phase);sawLaunch|="LAUNCHED".equals(phase);
                        float actual=0;for(var vehicle:session.vehicles)actual=Math.max(actual,runtime.arenaSystems().launches().compression(pad.id(),vehicle.id));
                        assertEquals(actual,(Float)marker.getUserData("launchCompression"),.00001f);
                    }
                    events.stream().filter(e->e.type()==GameEvent.Type.PICKUP&&e.subjectId()==0).forEach(pickups::add);
                    if(tick%2==0)showcase.frame(camera);
                    assertEquals(MatchSession.Outcome.NONE,session.outcome,"Diagnostic preparation must keep the match alive");
                }
                assertTrue(showcase.demonstrated(),()->showcase.evidence().toString());
                assertEquals(8,pickups.size(),"Exactly the eight intended pickup types");
                assertEquals(8,pickups.stream().map(GameEvent::kind).distinct().count());
                assertTrue(pickups.stream().allMatch(e->e.sessionId().equals(session.sessionId)&&e.objectId()!=null&&e.value()>0));
                assertEquals(1,pickups.stream().filter(e->e.kind().equals("cannon-ammo")).findFirst().orElseThrow().value());
                assertEquals(WeaponType.HOMING,session.vehicle(0).selectedWeapon,"Pickups do not auto-select another weapon");
                if(!arena.launchPads().isEmpty()) {assertTrue(sawCompression);assertTrue(sawLaunch);}
            }
        }
    }
}
