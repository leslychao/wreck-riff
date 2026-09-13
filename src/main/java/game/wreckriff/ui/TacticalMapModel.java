package game.wreckriff.ui;

import game.wreckriff.arena.ArenaDefinition;
import java.util.*;

/** North-up map projection. Pan and zoom never mutate simulation or navigation. */
public final class TacticalMapModel {
    public record Point(float x,float y) {}
    public record Segment(Point from,Point to) {}
    private final ArenaDefinition.Bounds bounds;
    private UiBounds viewport=new UiBounds(0,0,1,1);
    private float centerX,centerZ,zoom=1;
    public TacticalMapModel(ArenaDefinition.Bounds bounds) {this.bounds=Objects.requireNonNull(bounds);fit();}
    public void resize(UiBounds viewport) {this.viewport=Objects.requireNonNull(viewport);}
    public UiBounds viewport() {return viewport;}
    public float zoom() {return zoom;}
    public float centerX() {return centerX;}
    public float centerZ() {return centerZ;}
    public float pixelsPerMeter() {return Math.min(viewport.width()/(bounds.maxX()-bounds.minX()),viewport.height()/(bounds.maxZ()-bounds.minZ()))*zoom;}
    public void fit() {zoom=1;centerX=(bounds.minX()+bounds.maxX())/2;centerZ=(bounds.minZ()+bounds.maxZ())/2;}
    public void zoomBy(float factor) {if(Float.isFinite(factor)&&factor>0)zoom=Math.clamp(zoom*factor,1,8);}
    public void center(float x,float z) {centerX=Math.clamp(x,bounds.minX(),bounds.maxX());centerZ=Math.clamp(z,bounds.minZ(),bounds.maxZ());}
    public void pan(float horizontal,float vertical) {float step=Math.min(bounds.maxX()-bounds.minX(),bounds.maxZ()-bounds.minZ())*.12f/zoom;center(centerX+horizontal*step,centerZ+vertical*step);}
    public Point project(float x,float z) {return new Point(viewport.centerX()+(x-centerX)*pixelsPerMeter(),viewport.centerY()+(z-centerZ)*pixelsPerMeter());}
    public boolean visible(Point p) {return viewport.contains(p.x,p.y);}
    /** Clip a projected convex footprint to the current map viewport. */
    public List<Point> clipPolygon(List<Point> polygon) {
        List<Point> result=List.copyOf(polygon);
        for(int boundary=0;boundary<4;boundary++) {
            List<Point> output=new ArrayList<>();if(result.isEmpty())return List.of();
            Point previous=result.getLast();float pd=insideDistance(previous,boundary);
            for(Point point:result) {
                float d=insideDistance(point,boundary);
                if((d>=0)!=(pd>=0)) {
                    float t=pd/(pd-d);output.add(new Point(previous.x+(point.x-previous.x)*t,previous.y+(point.y-previous.y)*t));
                }
                if(d>=0)output.add(point);previous=point;pd=d;
            }
            result=output;
        }
        return List.copyOf(result);
    }
    private float insideDistance(Point p,int boundary) {
        return switch(boundary){case 0->p.x-viewport.x();case 1->viewport.right()-p.x;case 2->p.y-viewport.y();default->viewport.top()-p.y;};
    }
    public Optional<Segment> clip(float ax,float az,float bx,float bz) {
        Point a=project(ax,az),b=project(bx,bz);float dx=b.x-a.x,dy=b.y-a.y,lo=0,hi=1;
        float[] p={-dx,dx,-dy,dy},q={a.x-viewport.x(),viewport.right()-a.x,a.y-viewport.y(),viewport.top()-a.y};
        for(int i=0;i<4;i++) {
            if(Math.abs(p[i])<1e-7f) {if(q[i]<0)return Optional.empty();}
            else {float t=q[i]/p[i];if(p[i]<0)lo=Math.max(lo,t);else hi=Math.min(hi,t);if(lo>hi)return Optional.empty();}
        }
        return Optional.of(new Segment(new Point(a.x+lo*dx,a.y+lo*dy),new Point(a.x+hi*dx,a.y+hi*dy)));
    }
}
