package game.wreckriff.diagnostics;

import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import game.wreckriff.arena.*;
import game.wreckriff.combat.WeaponType;
import game.wreckriff.config.VehicleRules;
import game.wreckriff.simulation.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Verifies that the video scenario actually produces its claimed native combat events. */
class NativeShowcaseTest {
    @Test void completeShowcaseContainsRealNewWeaponHitsAndExpiresTheLethalWreck() {
        var rules=VehicleRules.load();var arena=ArenaDefinition.load();var session=new MatchSession(42,360);
        var world=new PhysicsWorld(rules);
        var content=new ArenaFactory(NativeArenaAssets.MANAGER).build(arena);
        for(var body:content.bodies())world.addStatic(body.shape(),body.position(),body.rotation());
        for(int id=0;id<5;id++)world.addVehicle(id,new Vector3f(id*8,.85f,-55),new Quaternion());
        try(var runtime=new MatchRuntime(session,world,arena,content.graph(),rules)) {
            for(int step=0;step<360;step++)world.step();
            var showcase=new CombatShowcase(session,world,runtime.combat());
            for(var vehicle:session.vehicles)for(var slot:vehicle.weapons())
                assertEquals(slot.maximumAmmo,slot.ammo,"Only the diagnostic must stage sufficient real ammunition");
            var camera=new com.jme3.renderer.Camera(1280,720);boolean warningCaptured=false;
            Set<String> comboCaptures=new HashSet<>();
            Set<String> shots=new HashSet<>(),damagingWeapons=new HashSet<>();
            Set<Float> gallery=new HashSet<>();long minePlacedTick=-1;boolean mineDamaged=false;
            for(int step=0;step<CombatShowcase.SECONDS*120;step++) {
                assertEquals(MatchSession.Outcome.NONE,session.outcome,"Demo must retain a living player");
                if(session.tick<1440)gallery.add(showcase.displayHpFraction(session.vehicle(1)));
                var events=runtime.tick(showcase.commands(),false);showcase.accept(events);
                for(var event:events) {
                    assertNotEquals(GameEvent.Type.EMPTY,event.type(),"Diagnostic ammunition must sustain every intended attack");
                    if(event.type()==GameEvent.Type.SHOT) {
                        shots.add(event.kind());assertNotNull(event.emission(),event.kind());
                    }
                    if(event.type()==GameEvent.Type.DAMAGE&&event.sourceId()!=event.subjectId()&&event.value()>0)
                        damagingWeapons.add(event.kind());
                    if(event.type()==GameEvent.Type.MINE_PLACED)minePlacedTick=event.simulationTick();
                    if(event.type()==GameEvent.Type.DAMAGE&&event.kind().equals("mine")) {
                        assertTrue(minePlacedTick>=0&&event.simulationTick()>minePlacedTick);
                        // A legitimate radius blast may also damage its nearby owner.
                        if(event.subjectId()==2)mineDamaged=true;
                    }
                }
                String frame=showcase.frame(camera);
                if(frame!=null&&frame.startsWith("combo-"))comboCaptures.add(frame);
                if("ballistic-warning".equals(frame)) {
                    var warnings=runtime.combat().ballisticWarnings();
                    assertFalse(warnings.isEmpty(),"Warning capture requires a live authoritative warning");
                    assertTrue(camera.getLocation().distance(warnings.getFirst().point())<25,
                            "Camera follows the actual impact surface even when another car is targeted");
                    warningCaptured=true;
                }
                if(session.tick==1980) {
                    var target=session.vehicle(1);
                    assertTrue(target.frozenTicks>0,"Freeze capture must show an active native freeze");
                    assertEquals(target.maximumHp*.5f,target.hp,.001f,"Freeze must be shown over damaged panels");
                }
                if(session.tick==3481)assertTrue(world.position(0).distance(world.position(2))>6,
                        "Cannon shooter must not overlap the earlier shield-test car");
            }
            assertTrue(showcase.complete());
            assertTrue(warningCaptured,"Showcase must capture a visible ballistic warning");
            assertTrue(showcase.demonstrated(),()->showcase.evidence().toString());
            assertInstanceOf(Map.class,showcase.evidence().get("freezeBallisticCombo"));
            var combo=(Map<?,?>)showcase.evidence().get("freezeBallisticCombo");
            assertEquals(4,combo.get("damagingCharges"));
            assertEquals(true,combo.get("allHitsWhileFrozen"));assertEquals(true,combo.get("controlEnded"));
            assertEquals(Set.of("combo-freeze-launch","combo-freeze-flight","combo-freeze-hit",
                    "combo-ballistic-launch","combo-ballistic-warning","combo-ballistic-hit-1","combo-ballistic-hit-2",
                    "combo-ballistic-hit-3","combo-ballistic-hit-4","combo-freeze-ended"),comboCaptures);
            assertEquals(Set.of(1f,.75f,.5f,.25f,0f),gallery);
            assertTrue(shots.contains("machine-gun"));
            for(var weapon:WeaponType.values())if(weapon!=WeaponType.MINE)
                assertTrue(shots.contains(weapon.name().toLowerCase(Locale.ROOT)),weapon.name());
            assertTrue(damagingWeapons.containsAll(Set.of("machine-gun","power","napalm-fire","cannon","homing","mine")),
                    ()->"Actual target damage: "+damagingWeapons);
            assertTrue(mineDamaged,"The staged live target must take damage from entering the armed mine");
            assertFalse(world.containsVehicle(1));assertFalse(runtime.hasWrecks());
        }
    }
}
