package game.wreckriff.presentation;

import com.jme3.asset.AssetManager;
import com.jme3.material.*;
import com.jme3.math.*;
import com.jme3.renderer.*;
import com.jme3.scene.*;
import com.jme3.scene.control.AbstractControl;
import com.jme3.util.BufferUtils;
import game.wreckriff.arena.*;
import game.wreckriff.simulation.*;
import java.util.*;

/** Session-owned visual feedback; reads the arena phase and physics without advancing either. */
public final class ArenaPresentation extends AbstractControl {
    private final List<HazardVisual> visuals=new ArrayList<>();
    private final List<AnimatedDetail> details=new ArrayList<>();
    public static ArenaPresentation attach(AssetManager assets,Node arenaVisual,
            MatchSession session,ArenaDefinition definition,ArenaSystems arenaSystems) {
        ArenaPresentation presentation=new ArenaPresentation();
        for(var hazard:definition.hazards()) {
            HazardVisual visual=new HazardVisual(assets,session,hazard,arenaSystems,surfaceHeight(definition,hazard));
            presentation.visuals.add(visual);arenaVisual.attachChild(visual.decoration);
        }
        arenaVisual.depthFirstTraversal(spatial->{
            String motion=spatial.getUserData("artMotion");
            if(motion!=null&&spatial instanceof Node node)
                presentation.details.add(new AnimatedDetail(node,session,ArenaArt.Motion.valueOf(motion)));
        });
        arenaVisual.addControl(presentation);presentation.controlUpdate(0);return presentation;
    }
    @Override protected void controlUpdate(float dt) {
        for(var visual:visuals)visual.update(dt);
        for(var detail:details)detail.update();
    }
    @Override protected void controlRender(RenderManager manager,ViewPort view) {}
    /** Hazard minY is a trigger bound (commonly -0.1), not the rendered driving surface. */
    public static float surfaceHeight(ArenaDefinition definition,ArenaDefinition.Hazard hazard) {
        float x=(hazard.minX()+hazard.maxX())*.5f,z=(hazard.minZ()+hazard.maxZ())*.5f;
        float result=hazard.minY(),distance=Float.MAX_VALUE;
        for(var surface:definition.surfaces()) {
            for(var box:definition.boxes())if(box.id().equals(surface.geometryId())
                    &&Math.abs(x-box.center().x())<=box.size().x()/2&&Math.abs(z-box.center().z())<=box.size().z()/2) {
                float y=box.center().y()+box.size().y()/2,d=Math.abs(y-hazard.minY());
                if(d<distance&&d<=1) {result=y;distance=d;}
            }
            for(var ramp:definition.ramps())if(ramp.id().equals(surface.geometryId())&&ramp.containsXZ(x,z,0)) {
                float y=ramp.heightAt(x,z),d=Math.abs(y-hazard.minY());
                if(d<distance&&d<=1) {result=y;distance=d;}
            }
        }
        return result;
    }
    private static final class AnimatedDetail {
        private record LitPart(Geometry geometry,ColorRGBA color,Vector3f scale) {}
        private final Node node;
        private final MatchSession session;
        private final ArenaArt.Motion motion;
        private final float period,offset;
        private final Vector3f origin;
        private final List<LitPart> lights=new ArrayList<>();
        private long lastTick=Long.MIN_VALUE;
        private MatchSession.Phase lastPhase;
        private int lastBossMode;
        AnimatedDetail(Node node,MatchSession session,ArenaArt.Motion motion) {
            this.node=node;this.session=session;this.motion=motion;origin=node.getLocalTranslation().clone();
            period=node.getUserData("artPeriod");offset=node.getUserData("artPhase");
            node.depthFirstTraversal(spatial->{if(spatial instanceof Geometry geometry) {
                var color=geometry.getMaterial().getParam("Color");
                if(color!=null)lights.add(new LitPart(geometry,((ColorRGBA)color.getValue()).clone(),geometry.getLocalScale().clone()));
            }});
        }
        void update() {
            if(lastTick==session.tick&&lastPhase==session.phase&&lastBossMode==session.bossMode)return;
            lastTick=session.tick;lastPhase=session.phase;lastBossMode=session.bossMode;
            double turns=session.tick/(double)MatchSession.TICKS_PER_SECOND/period+offset;
            float cycle=(float)(turns-Math.floor(turns)),angle=FastMath.TWO_PI*cycle;
            switch(motion) {
                case STATIC -> {}
                case ROTATE_Z -> node.setLocalRotation(new Quaternion().fromAngleAxis(angle,Vector3f.UNIT_Z));
                case SWAY_Z -> node.setLocalRotation(new Quaternion().fromAngleAxis(.075f*FastMath.sin(angle),Vector3f.UNIT_Z));
                case STEAM -> {
                    node.setLocalTranslation(origin.x+cycle*.5f,origin.y+cycle*3.2f,origin.z);
                    node.setLocalScale(.7f+cycle*.65f);
                    float opacity=.13f*FastMath.sin(FastMath.PI*cycle);
                    for(var part:lights) {ColorRGBA color=part.color().clone();color.a=opacity;part.geometry().getMaterial().setColor("Color",color);}
                }
                case PULSE -> setLightIntensity(.84f+.16f*FastMath.sin(angle),false,angle);
                case SCREEN -> setLightIntensity(.82f+.12f*FastMath.sin(angle),true,angle);
            }
            node.setUserData("presentationTick",session.tick);
            node.setUserData("presentationPhase",session.phase.name());
        }
        private void setLightIntensity(float intensity,boolean screen,float angle) {
            boolean boss=session.phase==MatchSession.Phase.BOSS_ENTRY||session.phase==MatchSession.Phase.BOSS_COMBAT;
            for(int i=0;i<lights.size();i++) {
                var part=lights.get(i);ColorRGBA base=part.color();
                if(screen&&boss)base=session.bossMode>=3?new ColorRGBA(1,.20f,.09f,1):new ColorRGBA(1,.56f,.18f,1);
                ColorRGBA color=base.mult(intensity);color.a=1;
                Material material=part.geometry().getMaterial();material.setColor("Color",color);
                if(material.getParam("GlowColor")!=null)material.setColor("GlowColor",color.mult(.55f));
                if(screen) {
                    Vector3f scale=part.scale();float amplitude=.88f+.12f*FastMath.sin(angle+i*.71f);
                    part.geometry().setLocalScale(scale.x,scale.y*amplitude,scale.z);
                }
            }
        }
    }
    private static final class HazardVisual {
    private final MatchSession session;
    private final ArenaDefinition.Hazard hazard;
    private final ArenaSystems arenaSystems;
    private final Node decoration;
    private final Material indicator;
    private final Geometry arcs;

    private HazardVisual(AssetManager assets,MatchSession session,
            ArenaDefinition.Hazard hazard,ArenaSystems arenaSystems,float surfaceHeight) {
        this.session=session;this.hazard=hazard;this.arenaSystems=arenaSystems;
        decoration=new Node("arena-feedback-"+hazard.id());decoration.setLocalTranslation(0,surfaceHeight,0);
        indicator=unlit(assets,new ColorRGBA(.15f,.105f,.025f,1));
        List<Vector3f> strips=new ArrayList<>();
        float cx=(hazard.minX()+hazard.maxX())*.5f,cz=(hazard.minZ()+hazard.maxZ())*.5f;
        float hx=(hazard.maxX()-hazard.minX())*.5f,hz=(hazard.maxZ()-hazard.minZ())*.5f;
        box(strips,new Vector3f(cx,.09f,hazard.minZ()+.09f),new Vector3f(hx,.045f,.075f));
        box(strips,new Vector3f(cx,.09f,hazard.maxZ()-.09f),new Vector3f(hx,.045f,.075f));
        box(strips,new Vector3f(hazard.minX()+.09f,.09f,cz),new Vector3f(.075f,.045f,hz));
        box(strips,new Vector3f(hazard.maxX()-.09f,.09f,cz),new Vector3f(.075f,.045f,hz));
        for(float x:new float[]{hazard.minX()+.25f,hazard.maxX()-.25f})
            for(float z:new float[]{hazard.minZ()+.25f,hazard.maxZ()-.25f})
                box(strips,new Vector3f(x,.34f,z),new Vector3f(.16f,.26f,.16f));
        Geometry frame=new Geometry("hazard-indicator",mesh(strips));frame.setMaterial(indicator);decoration.attachChild(frame);
        List<Vector3f> lightning=new ArrayList<>();
        if(hazard.type()==ArenaDefinition.HazardType.ELECTRIC)for(int row=1;row<=3;row++) {
            float z=hazard.minZ()+(hazard.maxZ()-hazard.minZ())*row/4;
            for(int segment=0;segment<14;segment++) {
                Vector3f a=arcPoint(segment,row,z),b=arcPoint(segment+1,row,z);
                // Two crossed narrow ribbons make the live line visible from every driving angle.
                for(Vector3f width:List.of(new Vector3f(0,.035f,0),new Vector3f(0,0,.035f)))
                    quad(lightning,a.subtract(width),a.add(width),b.add(width),b.subtract(width));
            }
        }
        else if(hazard.type()==ArenaDefinition.HazardType.FIRE) {
            for(int i=0;i<10;i++) {
                float x=hazard.minX()+.6f+(hazard.maxX()-hazard.minX()-1.2f)*(i%5)/4;
                float z=hazard.minZ()+.6f+(hazard.maxZ()-hazard.minZ()-1.2f)*(i/5);
                float height=.48f+(i%3)*.15f;
                quad(lightning,new Vector3f(x-.22f,.08f,z),new Vector3f(x+.22f,.08f,z),
                        new Vector3f(x+.09f,height,z),new Vector3f(x-.03f,height,z));
                quad(lightning,new Vector3f(x,.08f,z-.22f),new Vector3f(x,.08f,z+.22f),
                        new Vector3f(x,height,z+.09f),new Vector3f(x,height,z-.03f));
            }
        } else {
            // Mechanical threats retain ground-space direction marks, never electric arcs.
            for(int i=0;i<5;i++) {
                float x=hazard.minX()+.4f+(hazard.maxX()-hazard.minX()-.8f)*i/4;
                box(lightning,new Vector3f(x,.06f,cz),new Vector3f(.06f,.014f,Math.min(1.8f,hz*.5f)));
            }
        }
        arcs=new Geometry("hazard-active-arcs",mesh(lightning));arcs.setMaterial(unlit(assets,activeColor()));
        arcs.setCullHint(Spatial.CullHint.Always);decoration.attachChild(arcs);

    }

    void update(float dt) {
        updateHazard();
    }
    private void updateHazard() {
        ArenaSystems.HazardPhase phase=arenaSystems.hazardPhase(hazard.id());
        ColorRGBA color=switch(phase) {
            case OFF -> new ColorRGBA(.15f,.105f,.025f,1);
            case WARNING -> {
                float progress=arenaSystems.warningProgress(hazard.id());
                // Continuous amber charge, no flashing or independent presentation clock.
                float intensity=.55f+.45f*Math.clamp(progress,0,1);
                yield new ColorRGBA(intensity,.43f*intensity,.025f,1);
            }
            case ACTIVE -> activeColor();
        };
        indicator.setColor("Color",color);
        arcs.setCullHint(phase==ArenaSystems.HazardPhase.ACTIVE?Spatial.CullHint.Inherit:Spatial.CullHint.Always);
        decoration.setUserData("hazardPhase",phase.name());
    }
    private ColorRGBA activeColor() {
        return switch(hazard.type()) {
            case ELECTRIC -> new ColorRGBA(.12f,.78f,1,1);
            case FIRE -> new ColorRGBA(1,.35f,.045f,1);
            case CRANE, CAROUSEL -> new ColorRGBA(1,.62f,.09f,1);
            case TRAFFIC -> new ColorRGBA(.55f,.86f,1,1);
        };
    }
    private Vector3f arcPoint(int segment,int row,float z) {
        float x=hazard.minX()+.3f+(hazard.maxX()-hazard.minX()-.6f)*segment/14;
        float y=.22f+(segment%2==0?.10f:.36f)+(row-2)*.055f;
        return new Vector3f(x,y,z+(segment%3-1)*.21f);
    }
    }
    private static Material unlit(AssetManager assets,ColorRGBA color) {
        Material material=new Material(assets,"Common/MatDefs/Misc/Unshaded.j3md");material.setColor("Color",color);
        material.getAdditionalRenderState().setFaceCullMode(RenderState.FaceCullMode.Off);return material;
    }
    private static Mesh mesh(List<Vector3f> triangles) {
        Mesh mesh=new Mesh();mesh.setBuffer(VertexBuffer.Type.Position,3,BufferUtils.createFloatBuffer(triangles.toArray(Vector3f[]::new)));
        mesh.updateBound();mesh.setStatic();return mesh;
    }
    private static void quad(List<Vector3f> data,Vector3f a,Vector3f b,Vector3f c,Vector3f d) {
        Collections.addAll(data,a,b,c,a,c,d);
    }
    private static void box(List<Vector3f> data,Vector3f centre,Vector3f half) {
        Vector3f[] p=new Vector3f[8];
        for(int i=0;i<8;i++)p[i]=centre.add((i&1)==0?-half.x:half.x,(i&2)==0?-half.y:half.y,(i&4)==0?-half.z:half.z);
        for(int[] face:new int[][]{{0,2,3,1},{4,5,7,6},{0,4,6,2},{1,3,7,5},{2,6,7,3},{0,1,5,4}})
            quad(data,p[face[0]],p[face[1]],p[face[2]],p[face[3]]);
    }
}
