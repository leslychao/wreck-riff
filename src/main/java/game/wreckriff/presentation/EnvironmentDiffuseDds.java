package game.wreckriff.presentation;

import com.jme3.asset.AssetManager;
import com.jme3.texture.Texture;
import java.io.IOException;
import java.util.List;

/** Environment maps share the already verified DX10 BC7 sRGB container contract. */
public final class EnvironmentDiffuseDds {
    // Leafy grass retains its lossless PNG: BC7 exceeded the photographic quality bound.
    public static final List<String> MATERIALS=List.of("asphalt_02","asphalt_pit_lane","blue_metal_plate","brown_mud","concrete_wall_009","cracked_concrete","dirt","grass_ground","gravelly_sand","metal_plate_02","red_brick_03","rusty_metal_03","wood_planks_grey");
    private EnvironmentDiffuseDds(){}
    public static VehicleDiffuseDds read(byte[] bytes)throws IOException{return VehicleDiffuseDds.read(bytes,2048);}
    public static Texture load(AssetManager assets,String path){return VehicleDiffuseDds.load(assets,path);}
}
