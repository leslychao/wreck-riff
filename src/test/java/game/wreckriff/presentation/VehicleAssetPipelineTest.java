package game.wreckriff.presentation;

import com.google.gson.JsonParser;
import com.jme3.asset.DesktopAssetManager;
import com.jme3.asset.ModelKey;
import com.jme3.scene.Spatial;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VehicleAssetPipelineTest {
    @Test void scalarValidationUsesRawSamplesAndRejectsAnAwtGrayTransfer() throws Exception {
        Path folder=Files.createTempDirectory("vehicle-scalar-check");
        try {
            var source=new BufferedImage(16,16,BufferedImage.TYPE_INT_ARGB);var good=new BufferedImage(16,16,BufferedImage.TYPE_BYTE_GRAY);var bad=new BufferedImage(16,16,BufferedImage.TYPE_BYTE_GRAY);
            for(int y=0;y<16;y++)for(int x=0;x<16;x++){int value=y*16+x,pixel=0xff000000|value<<16|value<<8|value;source.setRGB(x,y,pixel);good.getRaster().setSample(x,y,0,value);bad.setRGB(x,y,pixel);}
            Path original=folder.resolve("source.png"),correct=folder.resolve("good.png"),gammaChanged=folder.resolve("bad.png");ImageIO.write(source,"png",original.toFile());ImageIO.write(good,"png",correct.toFile());ImageIO.write(bad,"png",gammaChanged.toFile());
            assertEquals(341,VehicleAssetsVerifier.verifyTextureChannels(correct,original,"L8"));
            assertThrows(IOException.class,()->VehicleAssetsVerifier.verifyTextureChannels(gammaChanged,original,"L8"));
        } finally {try(var files=Files.list(folder)){for(Path path:files.toList())Files.delete(path);}Files.delete(folder);}
    }
    @Test void runtimeVehicleNormalAndSpecularMapsPreserveEverySample() throws Exception {
        for(String profile:List.of("rivet","grinder","spark","boss_foreman","boss_prefect","boss_emcee","shared"))
            for(String map:List.of("normal","specular")) {
                String name=(profile.equals("shared")?"metal-":"")+map+".png";
                Path source=Path.of("src/tools/assets/vehicles",profile,name),runtime=Path.of("src/main/resources/textures/vehicles",profile,name);
                var original=ImageIO.read(source.toFile());var prepared=ImageIO.read(runtime.toFile());
                boolean scalar=map.equals("specular");
                assertEquals(scalar?1:3,prepared.getRaster().getNumBands(),runtime.toString());
                assertFalse(prepared.getColorModel().hasAlpha(),runtime.toString());
                assertEquals(original.getWidth(),prepared.getWidth());assertEquals(original.getHeight(),prepared.getHeight());
                for(int y=0;y<original.getHeight();y++) {
                    int[] pixels=original.getRGB(0,y,original.getWidth(),1,null,0,original.getWidth());
                    int[] output=scalar?prepared.getRaster().getSamples(0,y,prepared.getWidth(),1,0,(int[])null):prepared.getRGB(0,y,prepared.getWidth(),1,null,0,prepared.getWidth());
                    for(int x=0;x<pixels.length;x++) {
                        int pixel=pixels[x];assertEquals(255,pixel>>>24);
                        if(scalar){int value=pixel&255;assertEquals(value,pixel>>>8&255);assertEquals(value,pixel>>>16&255);assertEquals(value,output[x]);}
                        else assertEquals(pixel,output[x]);
                    }
                }
            }
    }
    @Test void offlineVerifierIncludesRealModelsSourcesAtlasAndTexturesInReleaseInventory() throws Exception {
        var assets=new ArrayList<game.wreckriff.diagnostics.VerifyAssets.Asset>();var manager=new VerifierAssets(null);
        VehicleAssetsVerifier.verifyOwned(assets,manager);
        assertEquals(1,manager.clearCount);
        assertNull(manager.getFromCache(new ModelKey("models/vehicles/rivet/vehicle.j3o")));
        for(String path:List.of("models/vehicles/rivet/vehicle.j3o","src/tools/assets/vehicles/rivet/source.blend","src/tools/assets/vehicles/rivet/source.json","textures/vehicles/shared/damage-atlas.png","textures/vehicles/rivet/damage-ownership.png"))
            assertTrue(assets.stream().anyMatch(a->a.path().equals(path)&&a.sha256().length()==64),path);
    }
    @Test void failedVerifierReleasesModelsCachedBeforeTheFailure() {
        var manager=new VerifierAssets("models/vehicles/rivet/vehicle.j3o");
        var error=assertThrows(IllegalStateException.class,()->VehicleAssetsVerifier.verifyOwned(new ArrayList<>(),manager));
        assertEquals("injected model failure",error.getMessage());
        assertTrue(manager.sawCachedRivet,"Failure must occur after a real model entered the cache");
        assertEquals(1,manager.clearCount);
        assertNull(manager.getFromCache(new ModelKey("models/vehicles/rivet/vehicle.j3o")));
    }
    private static final class VerifierAssets extends DesktopAssetManager {
        private final String failAt;int clearCount;boolean sawCachedRivet;
        VerifierAssets(String failAt){super(true);this.failAt=failAt;}
        @Override public Spatial loadModel(String name) {
            Spatial model=super.loadModel(name);
            if(name.equals(failAt)){sawCachedRivet=getFromCache(new ModelKey("models/vehicles/rivet/vehicle.j3o"))!=null;throw new IllegalStateException("injected model failure");}
            return model;
        }
        @Override public void clearCache(){clearCount++;super.clearCache();}
    }
    @Test void everyPlayableAndBossProfileHasPreparedAuthoredDamageAndLodAssets() throws Exception {
        Path manifest=Path.of("src/main/resources/models/vehicles/provenance.json");
        assertTrue(Files.isRegularFile(manifest),"Offline vehicle exports must be present before an ordinary build");
        var data=JsonParser.parseString(Files.readString(manifest)).getAsJsonObject();
        Set<String> expected=new HashSet<>(List.of("rivet","grinder","spark","boss_foreman","boss_prefect","boss_emcee"));
        for(var item:data.getAsJsonArray("profiles")) {
            var profile=item.getAsJsonObject();assertTrue(expected.remove(profile.get("id").getAsString()));
            assertEquals(5,profile.get("stages").getAsInt());assertEquals(8,profile.get("regions").getAsInt());
            assertEquals(3,profile.getAsJsonArray("lodTriangles").size());
            assertTrue(Files.isRegularFile(Path.of("src/main/resources",profile.get("model").getAsString())));
        }
        assertTrue(expected.isEmpty());
    }
}
