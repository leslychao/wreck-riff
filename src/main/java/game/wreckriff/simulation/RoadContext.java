package game.wreckriff.simulation;

/** Read-only last confirmed road and current physical transition; never inferred from apex height. */
public record RoadContext(String surfaceId,int level,float grip,Motion motion,String transitionId,
                          String targetSurfaceId,int targetLevel) {
    public enum Motion { ROAD, RAMP, AIRBORNE, LAUNCH }
    public static final RoadContext UNKNOWN=new RoadContext("",0,1,Motion.AIRBORNE,"","",0);
    public boolean known() { return !surfaceId.isEmpty(); }
    public boolean flying() { return motion==Motion.AIRBORNE||motion==Motion.LAUNCH; }
    public RoadContext airborne() { return new RoadContext(surfaceId,level,grip,Motion.AIRBORNE,"","",level); }
}
