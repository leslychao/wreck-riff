package game.wreckriff.presentation;

import com.jme3.light.PointLight;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.ViewPort;
import com.jme3.scene.Node;
import com.jme3.scene.control.AbstractControl;
import java.util.*;

/** Authored fixture illumination with a fixed budget; emissive fixture geometry remains visible at distance. */
final class ArenaLocalLights extends AbstractControl {
    static final int MAX_ACTIVE=8;
    private record Fixture(PointLight light,ColorRGBA color) { }
    private final Node root;
    private final List<Fixture> fixtures;
    private final Set<PointLight> active=new HashSet<>();
    private final Vector3f cameraPosition=new Vector3f();
    private boolean cameraKnown;
    ArenaLocalLights(Node root,List<ArenaArt.LocalLight> definitions) {
        this.root=root;List<Fixture> values=new ArrayList<>();
        for(var definition:definitions) {
            PointLight light=new PointLight();light.setName(definition.id());light.setPosition(definition.position().vector());light.setRadius(definition.radius());
            var rgb=definition.color();ColorRGBA color=new ColorRGBA(rgb.x(),rgb.y(),rgb.z(),1);light.setColor(color);
            values.add(new Fixture(light,color));
        }
        fixtures=List.copyOf(values);
    }
    @Override protected void controlUpdate(float tpf) {
        if(cameraKnown)select(cameraPosition);
    }
    @Override protected void controlRender(RenderManager manager,ViewPort view) {
        // Rendering follows updateGeometricState: changing the light list here would
        // invalidate descendants while RenderManager is already traversing them.
        cameraPosition.set(view.getCamera().getLocation());cameraKnown=true;
    }
    void select(Vector3f camera) {
        List<Fixture> nearest=fixtures.stream().filter(fixture->camera.distance(fixture.light.getPosition())<260)
                .sorted(Comparator.comparingDouble(fixture->camera.distanceSquared(fixture.light.getPosition())))
                .limit(MAX_ACTIVE).toList();
        Set<PointLight> next=new HashSet<>();
        for(var fixture:nearest) {
            PointLight light=fixture.light;float range=camera.distance(light.getPosition());
            float intensity=1-Math.clamp((range-180)/80,0,1);light.setColor(fixture.color.mult(intensity));
            next.add(light);if(active.add(light))root.addLight(light);
        }
        for(var iterator=active.iterator();iterator.hasNext();) {
            PointLight light=iterator.next();if(!next.contains(light)){root.removeLight(light);iterator.remove();}
        }
    }
}
