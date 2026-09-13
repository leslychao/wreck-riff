package game.wreckriff.presentation;

import com.jme3.asset.AssetManager;
import com.jme3.material.Material;
import com.jme3.math.*;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.*;
import game.wreckriff.combat.CombatSystem;
import game.wreckriff.combat.ProjectileState;
import java.util.*;

/** Shared immutable exported meshes; bounded per-session instances follow authoritative poses only. */
public final class OrdnancePresentation implements AutoCloseable {
    // Match CombatSystem's global budgets, without allocating or owning any simulation object.
    public static final int PROJECTILE_LIMIT=64,MINE_LIMIT=10;
    private static final float DETAIL_DISTANCE_SQUARED=42*42;
    private final Node root=new Node("ordnance-models");
    private final EnumMap<OrdnanceStyle,Node> templates=new EnumMap<>(OrdnanceStyle.class);
    private final EnumMap<OrdnanceStyle,ArrayDeque<Item>> free=new EnumMap<>(OrdnanceStyle.class);
    private final Map<Long,Item> projectiles=new HashMap<>(),mines=new HashMap<>();
    private final Material armedSignal,safeSignal;
    private int allocated;
    private boolean closed;
    private static final class Item {
        final OrdnanceStyle style;
        final Node model;
        final Spatial high,low,signal;
        Item(OrdnanceStyle style,Node model) {
            this.style=style;this.model=model;high=model.getChild("detail");low=model.getChild("distance");signal=model.getChild("signal");
        }
    }
    public OrdnancePresentation(AssetManager assets,Node parent) {
        for(var style:OrdnanceStyle.values()) {
            // AssetManager caches these recipes across retries. clone(false) shares all materials and meshes.
            templates.put(style,style.load(assets));free.put(style,new ArrayDeque<>());
        }
        armedSignal=signal(assets,new ColorRGBA(1,.13f,.025f,1));
        safeSignal=signal(assets,new ColorRGBA(.24f,.14f,.025f,1));
        root.setShadowMode(RenderQueue.ShadowMode.Receive);parent.attachChild(root);
    }
    private static Material signal(AssetManager assets,ColorRGBA color) {
        Material result=new Material(assets,"Common/MatDefs/Misc/Unshaded.j3md");
        result.setColor("Color",color);result.setColor("GlowColor",color.mult(.45f));return result;
    }
    public void update(List<ProjectileState> states,List<CombatSystem.MineView> mineStates,Vector3f observer) {
        if(closed)return;
        // Retire before acquisition so a saturated stream reuses slots in the same frame.
        retireMissing(projectiles,states.stream().limit(PROJECTILE_LIMIT).map(ProjectileState::id).toList());
        retireMissing(mines,mineStates.stream().limit(MINE_LIMIT).map(CombatSystem.MineView::id).toList());
        int count=0;
        for(var state:states) {
            if(count++>=PROJECTILE_LIMIT)break;
            var style=OrdnanceStyle.of(state.kind());Item item=obtain(projectiles,state.id(),style);
            item.model.setLocalTranslation(state.position());item.model.setLocalRotation(flightRotation(state.direction()));
            detail(item,observer);
        }
        count=0;
        for(var state:mineStates) {
            if(count++>=MINE_LIMIT)break;
            Item item=obtain(mines,state.id(),OrdnanceStyle.MINE);
            Vector3f normal=state.normal().normalize();
            item.model.setLocalTranslation(state.position().add(normal.mult(.015f)));
            item.model.setLocalRotation(surfaceRotation(normal));
            ((Geometry)item.signal).setMaterial(state.armed()?armedSignal:safeSignal);
            detail(item,observer);
        }
    }
    private Item obtain(Map<Long,Item> active,long id,OrdnanceStyle style) {
        Item item=active.get(id);
        if(item!=null&&item.style!=style){release(item);active.remove(id);item=null;}
        if(item==null) {
            item=free.get(style).pollFirst();
            if(item==null) {
                // Bound retained nodes across family changes as well as concurrent live IDs.
                if(allocated>=PROJECTILE_LIMIT+MINE_LIMIT)evictFree();
                Node model=templates.get(style).clone(false);model.setName(style.kind()+"-instance");
                item=new Item(style,model);allocated++;
            }
            active.put(id,item);root.attachChild(item.model);
        }
        return item;
    }
    private void evictFree() {
        for(var pool:free.values())if(!pool.isEmpty()){pool.removeFirst();allocated--;return;}
        throw new IllegalStateException("Ordnance visual pool budget exhausted");
    }
    private void retireMissing(Map<Long,Item> active,List<Long> live) {
        for(var it=active.entrySet().iterator();it.hasNext();) {
            var entry=it.next();if(!live.contains(entry.getKey())){release(entry.getValue());it.remove();}
        }
    }
    private void release(Item item){item.model.removeFromParent();free.get(item.style).addLast(item);}
    private static void detail(Item item,Vector3f observer) {
        boolean near=item.model.getLocalTranslation().distanceSquared(observer)<DETAIL_DISTANCE_SQUARED;
        item.high.setCullHint(near?Spatial.CullHint.Dynamic:Spatial.CullHint.Always);
        item.low.setCullHint(near?Spatial.CullHint.Always:Spatial.CullHint.Dynamic);
    }
    public static Quaternion flightRotation(Vector3f direction) {
        Vector3f heading=direction.lengthSquared()<.000001f?Vector3f.UNIT_Z:direction.normalize();
        return new Quaternion().lookAt(heading,Math.abs(heading.y)>.98f?Vector3f.UNIT_Z:Vector3f.UNIT_Y);
    }
    public static Quaternion surfaceRotation(Vector3f normal) {
        Vector3f n=normal.lengthSquared()<.000001f?Vector3f.UNIT_Y:normal.normalize(),axis=Vector3f.UNIT_Y.cross(n);
        return axis.lengthSquared()<.00001f?new Quaternion().fromAngleAxis(n.y<0?FastMath.PI:0,Vector3f.UNIT_X):
                new Quaternion().fromAngleAxis(FastMath.acos(Math.clamp(n.y,-1,1)),axis.normalizeLocal());
    }
    /** Exact nozzle position on the exported model, used by the existing particle emitter. */
    public static Vector3f trailAnchor(String kind,Vector3f centre,Vector3f direction) {
        return centre.add(flightRotation(direction).mult(new Vector3f(0,0,OrdnanceStyle.of(kind).nozzleZ())));
    }
    public int projectileCount(){return projectiles.size();}
    public int mineCount(){return mines.size();}
    int allocatedCount(){return allocated;}
    Node instance(long id,boolean mine){Item item=(mine?mines:projectiles).get(id);return item==null?null:item.model;}
    @Override public void close() {
        if(closed)return;closed=true;root.removeFromParent();root.detachAllChildren();projectiles.clear();mines.clear();
        free.values().forEach(ArrayDeque::clear);templates.clear();allocated=0;
    }
}
