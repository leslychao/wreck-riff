package game.wreckriff.presentation;

import com.jme3.asset.AssetManager;
import com.jme3.material.*;
import com.jme3.math.*;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.*;
import com.jme3.scene.shape.Cylinder;
import game.wreckriff.arena.*;
import game.wreckriff.simulation.*;
import game.wreckriff.ui.VectorIcons;
import java.util.*;
import java.util.function.BiConsumer;

/** Session-owned road pickups. Authority remains in ArenaSystems; animation only reads the simulation clock. */
public final class PickupPresentation implements AutoCloseable {
    public record Frame(boolean available,boolean modelVisible,float height,float scale,float yaw,boolean collected) {}
    private static final float BOB=.15f,BOB_PERIOD=2.4f,TURN_PERIOD=12,SHRINK_SECONDS=.2f,SPAWN_SECONDS=.25f;
    private final Node root=new Node("animated-pickups");
    private final Map<String,Item> items=new LinkedHashMap<>();
    private final LinkedHashSet<Long> seen=new LinkedHashSet<>();
    private final MatchSession session;
    private final ArenaSystems systems;
    private final BiConsumer<String,Vector3f> burst;
    private boolean glow=true;
    private static final class Item {
        final ArenaDefinition.Pickup definition;
        final Node holder;
        final Spatial model;
        final Material signal;
        final PickupStyle style;
        final float phase;
        boolean available;
        long collectedTick=-1000,spawnedTick=-1000;
        Frame frame;
        Item(ArenaDefinition.Pickup definition,Node holder,Spatial model,Material signal,boolean available) {
            this.definition=definition;this.holder=holder;this.model=model;this.signal=signal;this.available=available;
            style=PickupStyle.of(definition.type());phase=(definition.id().hashCode()&0x7fffffff)%1009/1009f;
        }
    }
    public PickupPresentation(AssetManager assets,Node parent,MatchSession session,ArenaDefinition arena,
                              ArenaSystems systems,BiConsumer<String,Vector3f> burst) {
        this.session=Objects.requireNonNull(session);this.systems=Objects.requireNonNull(systems);this.burst=Objects.requireNonNull(burst);
        var materials=new SurfaceMaterials(assets);var icons=new VectorIcons();
        Mesh rimMesh=ring(.91f,1.02f),baseMesh=new Cylinder(2,24,1.08f,.08f,true);
        com.jme3.util.mikktspace.MikktspaceTangentGenerator.generate(baseMesh);
        for(var definition:arena.pickups()) {
            var style=PickupStyle.of(definition.type());Node holder=new Node("pickup-"+definition.id());
            holder.setLocalTranslation(definition.position().vector());root.attachChild(holder);
            Geometry base=new Geometry("pickup-base",baseMesh);base.rotate(FastMath.HALF_PI,0,0);
            base.setLocalTranslation(0,.045f,0);base.setMaterial(materials.material("black"));holder.attachChild(base);
            Material signal=new Material(assets,"Common/MatDefs/Misc/Unshaded.j3md");signal.setColor("Color",style.color());
            signal.getAdditionalRenderState().setFaceCullMode(RenderState.FaceCullMode.Off);
            Geometry rim=new Geometry("pickup-rim",rimMesh);rim.setLocalTranslation(0,.092f,0);rim.setMaterial(signal);holder.attachChild(rim);
            Geometry symbol=new Geometry("pickup-symbol",icons.mesh(style.icon()));symbol.setMaterial(signal);
            symbol.rotate(-FastMath.HALF_PI,0,0);symbol.setLocalScale(.75f);symbol.setLocalTranslation(-.375f,.1f,.375f);holder.attachChild(symbol);
            Spatial model=assets.loadModel(style.model());model.setName("pickup-item");holder.attachChild(model);
            model.setShadowMode(RenderQueue.ShadowMode.CastAndReceive);
            items.put(definition.id(),new Item(definition,holder,model,signal,systems.active(definition.id())));
        }
        root.setShadowMode(RenderQueue.ShadowMode.Off);parent.attachChild(root);update(0);
    }
    public Node root(){return root;}
    public Frame frame(String id){return Objects.requireNonNull(items.get(id),"Unknown pickup "+id).frame;}
    public void setGlow(boolean enabled){glow=enabled;}
    public void accept(List<GameEvent> events) {
        for(var event:events) {
            if(event.type()!=GameEvent.Type.PICKUP||event.value()<=0||!session.sessionId.equals(event.sessionId()))continue;
            var item=items.get(event.objectId());
            if(item==null)throw new IllegalArgumentException("Pickup event references missing socket: "+event.objectId());
            if(!item.style.kind().equals(event.kind()))throw new IllegalArgumentException("Pickup event type mismatch: "+event.objectId());
            if(!seen.add(event.eventId()))continue;
            if(seen.size()>256)seen.remove(seen.iterator().next());
            item.collectedTick=session.tick;item.available=false;
            burst.accept(event.kind(),event.position());
        }
    }
    /** alpha is the interpolation fraction of the existing fixed step; paused calls retain alpha/tick. */
    public void update(float alpha) {
        double interpolatedTick=session.tick+Math.clamp(alpha,0,1);
        double seconds=interpolatedTick/MatchSession.TICKS_PER_SECOND;
        for(var item:items.values()) {
            boolean active=systems.active(item.definition.id());
            if(active&&!item.available)item.spawnedTick=session.tick;
            item.available=active;
            float collectedAge=(float)((interpolatedTick-item.collectedTick)/MatchSession.TICKS_PER_SECOND);
            float spawnAge=(float)((interpolatedTick-item.spawnedTick)/MatchSession.TICKS_PER_SECOND);
            boolean collecting=!active&&interpolatedTick>=item.collectedTick&&interpolatedTick-item.collectedTick<24;
            float scale=active?Math.clamp(spawnAge/SPAWN_SECONDS,.08f,1):collecting?1-collectedAge/SHRINK_SECONDS:0;
            float height=1.1f+BOB*FastMath.sin(FastMath.TWO_PI*((float)seconds/BOB_PERIOD+item.phase));
            float yaw=FastMath.TWO_PI*((float)(seconds%TURN_PERIOD)/TURN_PERIOD+item.phase);
            item.model.setLocalTranslation(0,height,0);item.model.setLocalScale(scale);
            item.model.setLocalRotation(new Quaternion().fromAngleAxis(yaw,Vector3f.UNIT_Y));
            item.model.setCullHint(scale>0?Spatial.CullHint.Inherit:Spatial.CullHint.Always);
            ColorRGBA color=item.style.color();
            item.signal.setColor("Color",active?color:color.mult(.2f));
            item.signal.setColor("GlowColor",glow&&active?color.mult(.45f):ColorRGBA.Black);
            item.frame=new Frame(active,scale>0,height,scale,yaw,collecting);
        }
    }
    private static Mesh ring(float inner,float outer) {
        float[] points=new float[32*18];int n=0;
        for(int i=0;i<32;i++) {
            float a=i*FastMath.TWO_PI/32,b=(i+1)*FastMath.TWO_PI/32;
            for(float[] p:new float[][]{{inner,a},{outer,a},{outer,b},{inner,a},{outer,b},{inner,b}}) {
                points[n++]=p[0]*FastMath.cos(p[1]);points[n++]=0;points[n++]=p[0]*FastMath.sin(p[1]);
            }
        }
        Mesh mesh=new Mesh();mesh.setBuffer(VertexBuffer.Type.Position,3,points);mesh.updateBound();mesh.setStatic();return mesh;
    }
    @Override public void close(){root.removeFromParent();root.detachAllChildren();items.clear();seen.clear();}
}
