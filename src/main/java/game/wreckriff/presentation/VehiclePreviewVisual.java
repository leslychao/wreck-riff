package game.wreckriff.presentation;

import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.*;
import com.jme3.texture.Texture;
import com.jme3.util.BufferUtils;
import java.nio.FloatBuffer;
import java.util.Objects;

/** A static copy of the actual vehicle, shaded once for guiNode without a second scene or live lights. */
public final class VehiclePreviewVisual {
    private static final Vector3f KEY_LIGHT=new Vector3f(-.45f,.75f,1).normalizeLocal();
    private static final float AMBIENT=.55f;
    private VehiclePreviewVisual() {}

    public static Spatial create(Spatial source,Quaternion orientation) {
        Spatial preview=copy(Objects.requireNonNull(source));preview.rotate(Objects.requireNonNull(orientation));
        preview.updateGeometricState();
        preview.depthFirstTraversal(spatial->{if(spatial instanceof Geometry geometry)bake(geometry);});
        return preview;
    }
    private static Spatial copy(Spatial source) {
        Spatial result;
        if(source instanceof Geometry geometry) {
            var clone=new Geometry(source.getName(),geometry.getMesh().deepClone());
            clone.setMaterial(geometry.getMaterial().clone());result=clone;
        } else if(source instanceof Node node) {
            var clone=new Node(source.getName());for(var child:node.getChildren())clone.attachChild(copy(child));result=clone;
        } else throw new IllegalArgumentException("Unsupported vehicle preview spatial: "+source.getClass().getName());
        result.setLocalTransform(source.getLocalTransform().clone());result.setCullHint(source.getLocalCullHint());
        result.setQueueBucket(source.getLocalQueueBucket());result.setShadowMode(RenderQueue.ShadowMode.Off);
        // Dynamic damage/FX controls belong to the live model and must not replace the baked snapshot.
        return result;
    }
    private static void bake(Geometry geometry) {
        Material original=geometry.getMaterial();
        if(original.getMaterialDef().getName().equals("Unshaded"))return;
        Material material=new Material(original.getMaterialDef().getAssetManager(),"Common/MatDefs/Misc/Unshaded.j3md");
        ColorRGBA tint=parameter(original,ColorRGBA.class,"Diffuse","BaseColor","Color");
        material.setColor("Color",tint==null?ColorRGBA.White.clone():tint.clone());
        Texture texture=parameter(original,Texture.class,"DiffuseMap","BaseColorMap","ColorMap");
        if(texture!=null)material.setTexture("ColorMap",texture);
        Number alpha=parameter(original,Number.class,"AlphaDiscardThreshold");
        if(alpha!=null)material.setFloat("AlphaDiscardThreshold",alpha.floatValue());
        material.getAdditionalRenderState().copyFrom(original.getAdditionalRenderState());
        material.setTransparent(original.isTransparent());material.setReceivesShadows(false);
        material.setBoolean("VertexColor",true);

        Mesh mesh=geometry.getMesh();FloatBuffer normals=mesh.getFloatBuffer(VertexBuffer.Type.Normal);
        boolean vertexColors=Boolean.TRUE.equals(parameter(original,Boolean.class,"UseVertexColor","VertexColor"));
        FloatBuffer sourceColors=vertexColors?mesh.getFloatBuffer(VertexBuffer.Type.Color):null;
        FloatBuffer colors=BufferUtils.createFloatBuffer(mesh.getVertexCount()*4);
        Vector3f scale=geometry.getWorldScale(),normal=new Vector3f();Quaternion rotation=geometry.getWorldRotation();
        for(int vertex=0;vertex<mesh.getVertexCount();vertex++) {
            float shade=1;
            if(normals!=null) {
                normal.set(normals.get(vertex*3)/scale.x,normals.get(vertex*3+1)/scale.y,normals.get(vertex*3+2)/scale.z);
                rotation.mult(normal,normal).normalizeLocal();
                shade=AMBIENT+(1-AMBIENT)*Math.max(0,normal.dot(KEY_LIGHT));
            }
            for(int channel=0;channel<4;channel++) {
                float authored=sourceColors==null?1:sourceColors.get(vertex*4+channel);
                colors.put(authored*(channel==3?1:shade));
            }
        }
        colors.flip();mesh.setBuffer(VertexBuffer.Type.Color,4,colors);mesh.setStatic();geometry.setMaterial(material);
    }
    private static <T> T parameter(Material material,Class<T> type,String... names) {
        for(String name:names) {
            var parameter=material.getParam(name);
            if(parameter!=null&&type.isInstance(parameter.getValue()))return type.cast(parameter.getValue());
        }
        return null;
    }
}
