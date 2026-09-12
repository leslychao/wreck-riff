package game.wreckriff.presentation;

import com.jme3.post.FilterPostProcessor;
import com.jme3.post.filters.BloomFilter;
import com.jme3.renderer.Camera;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.ViewPort;
import com.jme3.scene.Node;
import com.jme3.system.NullRenderer;
import com.jme3.texture.FrameBuffer;
import game.wreckriff.arena.ArenaDefinition.Theme;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the real jME post-processing lifecycle without claiming an OpenGL frame. */
class SceneLightingTest {
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

    private static void assertSinglePipeline(Fixture fixture) {
        assertEquals(2,fixture.viewport.getProcessors().size(),"One shared shadow renderer and one post processor");
        assertEquals(1,fixture.viewport.getProcessors().stream().filter(FilterPostProcessor.class::isInstance).count());
        assertEquals(1,fixture.post.getFilterList().size(),"No always-enabled passthrough filter is needed");
        assertSame(fixture.bloom,fixture.post.getFilterList().getFirst());
    }

    private static final class Fixture implements AutoCloseable {
        final FrameBuffer output=new FrameBuffer(64,64,1);
        final ViewPort viewport=new ViewPort("lighting-test",new Camera(64,64));
        final SceneLighting.Handle lighting;
        final FilterPostProcessor post;
        final BloomFilter bloom;
        Fixture() {
            viewport.setOutputFrameBuffer(output);
            lighting=SceneLighting.install(PresentationTestAssets.shared(),new Node("scene"),viewport);
            post=(FilterPostProcessor)viewport.getProcessors().stream().filter(FilterPostProcessor.class::isInstance).findFirst().orElseThrow();
            bloom=post.getFilter(BloomFilter.class);
        }
        void initialize() {post.initialize(new RenderManager(new NullRenderer()),viewport);}
        @Override public void close() {if(post.isInitialized())post.cleanup();output.dispose();}
    }
}
