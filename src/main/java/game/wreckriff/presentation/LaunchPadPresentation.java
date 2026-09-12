package game.wreckriff.presentation;

import com.jme3.asset.AssetManager;
import com.jme3.material.Material;
import com.jme3.math.*;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.*;
import game.wreckriff.arena.*;
import game.wreckriff.simulation.MatchSession;
import java.util.*;
import jme3tools.optimize.GeometryBatchFactory;

/** Flush launch arrows read the real per-participant compression/flight state. No timer or collider. */
final class LaunchPadPresentation {
    private record Pad(ArenaDefinition.LaunchPad definition,Node root,Node deck,Material indicator) {}
    private final List<Pad> pads=new ArrayList<>();
    private final MatchSession session;
    private final ArenaLaunches launches;
    LaunchPadPresentation(AssetManager assets,Node root,MatchSession session,ArenaDefinition arena,ArenaSystems systems) {
        this.session=session;launches=systems.launches();var materials=new SurfaceMaterials(assets);
        for(var definition:arena.launchPads()) {
            Node node=new Node("launch-pad-"+definition.id());node.setLocalTranslation(definition.source().vector());
            Vector3f direction=definition.direction();
            node.setLocalRotation(new Quaternion().fromAngleAxis(FastMath.atan2(direction.x,direction.z),Vector3f.UNIT_Y));
            node.setShadowMode(RenderQueue.ShadowMode.Off);
            Node deck=new Node("launch-deck");
            float x=definition.width()/2,z=definition.length()/2;
            plate(deck,"launch-inset",0,.014f,0,x,.012f,z,materials.material("black"));
            Material indicator=materials.emissive(new ColorRGBA(.42f,.82f,.95f,1));
            for(float side:new float[]{-1,1}) {
                plate(deck,"launch-rail",side*(x-.12f),.042f,0,.08f,.006f,z-.10f,indicator);
                for(int row=0;row<5;row++)plate(deck,"launch-guide",side*(x-.5f),.042f,-z+.6f+row*(2*z-1.2f)/4,
                        .18f,.006f,.11f,indicator);
            }
            // Broad double chevrons cannot be mistaken for a pickup disc or a striped danger rectangle.
            List<Vector3f> arrows=new ArrayList<>();
            for(float centre:new float[]{-z*.37f,z*.27f}) {
                float span=x*.57f,depth=Math.min(z*.28f,1.7f),thickness=Math.min(.5f,depth*.42f);
                Vector3f tip=new Vector3f(0,.048f,centre+depth/2);
                Vector3f inner=new Vector3f(0,.048f,tip.z-thickness);
                Vector3f left=new Vector3f(-span,.048f,centre-depth/2),right=new Vector3f(span,.048f,centre-depth/2);
                Collections.addAll(arrows,left,tip,inner,left,inner,left.add(0,0,-thickness),
                        inner,tip,right,inner,right,right.add(0,0,-thickness));
            }
            Geometry arrow=new Geometry("launch-direction-arrows",SurfaceMesh.triangles(arrows,4));
            indicator.getAdditionalRenderState().setFaceCullMode(com.jme3.material.RenderState.FaceCullMode.Off);
            arrow.setMaterial(indicator);deck.attachChild(arrow);
            // Batch in pad-local space before inheriting the platform's world position and heading.
            deck.updateGeometricState();GeometryBatchFactory.optimize(deck,false);node.attachChild(deck);
            root.attachChild(node);pads.add(new Pad(definition,node,deck,indicator));
        }
        update();
    }
    void update() {
        for(var pad:pads) {
            float compression=0;boolean flight=false;
            for(var vehicle:session.vehicles) {
                compression=Math.max(compression,launches.compression(pad.definition.id(),vehicle.id));
                var launched=launches.flight(vehicle.id);
                if(launched.isPresent()&&launched.get().launchId().equals(pad.definition.id())
                        &&session.tick-launched.get().launchTick()<30)flight=true;
            }
            String phase=compression>0?"COMPRESSING":flight?"LAUNCHED":"READY";
            pad.deck.setLocalScale(1,1-compression*.30f,1);
            ColorRGBA color=compression>0?new ColorRGBA(1,.45f+.4f*compression,.12f,1):
                    flight?new ColorRGBA(.85f,1,1,1):new ColorRGBA(.42f,.82f,.95f,1);
            pad.indicator.setColor("Color",color);pad.indicator.setColor("GlowColor",color.mult(.35f));
            pad.root.setUserData("launchPhase",phase);pad.root.setUserData("launchCompression",compression);
        }
    }
    static void plate(Node parent,String id,float x,float y,float z,float hx,float hy,float hz,Material material) {
        Geometry geometry=new Geometry(id,SurfaceMesh.box(hx,hy,hz,4));geometry.setLocalTranslation(x,y,z);
        geometry.setMaterial(material);parent.attachChild(geometry);
    }
}
