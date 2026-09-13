package game.wreckriff.tools;

import com.google.gson.GsonBuilder;
import com.jme3.asset.DesktopAssetManager;
import com.jme3.asset.plugins.FileLocator;
import com.jme3.export.binary.BinaryExporter;
import com.jme3.scene.Geometry;
import com.jme3.scene.Spatial;
import com.jme3.scene.VertexBuffer;
import com.jme3.util.mikktspace.MikktspaceTangentGenerator;
import game.wreckriff.presentation.SurfaceMaterials;
import game.wreckriff.simulation.ContactSurface;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Explicit offline GLB -> j3o conversion. The game never loads glTF or downloads architecture. */
public final class PrepareArenaModels {
    private PrepareArenaModels() { }
    public static void main(String[] args)throws Exception {
        if(args.length!=2)throw new IllegalArgumentException("source GLB directory and resource directory required");
        Path source=Path.of(args[0]).toAbsolutePath().normalize(),resources=Path.of(args[1]).toAbsolutePath().normalize();
        Path output=resources.resolve("models/arenas");Files.createDirectories(output);
        DesktopAssetManager assets=new DesktopAssetManager(true);
        assets.registerLocator(resources.toString(),FileLocator.class);assets.registerLocator(source.toString(),FileLocator.class);
        SurfaceMaterials materials=new SurfaceMaterials(assets);SurfaceMaterials.lightingDefinition(assets);
        List<Map<String,Object>> records=new ArrayList<>();
        try(var files=Files.list(source)) {
            for(Path glb:files.filter(path->path.getFileName().toString().endsWith(".glb")).sorted().toList()) {
                Spatial model=assets.loadModel(glb.getFileName().toString());
                Set<String> surfaces=new TreeSet<>();int[] triangles={0};
                model.depthFirstTraversal(spatial->{if(spatial instanceof Geometry geometry) {
                    String name=geometry.getName();
                    int begin=name.indexOf("surface:");
                    if(begin<0)throw new IllegalArgumentException("Model geometry requires surface:<material> name: "+name);
                    String surface=name.substring(begin+8).split("[^a-z-]",2)[0];
                    if(ContactSurface.fromMaterial(surface)==ContactSurface.UNKNOWN)throw new IllegalArgumentException("Architecture contact material is not authored: "+surface);
                    geometry.setMaterial(materials.material(surface));geometry.setUserData("surfaceMaterial",surface);surfaces.add(surface);
                    if(geometry.getMesh().getBuffer(VertexBuffer.Type.Normal)==null||geometry.getMesh().getBuffer(VertexBuffer.Type.TexCoord)==null)
                        throw new IllegalArgumentException("Normals and UVs required: "+name);
                    if(geometry.getMesh().getBuffer(VertexBuffer.Type.Tangent)==null)MikktspaceTangentGenerator.generate(geometry);
                    geometry.getMesh().setStatic();triangles[0]+=geometry.getMesh().getTriangleCount();
                }});
                if(triangles[0]==0)throw new IllegalArgumentException("Empty model: "+glb);
                model.setUserData("assetOrigin","ORIGINAL_PROJECT_CONTENT");model.setUserData("sourceGlb",glb.getFileName().toString());
                model.updateGeometricState();
                Path path=output.resolve(glb.getFileName().toString().replace(".glb",".j3o"));BinaryExporter.getInstance().save(model,path.toFile());
                var record=new LinkedHashMap<String,Object>();record.put("path","models/arenas/"+path.getFileName());
                record.put("source","src/tools/assets/architecture/"+glb.getFileName());record.put("sourceSha256",hash(glb));
                Path blend=glb.resolveSibling(glb.getFileName().toString().replace(".glb",".blend"));
                if(Files.isRegularFile(blend)) {
                    record.put("sourceBlend","src/tools/assets/architecture/"+blend.getFileName());record.put("sourceBlendSha256",hash(blend));
                }
                record.put("sha256",hash(path));record.put("triangles",triangles[0]);record.put("materials",surfaces);records.add(record);
                System.out.println("Prepared "+path.getFileName()+" ("+triangles[0]+" triangles)");
            }
        }
        if(records.isEmpty())throw new IllegalArgumentException("No authored GLB sources in "+source);
        var manifest=new LinkedHashMap<String,Object>();manifest.put("schemaVersion",1);manifest.put("origin","ORIGINAL_PROJECT_CONTENT");
        manifest.put("converter","src/tools/java/game/wreckriff/tools/PrepareArenaModels.java");
        manifest.put("converterSha256",hash(Path.of("src/tools/java/game/wreckriff/tools/PrepareArenaModels.java")));
        manifest.put("generator","src/tools/author_arena_models.py");manifest.put("generatorSha256",hash(Path.of("src/tools/author_arena_models.py")));
        manifest.put("layoutGeneratorSha256",hash(Path.of("src/tools/author_campaign_arenas.py")));
        manifest.put("supplyLayoutSha256",hash(Path.of("src/tools/assets/arena-revision3/supply-layout.json")));
        manifest.put("transformation","Local GLB geometry, authored UVs and normals; named surface materials replaced by shared lit Phong materials; tangent generation; jME binary export");
        manifest.put("artisticStatus","NEEDS_CREATIVE_REVIEW");manifest.put("assets",records);
        Files.writeString(output.resolve("provenance.json"),new GsonBuilder().setPrettyPrinting().create().toJson(manifest)+"\n",StandardCharsets.UTF_8);
    }
    private static String hash(Path path)throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));}
}
