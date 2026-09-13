package game.wreckriff.presentation;

import com.jme3.post.FilterPostProcessor;
import com.jme3.post.filters.BloomFilter;
import com.jme3.bounding.BoundingBox;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.ViewPort;
import com.jme3.scene.Node;
import com.jme3.system.NullRenderer;
import com.jme3.texture.FrameBuffer;
import game.wreckriff.arena.ArenaDefinition.Theme;
import game.wreckriff.config.VehicleRules;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the real jME post-processing lifecycle without claiming an OpenGL frame. */
class SceneLightingTest {
    @Test void liveMsaaChangesRecreateTheActualDepthSamplesWithOneProcessor() {
        var root=new Node();var viewport=new ViewPort("msaa",new Camera(64,64));
        var manager=new RenderManager(new NullRenderer(){@Override public java.util.EnumSet<com.jme3.renderer.Caps> getCaps(){return java.util.EnumSet.of(com.jme3.renderer.Caps.OpenGL32,com.jme3.renderer.Caps.FrameBufferMultisample);}});
        var lighting=SceneLighting.install(PresentationTestAssets.shared(),root,viewport);
        try(var visuals=new CombatVisuals(PresentationTestAssets.shared(),root,new SoftWorld())) {
            lighting.bindCombatVisuals(visuals);lighting.setSamples(4);lighting.initialize(manager);
            for(int samples:new int[]{4,0,2,8,4}) {
                lighting.setSamples(samples);
                assertEquals(Math.max(1,samples),visuals.statistics().get("sceneDepthSamples"));
                assertEquals(1,viewport.getProcessors().stream().filter(FilterPostProcessor.class::isInstance).count());
            }
        } finally {for(var processor:viewport.getProcessors())if(processor.isInitialized())processor.cleanup();}
    }
    @Test void everyRetainedLocationHasItsOwnFiniteLightingProfile() {
        var profiles=java.util.Arrays.stream(Theme.values()).map(SceneLighting::profile).toList();
        assertEquals(4,profiles.size());assertEquals(4,profiles.stream().map(SceneLighting.Profile::sky).distinct().count());
        for(var profile:profiles) {
            assertTrue(profile.ambient().r>0&&profile.ambient().g>0&&profile.ambient().b>0);
            assertTrue(profile.key().r>0&&profile.key().g>0&&profile.key().b>0);
            assertTrue(Float.isFinite(profile.glow())&&profile.glow()>0);
        }
    }
    @Test void garageLightingReturnsToTheSameArenaWithoutAddingASecondPipeline() {
        try(var fixture=new Fixture()) {
            fixture.lighting.apply(Theme.NEON,false);
            var arenaSky=fixture.viewport.getBackgroundColor().clone();
            fixture.lighting.applyMenu(true);
            assertNotEquals(arenaSky,fixture.viewport.getBackgroundColor());
            assertTrue(fixture.bloom.isEnabled());
            fixture.lighting.apply(Theme.NEON,false);
            assertEquals(arenaSky,fixture.viewport.getBackgroundColor());
            assertFalse(fixture.bloom.isEnabled());
            assertSinglePipeline(fixture);
        }
    }

    @Test void startupWithoutGlowRoutesTheSameThemeToTheOriginalFramebuffer() {
        try(var fixture=new Fixture()) {
            fixture.lighting.apply(Theme.INDUSTRIAL_YARD,false);
            assertFalse(fixture.bloom.isEnabled());
            fixture.initialize();
            fixture.post.preFrame(0);
            assertSame(fixture.output,fixture.viewport.getOutputFrameBuffer(),
                    "Disabled bloom must not strand the scene in an offscreen buffer at startup");
            for(int frame=0;frame<10;frame++) {
                fixture.lighting.apply(Theme.INDUSTRIAL_YARD,false);
                fixture.post.preFrame(.016f);
                assertSame(fixture.output,fixture.viewport.getOutputFrameBuffer(),
                        "A same-theme cache hit must not need a later theme change to restore output");
            }
        }
    }

    @Test void anyPreInitializationToggleSequencePreservesItsFinalRouting() {
        for(boolean enabled:new boolean[]{false,true}) {
            try(var fixture=new Fixture()) {
                fixture.lighting.apply(Theme.NEON,false);
                fixture.lighting.apply(Theme.CONSTRUCTION,true);
                fixture.lighting.apply(Theme.INDUSTRIAL_YARD,enabled);
                fixture.initialize();fixture.post.preFrame(0);
                assertEquals(enabled,fixture.bloom.isEnabled());
                assertEquals(enabled,fixture.viewport.getOutputFrameBuffer()!=fixture.output);
                assertSinglePipeline(fixture);
            }
        }
    }

    @Test void liveOnOffAndReshapeRetainExactlyOnePipelineAndRestoreOutput() {
        try(var fixture=new Fixture()) {
            fixture.initialize();fixture.post.preFrame(0);
            assertNotSame(fixture.output,fixture.viewport.getOutputFrameBuffer());
            for(var theme:Theme.values())for(boolean enabled:new boolean[]{false,true,false}) {
                fixture.lighting.apply(theme,enabled);fixture.post.preFrame(.016f);
                assertEquals(enabled,fixture.viewport.getOutputFrameBuffer()!=fixture.output);
                fixture.post.reshape(fixture.viewport,96,80);fixture.post.preFrame(.016f);
                assertEquals(enabled,fixture.viewport.getOutputFrameBuffer()!=fixture.output,
                        "Reshaping must retain the enabled state and original output target");
                assertEquals(96,fixture.viewport.getCamera().getWidth());
                assertEquals(80,fixture.viewport.getCamera().getHeight());
                assertSinglePipeline(fixture);
            }
        }
    }

    @Test void msaaOffUsesOnePostSampleAndEverySupportedSettingCanBeRepeated() {
        try(var fixture=new Fixture()) {
            for(int samples:new int[]{0,2,4,8,0}) {
                assertDoesNotThrow(()->fixture.lighting.setSamples(samples));
                assertEquals(Math.max(1,samples),fixture.post.getNumSamples());
                fixture.lighting.setSamples(samples);
                assertEquals(Math.max(1,samples),fixture.post.getNumSamples());
            }
            for(int invalid:new int[]{-1,1,3,16}) {
                assertThrows(IllegalArgumentException.class,()->fixture.lighting.setSamples(invalid));
                assertEquals(1,fixture.post.getNumSamples(),"Invalid settings cannot mutate a valid post configuration");
            }
            fixture.lighting.apply(Theme.INDUSTRIAL_YARD,false);
            fixture.initialize();fixture.post.preFrame(0);
            assertSame(fixture.output,fixture.viewport.getOutputFrameBuffer());
        }
    }

    @Test void firstGarageFrameAndGlowChangesKeepTheirAuthoredProjectionThroughTheRealProcessorLifecycle() {
        for(boolean initialGlow:new boolean[]{true,false})try(var fixture=new Fixture(1280,720);
                var garage=new GaragePresentation(PresentationTestAssets.shared(),VehicleRules.load())) {
            fixture.root.attachChild(garage.node());fixture.lighting.applyMenu(initialGlow);fixture.initialize();
            Camera camera=fixture.viewport.getCamera();
            for(int[] size:new int[][]{{1280,720},{640,480},{3840,2160}}) {
                camera.resize(size[0],size[1],true);fixture.post.reshape(fixture.viewport,size[0],size[1]);
                for(boolean glow:new boolean[]{initialGlow,false,true,false}) {
                    fixture.lighting.applyMenu(glow);
                    for(String profile:java.util.List.of("rivet","grinder","spark")) {
                        garage.show(profile);garage.update(1.289506f,camera,.36125f,.17f);
                        var projection=camera.getProjectionMatrix().clone();
                        // RenderManager initializes processors lazily after simpleUpdate. An
                        // uninitialized FPP would reshape and center the already-framed car here.
                        for(var processor:fixture.viewport.getProcessors()) {
                            if(!processor.isInitialized())processor.initialize(fixture.renderManager,fixture.viewport);
                            processor.preFrame(1f/60);
                        }
                        assertEquals(projection,camera.getProjectionMatrix(),"First render/glow toggle changed garage framing");
                        fixture.lighting.initialize(fixture.renderManager);
                        assertEquals(projection,camera.getProjectionMatrix(),"Repeated initialization changed the camera");
                        fixture.root.updateGeometricState();
                        BoundingBox bounds=(BoundingBox)garage.node().getChild("garage-vehicle").getWorldBound();
                        for(int x:new int[]{-1,1})for(int y:new int[]{-1,1})for(int z:new int[]{-1,1}) {
                            Vector3f pixel=camera.getScreenCoordinates(bounds.getCenter().add(x*bounds.getXExtent(),y*bounds.getYExtent(),z*bounds.getZExtent()));
                            assertTrue(pixel.x>=size[0]*.36125f&&pixel.x<=size[0]&&pixel.y>=size[1]*.17f&&pixel.y<=size[1],
                                    profile+" clipped after actual processor callbacks: "+pixel);
                        }
                        assertEquals(glow,fixture.viewport.getOutputFrameBuffer()!=fixture.output);
                        assertSinglePipeline(fixture);
                    }
                }
            }
        }
    }

    private static void assertSinglePipeline(Fixture fixture) {
        assertEquals(2,fixture.viewport.getProcessors().size(),"One shared shadow renderer and one post processor");
        assertEquals(1,fixture.viewport.getProcessors().stream().filter(FilterPostProcessor.class::isInstance).count());
        assertEquals(2,fixture.post.getFilterList().size(),"One bloom and one match-only soft VFX compositor");
        assertSame(fixture.bloom,fixture.post.getFilterList().getFirst());
    }
    @Test void softVfxKeepsDepthAvailableWithBloomOffAndUnbindRestoresNormalOutput() {
        try(var fixture=new Fixture();var visuals=new CombatVisuals(PresentationTestAssets.shared(),fixture.root,new SoftWorld())) {
            fixture.lighting.apply(Theme.NEON,false);
            fixture.lighting.bindCombatVisuals(visuals);fixture.initialize();fixture.post.preFrame(0);
            assertNotSame(fixture.output,fixture.viewport.getOutputFrameBuffer());
            assertFalse(fixture.bloom.isEnabled());
            for(int[] size:new int[][]{{96,80},{120,90}}) {
                fixture.post.reshape(fixture.viewport,size[0],size[1]);fixture.post.preFrame(0);
                assertNotNull(fixture.post.getDepthTexture());
            }
            fixture.lighting.bindCombatVisuals(null);fixture.post.preFrame(0);
            assertSame(fixture.output,fixture.viewport.getOutputFrameBuffer());
        }
    }
    private static final class SoftWorld implements game.wreckriff.simulation.WorldQuery {
        public Vector3f position(int id){return Vector3f.ZERO;} public Vector3f velocity(int id){return Vector3f.ZERO;}
        public com.jme3.math.Quaternion rotation(int id){return com.jme3.math.Quaternion.IDENTITY;}
        public boolean grounded(int id){return true;} public float mass(int id){return 1100;}
        public Hit ray(Vector3f a,Vector3f b,int id){return null;}
        public Hit sweep(Vector3f a,Vector3f b,float r,int id,float start,float end){return null;}
        public Hit staticSweep(Vector3f a,Vector3f b,float r){return null;}
        public boolean visible(Vector3f a,Vector3f b,int id){return true;}
        public float distanceToHull(int id,Vector3f p){return 0;}
        public void impulse(int id,Vector3f a,Vector3f b,float cap){throw new AssertionError("Presentation cannot change physics");}
        public Vector3f closestHullPoint(int id,Vector3f from){return Vector3f.ZERO;}
    }

    private static final class Fixture implements AutoCloseable {
        final FrameBuffer output;
        final ViewPort viewport;
        final Node root=new Node("scene");
        final RenderManager renderManager=new RenderManager(new NullRenderer());
        final SceneLighting.Handle lighting;
        final FilterPostProcessor post;
        final BloomFilter bloom;
        Fixture() {this(64,64);}
        Fixture(int width,int height) {
            output=new FrameBuffer(width,height,1);viewport=new ViewPort("lighting-test",new Camera(width,height));
            viewport.setOutputFrameBuffer(output);
            lighting=SceneLighting.install(PresentationTestAssets.shared(),root,viewport);
            post=(FilterPostProcessor)viewport.getProcessors().stream().filter(FilterPostProcessor.class::isInstance).findFirst().orElseThrow();
            bloom=post.getFilter(BloomFilter.class);
        }
        void initialize() {lighting.initialize(renderManager);}
        @Override public void close() {for(var processor:viewport.getProcessors())if(processor.isInitialized())processor.cleanup();output.dispose();}
    }
}
