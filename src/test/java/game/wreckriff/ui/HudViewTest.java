package game.wreckriff.ui;

import com.jme3.asset.DesktopAssetManager;
import com.jme3.font.BitmapText;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.scene.VertexBuffer;
import game.wreckriff.combat.AbilityId;
import game.wreckriff.combat.WeaponType;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HudViewTest {
    private static final DesktopAssetManager ASSETS=new DesktopAssetManager(true);
    private HudView.Snapshot snapshot(boolean lock,List<RadarProjection.Target> targets) {
        var weapons=new EnumMap<WeaponType,HudView.WeaponStatus>(WeaponType.class);
        for(WeaponType weapon:WeaponType.values())weapons.put(weapon,new HudView.WeaponStatus(weapon==WeaponType.MINE?0:4,weapon==WeaponType.CANNON?1.2f:0,2.4f));
        return new HudView.Snapshot(new HudView.Vitals(520,800,.6f,HudView.Effect.FROZEN),WeaponType.CANNON,weapons,
                Map.of(AbilityId.FREEZE,new HudView.AbilityStatus(0,8,10),AbilityId.SHIELD,new HudView.AbilityStatus(2,14,16)),
                "4 RIVALS",null,lock,"Cannon / RMB","",new RadarProjection.Observer(0,0,new RadarProjection.Heading(0,1),0),targets);
    }
    @Test void slotStatesUseRealIconsAndCooldownShadeStaysBelowReadableNumbers() {
        try(var view=new HudView(ASSETS,new Node())) {
            view.resize(640,480,.8f);view.update(snapshot(false,List.of()));
            Node root=view.root();
            for(WeaponType weapon:WeaponType.values())assertInstanceOf(Geometry.class,root.getChild("weapon-"+weapon.id()+"-icon"));
            assertNotEquals(Spatial.CullHint.Always,root.getChild("weapon-cannon-selected").getCullHint());
            assertEquals(Spatial.CullHint.Always,root.getChild("weapon-homing-selected").getCullHint());
            assertEquals("0",((BitmapText)root.getChild("weapon-mine-ammo")).getText());
            assertEquals("1.2",((BitmapText)root.getChild("weapon-cannon-cooldown-text")).getText());
            assertNotEquals(Spatial.CullHint.Always,root.getChild("ability-shield-active").getCullHint());
            Spatial plate=root.getChild("weapon-cannon-plate"),shade=root.getChild("weapon-cannon-cooldown-shade"),number=root.getChild("weapon-cannon-ammo");
            assertTrue(shade.getLocalTranslation().z>plate.getLocalTranslation().z);
            assertTrue(shade.getLocalTranslation().z<number.getLocalTranslation().z);
            root.depthFirstTraversal(spatial->{if(spatial instanceof BitmapText text)assertTrue(text.getSize()>=14,text.getName());});
        }
    }
    @Test void lockReticleRemainsCenteredAndResizePreservesSnapshot() {
        try(var view=new HudView(ASSETS,new Node())) {
            for(boolean locked:new boolean[]{false,true}) {
                view.resize(1920,1080,1);view.update(snapshot(locked,List.of()));view.root().updateGeometricState();
                var center=view.root().getChild("target-reticle").getWorldBound().getCenter();
                assertEquals(960,center.x,.001);assertEquals(540,center.y,.001);
            }
            view.resize(640,480,1);view.root().updateGeometricState();
            assertEquals("520 / 800",((BitmapText)view.root().getChild("health-value")).getText());
            assertEquals(3,view.layout().weaponColumns());
        }
    }
    @Test void arbitraryRosterKeepsMarkerIdentityAndRemovesDeadParticipants() {
        try(var view=new HudView(ASSETS,new Node())) {
            view.resize(1920,1080,1);
            var targets=new ArrayList<RadarProjection.Target>();
            for(int id=1;id<=9;id++)targets.add(new RadarProjection.Target(id,id*2,20,0,true,id==9,id==4));
            view.update(snapshot(true,targets));assertEquals(9,view.radarMarkerCount());
            Spatial retained=view.root().getChild("participant:9");
            targets.removeFirst();view.update(snapshot(true,targets));
            assertEquals(8,view.radarMarkerCount());assertNull(view.root().getChild("participant:1"));
            assertSame(retained,view.root().getChild("participant:9"));
            view.setVisible(false);assertEquals(Spatial.CullHint.Always,view.root().getCullHint());
        }
    }
    @Test void originalWeaponAndAbilityMeshesAreDistinctAndFinite() {
        var icons=new VectorIcons();var signatures=new HashSet<List<Float>>();
        for(VectorIcons.Icon icon:VectorIcons.Icon.values()) {
            var mesh=icons.mesh(icon);assertTrue(mesh.getTriangleCount()>0,icon.name());
            FloatBuffer data=(FloatBuffer)mesh.getBuffer(VertexBuffer.Type.Position).getData();
            var values=new ArrayList<Float>();for(int i=0;i<data.limit();i++){float value=data.get(i);assertTrue(Float.isFinite(value));values.add(value);}
            assertTrue(signatures.add(values),"Repeated icon geometry: "+icon);assertSame(mesh,icons.mesh(icon));
        }
    }
}
