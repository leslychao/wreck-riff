package game.wreckriff.presentation;

import com.jme3.asset.DesktopAssetManager;
import com.jme3.collision.CollisionResults;
import com.jme3.math.*;
import com.jme3.scene.*;
import game.wreckriff.arena.*;
import game.wreckriff.config.VehicleRules;
import game.wreckriff.simulation.PhysicsWorld;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Compares Bullet contact to actual visible high-detail triangles, not to an invisible roof proxy. */
class NativeArchitectureCollisionTest {
    private static final DesktopAssetManager ASSETS=NativeArenaAssets.MANAGER;
    @org.junit.jupiter.api.BeforeAll static void loadNativeShapes() {
        com.jme3.system.NativeLibraryLoader.loadNativeLibrary("bulletjme",true);
    }
    @ParameterizedTest @ValueSource(strings={"construction_17","neon_zero","euphoria_park"})
    void structuralModelRaysMatchTheVisibleArchitecture(String arenaId) {
        var definition=ArenaRegistry.load().definition(arenaId);var art=ArenaArt.load(definition);
        var content=new ArenaFactory(ASSETS).build(definition,art);int roofs=0;
        for(var instance:art.models()) {
            if(instance.collisionGeometryIds().isEmpty())continue;
            roofs++;
            for(String proxy:instance.collisionGeometryIds())assertTrue(content.bodies().stream().noneMatch(body->body.id().equals(proxy)),"Remove flat proxy "+proxy);
            var body=content.bodies().stream().filter(candidate->candidate.id().equals("architecture-"+instance.id())).findFirst().orElseThrow();
            Node visible=new Node(instance.id());visible.setLocalTranslation(instance.position().vector());visible.setLocalScale(instance.size().vector());
            visible.setLocalRotation(new Quaternion().fromAngles(instance.rotation().vector().mult(FastMath.DEG_TO_RAD).toArray(null)));
            visible.attachChild(ASSETS.loadModel(instance.asset()));visible.updateGeometricState();
            List<Geometry> geometries=new ArrayList<>();visible.depthFirstTraversal(spatial->{if(spatial instanceof Geometry geometry)geometries.add(geometry);});
            int checked=0;
            try(var world=new PhysicsWorld(VehicleRules.load())) {
                world.addStatic(body.id(),body.shape(),body.position(),body.rotation());
                for(var geometry:geometries)for(int triangle=0;triangle<geometry.getMesh().getTriangleCount();triangle+=Math.max(1,geometry.getMesh().getTriangleCount()/4)) {
                    Vector3f a=new Vector3f(),b=new Vector3f(),c=new Vector3f();geometry.getMesh().getTriangle(triangle,a,b,c);
                    a=geometry.localToWorld(a,null);b=geometry.localToWorld(b,null);c=geometry.localToWorld(c,null);
                    Vector3f normal=b.subtract(a).cross(c.subtract(a));if(normal.lengthSquared()<.04f)continue;normal.normalizeLocal();
                    Vector3f center=a.add(b).addLocal(c).divideLocal(3),from=center.add(normal.mult(.6f)),to=center.subtract(normal.mult(.6f));
                    Ray ray=new Ray(from,normal.negate());ray.setLimit(1.2f);CollisionResults expected=new CollisionResults();visible.collideWith(ray,expected);
                    assertTrue(expected.size()>0,"Fixture ray must hit visible architecture");
                    var actual=world.ray(from,to,-1);assertNotNull(actual,"Native collider missing for "+instance.id());
                    assertEquals(body.id(),actual.objectId());
                    assertTrue(actual.point().distance(expected.getClosestCollision().getContactPoint())<.02f,
                            "Native roof differs from visible triangles: "+instance.id()+" / "+geometry.getName());
                    checked++;
                }
            }
            assertTrue(checked>=4,"Sample the authored roof from several faces");
        }
        assertTrue(roofs>=3,"At least the three required interior models; outdoor structures share the same exact collision contract");
    }
}
