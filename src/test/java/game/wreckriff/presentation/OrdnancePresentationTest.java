package game.wreckriff.presentation;

import com.jme3.math.*;
import com.jme3.scene.*;
import game.wreckriff.combat.CombatSystem;
import game.wreckriff.combat.ProjectilePresentationFixtures;
import game.wreckriff.combat.ProjectileState;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class OrdnancePresentationTest {
    @Test void freezeKeepsItsLuminousIceNoseAndTechnicalSilhouetteAtBothDistances() {
        Node scene=new Node();
        try(var view=new OrdnancePresentation(PresentationTestAssets.shared(),scene)) {
            var projectile=state(77,"freeze",Vector3f.ZERO,Vector3f.UNIT_Z);
            for(Vector3f observer:List.of(Vector3f.ZERO,new Vector3f(100,0,0))) {
                view.update(List.of(projectile),List.of(),observer);Node model=view.instance(77,false);
                Geometry core=(Geometry)model.getChild("signal");var positions=core.getMesh().getFloatBuffer(VertexBuffer.Type.Position);
                float nose=0,rear=1;
                for(int vertex=0;vertex<positions.limit()/3;vertex++){nose=Math.max(nose,positions.get(vertex*3+2));rear=Math.min(rear,positions.get(vertex*3+2));}
                assertTrue(nose>=.45f&&rear<0,"The ice core must occupy the projectile, not just one thin glowing ring");
                assertNotEquals(Spatial.CullHint.Always,core.getLocalCullHint());
                Geometry body=(Geometry)model.getChild(observer.lengthSquared()<1?"detail":"distance");
                var bound=(com.jme3.bounding.BoundingBox)body.getMesh().getBound();
                assertTrue(bound.getXExtent()>=.2f,"Technical fins keep a readable silhouette at both LODs");
                assertEquals(-.24f,OrdnanceStyle.FREEZE.nozzleZ());
            }
        }
    }
    @Test void stableIdsShareImmutableMeshesFollowAuthoritativePosesAndReuseRemovedSlots() {
        Node scene=new Node();
        try(var view=new OrdnancePresentation(PresentationTestAssets.shared(),scene)) {
            var first=state(1,"power",new Vector3f(2,3,4),Vector3f.UNIT_Z);
            var second=state(2,"power",new Vector3f(5,3,4),Vector3f.UNIT_Z);
            view.update(List.of(first,second),List.of(),Vector3f.ZERO);
            Node a=view.instance(1,false),b=view.instance(2,false);Mesh mesh=((Geometry)a.getChild("detail")).getMesh();
            assertSame(mesh,((Geometry)b.getChild("detail")).getMesh());
            assertSame(((Geometry)a.getChild("detail")).getMaterial(),((Geometry)b.getChild("detail")).getMaterial());
            var vertices=mesh.getFloatBuffer(VertexBuffer.Type.Position);float[] original=new float[vertices.limit()];vertices.asReadOnlyBuffer().get(original);
            for(int frame=0;frame<40;frame++) {
                Vector3f direction=new Vector3f(.2f,.85f,-.3f).normalizeLocal(),position=new Vector3f(frame,4,7);
                view.update(List.of(state(1,"power",position,direction),second),List.of(),position);
                assertSame(a,view.instance(1,false));assertEquals(position,a.getLocalTranslation());
                assertTrue(a.getLocalRotation().mult(Vector3f.UNIT_Z).distance(direction)<.00001f);
            }
            float[] current=new float[vertices.limit()];vertices.asReadOnlyBuffer().get(current);assertArrayEquals(original,current);
            view.update(List.of(second),List.of(),Vector3f.ZERO);assertNull(a.getParent());
            view.update(List.of(state(3,"power",Vector3f.ZERO,Vector3f.UNIT_Z),second),List.of(),Vector3f.ZERO);
            assertSame(a,view.instance(3,false));assertEquals(2,view.allocatedCount());
        }
        assertEquals(0,scene.getQuantity());
    }
    @Test void everyProjectileFamilySelectsItsModelAndDistanceDetailWithoutReplacingMesh() {
        Node scene=new Node();
        try(var view=new OrdnancePresentation(PresentationTestAssets.shared(),scene)) {
            List<ProjectileState> states=new ArrayList<>();int id=1;
            for(var style:OrdnanceStyle.values())if(style!=OrdnanceStyle.MINE)states.add(state(id++,style.kind(),new Vector3f(id,1,3),Vector3f.UNIT_Y));
            view.update(states,List.of(),Vector3f.ZERO);
            for(var state:states) {
                Node model=view.instance(state.id(),false);assertEquals(state.kind(),model.getUserData("ordnanceKind"));
                assertEquals(Spatial.CullHint.Dynamic,model.getChild("detail").getLocalCullHint());
                assertEquals(Spatial.CullHint.Always,model.getChild("distance").getLocalCullHint());
                assertTrue(model.getLocalRotation().mult(Vector3f.UNIT_Z).distance(Vector3f.UNIT_Y)<.00001f);
            }
            view.update(states,List.of(),new Vector3f(400,0,0));
            for(var state:states)assertEquals(Spatial.CullHint.Dynamic,view.instance(state.id(),false).getChild("distance").getLocalCullHint());
        }
    }
    @Test void mineContactMatchesSurfaceNormalAndArmingDoesNotChangeTheBodyOrPausedPose() {
        Node scene=new Node();Vector3f point=new Vector3f(3,8,4),normal=new Vector3f(-.4f,1,.25f).normalizeLocal();
        try(var view=new OrdnancePresentation(PresentationTestAssets.shared(),scene)) {
            var snapshot=mine(4,point,normal.mult(3),false);Vector3f originalNormal=snapshot.normal().clone();
            view.update(List.of(),List.of(snapshot),point);Node model=view.instance(4,true);
            assertEquals(originalNormal,snapshot.normal(),"Presentation must not normalize the published snapshot in place");
            assertTrue(model.getLocalRotation().mult(Vector3f.UNIT_Y).distance(normal)<.00001f);
            assertEquals(.015f,model.getLocalTranslation().subtract(point).dot(normal),.000001f);
            var body=((Geometry)model.getChild("detail")).getMaterial();var safe=((Geometry)model.getChild("signal")).getMaterial();
            Quaternion before=model.getLocalRotation().clone();Vector3f location=model.getLocalTranslation().clone();
            for(int frame=0;frame<100;frame++)view.update(List.of(),List.of(snapshot),point);
            assertEquals(before,model.getLocalRotation());assertEquals(location,model.getLocalTranslation());
            view.update(List.of(),List.of(mine(4,point,normal,true)),point);
            assertNotSame(safe,((Geometry)model.getChild("signal")).getMaterial());assertSame(body,((Geometry)model.getChild("detail")).getMaterial());
            view.update(List.of(),List.of(),point);assertNull(model.getParent());assertEquals(0,view.mineCount());
        }
    }
    @Test void saturatedFamilyChangesNeverRetainMoreThanExistingSimulationBudgetsAndRetryIsEmpty() {
        Node scene=new Node();
        for(int retry=0;retry<3;retry++)try(var view=new OrdnancePresentation(PresentationTestAssets.shared(),scene)) {
            for(int frame=0;frame<20;frame++) {
                List<ProjectileState> states=new ArrayList<>();List<CombatSystem.MineView> mines=new ArrayList<>();
                for(int i=0;i<90;i++)states.add(state(frame*100L+i,OrdnanceStyle.values()[frame%7].kind(),new Vector3f(i,1,2),Vector3f.UNIT_Z));
                for(int i=0;i<15;i++)mines.add(mine(frame*100L+i,new Vector3f(i,0,0),Vector3f.UNIT_Y,true));
                view.update(states,mines,Vector3f.ZERO);assertEquals(64,view.projectileCount());assertEquals(10,view.mineCount());assertEquals(74,view.allocatedCount());
            }
        }
        assertEquals(0,scene.getQuantity());
    }
    @Test void trailsOriginateAtNozzlesIncludingVerticalFlightAndCannonRebounds() {
        for(var style:OrdnanceStyle.values())if(style!=OrdnanceStyle.MINE)for(var direction:List.of(Vector3f.UNIT_Z,Vector3f.UNIT_Y,Vector3f.UNIT_X.negate())) {
            Vector3f centre=new Vector3f(4,6,2),anchor=OrdnancePresentation.trailAnchor(style.kind(),centre,direction);
            assertTrue(anchor.distance(centre.add(direction.mult(style.nozzleZ())))<.00001f);
        }
    }
    private static ProjectileState state(long id,String kind,Vector3f point,Vector3f direction){return ProjectilePresentationFixtures.state(id,kind,point,direction);}
    private static CombatSystem.MineView mine(long id,Vector3f point,Vector3f normal,boolean armed){return new CombatSystem.MineView(id,0,point,normal,armed,3);}
}
