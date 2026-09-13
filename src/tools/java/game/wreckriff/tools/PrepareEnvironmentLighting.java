package game.wreckriff.tools;

import com.google.gson.GsonBuilder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Explicit offline extraction; ordinary builds only verify and package the prepared definition. */
public final class PrepareEnvironmentLighting {
    public static void main(String[] args)throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("Repository directory required");
        Path root=Path.of(args[0]).toAbsolutePath();byte[] original;
        String upstream="Common/MatDefs/Light/Lighting.j3md";
        try(var stream=PrepareEnvironmentLighting.class.getResourceAsStream("/"+upstream)) {
            if(stream==null)throw new IllegalStateException("Pinned jME Lighting definition missing");
            original=stream.readAllBytes();
        }
        String source=new String(original,StandardCharsets.UTF_8).replace("\r\n","\n");
        if(!source.contains("Texture2D DiffuseMap\n")||!source.contains("Texture2D SpecularMap\n"))
            throw new IllegalStateException("Pinned Phong parameter declarations changed");
        String transformed="// Derived from jMonkeyEngine 3.8.1-stable; BSD-3-Clause, licenses/jme-BSD3.txt.\n"
                +source.replace("Texture2D DiffuseMap\n","Texture2D DiffuseMap -LINEAR\n")
                .replace("Texture2D SpecularMap\n","Texture2D SpecularMap -LINEAR\n");
        String sourcePath="src/tools/assets/environment-lighting/Lighting.j3md";
        String runtimePath="materials/EnvironmentLighting.j3md";
        byte[] output=transformed.getBytes(StandardCharsets.UTF_8);
        write(root.resolve(sourcePath),original);write(root.resolve("src/main/resources").resolve(runtimePath),output);
        var manifest=new LinkedHashMap<String,Object>();
        manifest.put("engineVersion","3.8.1-stable");manifest.put("path",runtimePath);manifest.put("sha256",hash(output));
        manifest.put("sourcePath",sourcePath);manifest.put("sourceSha256",hash(original));
        manifest.put("upstream","https://raw.githubusercontent.com/jMonkeyEngine/jmonkeyengine/v3.8.1-stable/jme3-core/src/main/resources/"+upstream);
        manifest.put("license","licenses/jme-BSD3.txt");manifest.put("licenseSha256",hash(Files.readAllBytes(root.resolve("src/main/resources/licenses/jme-BSD3.txt"))));
        String generator="src/tools/java/game/wreckriff/tools/PrepareEnvironmentLighting.java";
        manifest.put("generator",generator);manifest.put("generatorSha256",hash(Files.readAllBytes(root.resolve(generator))));
        manifest.put("changes",List.of("Explicit BC7_UNORM_SRGB diffuse bypasses duplicate colour-space conversion", "Scalar specular remains linear"));
        write(root.resolve("src/main/resources/materials/environment-lighting-provenance.json"),
                (new GsonBuilder().setPrettyPrinting().create().toJson(manifest)+"\n").getBytes(StandardCharsets.UTF_8));
    }
    private static void write(Path path,byte[] bytes)throws Exception{Files.createDirectories(path.getParent());Files.write(path,bytes);}
    private static String hash(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
}
