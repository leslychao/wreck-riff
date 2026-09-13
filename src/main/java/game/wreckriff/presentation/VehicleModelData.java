package game.wreckriff.presentation;

import com.google.gson.*;
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
        Node bank=(Node)assets.loadModel("models/vehicles/"+profile+"/vehicle.j3o");JsonObject regional;
        try(var info=assets.locateAsset(new com.jme3.asset.AssetKey<>("models/vehicles/"+profile+"/regions.json.gz")).openStream();var zip=new GZIPInputStream(info)) {
            regional=JsonParser.parseReader(new InputStreamReader(zip,StandardCharsets.UTF_8)).getAsJsonObject();
        }catch(IOException e){throw new IllegalStateException("Cannot load prepared regional vehicle data "+profile,e);}
        for(int lod=0;lod<3;lod++) {
            Node level=(Node)bank.getChild("lod"+lod),intact=(Node)level.getChild("stage0");List<Part> parts=new ArrayList<>();
            for(Spatial child:intact.getChildren()) {
                Mesh[] stages=new Mesh[5];for(int stage=0;stage<5;stage++)stages[stage]=((Geometry)((Node)level.getChild("stage"+stage)).getChild(child.getName())).getMesh();
                var records=regional.getAsJsonArray("lods").get(lod).getAsJsonObject().getAsJsonArray(child.getName());Delta[] delta=new Delta[8];
                for(int i=0;i<8;i++){var record=records.get(i).getAsJsonObject();var ids=record.getAsJsonArray("indices");var values=record.getAsJsonArray("delta");int[] indices=new int[ids.size()];float[] xyz=new float[values.size()];
                    for(int k=0;k<indices.length;k++)indices[k]=ids.get(k).getAsInt();for(int k=0;k<xyz.length;k++)xyz[k]=values.get(k).getAsFloat();delta[i]=new Delta(indices,xyz);}
                if(lod==0)stages[0].createCollisionData();if(lod==2&&child.getName().startsWith("panel-"))for(Mesh stage:stages)centeredPanel(stage);
                parts.add(new Part(child.getName(),stages,delta,child.getUserData("damageIslandId")));
            }
            lods.add(List.copyOf(parts));
        }
    }
}
