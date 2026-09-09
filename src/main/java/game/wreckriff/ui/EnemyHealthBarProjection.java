package game.wreckriff.ui;

import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;
import game.wreckriff.config.VehicleProfile;
import game.wreckriff.simulation.PhysicsWorld.Pose;
import game.wreckriff.simulation.WorldQuery;

/** Read-only camera projection; occlusion always uses the existing world's real hulls and colliders. */
public final class EnemyHealthBarProjection {
    private EnemyHealthBarProjection() { }

    public static EnemyHealthBars.Marker project(Camera camera,WorldQuery world,int id,Pose pose,
                                                 float hp,float maximumHp,VehicleProfile profile) {
        if(!Float.isFinite(hp)||!Float.isFinite(maximumHp)||maximumHp<=0)
            throw new IllegalArgumentException("Invalid enemy health");
        if(hp<=0)return null;
        var bounds=profile.hullBounds();
        Vector3f[] corners=new Vector3f[8];float[] depths=new float[8];
        ScreenBounds screen=new ScreenBounds();
        for(int i=0;i<8;i++) {
            Vector3f local=new Vector3f((i&1)==0?bounds.minX():bounds.maxX(),
                    (i&2)==0?bounds.minY():bounds.maxY(),(i&4)==0?bounds.minZ():bounds.maxZ());
            corners[i]=pose.position().add(pose.rotation().mult(local));
            depths[i]=depth(camera,corners[i]);
            if(depths[i]>=camera.getFrustumNear()&&depths[i]<=camera.getFrustumFar())screen.include(camera,corners[i]);
        }
        // Clip the twelve hull-envelope edges to near/far planes before perspective division.
        // This avoids mirrored markers behind the camera and handles a nearby hull crossing its near plane.
        for(int i=0;i<8;i++)for(int bit=1;bit<=4;bit*=2) {
            int other=i^bit;if(other<=i)continue;
            for(float plane:new float[]{camera.getFrustumNear(),camera.getFrustumFar()}) {
                if((depths[i]<plane&&depths[other]>plane)||(depths[i]>plane&&depths[other]<plane)) {
                    float fraction=(plane-depths[i])/(depths[other]-depths[i]);
                    screen.include(camera,corners[i].clone().interpolateLocal(corners[other],fraction));
                }
            }
        }
        float left=camera.getWidth()*camera.getViewPortLeft(),right=camera.getWidth()*camera.getViewPortRight();
        float bottom=camera.getHeight()*camera.getViewPortBottom(),top=camera.getHeight()*camera.getViewPortTop();
        if(screen.maxX<left||screen.minX>right||screen.maxY<bottom||screen.minY>top)return null;
        if(!visibleHull(camera,world,id,profile))return null;
        return new EnemyHealthBars.Marker(id,(Math.max(left,screen.minX)+Math.min(right,screen.maxX))/2,
                Math.min(top,screen.maxY),Math.clamp(hp/maximumHp,0,1));
    }

    private static boolean visibleHull(Camera camera,WorldQuery world,int id,VehicleProfile profile) {
        Vector3f position=world.position(id);Quaternion rotation=world.rotation(id);
        for(var box:profile.hullBoxes()) {
            // Face centres and inset corners cover a visible cabin above cover and partial side exposure.
            // All endpoints lie inside a real native hull box, rather than an empty envelope corner.
            if(visiblePoint(camera,world,id,position.add(rotation.mult(box.center()))))return true;
            for(int axis=0;axis<3;axis++)for(int sign:new int[]{-1,1}) {
                Vector3f point=box.center();
                point.set(axis,point.get(axis)+box.extent().get(axis)*sign*.95f);
                if(visiblePoint(camera,world,id,position.add(rotation.mult(point))))return true;
            }
            for(int i=0;i<8;i++) {
                Vector3f point=box.center().add((i&1)==0?-box.halfWidth()*.95f:box.halfWidth()*.95f,
                        (i&2)==0?-box.halfHeight()*.95f:box.halfHeight()*.95f,
                        (i&4)==0?-box.halfLength()*.95f:box.halfLength()*.95f);
                if(visiblePoint(camera,world,id,position.add(rotation.mult(point))))return true;
            }
        }
        return false;
    }

    private static boolean visiblePoint(Camera camera,WorldQuery world,int id,Vector3f point) {
        float depth=depth(camera,point);
        if(depth<camera.getFrustumNear()||depth>camera.getFrustumFar())return false;
        Vector3f screen=camera.getScreenCoordinates(point);
        if(screen.x<camera.getWidth()*camera.getViewPortLeft()||screen.x>camera.getWidth()*camera.getViewPortRight()
                ||screen.y<camera.getHeight()*camera.getViewPortBottom()||screen.y>camera.getHeight()*camera.getViewPortTop())return false;
        return world.visible(camera.getLocation(),point,id);
    }

    private static float depth(Camera camera,Vector3f point) {return point.subtract(camera.getLocation()).dot(camera.getDirection());}

    private static final class ScreenBounds {
        float minX=Float.POSITIVE_INFINITY,minY=Float.POSITIVE_INFINITY;
        float maxX=Float.NEGATIVE_INFINITY,maxY=Float.NEGATIVE_INFINITY;
        void include(Camera camera,Vector3f point) {
            Vector3f screen=camera.getScreenCoordinates(point);
            if(!Float.isFinite(screen.x)||!Float.isFinite(screen.y))return;
            minX=Math.min(minX,screen.x);maxX=Math.max(maxX,screen.x);
            minY=Math.min(minY,screen.y);maxY=Math.max(maxY,screen.y);
        }
    }
}
