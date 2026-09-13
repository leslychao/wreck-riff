package game.wreckriff.combat;

import com.jme3.asset.AssetManager;
import com.jme3.math.Vector3f;
import com.jme3.scene.Node;
import game.wreckriff.config.Configs;
import game.wreckriff.presentation.OrdnancePresentation;
import game.wreckriff.presentation.OrdnanceStyle;
import java.util.*;

/** Diagnostic pose fixtures only: the real presentation at configured limits, without combat emission or physics. */
public final class OrdnanceInspectionScene implements AutoCloseable {
    private final CombatRules rules=Configs.load("combat",CombatRules.class);
    private final List<ProjectileState> projectiles=new ArrayList<>();
    private final List<CombatSystem.MineView> mines=new ArrayList<>();
    private final OrdnancePresentation presentation;
    private int frames,maximumProjectiles,maximumMines;
    private boolean cleared;

    public OrdnanceInspectionScene(AssetManager assets,Node parent) {
        if(rules.maximumProjectiles()>OrdnancePresentation.PROJECTILE_LIMIT||rules.mine().maximumActive()>OrdnancePresentation.MINE_LIMIT)
            throw new IllegalStateException("Inspection exceeds presentation budgets");
        presentation=new OrdnancePresentation(assets,parent);
        for(int i=0;i<rules.maximumProjectiles();i++)projectiles.add(new ProjectileState(i+1,0,
                OrdnanceStyle.values()[i%7].kind(),new Vector3f(),Vector3f.UNIT_Z,120,-1));
    }
    public void update(float seconds,Vector3f observer) {
        for(int i=0;i<projectiles.size();i++) {
            var state=projectiles.get(i);float angle=seconds*1.4f+i*.6f;
            state.previousPosition.set(state.position);
            state.position.set((i%8-3.5f)*2.3f+(float)Math.sin(angle)*.45f,
                    1.3f+(i/8%2)*.65f+(float)Math.cos(angle)*.22f,(i/8-3.5f)*2.3f);
            state.direction.set((float)Math.cos(angle),-.35f*(float)Math.sin(angle),(float)Math.sin(angle)).normalizeLocal();
        }
        mines.clear();
        for(int i=0;i<rules.mine().maximumActive();i++)mines.add(new CombatSystem.MineView(1000+i,0,mineSupport(i),mineNormal(i),
                (i+(int)seconds)%2==0,rules.mine().triggerRadius()));
        presentation.update(projectiles,mines,observer);frames++;
        maximumProjectiles=Math.max(maximumProjectiles,presentation.projectileCount());maximumMines=Math.max(maximumMines,presentation.mineCount());
        if(presentation.projectileCount()!=rules.maximumProjectiles()||presentation.mineCount()!=rules.mine().maximumActive())
            throw new IllegalStateException("Saturated presentation lost a live fixture");
    }
    public int projectileLimit(){return rules.maximumProjectiles();}
    public int mineLimit(){return rules.mine().maximumActive();}
    public static Vector3f mineSupport(int index){return new Vector3f((index-4.5f)*2,0,11);}
    public static Vector3f mineNormal(int index){return new Vector3f(index%2==0?-.27f:.27f,1,.14f).normalizeLocal();}
    public void clear() {
        presentation.update(List.of(),List.of(),new Vector3f());
        cleared=presentation.projectileCount()==0&&presentation.mineCount()==0;
        if(!cleared)throw new IllegalStateException("Destroyed fixtures retained visible models");
    }
    public Map<String,Object> evidence() {
        return Map.of("source","Diagnostic pose fixtures; no CombatSystem emission, trajectory, damage or performance acceptance is claimed",
                "configuredProjectiles",rules.maximumProjectiles(),"configuredMines",rules.mine().maximumActive(),
                "maximumVisibleProjectiles",maximumProjectiles,"maximumVisibleMines",maximumMines,"renderFrames",frames,"cleared",cleared);
    }
    @Override public void close(){clear();presentation.close();}
}
