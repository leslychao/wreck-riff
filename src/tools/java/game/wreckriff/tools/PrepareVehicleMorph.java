package game.wreckriff.tools;

import com.google.gson.GsonBuilder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Explicit offline extraction from the project's locked jME 3.8.1 dependencies. Never a build prerequisite. */
public final class PrepareVehicleMorph {
    private record Source(String module,String upstream,String runtime) {}
    private static final List<Source> SOURCES=List.of(
            new Source("jme3-core","Common/ShaderLib/MorphAnim.glsllib","VehicleMorph.glsllib"),
            new Source("jme3-core","Common/MatDefs/Light/Lighting.vert","VehicleLighting.vert"),
            new Source("jme3-core","Common/MatDefs/Light/SPLighting.vert","VehicleSPLighting.vert"),
            new Source("jme3-core","Common/MatDefs/Shadow/PreShadow.vert","VehiclePreShadow.vert"),
            new Source("jme3-core","Common/MatDefs/Shadow/PostShadow.vert","VehiclePostShadow.vert"),
            new Source("jme3-effects","Common/MatDefs/SSAO/normal.vert","VehicleNormal.vert"),
            new Source("jme3-core","Common/MatDefs/Misc/Unshaded.vert","VehicleUnshaded.vert"),
            new Source("jme3-core","Common/MatDefs/Misc/Unshaded.j3md","VehicleUnshaded.j3md"));
    private PrepareVehicleMorph() {}
    public static void main(String[] args)throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("Repository directory required");
        Path root=Path.of(args[0]).toAbsolutePath();var entries=new ArrayList<Map<String,Object>>();
        for(Source source:SOURCES) {
            byte[] original;
            try(var stream=PrepareVehicleMorph.class.getResourceAsStream("/"+source.upstream)) {
                if(stream==null)throw new IllegalStateException("Missing pinned dependency source: "+source.upstream);original=stream.readAllBytes();
            }
            String code=new String(original,StandardCharsets.UTF_8).replace("\r\n","\n");
            if(source.runtime.equals("VehicleMorph.glsllib"))code=correctMorph(code);
            else {
                code=code.replace("Common/ShaderLib/MorphAnim.glsllib","materials/VehicleMorph.glsllib");
                if(source.runtime.equals("VehicleNormal.vert"))code=code.replace("#import \"Common/ShaderLib/Skinning.glsllib\"","#import \"Common/ShaderLib/Skinning.glsllib\"\n#import \"materials/VehicleMorph.glsllib\"")
                        .replace("   #ifdef NUM_BONES","   #ifdef NUM_MORPH_TARGETS\n       Morph_Compute(modelSpacePos, modelSpaceNormals);\n   #endif\n   #ifdef NUM_BONES");
                if(source.runtime.equals("VehiclePostShadow.vert"))code=code.replace("   #ifdef NUM_MORPH_TARGETS\n       Morph_Compute(modelSpacePos);\n   #endif","   #ifndef BACKFACE_SHADOWS\n       vec3 modelSpaceNormal = inNormal;\n   #endif\n   #ifdef NUM_MORPH_TARGETS\n       #ifndef BACKFACE_SHADOWS\n           Morph_Compute(modelSpacePos, modelSpaceNormal);\n       #else\n           Morph_Compute(modelSpacePos);\n       #endif\n   #endif")
                        .replace("TransformWorld(vec4(inNormal,0.0))","TransformWorld(vec4(modelSpaceNormal,0.0))");
                if(source.runtime.endsWith(".j3md"))for(Source vertex:SOURCES)if(vertex.runtime.endsWith(".vert"))code=code.replace(vertex.upstream,"materials/"+vertex.runtime);
            }
            code="// Derived from jMonkeyEngine 3.8.1-stable, BSD-3-Clause. See licenses/jme-BSD3.txt.\n"+code;
            String sourcePath="src/tools/assets/vehicle-morph/upstream/"+source.runtime;
            String runtimePath="materials/"+source.runtime;
            write(root.resolve(sourcePath),original);byte[] result=code.getBytes(StandardCharsets.UTF_8);write(root.resolve("src/main/resources").resolve(runtimePath),result);
            var entry=new LinkedHashMap<String,Object>();entry.put("path",runtimePath);entry.put("sha256",hash(result));entry.put("sourcePath",sourcePath);entry.put("sourceSha256",hash(original));
            entry.put("upstream","https://raw.githubusercontent.com/jMonkeyEngine/jmonkeyengine/v3.8.1-stable/"+source.module+"/src/main/resources/"+source.upstream);entries.add(entry);
        }
        var provenance=new LinkedHashMap<String,Object>();provenance.put("engineVersion","3.8.1-stable");provenance.put("license","licenses/jme-BSD3.txt");
        provenance.put("licenseSha256",hash(Files.readAllBytes(root.resolve("src/main/resources/licenses/jme-BSD3.txt"))));
        provenance.put("generator","src/tools/java/game/wreckriff/tools/PrepareVehicleMorph.java");provenance.put("generatorSha256",hash(Files.readAllBytes(root.resolve("src/tools/java/game/wreckriff/tools/PrepareVehicleMorph.java"))));
        provenance.put("changes",List.of("One normal-weight declaration across all tangent target branches","Normal-only overload forwards the real normal with three source buffers","Vehicle vertex and unshaded techniques import a unique project-owned path","Normal prepass and shadow-facing normals follow the deformed surface"));
        provenance.put("files",entries);write(root.resolve("src/main/resources/materials/vehicle-morph-provenance.json"),(new GsonBuilder().setPrettyPrinting().create().toJson(provenance)+"\n").getBytes(StandardCharsets.UTF_8));
        System.out.println("Prepared eight vehicle morph shader resources from pinned jMonkeyEngine 3.8.1; no network or engine override.");
    }
    private static String correctMorph(String code) {
        String start="void Morph_Compute_Pos_Norm_Tan(inout vec4 pos, inout vec3 norm, inout vec3 tan){";
        int offset=code.indexOf(start),end=code.indexOf("void Morph_Compute(inout vec4 pos){",offset);
        if(offset<0||end<0)throw new IllegalStateException("Pinned morph source signature changed");
        String target=code.substring(offset,end);
        if(target.split("float normWeight",-1).length!=5)throw new IllegalStateException("Expected four upstream scope collisions");
        target=target.replace("float invWeightsSum = Get_Inverse_Weights_Sum();","float invWeightsSum = Get_Inverse_Weights_Sum();\n            float normWeight;").replace("float normWeight =","normWeight =");
        code=code.substring(0,offset)+target+code.substring(end);
        offset=code.indexOf("void Morph_Compute(inout vec4 pos, inout vec3 norm){");end=code.indexOf("void Morph_Compute(inout vec4 pos, inout vec3 norm, inout vec3 tan){",offset);
        String normal=code.substring(offset,end).replace("            vec3 dummy_norm = vec3(0.0);\n","").replace("Morph_Compute_Pos_Norm_Tan(pos, dummy_norm, dummy_tan);","Morph_Compute_Pos_Norm_Tan(pos, norm, dummy_tan);");
        return code.substring(0,offset)+normal+code.substring(end);
    }
    private static void write(Path path,byte[] bytes)throws Exception{Files.createDirectories(path.getParent());Files.write(path,bytes);}
    private static String hash(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
}
