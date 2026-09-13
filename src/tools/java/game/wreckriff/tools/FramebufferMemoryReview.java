package game.wreckriff.tools;

import com.google.gson.GsonBuilder;
import com.jme3.app.SimpleApplication;
import com.jme3.math.*;
import com.jme3.post.FilterPostProcessor;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.Geometry;
import com.jme3.scene.shape.Box;
import com.jme3.system.*;
import com.jme3.texture.*;
import game.wreckriff.arena.ArenaDefinition.Theme;
import game.wreckriff.presentation.*;
import org.lwjgl.opengl.*;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Attachment census and native disposal regression. No forced collection is used. */
public final class FramebufferMemoryReview extends SimpleApplication {
    private final Path output;
    private final CountDownLatch done=new CountDownLatch(1);
    private final List<Map<String,Object>> reports=new ArrayList<>();
    private final Map<String,Set<Integer>> priorTextures=new LinkedHashMap<>(),priorFbos=new LinkedHashMap<>();
    private volatile Throwable failure;
    private SceneLighting.Handle lighting;
    private CombatVisuals visuals;
    private Matrix4f beforeSameSizeProjection;
    private int frame;
    private boolean complete;
    private FramebufferMemoryReview(Path output){this.output=output;}
    public static void main(String[] args)throws Exception {
        Path output=Path.of(args[0]).toAbsolutePath();Files.createDirectories(output.getParent());Files.deleteIfExists(output);
        var app=new FramebufferMemoryReview(output);var settings=new AppSettings(true);
        settings.setTitle("Wreck Riff - framebuffer allocation census");settings.setResolution(1280,720);
        settings.setSamples(4);settings.setVSync(false);settings.setFrameRate(60);settings.setGammaCorrection(true);
        app.setSettings(settings);app.setShowSettings(false);app.setPauseOnLostFocus(false);app.start(JmeContext.Type.Display);
        if(!app.done.await(15,TimeUnit.SECONDS)){app.stop(false);throw new IllegalStateException("Framebuffer census exceeded 15 seconds");}
        if(app.failure!=null)throw new IllegalStateException("Framebuffer census failed",app.failure);
        if(!app.complete)throw new IllegalStateException("Framebuffer census incomplete");
    }
    @Override public void simpleInitApp(){
        setDisplayFps(false);setDisplayStatView(false);flyCam.setEnabled(false);
        var floor=new Geometry("floor",new Box(15,.1f,15));floor.setLocalTranslation(0,-.1f,0);
        floor.setMaterial(SurfaceMaterials.lit(assetManager,new ColorRGBA(.3f,.3f,.3f,1),5,.2f));floor.setShadowMode(RenderQueue.ShadowMode.CastAndReceive);rootNode.attachChild(floor);
        var box=new Geometry("caster",new Box(1,1,1));box.setLocalTranslation(0,1,0);box.setMaterial(floor.getMaterial());box.setShadowMode(RenderQueue.ShadowMode.CastAndReceive);rootNode.attachChild(box);
        lighting=SceneLighting.install(assetManager,rootNode,viewPort);lighting.setSamples(4);lighting.apply(Theme.CONSTRUCTION,true);lighting.initialize(renderManager);
        var world=(game.wreckriff.simulation.WorldQuery)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{game.wreckriff.simulation.WorldQuery.class},(proxy,method,args)->{throw new AssertionError("No simulation calls expected in an empty framebuffer census: "+method);});
        visuals=new CombatVisuals(assetManager,rootNode,world);lighting.bindCombatVisuals(visuals);
        cam.setLocation(new Vector3f(7,5,10));cam.lookAt(Vector3f.ZERO,Vector3f.UNIT_Y);
        cam.setFrustum(.05f,150,-.06f,.035f,.027f,-.027f);
    }
    @Override public void simpleUpdate(float dt){
        if(lighting==null)return;
        try {
            switch(++frame){
                case 30 -> {snapshot("initial-msaa4");beforeSameSizeProjection=cam.getProjectionMatrix().clone();post().reshape(viewPort,cam.getWidth(),cam.getHeight());}
                case 36 -> {if(!beforeSameSizeProjection.equals(cam.getProjectionMatrix()))throw new IllegalStateException("Same-size callback changed off-axis projection");snapshot("same-size-noop");}
                case 48 -> {lighting.apply(Theme.CONSTRUCTION,false);lighting.setSamples(0);}
                case 54 -> {snapshot("sample-change-msaa0-bloom-off");org.lwjgl.glfw.GLFW.glfwSetWindowSize(((com.jme3.system.lwjgl.LwjglWindow)context).getWindowHandle(),960,720);}
                case 65 -> {snapshot("native-resize-msaa0-bloom-off");lighting.setSamples(4);}
                case 78 -> {snapshot("sample-change-msaa4-bloom-off");Files.writeString(output,new GsonBuilder().setPrettyPrinting().create().toJson(Map.of("renderer",GL11.glGetString(GL11.GL_RENDERER),"driver",GL11.glGetString(GL11.GL_VERSION),"checkpoints",reports,"scope","GL attachment dimensions, component bits and IDs. Payload bytes exclude driver padding/compression, swap chain and textures outside these processors. Same-size no-op and replaced-target retirement assertions run without forced GC.")));complete=true;stop();}
            }
        }catch(Exception e){throw new IllegalStateException(e);}
    }
    private FilterPostProcessor post()throws Exception{return (FilterPostProcessor)field(lighting,"post");}
    private void snapshot(String name)throws Exception {
        var fbs=new LinkedHashMap<String,FrameBuffer>();Object post=post();
        fbs.put("scene-single",(FrameBuffer)field(post,"renderFrameBuffer"));fbs.put("scene-ms",(FrameBuffer)field(post,"renderFrameBufferMS"));
        FrameBuffer[] shadows=(FrameBuffer[])field(field(lighting,"shadows"),"shadowFB");for(int i=0;i<shadows.length;i++)fbs.put("shadow-"+i,shadows[i]);
        Object bloom=field(lighting,"bloom"),vfx=field(lighting,"combatVfx");
        fbs.put("bloom-default",pass(field(bloom,"defaultPass")));fbs.put("vfx-default-unused",pass(field(vfx,"defaultPass")));fbs.put("bloom-preglow",pass(field(bloom,"preGlowPass")));
        List<?> passes=(List<?>)field(bloom,"postRenderPasses");for(int i=0;i<passes.size();i++)fbs.put("bloom-post-"+i,pass(passes.get(i)));
        fbs.put("vfx-composite",(FrameBuffer)field(vfx,"composite"));
        var attachments=new ArrayList<Map<String,Object>>();var buffers=new ArrayList<Map<String,Object>>();Set<Integer> textures=new TreeSet<>(),rbIds=new TreeSet<>(),fbIds=new TreeSet<>();
        long total=0;
        for(var entry:fbs.entrySet())if(entry.getValue()!=null){FrameBuffer fb=entry.getValue();if(fb.getId()>0)fbIds.add(fb.getId());
            buffers.add(Map.of("name",entry.getKey(),"id",fb.getId(),"width",fb.getWidth(),"height",fb.getHeight(),"samples",fb.getSamples()));
            for(int i=-1;i<fb.getNumColorTargets();i++){var rb=i<0?fb.getDepthTarget():fb.getColorTarget(i);if(rb==null)continue;
                Map<String,Object> row=attachment(entry.getKey()+(i<0?".depth":".color"+i),fb,rb);attachments.add(row);
                int id=(int)row.get("id");if(id>0&&(rb.getTexture()!=null?textures:rbIds).add(id))total+=(long)row.get("payloadBytes");
            }
        }
        var prior=new LinkedHashMap<String,Object>();for(String old:priorTextures.keySet())prior.put(old,Map.of("texturesStillAlive",priorTextures.get(old).stream().filter(GL11::glIsTexture).toList(),"fbosStillAlive",priorFbos.get(old).stream().filter(GL30::glIsFramebuffer).toList()));
        if(name.equals("same-size-noop")&&(!textures.equals(priorTextures.get("initial-msaa4"))||!fbIds.equals(priorFbos.get("initial-msaa4"))))throw new IllegalStateException("Same-size callback changed allocated GL targets");
        if(name.equals("native-resize-msaa0-bloom-off")) {
            for(int id:priorTextures.get("sample-change-msaa0-bloom-off"))if(!textures.contains(id)&&GL11.glIsTexture(id))throw new IllegalStateException("Old resize texture survived without explicit disposal: "+id);
            for(int id:priorFbos.get("sample-change-msaa0-bloom-off"))if(!fbIds.contains(id)&&GL30.glIsFramebuffer(id))throw new IllegalStateException("Old resize framebuffer survived without explicit disposal: "+id);
        }
        var report=new LinkedHashMap<String,Object>();report.put("name",name);report.put("frame",frame);report.put("camera",List.of(cam.getWidth(),cam.getHeight()));report.put("framebuffers",buffers);report.put("attachments",attachments);report.put("uniquePayloadBytes",total);report.put("priorGenerationGLIds",prior);reports.add(report);
        priorTextures.put(name,textures);priorFbos.put(name,fbIds);
    }
    private static Map<String,Object> attachment(String name,FrameBuffer fb,FrameBuffer.RenderBuffer rb){
        var result=new LinkedHashMap<String,Object>();Texture texture=rb.getTexture();int id=texture==null?rb.getId():texture.getImage().getId();
        result.put("name",name);result.put("kind",texture==null?"renderbuffer":"texture");result.put("jmeFormat",rb.getFormat().toString());result.put("id",id);
        long bytes=0;
        if(id>0){int width,height,samples,format,bits=0;
            if(texture!=null){boolean ms=texture.getImage().getMultiSamples()>1;int target=ms?GL32.GL_TEXTURE_2D_MULTISAMPLE:GL11.GL_TEXTURE_2D;
                int previous=GL11.glGetInteger(ms?GL32.GL_TEXTURE_BINDING_2D_MULTISAMPLE:GL11.GL_TEXTURE_BINDING_2D);GL11.glBindTexture(target,id);
                width=GL11.glGetTexLevelParameteri(target,0,GL11.GL_TEXTURE_WIDTH);height=GL11.glGetTexLevelParameteri(target,0,GL11.GL_TEXTURE_HEIGHT);format=GL11.glGetTexLevelParameteri(target,0,GL11.GL_TEXTURE_INTERNAL_FORMAT);samples=ms?GL11.glGetTexLevelParameteri(target,0,GL32.GL_TEXTURE_SAMPLES):1;
                for(int p:new int[]{GL11.GL_TEXTURE_RED_SIZE,GL11.GL_TEXTURE_GREEN_SIZE,GL11.GL_TEXTURE_BLUE_SIZE,GL11.GL_TEXTURE_ALPHA_SIZE,GL14.GL_TEXTURE_DEPTH_SIZE})bits+=GL11.glGetTexLevelParameteri(target,0,p);
                GL11.glBindTexture(target,previous);
            }else{int previous=GL11.glGetInteger(GL30.GL_RENDERBUFFER_BINDING);GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER,id);
                width=GL30.glGetRenderbufferParameteri(GL30.GL_RENDERBUFFER,GL30.GL_RENDERBUFFER_WIDTH);height=GL30.glGetRenderbufferParameteri(GL30.GL_RENDERBUFFER,GL30.GL_RENDERBUFFER_HEIGHT);format=GL30.glGetRenderbufferParameteri(GL30.GL_RENDERBUFFER,GL30.GL_RENDERBUFFER_INTERNAL_FORMAT);samples=Math.max(1,GL30.glGetRenderbufferParameteri(GL30.GL_RENDERBUFFER,GL30.GL_RENDERBUFFER_SAMPLES));
                for(int p:new int[]{GL30.GL_RENDERBUFFER_RED_SIZE,GL30.GL_RENDERBUFFER_GREEN_SIZE,GL30.GL_RENDERBUFFER_BLUE_SIZE,GL30.GL_RENDERBUFFER_ALPHA_SIZE,GL30.GL_RENDERBUFFER_DEPTH_SIZE,GL30.GL_RENDERBUFFER_STENCIL_SIZE})bits+=GL30.glGetRenderbufferParameteri(GL30.GL_RENDERBUFFER,p);
                GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER,previous);
            }
            bytes=(long)width*height*samples*bits/8;result.put("width",width);result.put("height",height);result.put("samples",samples);result.put("glInternalFormat",String.format("0x%04x",format));result.put("componentBits",bits);
        }
        result.put("payloadBytes",bytes);return result;
    }
    private static FrameBuffer pass(Object value)throws Exception{return value==null?null:(FrameBuffer)field(value,"renderFrameBuffer");}
    private static Object field(Object target,String name)throws Exception{for(Class<?> type=target.getClass();type!=null;type=type.getSuperclass())try{Field field=type.getDeclaredField(name);field.setAccessible(true);return field.get(target);}catch(NoSuchFieldException ignored){}throw new NoSuchFieldException(name);}
    @Override public void handleError(String message,Throwable error){failure=error;super.handleError(message,error);}
    @Override public void destroy(){try{if(lighting!=null)lighting.bindCombatVisuals(null);if(visuals!=null)visuals.close();super.destroy();}finally{done.countDown();}}
}
