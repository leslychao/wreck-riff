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
    record Part(String name,Mesh[] stages,Delta[] regions) {}
    private static final Map<AssetManager,Map<String,VehicleModelData>> CACHE=new WeakHashMap<>();
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
                parts.add(new Part(child.getName(),stages,delta));
            }
            lods.add(List.copyOf(parts));
        }
    }
}
