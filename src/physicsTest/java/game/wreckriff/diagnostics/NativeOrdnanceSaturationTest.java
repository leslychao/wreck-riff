package game.wreckriff.diagnostics;

import com.jme3.asset.DesktopAssetManager;
import com.jme3.scene.Node;
import game.wreckriff.combat.*;
import game.wreckriff.presentation.CombatVisuals;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeOrdnanceSaturationTest {
    @Test void ordinaryNativeCommandsReachBothCapsRenderEveryStateAndPauseWithoutSpendingAmmoOrTime() {
        var assets=new DesktopAssetManager(true);Node scene=new Node();
        for(int retry=0;retry<2;retry++)try(var rig=new NativeOrdnanceSaturationRig();var visuals=new CombatVisuals(assets,scene,rig.world)) {
            int nativeBodies=rig.world.bodyCount();
            for(int tick=0;tick<600&&!rig.saturated();tick++)visuals.accept(rig.step(true));
            assertTrue(rig.saturated(),()->rig.evidence().toString());
            assertTrue(rig.projectileDenials()>0,"The next simultaneous shot must be rejected by the real cap");
            assertTrue(rig.mineDenials()>0,"The 11th placement must be rejected by the real cap");
            assertEquals(rig.rules.maximumProjectiles(),rig.evidence().get("homingShotEvents"));
            assertEquals(rig.rules.mine().maximumActive(),rig.evidence().get("minePlacedEvents"));
            assertEquals(rig.session.vehicles.size()*rig.rules.homing().initialAmmo()-rig.rules.maximumProjectiles(),rig.evidence().get("homingAmmoRemaining"));
            assertEquals(rig.session.vehicles.size()*rig.rules.mine().initialAmmo()-rig.rules.mine().maximumActive(),rig.evidence().get("mineAmmoRemaining"));
            long tick=rig.session.tick;var original=rig.combat.projectiles().stream().map(ProjectileState::position).toList();
            for(int frame=0;frame<120;frame++)render(rig,visuals,0);
            assertEquals(tick,rig.session.tick);assertEquals(original,rig.combat.projectiles().stream().map(ProjectileState::position).toList());
            assertEquals(rig.rules.maximumProjectiles(),visuals.projectileCount());
            assertEquals(rig.rules.maximumProjectiles()+rig.rules.mine().maximumActive(),((Node)scene.getChild("ordnance-models")).getQuantity());
            assertEquals(nativeBodies,rig.world.bodyCount(),"Detailed ordnance presentation must not create native bodies");
            for(int i=0;i<CombatRules.ticks(rig.rules.mine().lifetimeSeconds())+2;i++)rig.step(false);
            render(rig,visuals,0);assertTrue(rig.combat.projectiles().isEmpty());assertTrue(rig.combat.mines().isEmpty());
            assertEquals(0,((Node)scene.getChild("ordnance-models")).getQuantity());
            assertTrue(rig.session.vehicles.stream().allMatch(v->v.hp==v.maximumHp),"The firing lanes must remain independent and clear");
        }
        assertNull(scene.getChild("ordnance-models"),"Retry must detach the previous presentation root");
    }
    static void render(NativeOrdnanceSaturationRig rig,CombatVisuals visuals,float dt) {
        visuals.update(rig.combat.projectiles(),rig.combat.mines(),rig.combat.fireZones(),rig.combat.ballisticWarnings(),rig.session,dt);
    }
}
