package game.wreckriff.tools;

import com.google.gson.GsonBuilder;
import com.jme3.app.SimpleApplication;
import com.jme3.app.state.ScreenshotAppState;
import com.jme3.asset.TextureKey;
import com.jme3.asset.plugins.FileLocator;
import com.jme3.material.*;
import com.jme3.math.*;
import com.jme3.renderer.Caps;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.*;
import com.jme3.system.*;
import com.jme3.texture.*;
import com.jme3.texture.image.ColorSpace;
import game.wreckriff.presentation.CombatVfxAtlas;
import org.lwjgl.opengl.*;
import java.io.File;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Native A/B review of all four prepared atlases against their preserved lossless sources. */
public final class CompressedVfxReview extends SimpleApplication {
    private static final String[] FAMILIES={"smoke","flame","blast","dust"};
    private static final float[] SHAPES={1,8,4,7};
    private static final float[] FRAMES={2,5.35f,9.65f,23.2f,29,47.5f};
    private final Path input,output;
    private final CountDownLatch done=new CountDownLatch(1);
    private volatile Throwable failure;
    private boolean complete;
    private final Texture[] originals=new Texture[4],compressedAtlases=new Texture[4];
    private final Node[] families=new Node[4];
    private Material material;
    private ScreenshotAppState screenshots;
    private int frame,stage;
    private final List<Map<String,Object>> evidence=new ArrayList<>();
    private CompressedVfxReview(Path input,Path output){this.input=input;this.output=output;}
    public static void main(String[] args)throws Exception {
        Path input=Path.of(args[0]).toAbsolutePath(),output=Path.of(args[1]).toAbsolutePath();Files.createDirectories(output);Files.deleteIfExists(output.resolve("complete.json"));
        var app=new CompressedVfxReview(input,output);var settings=new AppSettings(true);
        settings.setTitle("Wreck Riff - original RGBA versus prepared BC3 VFX");settings.setResolution(1600,900);settings.setSamples(4);settings.setVSync(false);settings.setFrameRate(60);settings.setGammaCorrection(true);
        app.setSettings(settings);app.setShowSettings(false);app.setPauseOnLostFocus(false);app.start(JmeContext.Type.Display);
        if(!app.done.await(20,TimeUnit.SECONDS)){app.stop(false);throw new IllegalStateException("Compressed atlas review exceeded 20 seconds");}
        if(app.failure!=null)throw new IllegalStateException("Compressed atlas review failed",app.failure);
        if(!app.complete)throw new IllegalStateException("Compressed atlas review incomplete");
    }
    @Override public void simpleInitApp(){
        setDisplayFps(false);setDisplayStatView(false);flyCam.setEnabled(false);
        if(!renderer.getCaps().contains(Caps.TextureCompressionS3TC))throw new IllegalStateException("BC3 requires S3TC texture compression");
        screenshots=new ScreenshotAppState(output+File.separator);stateManager.attach(screenshots);
        assetManager.registerLocator(Path.of("src/tools/assets/combat-vfx").toAbsolutePath().toString(),FileLocator.class);
        assetManager.registerLocator(input.toString(),FileLocator.class);
        for(int family=0;family<4;family++) {
            var pngKey=new TextureKey(FAMILIES[family]+".png",false);pngKey.setGenerateMips(true);originals[family]=assetManager.loadTexture(pngKey);
            var ddsKey=new TextureKey(FAMILIES[family]+".dds",false);ddsKey.setGenerateMips(false);compressedAtlases[family]=assetManager.loadTexture(ddsKey);
            Texture compressed=compressedAtlases[family];
            if(compressed.getImage().getFormat()!=Image.Format.DXT5||compressed.getImage().getMipMapSizes().length!=12)throw new IllegalStateException("Expected native DXT5 with twelve baked mip levels");
            for(Texture texture:List.of(originals[family],compressed)){texture.getImage().setColorSpace(ColorSpace.sRGB);texture.setMinFilter(Texture.MinFilter.Trilinear);texture.setMagFilter(Texture.MagFilter.Bilinear);texture.setWrap(Texture.WrapMode.EdgeClamp);}
        }
        material=new Material(assetManager,"materials/CombatParticles.j3md");CombatVfxAtlas.load(assetManager).bind(material);
        material.setVector3("LightDirection",new Vector3f(-.4f,-.7f,.5f));material.setColor("KeyLight",ColorRGBA.White);material.setColor("FillLight",new ColorRGBA(.4f,.45f,.5f,1));material.setFloat("FlashIntensity",1);
        material.getAdditionalRenderState().setBlendMode(RenderState.BlendMode.Alpha);material.getAdditionalRenderState().setDepthWrite(false);
        for(int family=0;family<4;family++) {
            families[family]=new Node(FAMILIES[family]);rootNode.attachChild(families[family]);
            for(int row=0;row<3;row++)for(int column=0;column<FRAMES.length;column++)quad(family,-4.8f+column*1.92f,1.85f-row*1.7f,row==0?.84f:row==1?.23f:.055f,FRAMES[column]);
        }
        cam.setParallelProjection(true);cam.setLocation(new Vector3f(0,0,10));cam.lookAt(Vector3f.ZERO,Vector3f.UNIT_Y);cam.setFrustum(1,100,-6,6,3.375f,-3.375f);applyStage();
    }
    private void quad(int family,float x,float y,float radius,float animation){
        var mesh=new Mesh();float[] p=new float[18],colors=new float[24],shape=new float[12],variation=new float[12],frames=new float[12];
        float[] uv={0,0,1,0,1,1,0,0,1,1,0,1};
        for(int i=0;i<6;i++){p[i*3]=x;p[i*3+1]=y;Arrays.fill(colors,i*4,i*4+4,1);shape[i*2]=radius;shape[i*2+1]=SHAPES[family];frames[i*2]=animation;frames[i*2+1]=.1f;}
        mesh.setBuffer(VertexBuffer.Type.Position,3,p);mesh.setBuffer(VertexBuffer.Type.Color,4,colors);mesh.setBuffer(VertexBuffer.Type.TexCoord,2,uv);mesh.setBuffer(VertexBuffer.Type.TexCoord2,2,shape);mesh.setBuffer(VertexBuffer.Type.TexCoord3,2,variation);mesh.setBuffer(VertexBuffer.Type.TexCoord4,2,frames);mesh.updateBound();
        var geometry=new Geometry("actual-blast-shader-frame-"+animation,mesh);geometry.setMaterial(material);geometry.setQueueBucket(RenderQueue.Bucket.Transparent);geometry.setShadowMode(RenderQueue.ShadowMode.Off);geometry.setCullHint(Spatial.CullHint.Inherit);families[family].attachChild(geometry);
    }
    private void applyStage(){
        int family=stage/4;
        for(int i=0;i<4;i++){families[i].setCullHint(i==family?Spatial.CullHint.Never:Spatial.CullHint.Always);material.setTexture(Character.toUpperCase(FAMILIES[i].charAt(0))+FAMILIES[i].substring(1)+"Atlas",i==family&&stage%2==0?originals[i]:compressedAtlases[i]);}
        viewPort.setBackgroundColor(stage%4<2?new ColorRGBA(.02f,.025f,.035f,1):new ColorRGBA(.35f,.38f,.42f,1));
    }
    @Override public void simpleUpdate(float dt){
        if(material==null)return;
        int capture=stage==0?30:10;
        if(++frame==capture){
            String name=FAMILIES[stage/4]+"-"+(stage%4<2?"night-":"day-")+(stage%2==0?"rgba":"bc3");
            if(cam.getWidth()!=1600||cam.getHeight()!=900)throw new IllegalStateException("Unexpected actual framebuffer dimensions");
            screenshots.setFileName(name);screenshots.takeScreenshot();
            Texture texture=stage%2==0?originals[stage/4]:compressedAtlases[stage/4];int previous=GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);GL11.glBindTexture(GL11.GL_TEXTURE_2D,texture.getImage().getId());
            int format=GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D,0,GL11.GL_TEXTURE_INTERNAL_FORMAT),isCompressed=GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D,0,GL13.GL_TEXTURE_COMPRESSED);long gpuBytes=0;
            if(stage%2==1){if(format!=0x8c4f||isCompressed!=1)throw new IllegalStateException("DDS did not upload as native sRGB BC3: "+Integer.toHexString(format));for(int mip=0;mip<12;mip++)gpuBytes+=GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D,mip,GL13.GL_TEXTURE_COMPRESSED_IMAGE_SIZE);}
            GL11.glBindTexture(GL11.GL_TEXTURE_2D,previous);var row=new LinkedHashMap<String,Object>();row.put("stage",name);row.put("actualWidth",cam.getWidth());row.put("actualHeight",cam.getHeight());row.put("samples",context.getSettings().getSamples());
            Path texturePath=stage%2==0?Path.of("src/tools/assets/combat-vfx/"+FAMILIES[stage/4]+".png"):input.resolve(FAMILIES[stage/4]+".dds");
            try{row.put("texturePath",texturePath.toAbsolutePath().toString());row.put("textureSha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(texturePath))));}catch(Exception failure){throw new IllegalStateException("Cannot record compressed texture identity",failure);}row.put("glInternalFormat",String.format("0x%04x",format));row.put("compressed",isCompressed);row.put("compressedMipBytes",gpuBytes);row.put("cpuDataBytes",texture.getImage().getData(0).capacity());row.put("frameInterpolation",FRAMES);row.put("screenRadiiPixels",List.of(112,31,7));evidence.add(row);
        }
        if(frame>capture+4){if(++stage<16){frame=0;applyStage();}else finish();}
    }
    private void finish(){try{var hashes=new TreeMap<String,String>();try(var paths=Files.list(output)){for(Path p:paths.filter(p->p.toString().endsWith(".png")).toList())hashes.put(p.getFileName().toString(),HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p))));}if(hashes.size()!=16)throw new IllegalStateException("Missing A/B screenshots");Files.writeString(output.resolve("complete.json"),new GsonBuilder().setPrettyPrinting().create().toJson(Map.of("stages",evidence,"captures",hashes,"renderer",GL11.glGetString(GL11.GL_RENDERER),"scope","Same actual CombatParticles smoke, flame, blast and dust shader, subframe interpolation, camera, sRGB and MSAA4. Large/normal/small sprites exercise mip selection. Texture file alone changes; no offline Y flip.")));complete=true;stop();}catch(Exception e){throw new IllegalStateException(e);}}
    @Override public void handleError(String message,Throwable error){failure=error;super.handleError(message,error);}
    @Override public void destroy(){try{super.destroy();}finally{done.countDown();}}
}
