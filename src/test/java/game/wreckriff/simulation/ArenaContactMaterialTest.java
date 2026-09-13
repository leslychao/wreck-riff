package game.wreckriff.simulation;

import game.wreckriff.arena.ArenaDefinition;
import game.wreckriff.config.Configs;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ArenaContactMaterialTest {
    @Test void everyAuthoredArenaContactFinishHasAnExplicitMaterial() {
        for(String resource:List.of("arena","arena-construction-17","arena-neon-zero","arena-euphoria-park")) {
            var arena=Configs.load(resource,ArenaDefinition.class);
            for(var box:arena.boxes())known(box.material(),resource+"/"+box.id());
            for(var ramp:arena.ramps())known(ramp.material(),resource+"/"+ramp.id());
            for(var mesh:arena.meshes()) {
                known(mesh.material(),resource+"/"+mesh.id());
                for(String material:mesh.triangleMaterials())known(material,resource+"/"+mesh.id());
            }
        }
        for(String material:List.of("cast-concrete","park-paving"))assertEquals(ContactSurface.CONCRETE,ContactSurface.fromMaterial(material));
        assertEquals(ContactSurface.ASPHALT,ContactSurface.fromMaterial("road-marking"));
        assertEquals(ContactSurface.UNKNOWN,ContactSurface.fromMaterial("not-authored"));
    }
    private static void known(String material,String location){assertNotEquals(ContactSurface.UNKNOWN,ContactSurface.fromMaterial(material),location+" material="+material);}
}
