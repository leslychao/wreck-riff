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
    public static ArenaPresentation attach(AssetManager assets,Node arenaVisual,
            MatchSession session,ArenaDefinition definition,ArenaSystems arenaSystems) {
        ArenaPresentation presentation=new ArenaPresentation();
        for(var hazard:definition.hazards()) {
            HazardVisual visual=new HazardVisual(assets,session,hazard,arenaSystems);
            presentation.visuals.add(visual);arenaVisual.attachChild(visual.decoration);
        }
        arenaVisual.addControl(presentation);presentation.controlUpdate(0);return presentation;
    }
    @Override protected void controlUpdate(float dt) { for(var visual:visuals)visual.update(dt); }
    @Override protected void controlRender(RenderManager manager,ViewPort view) {}
    private static final class HazardVisual {
    private final MatchSession session;
    private final ArenaDefinition.Hazard hazard;
    private final ArenaSystems arenaSystems;
    private final Node decoration;
    private final Material indicator;
    private final Geometry arcs;

    private HazardVisual(AssetManager assets,MatchSession session,
            ArenaDefinition.Hazard hazard,ArenaSystems arenaSystems) {
        this.session=session;this.hazard=hazard;this.arenaSystems=arenaSystems;
        decoration=new Node("arena-feedback-"+hazard.id());decoration.setLocalTranslation(0,hazard.minY(),0);
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
        for(int row=1;row<=3;row++) {
            float z=hazard.minZ()+(hazard.maxZ()-hazard.minZ())*row/4;
            for(int segment=0;segment<14;segment++) {
                Vector3f a=arcPoint(segment,row,z),b=arcPoint(segment+1,row,z);
                // Two crossed narrow ribbons make the live line visible from every driving angle.
                for(Vector3f width:List.of(new Vector3f(0,.035f,0),new Vector3f(0,0,.035f)))
                    quad(lightning,a.subtract(width),a.add(width),b.add(width),b.subtract(width));
            }
        }
        arcs=new Geometry("hazard-active-arcs",mesh(lightning));arcs.setMaterial(unlit(assets,new ColorRGBA(.15f,.86f,1,1)));
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
            case ACTIVE -> new ColorRGBA(.12f,.78f,1,1);
        };
        indicator.setColor("Color",color);
        arcs.setCullHint(phase==ArenaSystems.HazardPhase.ACTIVE?Spatial.CullHint.Inherit:Spatial.CullHint.Always);
        decoration.setUserData("hazardPhase",phase.name());
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
