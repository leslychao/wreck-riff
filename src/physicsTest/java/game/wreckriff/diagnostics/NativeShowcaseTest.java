package game.wreckriff.diagnostics;

import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import game.wreckriff.arena.*;
import game.wreckriff.config.VehicleRules;
import game.wreckriff.simulation.*;
import org.junit.jupiter.api.Test;
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
            for(int step=0;step<CombatShowcase.SECONDS*120;step++) {
                assertEquals(MatchSession.Outcome.NONE,session.outcome,"Demo must retain a living player");
                showcase.accept(runtime.tick(showcase.commands(),false));
                if(session.tick==1980) {
                    var target=session.vehicle(1);
                    assertTrue(target.frozenTicks>0,"Freeze capture must show an active native freeze");
                    assertEquals(target.maximumHp*.5f,target.hp,.001f,"Freeze must be shown over damaged panels");
                }
                if(session.tick==3481)assertTrue(world.position(0).distance(world.position(2))>6,
                        "Cannon shooter must not overlap the earlier shield-test car");
            }
            assertTrue(showcase.complete());
            assertTrue(showcase.demonstrated(),()->showcase.evidence().toString());
            assertFalse(world.containsVehicle(1));assertFalse(runtime.hasWrecks());
        }
    }
}
