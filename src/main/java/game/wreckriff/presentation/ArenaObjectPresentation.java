package game.wreckriff.presentation;

import com.jme3.asset.AssetManager;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.*;
import game.wreckriff.arena.*;
import java.util.*;
import jme3tools.optimize.GeometryBatchFactory;

/** One visual binding for the original solid and all its authored overlays, driven by ArenaSystems. */
final class ArenaObjectPresentation {
    private record ObjectVisual(ArenaDefinition.Destructible definition,List<Spatial> solids,Node marker,Material lamp) {}
    private record BarrierVisual(ArenaDefinition.Barrier definition,List<Spatial> solids,Node marker,Material lamp) {}
    private final List<ObjectVisual> objects=new ArrayList<>();
    private final List<BarrierVisual> barriers=new ArrayList<>();
    private final ArenaSystems systems;
    ArenaObjectPresentation(AssetManager assets,Node root,ArenaDefinition arena,ArenaSystems systems) {
        this.systems=systems;var materials=new SurfaceMaterials(assets);
        for(var object:arena.destructibles()) {
            var box=arena.boxes().stream().filter(b->b.id().equals(object.geometryId())).findFirst().orElseThrow();
            Material lamp=materials.emissive(new ColorRGBA(.2f,.11f,.03f,1));
            Node marker=marker(root,"arena-object-feedback-"+object.id(),box,lamp);
            objects.add(new ObjectVisual(object,solids(root,object.geometryId()),marker,lamp));
        }
        for(var barrier:arena.barriers()) {
            var box=arena.boxes().stream().filter(b->b.id().equals(barrier.geometryId())).findFirst().orElseThrow();
            Material lamp=materials.emissive(new ColorRGBA(.2f,.11f,.03f,1));
            Node marker=marker(root,"arena-barrier-feedback-"+barrier.id(),box,lamp);
            barriers.add(new BarrierVisual(barrier,solids(root,barrier.geometryId()),marker,lamp));
        }
        update();
    }
    private static List<Spatial> solids(Node root,String geometryId) {
        List<Spatial> result=new ArrayList<>();Spatial body=root.getChild(geometryId);if(body!=null)result.add(body);
        root.depthFirstTraversal(spatial->{if(geometryId.equals(spatial.getUserData("artAnchor")))result.add(spatial);});
        return List.copyOf(result);
    }
    private static Node marker(Node root,String id,ArenaDefinition.BoxPart box,Material lamp) {
        Node marker=new Node(id);marker.setShadowMode(RenderQueue.ShadowMode.Off);
        float bottom=box.center().y()-box.size().y()/2;
        marker.setLocalTranslation(box.center().x(),Math.max(0,bottom),box.center().z());
        float x=box.size().x()/2,z=box.size().z()/2;
        // Mark only the exact collider footprint. Empty/retracted barriers never retain a visual wall.
        for(float side:new float[]{-1,1}) {
            LaunchPadPresentation.plate(marker,"object-footprint",0,.035f,side*(z-.04f),x,.008f,.04f,lamp);
            LaunchPadPresentation.plate(marker,"object-footprint",side*(x-.04f),.035f,0,.04f,.008f,z,lamp);
        }
        marker.updateGeometricState();GeometryBatchFactory.optimize(marker,false);
        root.attachChild(marker);return marker;
    }
    void update() {
        for(var object:objects) {
            var state=systems.objectState(object.definition.id());boolean warning=state.destroyed()&&!state.open();
            visible(object.solids,!state.open());
            object.marker.setUserData("objectPhase",state.open()?"OPEN":warning?"WARNING":"INTACT");
            color(object.lamp,warning?new ColorRGBA(1,.35f+.3f*systems.warningProgress(object.definition.id()),.045f,1):
                    state.open()?new ColorRGBA(.13f,.13f,.12f,1):new ColorRGBA(.28f,.16f,.04f,1));
        }
        for(var barrier:barriers) {
            var phase=systems.barrierPhase(barrier.definition.id());visible(barrier.solids,phase==ArenaSystems.HazardPhase.ACTIVE);
            barrier.marker.setUserData("barrierPhase",phase.name());
            color(barrier.lamp,switch(phase) {
                case OFF -> new ColorRGBA(.16f,.18f,.20f,1);
                case WARNING -> new ColorRGBA(1,.43f+.25f*systems.warningProgress(barrier.definition.id()),.025f,1);
                case ACTIVE -> new ColorRGBA(1,.18f,.025f,1);
            });
        }
    }
    private static void visible(List<Spatial> solids,boolean visible) {
        for(var solid:solids)solid.setCullHint(visible?Spatial.CullHint.Inherit:Spatial.CullHint.Always);
    }
    private static void color(Material material,ColorRGBA color) {
        material.setColor("Color",color);material.setColor("GlowColor",color.mult(.28f));
    }
}
