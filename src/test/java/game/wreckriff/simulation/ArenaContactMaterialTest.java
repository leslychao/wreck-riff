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
            for(var box:arena.boxes())known(box.surfaceMaterial(arena.metadata().theme()),resource+"/"+box.id());
            for(var ramp:arena.ramps())known(ramp.material(),resource+"/"+ramp.id());
            for(var mesh:arena.meshes()) {
                known(mesh.material(),resource+"/"+mesh.id());
                for(String material:mesh.triangleMaterials())known(material,resource+"/"+mesh.id());
                if(mesh.thickness()>0)known(mesh.structureMaterial(),resource+"/"+mesh.id()+" underside");
            }
        }
        for(String material:List.of("cast-concrete","park-paving"))assertEquals(ContactSurface.CONCRETE,ContactSurface.fromMaterial(material));
        assertEquals(ContactSurface.ASPHALT,ContactSurface.fromMaterial("road-marking"));
        assertEquals(ContactSurface.UNKNOWN,ContactSurface.fromMaterial("not-authored"));
    }
    @Test void sharedFinishRulesPreserveThemeAndRoadStructureAppearance() {
        var center=new ArenaDefinition.Vec3(0,0,0);var size=new ArenaDefinition.Vec3(1,1,1);
        var rust=new ArenaDefinition.BoxPart("rust",center,size,"rust",true);
        assertEquals("dark-concrete",rust.surfaceMaterial(ArenaDefinition.Theme.NEON));
        assertEquals("rust",rust.surfaceMaterial(ArenaDefinition.Theme.CONSTRUCTION));
        assertEquals("faded-red",new ArenaDefinition.BoxPart("red",center,size,"red",true).surfaceMaterial(ArenaDefinition.Theme.CARNIVAL));
        for(String finish:List.of("road-surface","road-wet","park-paving","cast-concrete")) {
            var mesh=new ArenaDefinition.TriangleSurface("surface",List.of(center,new ArenaDefinition.Vec3(0,0,1),new ArenaDefinition.Vec3(1,0,0)),
                    List.of(0,1,2),finish,true,List.of(),1);
            assertEquals("cast-concrete",mesh.structureMaterial());
        }
        for(String finish:List.of("wood","steel","purple","faded-red")) {
            var roof=new ArenaDefinition.TriangleSurface("roof",List.of(center,new ArenaDefinition.Vec3(0,0,1),new ArenaDefinition.Vec3(1,0,0)),
                    List.of(0,1,2),finish,true,List.of(),.35f);
            assertEquals(finish,roof.structureMaterial());
        }
    }
    private static void known(String material,String location){assertNotEquals(ContactSurface.UNKNOWN,ContactSurface.fromMaterial(material),location+" material="+material);}
}
