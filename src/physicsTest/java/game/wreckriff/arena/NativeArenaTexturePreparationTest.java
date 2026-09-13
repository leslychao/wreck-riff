package game.wreckriff.arena;

import com.jme3.asset.DesktopAssetManager;
import com.jme3.asset.TextureKey;
import com.jme3.texture.Image;
import com.jme3.texture.Texture;
import game.wreckriff.config.VehicleRules;
import game.wreckriff.presentation.ArenaArt;
import game.wreckriff.presentation.SurfaceMaterials;
import game.wreckriff.simulation.PhysicsWorld;
import org.junit.jupiter.api.Test;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real local PNG decoding, clone-cache reuse and native arena construction; no renderer or window. */
class NativeArenaTexturePreparationTest {
    @Test void eachShippedArenaPlansExactlyTheSurfaceTexturesItsActualBuildRequests() {
        var registry=ArenaRegistry.load();
        // Avoid repeated decoding across this audit; each arena's held list models the application's loading lifetime.
        var retained=new HashMap<TextureKey,Texture>();
        try(var assets=new TrackingAssets();var world=new PhysicsWorld(VehicleRules.load())) {
            for(var entry:registry.entries()) {
                var arena=registry.definition(entry.id());var art=ArenaArt.load(arena);var factory=new ArenaFactory(assets);
                assets.requested.clear();var requirements=factory.textureRequirements(arena,art);
                assertTrue(assets.requested.isEmpty(),"Planning must not decode a texture");
                var planned=new LinkedHashSet<TextureKey>();var held=new ArrayList<Texture>();
                for(var requirement:requirements) {
                    TextureKey key=requirement.key();assertTrue(planned.add(key),"Each asset gets one loading step");
                    assertTrue(key.isFlipY());assertTrue(key.isGenerateMips());
                    Texture texture=requirement.load(assets);held.add(texture);retained.put(key,texture);
                }
                assertEquals(switch(entry.id()){case "neon_zero","doomsday_arena"->13;case "ash_necropolis"->14;default->16;},planned.size());
                assets.requested.clear();factory.build(arena,art);
                assertEquals(planned,assets.requested.keySet(),"Missing or speculative preload for "+entry.id());
                for(Texture texture:held)assertSame(texture.getImage(),assets.requested.get(texture.getKey()),
                        "Environment build must reuse the decoded image for "+texture.getKey());
                held.clear();
            }
        } finally {retained.clear();}
    }
    @Test void closingTheIndependentTraceCacheReleasesItsRealImageBufferEvenAfterFailure() throws Exception {
        for(boolean fail:List.of(false,true)) {
            WeakReference<ByteBuffer> buffer=closedTraceBuffer(fail);
            for(int attempt=0;attempt<40&&buffer.get()!=null;attempt++){System.gc();Thread.sleep(10);}
            assertNull(buffer.get(),"The headless loader thread must not keep a finished trace cache's decoded buffer alive");
        }
    }
    private static WeakReference<ByteBuffer> closedTraceBuffer(boolean fail) {
        WeakReference<ByteBuffer> buffer=new WeakReference<>(null);
        try(var assets=new TrackingAssets()) {
            // Loading a material also exercises jME's thread-local J3MLoader -> AssetManager reference.
            var material=new SurfaceMaterials(assets).material("yellow");
            buffer=new WeakReference<>(material.getTextureParam("DiffuseMap").getTextureValue().getImage().getData(0));
            if(fail)throw new IllegalStateException("fixture failure");
        } catch(IllegalStateException expected) {assertTrue(fail);assertEquals("fixture failure",expected.getMessage());}
        return buffer;
    }
    private static final class TrackingAssets extends DesktopAssetManager implements AutoCloseable {
        final Map<TextureKey,Image> requested=new LinkedHashMap<>();
        TrackingAssets(){super(true);}
        @Override public Texture loadTexture(TextureKey key) {
            Texture result=super.loadTexture(key);
            if(key.getName().startsWith("textures/"))requested.put(key,result.getImage());
            return result;
        }
        @Override public void close() {
            // A headless jME loader can retain its AssetManager in a thread-local after this test returns.
            // Only this private tracing cache is retired; shared arena images remain owned by their fixtures.
            clearCache();requested.clear();
        }
    }
}
