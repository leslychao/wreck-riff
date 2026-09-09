package game.wreckriff.config;

import com.jme3.math.Vector3f;
import java.util.List;
import java.util.Objects;

/** Immutable native chassis, wheel and socket geometry, in metres about the centre of mass. */
public final class VehicleProfile {
    public record HullBox(float x,float y,float z,float halfWidth,float halfHeight,float halfLength) {
        public Vector3f center() { return new Vector3f(x,y,z); }
        public Vector3f extent() { return new Vector3f(halfWidth,halfHeight,halfLength); }
    }
    public record Envelope(float minX,float maxX,float minY,float maxY,float minZ,float maxZ) {
        public Vector3f extent() { return new Vector3f((maxX-minX)/2,(maxY-minY)/2,(maxZ-minZ)/2); }
        public Vector3f center() { return new Vector3f((maxX+minX)/2,(maxY+minY)/2,(maxZ+minZ)/2); }
        public float radius() {
            float x=Math.max(Math.abs(minX),Math.abs(maxX));
            float y=Math.max(Math.abs(minY),Math.abs(maxY));
            float z=Math.max(Math.abs(minZ),Math.abs(maxZ));
            return (float)Math.sqrt(x*x+y*y+z*z);
        }
    }

    private final String id;
    private final VehicleRules reference;
    private final float length,width,height,mass,speedMultiplier,turboMultiplier,accelerationMultiplier,turnMultiplier;
    private final float scaleX,scaleY,scaleZ;
    private final List<HullBox> hullBoxes;
    private final Envelope hullBounds,fullBounds;

    private VehicleProfile(String id,VehicleRules reference,float length,float width,float height,
                           float speedMultiplier,float accelerationMultiplier,float turnMultiplier,boolean rivet) {
        this(id,reference,length,width,height,Float.NaN,speedMultiplier,speedMultiplier,accelerationMultiplier,turnMultiplier,rivet,null);
    }
    private VehicleProfile(String id,VehicleRules reference,float length,float width,float height,float explicitMass,
                           float speedMultiplier,float turboMultiplier,float accelerationMultiplier,float turnMultiplier,
                           boolean rivet,List<HullBox> explicitHull) {
        this.id=Objects.requireNonNull(id);this.reference=Objects.requireNonNull(reference);
        this.length=length;this.width=width;this.height=height;
        this.speedMultiplier=speedMultiplier;this.turboMultiplier=turboMultiplier;this.accelerationMultiplier=accelerationMultiplier;this.turnMultiplier=turnMultiplier;
        scaleX=width/reference.width();scaleZ=length/reference.length();
        // Rivet's declared visual height differs from its two-box physical envelope.
        // Keep its exact existing compound shape; boss heights describe the physical hull.
        scaleY=rivet?1:height/1.07f;
        mass=Float.isNaN(explicitMass)?reference.mass()*scaleX*scaleZ:explicitMass;
        hullBoxes=explicitHull==null?List.of(new HullBox(0,.2f*scaleY,0,width/2,.3f*scaleY,length/2),
                new HullBox(0,.65f*scaleY,-.25f*scaleZ,.83f*scaleX,.32f*scaleY,.85f*scaleZ))
                :List.copyOf(explicitHull);
        hullBounds=explicitHull==null?new Envelope(-width/2,width/2,-.1f*scaleY,.97f*scaleY,-length/2,length/2):bounds(hullBoxes);
        // Include the visible tyre width and the full suspension extension, plus weapon/exhaust sockets.
        float wheelX=wheelConnection(1).x+.2f*scaleX;
        float wheelFront=wheelBase()/2+wheelRadius();
        fullBounds=new Envelope(-Math.max(width/2,wheelX),Math.max(width/2,wheelX),
                Math.min(hullBounds.minY(),wheelConnection(0).y-suspensionRestLength()*2-wheelRadius()),
                Math.max(Math.max(hullBounds.maxY(),wheelConnection(0).y+wheelRadius()),muzzle().y),
                -Math.max(Math.max(length/2,wheelFront),2.5f*scaleZ),
                Math.max(Math.max(length/2,wheelFront),muzzle().z));
    }

    public static VehicleProfile rivet(VehicleRules rules) {
        return new VehicleProfile("rivet",rules,rules.length(),rules.width(),rules.height(),1,1,1,true);
    }
    public static VehicleProfile player(String id,VehicleRules rules) {
        return switch(VehicleDefinition.forId(id)) {
            case RIVET -> rivet(rules);
            case GRINDER -> new VehicleProfile(id,rules,5.9f,2.55f,2.6f,1900,24f/28f,34f/40f,.8f,.8f,false,
                    List.of(new HullBox(0,.35f,-.2f,1.15f,.45f,2.75f),
                            new HullBox(0,1.45f,.7f,1.03f,1.05f,1.1f),
                            new HullBox(0,.42f,2.78f,1.275f,.4f,.17f)));
            case SPARK -> new VehicleProfile(id,rules,3.1f,1.65f,1.2f,650,31f/28f,44f/40f,1.2f,1.15f,false,
                    List.of(new HullBox(0,.18f,0,.825f,.28f,1.55f),
                            new HullBox(0,.66f,-.1f,.72f,.44f,.8f)));
        };
    }
    private static Envelope bounds(List<HullBox> boxes) {
        float minX=Float.POSITIVE_INFINITY,minY=minX,minZ=minX,maxX=Float.NEGATIVE_INFINITY,maxY=maxX,maxZ=maxX;
        for(var box:boxes) {
            minX=Math.min(minX,box.x-box.halfWidth);maxX=Math.max(maxX,box.x+box.halfWidth);
            minY=Math.min(minY,box.y-box.halfHeight);maxY=Math.max(maxY,box.y+box.halfHeight);
            minZ=Math.min(minZ,box.z-box.halfLength);maxZ=Math.max(maxZ,box.z+box.halfLength);
        }
        return new Envelope(minX,maxX,minY,maxY,minZ,maxZ);
    }
    public static VehicleProfile boss(String id,VehicleRules rules) {
        return switch(id) {
            case "boss_foreman" -> new VehicleProfile(id,rules,12,5,5.5f,.72f,.65f,.65f,false);
            case "boss_prefect" -> new VehicleProfile(id,rules,9,3.3f,3,1.2f,1.2f,1.1f,false);
            case "boss_emcee" -> new VehicleProfile(id,rules,8,4.2f,4.6f,1.05f,1.05f,1,false);
            case "boss_ash_shepherd" -> new VehicleProfile(id,rules,10,4.4f,4.8f,.8f,.8f,.75f,false);
            case "boss_director" -> new VehicleProfile(id,rules,14,5.2f,6,.85f,.75f,.7f,false);
            default -> throw new IllegalArgumentException("Unknown boss vehicle profile: "+id);
        };
    }
    public static List<VehicleProfile> bosses(VehicleRules rules) {
        return List.of(boss("boss_foreman",rules),boss("boss_prefect",rules),boss("boss_emcee",rules),
                boss("boss_ash_shepherd",rules),boss("boss_director",rules));
    }
    /** Shared immutable default for query-only worlds; live worlds supply their registered profile. */
    public static VehicleProfile rivet() { return Defaults.RIVET; }
    private static final class Defaults { private static final VehicleProfile RIVET=rivet(VehicleRules.load()); }

    public String id() { return id; }
    public float length() { return length; }
    public float width() { return width; }
    public float height() { return height; }
    public float mass() { return mass; }
    public float massRatio() { return mass/reference.mass(); }
    public float speedMultiplier() { return speedMultiplier; }
    public float turboMultiplier() { return turboMultiplier; }
    public float accelerationMultiplier() { return accelerationMultiplier; }
    public float turnMultiplier() { return turnMultiplier; }
    public List<HullBox> hullBoxes() { return hullBoxes; }
    public Envelope hullBounds() { return hullBounds; }
    public Envelope fullBounds() { return fullBounds; }
    public float wheelRadius() { return reference.wheelRadius()*scaleX; }
    public float wheelBase() { return reference.wheelBase()*scaleZ; }
    public float suspensionRestLength() { return reference.suspensionRestLength()*scaleX; }
    public Vector3f wheelConnection(int wheel) {
        if(wheel<0||wheel>=4)throw new IllegalArgumentException("Wheel index: "+wheel);
        return new Vector3f((wheel%2==0?-1:1)*(width/2-.05f*scaleX),.15f*scaleX,(wheel<2?1:-1)*wheelBase()/2);
    }
    /** Centre-of-mass height above a flat road with unloaded suspension. */
    public float roadOffset() { return suspensionRestLength()+wheelRadius()-wheelConnection(0).y; }
    public Vector3f weaponBase() { return id.equals("grinder")?new Vector3f(0,2.58f,1.35f):new Vector3f(0,.55f*scaleY,1.6f*scaleZ); }
    public Vector3f muzzle() { return id.equals("grinder")?new Vector3f(0,2.58f,3.15f):new Vector3f(0,.55f*scaleY,2.5f*scaleZ); }
    /** Front contact mouth, beneath the truck's weapon mounts. */
    public Vector3f grinderIntake() { return new Vector3f(0,.42f,hullBounds.maxZ()); }
    public Vector3f machineGunMuzzle(int barrel) {
        if(barrel<0||barrel>1)throw new IllegalArgumentException("Barrel index: "+barrel);
        if(id.equals("grinder"))return new Vector3f(barrel==0?-.75f:.75f,2.48f,2.95f);
        return new Vector3f((barrel==0?-.53f:.53f)*scaleX,.47f*scaleY,2.28f*scaleZ);
    }
    public List<Vector3f> hullVisibilityPoints() {
        if(id.equals("grinder"))return List.of(new Vector3f(0,.35f,0),new Vector3f(0,.42f,2.78f),
                new Vector3f(0,.35f,-2),new Vector3f(0,1.6f,.7f));
        return List.of(new Vector3f(),new Vector3f(0,.4f*scaleY,1.6f*scaleZ),new Vector3f(0,.4f*scaleY,-1.6f*scaleZ));
    }
}
