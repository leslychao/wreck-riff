package game.wreckriff.arena;

import com.jme3.math.Vector3f;
import com.jme3.math.Quaternion;
import com.jme3.math.FastMath;
import game.wreckriff.config.Configs;
import java.util.*;

/** Authoritative arena geometry, surface coordinates and navigation data. */
public record ArenaDefinition(int schemaVersion, String id, Metadata metadata, Bounds bounds,
        List<BoxPart> boxes, List<Ramp> ramps, List<Spawn> spawns,
        List<Pickup> pickups, List<Hazard> hazards, List<NavNode> nodes, List<NavEdge> edges,
        List<Surface> surfaces,List<LaunchPad> launchPads,List<Drop> drops,
        List<Destructible> destructibles,List<Secret> secrets,List<Barrier> barriers,List<Boss> bosses,
        int layoutRevision,List<TriangleSurface> meshes,List<District> districts) {
    public enum Theme { INDUSTRIAL_YARD, CONSTRUCTION, NEON, CARNIVAL }
    public enum Axis { X, Z }
    public enum Transition { ROAD, RAMP, LAUNCH, DROP, OPENABLE }
    public enum HazardType { ELECTRIC, FIRE, CRANE, CAROUSEL, TRAFFIC }
    public record Metadata(String title,Theme theme,int normalEnemies,int durationSeconds,float recoveryCost,
                           String introduction,String music,String bossMusic) {
        public Metadata {
            text(title,"title");Objects.requireNonNull(theme);Objects.requireNonNull(introduction);
            text(music,"music");text(bossMusic,"bossMusic");
            require(normalEnemies>0&&durationSeconds>=0&&Float.isFinite(recoveryCost)&&recoveryCost>=0,"Invalid arena settings");
        }
    }
    public record Vec3(float x, float y, float z) {
        public Vec3 {
            if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z))
                throw new IllegalArgumentException("Non-finite arena coordinate");
        }
        public Vector3f vector() { return new Vector3f(x,y,z); }
    }
    public record Bounds(float minX, float maxX, float minZ, float maxZ, float recoveryY) {
        public Bounds {
            require(Float.isFinite(minX)&&Float.isFinite(maxX)&&Float.isFinite(minZ)&&Float.isFinite(maxZ)
                    &&Float.isFinite(recoveryY)&&minX<maxX&&minZ<maxZ,"Invalid arena bounds");
        }
        public boolean contains(Vector3f point) {
            return point.x >= minX && point.x <= maxX && point.z >= minZ && point.z <= maxZ;
        }
    }
    public record BoxPart(String id, Vec3 center, Vec3 size, String material, boolean collision,float yawDegrees) {
        public BoxPart(String id,Vec3 center,Vec3 size,String material,boolean collision) {this(id,center,size,material,collision,0);}
        public BoxPart {text(id,"box");text(material,"material");Objects.requireNonNull(center);Objects.requireNonNull(size);require(Float.isFinite(yawDegrees),"Invalid box yaw");}
        public Quaternion rotation(){return new Quaternion().fromAngleAxis(yawDegrees*FastMath.DEG_TO_RAD,Vector3f.UNIT_Y);}
        /** Conservative world-space broadphase bounds; exact narrowphase still uses the oriented collider. */
        public Vector3f worldHalfExtents() {
            double yaw=Math.toRadians(yawDegrees),cos=Math.abs(Math.cos(yaw)),sin=Math.abs(Math.sin(yaw));
            return new Vector3f((float)(cos*size.x+sin*size.z)/2,size.y/2,(float)(sin*size.x+cos*size.z)/2);
        }
        public boolean containsXZ(float x,float z,float inset) {
            double a=Math.toRadians(yawDegrees),dx=x-center.x,dz=z-center.z;
            return Math.abs(Math.cos(a)*dx-Math.sin(a)*dz)<=size.x/2-inset
                    &&Math.abs(Math.sin(a)*dx+Math.cos(a)*dz)<=size.z/2-inset;
        }
    }
    /** Indexed, top-facing authored triangles; the same vertices drive rendering and collision. */
    public record TriangleSurface(String id,List<Vec3> vertices,List<Integer> indices,String material,boolean collision) {
        public TriangleSurface {
            text(id,"mesh");text(material,"material");vertices=List.copyOf(vertices);indices=List.copyOf(indices);
            require(vertices.size()>=3&&!indices.isEmpty()&&indices.size()%3==0,"Invalid triangle mesh: "+id);
            for(int index:indices)require(index>=0&&index<vertices.size(),"Invalid triangle index: "+id);
            for(int i=0;i<indices.size();i+=3) {
                Vector3f a=vertices.get(indices.get(i)).vector(),b=vertices.get(indices.get(i+1)).vector(),c=vertices.get(indices.get(i+2)).vector();
                require(b.subtract(a).cross(c.subtract(a)).y>1e-5f,"Driving triangles must face upwards: "+id);
            }
        }
        private int triangleAt(float x,float z) {
            for(int i=0;i<indices.size();i+=3) {
                Vec3 a=vertices.get(indices.get(i)),b=vertices.get(indices.get(i+1)),c=vertices.get(indices.get(i+2));
                double den=(b.z-c.z)*(a.x-c.x)+(c.x-b.x)*(a.z-c.z);
                double u=((b.z-c.z)*(x-c.x)+(c.x-b.x)*(z-c.z))/den;
                double v=((c.z-a.z)*(x-c.x)+(a.x-c.x)*(z-c.z))/den;
                if(u>=-1e-5&&v>=-1e-5&&u+v<=1.00001)return i;
            }
            return -1;
        }
        public boolean containsXZ(float x,float z,float inset) {
            if(triangleAt(x,z)<0)return false;
            if(inset<=0)return true;
            // An inset applies to the exterior, never to a shared triangle seam.
            for(int i=0;i<8;i++){double angle=i*Math.PI/4;if(triangleAt(x+(float)Math.cos(angle)*inset,z+(float)Math.sin(angle)*inset)<0)return false;}
            return true;
        }
        public float heightAt(float x,float z) {
            int i=triangleAt(x,z);if(i<0)return Float.NaN;
            Vector3f a=vertices.get(indices.get(i)).vector();Vector3f n=normal(i);
            return a.y-(n.x*(x-a.x)+n.z*(z-a.z))/n.y;
        }
        private Vector3f normal(int i) {
            Vector3f a=vertices.get(indices.get(i)).vector();
            return vertices.get(indices.get(i+1)).vector().subtractLocal(a)
                    .crossLocal(vertices.get(indices.get(i+2)).vector().subtractLocal(a)).normalizeLocal();
        }
        public Vector3f normalAt(float x,float z) {int i=triangleAt(x,z);return i<0?Vector3f.UNIT_Y.clone():normal(i);}
    }
    public record District(String id,String title,Vec3 center,List<Integer> patrolNodeIds) {
        public District {text(id,"district");text(title,"district title");Objects.requireNonNull(center);patrolNodeIds=List.copyOf(patrolNodeIds);
            require(patrolNodeIds.size()>=2&&new HashSet<>(patrolNodeIds).size()==patrolNodeIds.size(),"District needs distinct patrol nodes: "+id);}
    }
    public record Ramp(String id, float minX, float maxX, float minZ, float maxZ,
                       float startY, float endY, float bottomY, Axis axis, String material) {
        public Ramp {
            text(id,"ramp");text(material,"material");Objects.requireNonNull(axis);
            require(Float.isFinite(minX)&&Float.isFinite(maxX)&&Float.isFinite(minZ)&&Float.isFinite(maxZ)
                    &&Float.isFinite(startY)&&Float.isFinite(endY)&&Float.isFinite(bottomY)
                    &&minX<maxX&&minZ<maxZ&&bottomY<=Math.min(startY,endY),"Invalid ramp: "+id);
        }
        public float heightAt(float x,float z) {
            float t=axis==Axis.X?(x-minX)/(maxX-minX):(z-minZ)/(maxZ-minZ);
            return startY+(endY-startY)*t;
        }
        public boolean containsXZ(float x,float z,float margin) {
            return x>=minX+margin&&x<=maxX-margin&&z>=minZ+margin&&z<=maxZ-margin;
        }
    }
    /** Position is on the surface, not the center of a vehicle body. */
    public record Spawn(int id, Vec3 position, float yawDegrees) {}
    public enum PickupType { REPAIR, HOMING_AMMO, POWER_AMMO, MINE_AMMO, NAPALM_AMMO, BALLISTIC_AMMO, CANNON_AMMO, TURBO_CELL }
    public record Pickup(String id, PickupType type, Vec3 position, int respawnTicks) {}
    public record Hazard(String id,HazardType type,String sector,float minX, float maxX, float minZ, float maxZ,
                         float minY, float maxY, int offTicks, int warningTicks,
                         int activeTicks, int damageIntervalTicks, float damage) {
        public Hazard {
            text(id,"hazard");Objects.requireNonNull(type);text(sector,"sector");
            require(Float.isFinite(minX)&&Float.isFinite(maxX)&&Float.isFinite(minZ)&&Float.isFinite(maxZ)
                    &&Float.isFinite(minY)&&Float.isFinite(maxY)&&Float.isFinite(damage)
                    &&minX<maxX&&minZ<maxZ&&minY<maxY&&offTicks>0&&warningTicks>0&&activeTicks>0
                    &&damageIntervalTicks>0&&damage>0,"Invalid hazard: "+id);
            Math.addExact(offTicks,Math.addExact(warningTicks,activeTicks));
        }
        public boolean contains(Vector3f point) {
            if(point.x<minX||point.x>maxX||point.z<minZ||point.z>maxZ||point.y<minY||point.y>maxY)return false;
            if(type==HazardType.CRANE||type==HazardType.CAROUSEL) {
                float x=(point.x-(minX+maxX)/2)/((maxX-minX)/2),z=(point.z-(minZ+maxZ)/2)/((maxZ-minZ)/2);
                return x*x+z*z<=1;
            }
            return true;
        }
        public Vec3 center() { return new Vec3((minX+maxX)/2,minY+.5f,(minZ+maxZ)/2); }
        public int periodTicks() { return offTicks + warningTicks + activeTicks; }
    }
    public record Surface(String id,String geometryId,int level,float grip) {
        public Surface { text(id,"surface");text(geometryId,"surface geometry");require(Float.isFinite(grip)&&grip>0&&grip<=1,"Invalid grip: "+id); }
    }
    public record NavNode(int id, Vec3 position,String surfaceId) {}
    public record NavEdge(String id,int from, int to, float width,float clearance,Transition type,String objectId,boolean bidirectional) {
        public NavEdge {
            text(id,"edge");Objects.requireNonNull(type);Objects.requireNonNull(objectId);
            require(Float.isFinite(width)&&width>=3&&Float.isFinite(clearance)&&clearance>0,"Invalid edge clearance: "+id);
            require((type!=Transition.LAUNCH&&type!=Transition.DROP)||!bidirectional,"Directed air transition required: "+id);
        }
    }
    public record LaunchPad(String id,Vec3 source,Vec3 target,String sourceSurfaceId,String landingSurfaceId,
                            float length,float width,float maximumEntryAngle,float minimumSpeed,
                            int compressionTicks,float flightSeconds,int rearmTicks) {
        public LaunchPad {
            text(id,"launch");Objects.requireNonNull(source);Objects.requireNonNull(target);
            text(sourceSurfaceId,"launch source surface");text(landingSurfaceId,"launch target surface");
            require(Float.isFinite(length)&&Float.isFinite(width)&&Float.isFinite(maximumEntryAngle)&&Float.isFinite(minimumSpeed)
                    &&Float.isFinite(flightSeconds)&&length>0&&width>0&&maximumEntryAngle>0&&maximumEntryAngle<90
                    &&minimumSpeed>0&&compressionTicks>0&&flightSeconds>0&&rearmTicks>0,"Invalid launch: "+id);
            require(source.vector().subtract(target.vector()).setY(0).length()>1,"Zero launch distance: "+id);
        }
        public Vector3f direction() { return target.vector().subtractLocal(source.vector()).setY(0).normalizeLocal(); }
    }
    public record Drop(String id,Vec3 start,Vec3 end,float width) {
        public Drop { text(id,"drop");Objects.requireNonNull(start);Objects.requireNonNull(end);require(start.y>end.y&&Float.isFinite(width)&&width>=3,"Invalid drop: "+id); }
    }
    public record Destructible(String id,String geometryId,float maximumHp) {
        public Destructible {
            text(id,"object");text(geometryId,"object geometry");
            require(Float.isFinite(maximumHp)&&maximumHp>0,"Invalid object: "+id);
        }
    }
    public record Secret(String id,String gateId,Vec3 entry,Vec3 exit,String pickupId) {
        public Secret { text(id,"secret");text(gateId,"gate");text(pickupId,"secret pickup");require(entry.vector().distance(exit.vector())>3,"Secret needs two exits: "+id); }
    }
    public record Barrier(String id,String geometryId,String sector,int offTicks,int warningTicks,int activeTicks) {
        public Barrier { text(id,"barrier");text(geometryId,"barrier geometry");text(sector,"barrier sector");
            require(offTicks>0&&warningTicks>=144&&activeTicks>0,"Invalid barrier: "+id); }
    }
    public record Boss(String id,String name,String profileId,float maximumHp,
                       game.wreckriff.combat.WeaponType primary,game.wreckriff.combat.WeaponType secondary,
                       List<Spawn> entrances,List<String> launchPadIds,List<String> quotes) {
        public Boss {
            text(id,"boss");text(name,"boss name");text(profileId,"boss profile");Objects.requireNonNull(primary);Objects.requireNonNull(secondary);
            require(Float.isFinite(maximumHp)&&maximumHp>0,"Invalid boss HP: "+id);
            entrances=List.copyOf(entrances);launchPadIds=List.copyOf(launchPadIds);quotes=List.copyOf(quotes);
            require(entrances.size()==2&&!quotes.isEmpty(),"Boss needs entrances and quotes: "+id);
        }
    }

    public ArenaDefinition {
        if (schemaVersion != 3 || id == null || id.isBlank()||layoutRevision<1) throw new IllegalArgumentException("Invalid arena identity");
        Objects.requireNonNull(bounds);Objects.requireNonNull(metadata);
        boxes=List.copyOf(boxes); ramps=List.copyOf(ramps); spawns=List.copyOf(spawns);
        pickups=List.copyOf(pickups); nodes=List.copyOf(nodes); edges=List.copyOf(edges);
        hazards=List.copyOf(hazards);surfaces=List.copyOf(surfaces);launchPads=List.copyOf(launchPads);
        drops=List.copyOf(drops);destructibles=List.copyOf(destructibles);secrets=List.copyOf(secrets);barriers=List.copyOf(barriers);bosses=List.copyOf(bosses);
        meshes=List.copyOf(meshes);districts=List.copyOf(districts);
        if (!(bounds.minX < bounds.maxX && bounds.minZ < bounds.maxZ)) throw new IllegalArgumentException("Invalid bounds");
        Set<String> objectIds=new HashSet<>();
        for (BoxPart box:boxes) {
            if (!objectIds.add(box.id) || box.size.x<=0 || box.size.y<=0 || box.size.z<=0)
                throw new IllegalArgumentException("Invalid/duplicate arena box " + box.id);
        }
        for (Ramp ramp:ramps) if (!objectIds.add(ramp.id) || ramp.minX>=ramp.maxX || ramp.minZ>=ramp.maxZ)
            throw new IllegalArgumentException("Invalid/duplicate ramp " + ramp.id);
        for(var mesh:meshes)require(objectIds.add(mesh.id),"Duplicate triangle mesh "+mesh.id);
        if (spawns.size()!=metadata.normalEnemies+1) throw new IllegalArgumentException("Arena participant/spawn mismatch: "+id);
        Set<Integer> spawnIds=new HashSet<>();
        for (Spawn spawn:spawns) if (!spawnIds.add(spawn.id) || !bounds.contains(spawn.position.vector()))
            throw new IllegalArgumentException("Invalid spawn " + spawn.id);
        Set<String> pickupIds=new HashSet<>();
        for (Pickup pickup:pickups) if (!pickupIds.add(pickup.id) || pickup.respawnTicks<=0
                || !bounds.contains(pickup.position.vector())) throw new IllegalArgumentException("Invalid pickup " + pickup.id);
        Set<String> surfaceIds=new HashSet<>(),dynamicIds=new HashSet<>();
        for(var surface:surfaces)require(surfaceIds.add(surface.id)&&objectIds.contains(surface.geometryId),
                "Invalid surface "+id+"/"+surface.id+" geometry "+surface.geometryId);
        for(var hazard:hazards)require(dynamicIds.add(hazard.id),"Duplicate hazard "+id+"/"+hazard.id);
        for(var object:destructibles)require(dynamicIds.add(object.id)&&objectIds.contains(object.geometryId),"Invalid object "+id+"/"+object.id);
        for(var barrier:barriers)require(dynamicIds.add(barrier.id)&&objectIds.contains(barrier.geometryId),"Invalid barrier "+id+"/"+barrier.id);
        for(var pad:launchPads)require(dynamicIds.add(pad.id)&&surfaceIds.contains(pad.sourceSurfaceId)&&surfaceIds.contains(pad.landingSurfaceId)
                &&bounds.contains(pad.source.vector())&&bounds.contains(pad.target.vector()),"Invalid launch "+id+"/"+pad.id);
        for(var drop:drops)require(dynamicIds.add(drop.id)&&bounds.contains(drop.start.vector())&&bounds.contains(drop.end.vector()),"Invalid drop "+id+"/"+drop.id);
        if (nodes.isEmpty()) throw new IllegalArgumentException("Navigation needs nodes: "+id);
        Set<Integer> nodeIds=new HashSet<>();
        for (NavNode node:nodes) if (!nodeIds.add(node.id) || !bounds.contains(node.position.vector())||!surfaceIds.contains(node.surfaceId))
            throw new IllegalArgumentException("Invalid navigation node "+id+"/"+node.id+" surface "+node.surfaceId);
        Set<String> districtIds=new HashSet<>();
        for(var district:districts)require(districtIds.add(district.id)&&bounds.contains(district.center.vector())&&nodeIds.containsAll(district.patrolNodeIds),"Invalid district: "+district.id);
        Set<String> edgeIds=new HashSet<>();
        for (NavEdge edge:edges) {
            String key=edge.id;
            if (edge.from==edge.to || edge.width<3 || !nodeIds.contains(edge.from) || !nodeIds.contains(edge.to)
                    || !edgeIds.add(key)) throw new IllegalArgumentException("Invalid navigation edge " + key);
            boolean resolved=switch(edge.type) {
                case ROAD -> edge.objectId.isEmpty();
                case RAMP -> ramps.stream().anyMatch(r->r.id.equals(edge.objectId))||meshes.stream().anyMatch(m->m.id.equals(edge.objectId));
                case LAUNCH -> launchPads.stream().anyMatch(p->p.id.equals(edge.objectId));
                case DROP -> drops.stream().anyMatch(d->d.id.equals(edge.objectId));
                case OPENABLE -> destructibles.stream().anyMatch(d->d.id.equals(edge.objectId))||barriers.stream().anyMatch(b->b.id.equals(edge.objectId));
            };
            require(resolved,"Unknown transition "+id+"/"+key+" object "+edge.objectId);
        }
        for(var secret:secrets)require(dynamicIds.add(secret.id)&&pickupIds.contains(secret.pickupId)
                &&destructibles.stream().anyMatch(d->d.id.equals(secret.gateId)),"Invalid secret "+id+"/"+secret.id);
        for(var boss:bosses) {
            require(dynamicIds.add(boss.id),"Duplicate boss "+id+"/"+boss.id);
            for(String padId:boss.launchPadIds)require(launchPads.stream().anyMatch(p->p.id.equals(padId)),"Invalid boss launch: "+padId);
            for(var entrance:boss.entrances)require(bounds.contains(entrance.position.vector()),"Invalid boss entrance: "+boss.id);
        }
    }
    /** Small fixture constructor; shipped resources use the complete canonical schema. */
    public ArenaDefinition(int schemaVersion,String id,Metadata metadata,Bounds bounds,List<BoxPart> boxes,List<Ramp> ramps,
            List<Spawn> spawns,List<Pickup> pickups,List<Hazard> hazards,List<NavNode> nodes,List<NavEdge> edges,
            List<Surface> surfaces,List<LaunchPad> launchPads,List<Drop> drops,List<Destructible> destructibles,
            List<Secret> secrets,List<Barrier> barriers,List<Boss> bosses) {
        this(schemaVersion,id,metadata,bounds,boxes,ramps,spawns,pickups,hazards,nodes,edges,surfaces,launchPads,drops,
                destructibles,secrets,barriers,bosses,1,List.of(),List.of());
    }
    public static ArenaDefinition load() { return Configs.load("arena",ArenaDefinition.class); }
    public List<Spawn> shuffledSpawns(long seed) {
        List<Spawn> result=new ArrayList<>(spawns);
        Collections.shuffle(result,new Random(seed));
        return List.copyOf(result);
    }
    public ArenaDefinition withPickups(List<Pickup> replacement) {
        return new ArenaDefinition(schemaVersion,id,metadata,bounds,boxes,ramps,spawns,replacement,hazards,nodes,edges,
                surfaces,launchPads,drops,destructibles,secrets,barriers,bosses,layoutRevision,meshes,districts);
    }
    public Optional<Surface> surfaceAt(Vector3f point,float inset,float tolerance) {
        Surface found=null;float nearest=tolerance;
        // Reject spatial/height misses before searching the short surface catalogue.
        // Large locations have many non-road buildings but usually only one local support.
        for(var box:boxes)if(box.collision&&box.containsXZ(point.x,point.z,inset)) {
            float distance=Math.abs(point.y-box.center.y-box.size.y/2);
            if(distance<=nearest)for(var surface:surfaces)if(surface.geometryId.equals(box.id)){found=surface;nearest=distance;break;}
        }
        for(var ramp:ramps)if(ramp.containsXZ(point.x,point.z,inset)) {
            float distance=Math.abs(point.y-ramp.heightAt(point.x,point.z));
            if(distance<=nearest)for(var surface:surfaces)if(surface.geometryId.equals(ramp.id)){found=surface;nearest=distance;break;}
        }
        for(var mesh:meshes)if(mesh.collision&&mesh.containsXZ(point.x,point.z,inset)) {
            float distance=Math.abs(point.y-mesh.heightAt(point.x,point.z));
            if(distance<=nearest)for(var surface:surfaces)if(surface.geometryId.equals(mesh.id)){found=surface;nearest=distance;break;}
        }
        return Optional.ofNullable(found);
    }
    public float surfaceHeight(String surfaceId,float x,float z) {
        var surface=surfaces.stream().filter(s->s.id.equals(surfaceId)).findFirst().orElse(null);if(surface==null)return Float.NaN;
        for(var box:boxes)if(box.id.equals(surface.geometryId)&&box.containsXZ(x,z,0))return box.center.y+box.size.y/2;
        for(var ramp:ramps)if(ramp.id.equals(surface.geometryId)&&ramp.containsXZ(x,z,0))return ramp.heightAt(x,z);
        for(var mesh:meshes)if(mesh.id.equals(surface.geometryId))return mesh.heightAt(x,z);
        return Float.NaN;
    }
    public Vector3f surfaceNormal(String surfaceId,float x,float z) {
        var surface=surfaces.stream().filter(s->s.id.equals(surfaceId)).findFirst().orElse(null);if(surface==null)return Vector3f.UNIT_Y.clone();
        for(var ramp:ramps)if(ramp.id.equals(surface.geometryId)) {
            float slope=(ramp.endY-ramp.startY)/(ramp.axis==Axis.X?ramp.maxX-ramp.minX:ramp.maxZ-ramp.minZ);
            return (ramp.axis==Axis.X?new Vector3f(-slope,1,0):new Vector3f(0,1,-slope)).normalizeLocal();
        }
        for(var mesh:meshes)if(mesh.id.equals(surface.geometryId))return mesh.normalAt(x,z);
        return Vector3f.UNIT_Y.clone();
    }
    private static void text(String value,String field) { require(value!=null&&!value.isBlank(),"Missing "+field); }
    private static void require(boolean valid,String error) { if(!valid)throw new IllegalArgumentException(error); }
}
