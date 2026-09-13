package game.wreckriff.presentation;

import com.google.gson.stream.JsonReader;
import com.jme3.asset.AssetManager;
import com.jme3.scene.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.GZIPInputStream;

/** Shared read-only authored poses; only per-instance two-target composites are mutable. */
final class VehicleModelData {
    record Delta(int[] indices,float[] xyz) {}
    record Part(String name,Mesh[] stages,Delta[] regions,int islandId) {}
    private static final Map<AssetManager,Map<String,VehicleModelData>> CACHE=new WeakHashMap<>();
    private static final Map<Mesh,Mesh> CENTERED_PANELS=new WeakHashMap<>();
    static synchronized Mesh centeredPanel(Mesh source){return CENTERED_PANELS.computeIfAbsent(source,key->{Mesh mesh=source.clone();var center=((com.jme3.bounding.BoundingBox)source.getBound()).getCenter();var original=source.getFloatBuffer(VertexBuffer.Type.Position);float[] p=new float[original.limit()];for(int i=0;i<p.length;i+=3){p[i]=original.get(i)-center.x;p[i+1]=original.get(i+1)-center.y;p[i+2]=original.get(i+2)-center.z;}mesh.setBuffer(VertexBuffer.Type.Position,3,p);mesh.updateBound();mesh.setStatic();return mesh;});}
    final List<List<Part>> lods=new ArrayList<>();
    static synchronized VehicleModelData load(AssetManager assets,String profile) {
        return CACHE.computeIfAbsent(assets,key->new HashMap<>()).computeIfAbsent(profile,key->new VehicleModelData(assets,key));
    }
    private VehicleModelData(AssetManager assets,String profile) {
        Node bank=(Node)assets.loadModel("models/vehicles/"+profile+"/vehicle.j3o");
        List<Map<String,Integer>> vertexCounts=new ArrayList<>();
        for(int lod=0;lod<3;lod++) {
            Node intact=(Node)((Node)bank.getChild("lod"+lod)).getChild("stage0");Map<String,Integer> counts=new HashMap<>();
            for(Spatial child:intact.getChildren())counts.put(child.getName(),((Geometry)child).getMesh().getVertexCount());
            vertexCounts.add(counts);
        }
        List<Map<String,Delta[]>> regional;
        try(var info=assets.locateAsset(new com.jme3.asset.AssetKey<>("models/vehicles/"+profile+"/regions.json.gz")).openStream();var zip=new GZIPInputStream(info)) {
            regional=readRegions(new InputStreamReader(zip,StandardCharsets.UTF_8),vertexCounts);
        }catch(IOException e){throw new IllegalStateException("Cannot load prepared regional vehicle data "+profile,e);}
        for(int lod=0;lod<3;lod++) {
            Node level=(Node)bank.getChild("lod"+lod),intact=(Node)level.getChild("stage0");List<Part> parts=new ArrayList<>();
            for(Spatial child:intact.getChildren()) {
                Mesh[] stages=new Mesh[5];for(int stage=0;stage<5;stage++)stages[stage]=((Geometry)((Node)level.getChild("stage"+stage)).getChild(child.getName())).getMesh();
                Delta[] delta=regional.get(lod).get(child.getName());
                if(lod==0)stages[0].createCollisionData();if(lod==2&&child.getName().startsWith("panel-"))for(Mesh stage:stages)centeredPanel(stage);
                parts.add(new Part(child.getName(),stages,delta,child.getUserData("damageIslandId")));
            }
            lods.add(List.copyOf(parts));
        }
    }

    /** Stream into bounded primitive arrays: a JSON tree retained millions of strings during arena loading. */
    static List<Map<String,Delta[]>> readRegions(Reader source,List<Map<String,Integer>> vertexCounts) throws IOException {
        try(JsonReader reader=new JsonReader(source)) {
            List<Map<String,Delta[]>> result=new ArrayList<>();boolean schema=false,lods=false;
            reader.beginObject();
            while(reader.hasNext())switch(reader.nextName()) {
                case "schemaVersion" -> {if(schema||reader.nextInt()!=1)throw new IOException("Unsupported regional schema");schema=true;}
                case "lods" -> {
                    if(lods)throw new IOException("Duplicate regional LODs");lods=true;reader.beginArray();
                    while(reader.hasNext()) {
                        if(result.size()>=vertexCounts.size())throw new IOException("Too many regional LODs");
                        Map<String,Integer> expected=vertexCounts.get(result.size());Map<String,Delta[]> parts=new HashMap<>();reader.beginObject();
                        while(reader.hasNext()) {
                            String name=reader.nextName();Integer vertices=expected.get(name);
                            if(vertices==null||parts.containsKey(name))throw new IOException("Unknown or duplicate regional part: "+name);
                            Delta[] regions=new Delta[8];int count=0;reader.beginArray();
                            while(reader.hasNext()) {if(count==8)throw new IOException("Too many damage regions");regions[count++]=readDelta(reader,vertices);}
                            reader.endArray();if(count!=8)throw new IOException("Eight damage regions required");parts.put(name,regions);
                        }
                        reader.endObject();if(!parts.keySet().equals(expected.keySet()))throw new IOException("Missing regional parts");result.add(Map.copyOf(parts));
                    }
                    reader.endArray();
                }
                default -> throw new IOException("Unknown regional field");
            }
            reader.endObject();
            if(!schema||!lods||result.size()!=vertexCounts.size()||reader.peek()!=com.google.gson.stream.JsonToken.END_DOCUMENT)throw new IOException("Incomplete regional data");
            return List.copyOf(result);
        }catch(IllegalStateException|NumberFormatException e){throw new IOException("Malformed regional data",e);}
    }
    private static Delta readDelta(JsonReader reader,int vertices) throws IOException {
        int[] indices=null;float[] xyz=null;reader.beginObject();
        while(reader.hasNext())switch(reader.nextName()) {
            case "indices" -> {
                if(indices!=null)throw new IOException("Duplicate region indices");int[] values=new int[Math.min(64,vertices)];int size=0,previous=-1;reader.beginArray();
                while(reader.hasNext()) {
                    if(size>=vertices)throw new IOException("Regional indices exceed mesh");int value=reader.nextInt();
                    if(value<=previous||value>=vertices)throw new IOException("Regional vertices must be valid and strictly increasing");previous=value;
                    if(size==values.length)values=Arrays.copyOf(values,Math.min(vertices,Math.max(1,size*2)));values[size++]=value;
                }
                reader.endArray();indices=Arrays.copyOf(values,size);
            }
            case "delta" -> {
                if(xyz!=null)throw new IOException("Duplicate region deltas");int maximum=Math.multiplyExact(vertices,3),size=0;float[] values=new float[Math.min(192,maximum)];reader.beginArray();
                while(reader.hasNext()) {
                    if(size>=maximum)throw new IOException("Regional deltas exceed mesh");float value=(float)reader.nextDouble();if(!Float.isFinite(value))throw new IOException("Non-finite regional delta");
                    if(size==values.length)values=Arrays.copyOf(values,Math.min(maximum,Math.max(1,size*2)));values[size++]=value;
                }
                reader.endArray();xyz=Arrays.copyOf(values,size);
            }
            default -> throw new IOException("Unknown damage region field");
        }
        reader.endObject();if(indices==null||xyz==null||xyz.length!=indices.length*3)throw new IOException("Regional positions do not match indices");return new Delta(indices,xyz);
    }
}
