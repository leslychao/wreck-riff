package game.wreckriff.presentation;

import com.jme3.asset.DesktopAssetManager;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Quaternion;
import com.jme3.scene.*;
import com.jme3.scene.shape.Box;
import com.jme3.texture.Texture;
import com.jme3.util.BufferUtils;
import game.wreckriff.config.VehicleDefinition;
import game.wreckriff.config.VehicleRules;
import org.junit.jupiter.api.Test;
import java.nio.FloatBuffer;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class VehiclePreviewVisualTest {
    private static final DesktopAssetManager ASSETS=new DesktopAssetManager(true);

    @Test void bakedPreviewPreservesTintTextureUvAlphaAndAuthoredVertexColorsWithoutEditingSource() {
        var source=new Geometry("two-tone-panel",new Box(1,1,1));
        Material paint=new SurfaceMaterials(ASSETS).paint(new ColorRGBA(.67f,.54f,.24f,.8f));
        paint.setBoolean("UseVertexColor",true);source.setMaterial(paint);
        float[] originalColors=new float[source.getVertexCount()*4];
        for(int vertex=0;vertex<source.getVertexCount();vertex++) {
            originalColors[vertex*4]=vertex%2==0?.2f:.8f;originalColors[vertex*4+1]=.4f;
            originalColors[vertex*4+2]=.6f;originalColors[vertex*4+3]=.7f;
        }
        source.getMesh().setBuffer(VertexBuffer.Type.Color,4,BufferUtils.createFloatBuffer(originalColors));
        var preview=(Geometry)VehiclePreviewVisual.create(source,new Quaternion().fromAngles(.28f,.65f,0));
        assertNotSame(source.getMesh(),preview.getMesh());assertNotSame(paint,preview.getMaterial());
        assertEquals("Unshaded",preview.getMaterial().getMaterialDef().getName());
        assertEquals(paint.getParam("Diffuse").getValue(),preview.getMaterial().getParam("Color").getValue());
        assertSame(paint.getParam("DiffuseMap").getValue(),preview.getMaterial().getParam("ColorMap").getValue());
        assertEquals(Boolean.TRUE,preview.getMaterial().getParam("VertexColor").getValue());
        assertBufferEquals(source.getMesh().getFloatBuffer(VertexBuffer.Type.TexCoord),preview.getMesh().getFloatBuffer(VertexBuffer.Type.TexCoord));
        FloatBuffer baked=preview.getMesh().getFloatBuffer(VertexBuffer.Type.Color),authored=source.getMesh().getFloatBuffer(VertexBuffer.Type.Color);
        float minimum=1,maximum=0;
        for(int vertex=0;vertex<source.getVertexCount();vertex++) {
            float shade=baked.get(vertex*4)/originalColors[vertex*4];minimum=Math.min(minimum,shade);maximum=Math.max(maximum,shade);
            assertTrue(shade>=.549f&&shade<=1.001f);
            for(int channel=0;channel<3;channel++)assertEquals(originalColors[vertex*4+channel]*shade,baked.get(vertex*4+channel),.00001f);
            assertEquals(.7f,baked.get(vertex*4+3),.00001f);
        }
        assertTrue(maximum-minimum>.1f,"Different surface normals must retain visible face shading");
        for(int index=0;index<originalColors.length;index++)assertEquals(originalColors[index],authored.get(index));
        assertEquals(Quaternion.IDENTITY,source.getLocalRotation());assertEquals("Phong Lighting",paint.getMaterialDef().getName());
    }

    @Test void everyActualVehicleHasReadableStaticPaintAndPreservesOriginalLampColors() {
        for(var definition:VehicleDefinition.values()) {
            Node source=VehicleVisual.create(ASSETS,definition.profile(VehicleRules.load()),0);
            var originalPaint=(Geometry)source.getChild("paint");Material original=originalPaint.getMaterial();Mesh mesh=originalPaint.getMesh();
            var preview=(Node)VehiclePreviewVisual.create(source,new Quaternion().fromAngles(.28f,.65f,0));
            var painted=(Geometry)preview.getChild("paint");
            assertEquals("Unshaded",painted.getMaterial().getMaterialDef().getName(),definition.id());
            var color=(ColorRGBA)painted.getMaterial().getParam("Color").getValue();
            assertTrue(Math.max(color.r,Math.max(color.g,color.b))>.1f,definition.id());
            assertInstanceOf(Texture.class,painted.getMaterial().getParam("ColorMap").getValue());
            assertSame(original,originalPaint.getMaterial());assertSame(mesh,originalPaint.getMesh());
            assertTrue(source.getNumControls()>0);assertEquals(0,preview.getNumControls());
            assertEquals(source.getTriangleCount(),preview.getTriangleCount());
            var lamp=(Geometry)source.getChild("headlights");var previewLamp=(Geometry)preview.getChild("headlights");
            assertEquals(lamp.getMaterial().getParam("Color").getValue(),previewLamp.getMaterial().getParam("Color").getValue());
            if(lamp.getMesh().getFloatBuffer(VertexBuffer.Type.Color)!=null)
                assertBufferEquals(lamp.getMesh().getFloatBuffer(VertexBuffer.Type.Color),previewLamp.getMesh().getFloatBuffer(VertexBuffer.Type.Color));
            Set<Material> materials=new HashSet<>();preview.depthFirstTraversal(spatial->{if(spatial instanceof Geometry geometry)materials.add(geometry.getMaterial());});
            assertTrue(materials.stream().allMatch(material->material.getMaterialDef().getName().equals("Unshaded")),definition.id());
        }
    }
    private static void assertBufferEquals(FloatBuffer expected,FloatBuffer actual) {
        assertEquals(expected.limit(),actual.limit());for(int index=0;index<expected.limit();index++)assertEquals(expected.get(index),actual.get(index));
    }
}
