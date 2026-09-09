package game.wreckriff.presentation;

import com.jme3.asset.AssetManager;
import com.jme3.math.*;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.ViewPort;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.*;
import com.jme3.scene.control.AbstractControl;
import game.wreckriff.arena.ArenaDefinition;
import game.wreckriff.arena.ArenaSystems;
import game.wreckriff.simulation.MatchSession;
import game.wreckriff.presentation.VehicleVisual.Builder;
import java.util.*;
import java.util.function.Supplier;

/** Renders the native owner's mechanical bodies. Never advances hazards, physics, or a visual motion clock. */
public final class ArenaMechanismPresentation extends AbstractControl implements AutoCloseable {
    private record Model(Node node,ArenaDefinition.HazardType type,Vector3f halfExtents) {}
    private final Node root=new Node("arena-mechanisms");
    private final MatchSession session;
    private final Supplier<List<ArenaSystems.MechanismView>> snapshots;
    private final Map<String,ArenaDefinition.Hazard> hazards=new LinkedHashMap<>();
    private final Map<String,Model> models=new LinkedHashMap<>();
    private final SurfaceMaterials materials;
    private final AssetManager assets;
    private boolean closed;

    public ArenaMechanismPresentation(AssetManager assets,Node parent,MatchSession session,
                                      ArenaDefinition definition,ArenaSystems systems) {
        this(assets,parent,session,definition,systems::mechanisms);
    }
    ArenaMechanismPresentation(AssetManager assets,Node parent,MatchSession session,
                              ArenaDefinition definition,Supplier<List<ArenaSystems.MechanismView>> snapshots) {
        this.assets=Objects.requireNonNull(assets);this.session=Objects.requireNonNull(session);
        this.snapshots=Objects.requireNonNull(snapshots);materials=new SurfaceMaterials(assets);
        for(var hazard:definition.hazards())if(mechanical(hazard.type()))hazards.put(hazard.id(),hazard);
        for(var hazard:hazards.values())if(hazard.type()==ArenaDefinition.HazardType.TRAFFIC)thresholds(hazard);
        root.setShadowMode(RenderQueue.ShadowMode.CastAndReceive);parent.attachChild(root);root.addControl(this);synchronize();
    }
    @Override protected void controlUpdate(float tpf) {synchronize();}
    @Override protected void controlRender(RenderManager manager,ViewPort view) {}

    /** Also callable immediately after checkpoint/geometry synchronization, including at an unchanged tick. */
    public void synchronize() {
        if(closed)return;
        Set<String> present=new HashSet<>();
        for(var view:snapshots.get()) {
            var hazard=hazards.get(view.hazardId());
            if(hazard==null||hazard.type()!=view.type())throw new IllegalArgumentException("Unknown mechanical hazard "+view.hazardId());
            if(!present.add(view.hazardId()))throw new IllegalArgumentException("Duplicate body for hazard "+view.hazardId());
            Vector3f half=view.halfExtents(),position=view.position();Quaternion rotation=view.rotation();
            if(!Vector3f.isValidVector(half)||half.x<=0||half.y<=0||half.z<=0||!Vector3f.isValidVector(position)
                    ||!Float.isFinite(rotation.norm())||rotation.norm()<.0001f)
                throw new IllegalArgumentException("Invalid native mechanism pose "+view.id());
            Model model=models.get(view.hazardId());
            if(model==null||model.type()!=view.type()||!model.halfExtents().equals(half)) {
                if(model!=null)model.node().removeFromParent();
                Node node=build(view,hazard);root.attachChild(node);
                model=new Model(node,view.type(),half.clone());models.put(view.hazardId(),model);
            }
            model.node().setName(view.id());model.node().setLocalTranslation(position);model.node().setLocalRotation(rotation);
            model.node().setCullHint(Spatial.CullHint.Inherit);model.node().setUserData("presentationTick",session.tick);
        }
        for(var entry:models.entrySet())if(!present.contains(entry.getKey()))entry.getValue().node().setCullHint(Spatial.CullHint.Always);
        root.setUserData("presentationTick",session.tick);
    }
    private Node build(ArenaSystems.MechanismView view,ArenaDefinition.Hazard hazard) {
        Node node=new Node(view.id());Vector3f half=view.halfExtents();
        node.setUserData("hazardId",view.hazardId());node.setUserData("mechanismType",view.type().name());
        node.setUserData("assetOrigin","original-java-procedural");node.setUserData("nativeHalfExtents",half.clone());
        switch(view.type()) {
            case CRANE -> crane(node,half);
            case CAROUSEL -> carousel(node,half);
            case TRAFFIC -> traffic(node,half,hazard);
            default -> throw new IllegalArgumentException("Non-mechanical body "+view.type());
        }
        return node;
    }
    private void crane(Node node,Vector3f h) {
        Builder load=new Builder(),frame=new Builder(),mark=new Builder();
        load.box(0,-h.y*.06f,0,h.x*.96f,h.y*.86f,h.z*.96f);
        // Layer seams and steel retaining bands make an eight-metre suspended load readable at arena scale.
        for(float y:new float[]{-.47f,.03f,.53f})for(int side:new int[]{-1,1}) {
            frame.box(side*h.x*.975f,y*h.y,0,h.x*.014f,h.y*.022f,h.z*.97f);
            frame.box(0,y*h.y,side*h.z*.975f,h.x*.97f,h.y*.022f,h.z*.014f);
        }
        for(int x:new int[]{-1,1})for(int z:new int[]{-1,1}) {
            frame.box(x*h.x*.975f,-h.y*.02f,z*h.z*.90f,h.x*.017f,h.y*.90f,h.z*.050f);
            frame.box(x*h.x*.68f,h.y*.88f,z*h.z*.52f,h.x*.045f,h.y*.10f,h.z*.04f);
            mark.box(x*h.x*.82f,-h.y*.06f,z*h.z*.985f,h.x*.075f,h.y*.25f,h.z*.006f);
        }
        load.attach(node,"load-concrete",materials.material("concrete"));frame.attach(node,"load-bands",materials.material("steel"));
        mark.attach(node,"load-markings",materials.material("yellow"));
    }
    private void carousel(Node node,Vector3f h) {
        Builder beam=new Builder(),padding=new Builder(),drive=new Builder();
        beam.box(0,0,0,h.x*.985f,h.y*.64f,h.z*.69f);
        for(int side:new int[]{-1,1}) {
            padding.box(side*h.x*.82f,0,0,h.x*.18f,h.y*.95f,h.z*.95f);
            beam.box(0,side*h.y*.72f,side*h.z*.60f,h.x*.78f,h.y*.055f,h.z*.065f);
        }
        // The pivot is part of the same native moving arm, not a second independently simulated mechanism.
        drive.box(0,0,0,Math.min(.65f,h.x*.12f),h.y*.98f,h.z*.98f);
        for(int stripe=-6;stripe<=6;stripe++) {
            float x=stripe*h.x*.10f;
            drive.box(x,h.y*.705f,0,h.x*.011f,h.y*.012f,h.z*.58f);
        }
        beam.attach(node,"carousel-arm",materials.material("steel"));padding.attach(node,"carousel-end-pads",materials.material("faded-red"));
        drive.attach(node,"carousel-drive",materials.material("black"));
    }
    private void traffic(Node node,Vector3f half,ArenaDefinition.Hazard hazard) {
        // Author a service van with +Z nose, then orient its mesh inside the native X-long body.
        float w=half.z,h=half.y,l=half.x;
        Builder body=new Builder(),metal=new Builder(),dark=new Builder(),glass=new Builder(),lamps=new Builder();
        body.loft(new float[][]{{-l*.97f,w*.83f,-h*.60f,h*.18f},{-l*.82f,w*.94f,-h*.62f,h*.32f},
                {l*.74f,w*.91f,-h*.62f,h*.30f},{l*.98f,w*.76f,-h*.49f,h*.02f}});
        body.loft(new float[][]{{-l*.74f,w*.78f,h*.22f,h*.86f},{-l*.62f,w*.79f,h*.22f,h*.95f},
                {l*.20f,w*.73f,h*.20f,h*.95f},{l*.49f,w*.81f,h*.20f,h*.28f}});
        glass.quad(v(-w*.68f,h*.30f,l*.477f),v(w*.68f,h*.30f,l*.477f),v(w*.64f,h*.91f,l*.20f),v(-w*.64f,h*.91f,l*.20f));
        for(int side:new int[]{-1,1}) {
            glass.box(side*w*.793f,h*.57f,-l*.28f,w*.005f,h*.23f,l*.25f);
            metal.box(side*w*.94f,-h*.03f,-l*.05f,w*.022f,h*.045f,l*.75f);
            metal.box(side*w*.945f,h*.15f,-l*.31f,w*.012f,h*.017f,l*.11f);
            dark.box(side*w*.80f,h*.59f,l*.28f,w*.10f,h*.055f,l*.035f);
            lamps.box(side*w*.56f,-h*.12f,l*.978f,w*.16f,h*.06f,l*.010f);
            for(float z:new float[]{-l*.63f,l*.61f}) {
                dark.cylinderX(side*w*.86f,-h*.62f,z,h*.35f,w*.17f,14);
                metal.cylinderX(side*w*.955f,-h*.62f,z,h*.19f,w*.025f,10);
            }
        }
        metal.box(0,-h*.43f,l*.98f,w*.81f,h*.047f,l*.02f);
        dark.box(0,-h*.10f,l*.993f,w*.28f,h*.075f,l*.007f);
        dark.box(0,h*.55f,-l*.753f,w*.49f,h*.20f,l*.009f);
        Node shell=new Node("service-vehicle-shell");
        boolean alongX=hazard.maxX()-hazard.minX()>=hazard.maxZ()-hazard.minZ();
        shell.setLocalRotation(new Quaternion().fromAngleAxis(alongX?FastMath.HALF_PI:-FastMath.HALF_PI,Vector3f.UNIT_Y));
        body.attach(shell,"service-body",materials.material("yellow"));metal.attach(shell,"service-metal",materials.material("steel"));
        dark.attach(shell,"service-rubber",materials.rubber());glass.attach(shell,"service-glass",SurfaceMaterials.lit(assets,new ColorRGBA(.022f,.052f,.065f,1),85,.25f));
        lamps.attach(shell,"service-headlights",materials.material("light-amber"));node.attachChild(shell);
    }
    private void thresholds(ArenaDefinition.Hazard hazard) {
        boolean alongX=hazard.maxX()-hazard.minX()>=hazard.maxZ()-hazard.minZ();
        float cx=(hazard.minX()+hazard.maxX())/2,cz=(hazard.minZ()+hazard.maxZ())/2;
        float distance=Math.min(64,(alongX?hazard.maxX()-hazard.minX():hazard.maxZ()-hazard.minZ())-8);
        float start=-distance/2,end=start+hazard.activeTicks()*MatchSession.DT*16;
        for(int endpoint=0;endpoint<2;endpoint++) {
            float along=endpoint==0?start:end;Node marker=new Node("service-"+(endpoint==0?"entry-":"exit-")+hazard.id());
            marker.setLocalTranslation(cx+(alongX?along:0),hazard.minY()+.108f,cz+(alongX?0:along));
            if(!alongX)marker.setLocalRotation(new Quaternion().fromAngleAxis(FastMath.HALF_PI,Vector3f.UNIT_Y));
            Builder paint=new Builder();
            for(int stripe=-3;stripe<=3;stripe++)paint.box(0,0,stripe*.55f,.30f,.004f,.17f);
            paint.attach(marker,"service-threshold",materials.material("ivory"));marker.setShadowMode(RenderQueue.ShadowMode.Off);
            marker.setUserData("hazardId",hazard.id());root.attachChild(marker);
        }
    }
    private static boolean mechanical(ArenaDefinition.HazardType type) {
        return type==ArenaDefinition.HazardType.CRANE||type==ArenaDefinition.HazardType.CAROUSEL||type==ArenaDefinition.HazardType.TRAFFIC;
    }
    private static Vector3f v(float x,float y,float z) {return new Vector3f(x,y,z);}
    @Override public void close() {
        if(closed)return;closed=true;root.removeControl(this);root.removeFromParent();root.detachAllChildren();models.clear();
    }
}
