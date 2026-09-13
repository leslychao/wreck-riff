package game.wreckriff.presentation;

import com.jme3.post.FilterPostProcessor;
import com.jme3.renderer.*;
import com.jme3.scene.*;
import com.jme3.system.NullRenderer;
import com.jme3.texture.FrameBuffer;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@org.junit.jupiter.api.extension.ExtendWith(PresentationTestAssets.class)
class CombatVfxFilterTest {
    @Test void timestampIncludesTheActualFppFullscreenComposite() {
        try(var fixture=new Fixture(false,false)) {
            fixture.post.postFrame(fixture.output);
            assertEquals(List.of("begin","resolve","fullscreen","end"),fixture.calls);
        }
    }
    @Test void timestampClosesOnResolveFailureWithoutPretendingToDrawTheComposite() {
        try(var fixture=new Fixture(true,false)) {
            assertThrows(IllegalStateException.class,()->fixture.post.postFrame(fixture.output));
            assertEquals(List.of("begin","resolve","end"),fixture.calls);
        }
    }
    @Test void timestampClosesIfTheDownstreamFppCompositeFails() {
        try(var fixture=new Fixture(false,true)) {
            assertThrows(IllegalStateException.class,()->fixture.post.postFrame(fixture.output));
            assertEquals(List.of("begin","resolve","fullscreen","end"),fixture.calls);
        }
    }
    private static final class Fixture implements AutoCloseable {
        final List<String> calls=new ArrayList<>();final FrameBuffer output=new FrameBuffer(32,32,1);
        final ViewPort viewport=new ViewPort("vfx-filter-test",new Camera(32,32));
        final FilterPostProcessor post;
        Fixture(boolean failResolve,boolean failComposite) {
            var renderer=new NullRenderer(){@Override public void copyFrameBuffer(FrameBuffer source,FrameBuffer target,boolean color,boolean depth){calls.add("resolve");if(failResolve)throw new IllegalStateException("resolve failed");}};
            var manager=new RenderManager(renderer){@Override public void renderGeometry(Geometry geometry){calls.add("fullscreen");if(failComposite)throw new IllegalStateException("composite failed");}};
            viewport.setOutputFrameBuffer(output);
            var lighting=SceneLighting.install(PresentationTestAssets.shared(),new Node(),viewport);lighting.applyMenu(false);
            lighting.setCombatVfxProbe(new CombatVfxFilter.Probe(){public void begin(String name){calls.add("begin");}public void end(String name){calls.add("end");}});
            post=(FilterPostProcessor)viewport.getProcessors().stream().filter(FilterPostProcessor.class::isInstance).findFirst().orElseThrow();
            post.getFilter(CombatVfxFilter.class).setEnabled(true);lighting.initialize(manager);post.preFrame(0);
        }
        public void close(){post.cleanup();output.dispose();}
    }
}
