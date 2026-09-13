package game.wreckriff.arena;

import com.jme3.math.Vector3f;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RoadStructureTest {
    @Test void sharedEdgesIncludingClippedTJunctionsHaveNoInteriorWalls() {
        var meshes=List.of(rect("west",0,10,0,20),rect("east-south",10,20,0,8),rect("east-north",10,20,8,20));
        var shells=RoadStructure.shells(meshes);float bottomArea=0,sideArea=0;
        for(var triangles:shells.values())for(int i=0;i<triangles.size();i+=3) {
            var a=triangles.get(i);var b=triangles.get(i+1);var c=triangles.get(i+2);
            var normal=b.subtract(a).cross(c.subtract(a));float area=normal.length()/2;
            if(normal.y<0)bottomArea+=area;
            else {
                assertEquals(0,normal.y,.0001f);sideArea+=area;
                assertFalse(Math.abs(a.x-10)<.001&&Math.abs(b.x-10)<.001&&Math.abs(c.x-10)<.001,
                        "No coincident shared edge or invisible wall at the road junction");
                var center=a.add(b).addLocal(c).divideLocal(3);
                assertTrue(normal.dot(new Vector3f(center.x-10,0,center.z-10))>0,"Exterior walls face outside");
            }
        }
        assertEquals(400,bottomArea,.01f);assertEquals(80*1.2f,sideArea,.01f);
    }
    @Test void roadSupportRemainsOnTheAuthoredTopWhileStructureIsBelow() {
        var road=rect("raised",0,10,0,10);
        assertEquals(6,road.heightAt(5,5));assertEquals(Vector3f.UNIT_Y,road.normalAt(5,5));
        var shell=RoadStructure.shells(List.of(road)).get(road.id());
        assertTrue(shell.stream().allMatch(p->p.y>=4.8f-.0001f&&p.y<=6));
        assertThrows(IllegalArgumentException.class,()->new ArenaDefinition.TriangleSurface(road.id(),road.vertices(),road.indices(),"concrete",true,List.of(),-1));
    }
    private static ArenaDefinition.TriangleSurface rect(String id,float minX,float maxX,float minZ,float maxZ) {
        var vertices=List.of(new ArenaDefinition.Vec3(minX,6,minZ),new ArenaDefinition.Vec3(minX,6,maxZ),
                new ArenaDefinition.Vec3(maxX,6,maxZ),new ArenaDefinition.Vec3(maxX,6,minZ));
        return new ArenaDefinition.TriangleSurface(id,vertices,List.of(0,1,2,0,2,3),"road-surface",true,List.of(),1.2f);
    }
}
