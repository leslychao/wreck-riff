package game.wreckriff.tools;

import com.google.gson.GsonBuilder;
import com.jme3.asset.DesktopAssetManager;
import com.jme3.asset.plugins.FileLocator;
import com.jme3.export.binary.BinaryExporter;
import com.jme3.material.Material;
import com.jme3.math.*;
import com.jme3.scene.*;
import com.jme3.util.BufferUtils;
import com.jme3.util.mikktspace.MikktspaceTangentGenerator;
import game.wreckriff.presentation.OrdnanceStyle;
import game.wreckriff.presentation.SurfaceMaterials;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.List;

/** Original offline model/UV/texture recipe; lettering uses locally retained OFL Roboto. No network. */
public final class GenerateOrdnance {
    private static final int SIZE=2048,CELL=512,GUTTER=10;
    private static final String SOURCE="src/tools/java/game/wreckriff/tools/GenerateOrdnance.java";
    private static final String FONT_SOURCE="src/tools/assets/fonts/RobotoCondensed-Bold.ttf";
    private static final Color[] PALETTE={new Color(118,128,104),new Color(140,61,43),new Color(182,119,39),
            new Color(85,106,119),new Color(160,118,59),new Color(57,64,70),new Color(160,184,185),new Color(80,93,65),
            new Color(135,144,148),new Color(42,37,33),new Color(29,32,35),new Color(29,60,68),
            new Color(193,148,42),new Color(163,106,57),new Color(115,121,124),new Color(193,195,173)};
    private static final String[] LABELS={"HR-07 / GUIDED","PR-90 / HEAVY","NP-25 / INCENDIARY","BL-04 / CARRIER", "BL-04 / CHARGE",
            "CB-65 / INERTIAL","FZ-12 / CRYOGENIC","MX-10 / PROXIMITY","ALLOY / 4140","EXHAUST / HOT","ELASTOMER","SENSOR / OPTICAL",
            "CAUTION / LIVE","DRIVE BAND","M8 / TORQUE 12Nm","INSPECTION / QC"};
    private final DesktopAssetManager assets=new DesktopAssetManager(true);
    private final Map<String,Material> materials=new HashMap<>();
    private final List<Map<String,Object>> records=new ArrayList<>();
    private Path output;
    private Font stencil;
    public static void main(String[] args)throws Exception {
        if(args.length!=2)throw new IllegalArgumentException("generated-resource directory and generator source required");
        new GenerateOrdnance().generate(Path.of(args[0]),Path.of(args[1]));
    }
    private void generate(Path directory,Path source)throws Exception {
        output=directory;Files.createDirectories(output.resolve("textures/ordnance"));Files.createDirectories(output.resolve("models/ordnance"));
        stencil=Font.createFont(Font.TRUETYPE_FONT,Path.of(FONT_SOURCE).toFile());
        textureAtlas("projectiles");textureAtlas("mines");
        assets.registerLocator(output.toAbsolutePath().toString(),FileLocator.class);
        for(var style:OrdnanceStyle.values()) {
            Node model=new Node("ordnance-"+style.kind());
            model.attachChild(geometry(style,true));model.attachChild(geometry(style,false));
            model.getChild("distance").setCullHint(Spatial.CullHint.Always);
            if(style.signal()!=null)model.attachChild(signal(style));
            model.setUserData("assetOrigin","ORIGINAL_PROJECT_CONTENT");model.setUserData("ordnanceKind",style.kind());
            model.setUserData("nozzleZ",style.nozzleZ());model.setUserData("atlas",style.atlas());
            model.updateGeometricState();Path path=output.resolve(style.model());BinaryExporter.getInstance().save(model,path.toFile());
            record(style.model(),"model",Map.of("kind",style.kind(),"detailTriangles",((Geometry)model.getChild("detail")).getMesh().getTriangleCount(),
                    "distanceTriangles",((Geometry)model.getChild("distance")).getMesh().getTriangleCount()));
        }
        Map<String,Object> manifest=new LinkedHashMap<>();manifest.put("schemaVersion",1);manifest.put("origin","ORIGINAL_PROJECT_CONTENT");
        manifest.put("generator",SOURCE);manifest.put("generatorSha256",hash(Files.readAllBytes(source)));
        manifest.put("licensePermission","Original Wreck Riff geometry and texture recipe; embedded stencil glyphs derived from OFL Roboto Condensed, notices retained.");
        manifest.put("externalImages",false);manifest.put("fontSource",FONT_SOURCE);manifest.put("fontSourceSha256",hash(Files.readAllBytes(Path.of(FONT_SOURCE))));
        manifest.put("fontLicense","OFL-1.1");manifest.put("fontLicensePath","licenses/assets/Roboto-OFL.txt");
        manifest.put("artisticStatus","NEEDS_CREATIVE_REVIEW");manifest.put("atlasSize",SIZE);
        manifest.put("transformation","Authored lathed shells, solid fins, panel bands, sensors and fasteners; Freeze has an exposed faceted ice core, cooling cage and technical fins in both detail levels; deterministic UV atlases with metal/paint/rubber/optics, marking, edge wear and tangent-space normal/specular data; jME binary export.");
        manifest.put("assets",records);Files.writeString(output.resolve("models/ordnance/provenance.json"),new GsonBuilder().setPrettyPrinting().create().toJson(manifest)+"\n",StandardCharsets.UTF_8);
        System.out.println("Exported eight original ordnance models and six 2048x2048 texture maps.");
    }
    private Geometry geometry(OrdnanceStyle style,boolean detail) {
        Author mesh=new Author();int n=detail?24:8,tile=style.ordinal();
        switch(style) {
            case HOMING -> {
                mesh.lathe(0,0,n,tile,-.33f,.07f,-.29f,.10f,-.12f,.108f,.22f,.108f,.29f,.096f,.40f,.058f,.47f,.02f);
                mesh.lathe(0,0,n,11,.40f,.058f,.48f,.026f,.53f,.001f);
                fins(mesh,3,.10f,.25f,-.32f,-.05f,.018f,tile);
                band(mesh,n,.112f,-.15f,.026f,8);band(mesh,n,.108f,.245f,.018f,13);
                nozzle(mesh,n,.075f,-.35f,.052f);
                if(detail)fasteners(mesh,8,.108f,.12f,.011f);
            }
            case POWER -> {
                mesh.lathe(0,0,n,tile,-.48f,.115f,-.42f,.178f,-.25f,.186f,.23f,.19f,.32f,.164f,.44f,.09f,.52f,.008f);
                fins(mesh,4,.17f,.445f,-.49f,-.09f,.03f,8);
                band(mesh,n,.20f,-.25f,.075f,13);band(mesh,n,.198f,.20f,.055f,8);
                nozzle(mesh,n,.125f,-.50f,.10f);
                if(detail){fasteners(mesh,10,.19f,-.22f,.018f);fasteners(mesh,10,.19f,.225f,.018f);}
            }
            case NAPALM -> {
                mesh.lathe(0,0,n,tile,-.28f,.13f,-.24f,.21f,-.17f,.25f,.18f,.25f,.27f,.21f,.34f,.09f,.36f,.015f);
                band(mesh,n,.258f,-.145f,.044f,8);band(mesh,n,.258f,.12f,.044f,8);
                nozzle(mesh,n,.10f,-.28f,.045f);fins(mesh,4,.20f,.35f,-.26f,-.10f,.025f,9);
                if(detail){fasteners(mesh,12,.253f,-.125f,.014f);mesh.box(0,.252f,0,.085f,.025f,.10f,12,0);}
            }
            case BALLISTIC -> {
                mesh.lathe(0,0,n,tile,-.83f,.18f,-.73f,.28f,-.62f,.32f,.35f,.34f,.51f,.28f,.74f,.12f,.89f,.005f);
                fins(mesh,4,.29f,.77f,-.84f,-.32f,.04f,8);
                band(mesh,n,.346f,-.32f,.072f,8);band(mesh,n,.342f,.34f,.09f,12);
                nozzle(mesh,n,.20f,-.85f,.12f);
                if(detail) {
                    fasteners(mesh,12,.335f,-.29f,.025f);fasteners(mesh,12,.34f,.38f,.025f);
                    for(int i=0;i<4;i++){float a=i*FastMath.HALF_PI;mesh.box(FastMath.cos(a)*.29f,FastMath.sin(a)*.29f,-.06f,.09f,.045f,.20f,9,a);}
                }
            }
            case BALLISTIC_FALL -> {
                mesh.lathe(0,0,n,tile,-.42f,.11f,-.31f,.16f,-.10f,.19f,.25f,.20f,.34f,.155f,.48f,.065f,.55f,.003f);
                fins(mesh,4,.13f,.42f,-.42f,-.17f,.022f,8);
                band(mesh,n,.207f,.19f,.045f,12);band(mesh,n,.175f,-.24f,.03f,8);nozzle(mesh,n,.082f,-.42f,.055f);
                if(detail)fasteners(mesh,8,.19f,.02f,.014f);
            }
            case CANNON -> {
                float[] profile=new float[(detail?17:9)*2];int steps=profile.length/2;
                for(int i=0;i<steps;i++){float angle=-FastMath.HALF_PI+i*FastMath.PI/(steps-1);profile[i*2]=FastMath.sin(angle)*.25f;profile[i*2+1]=Math.max(.001f,FastMath.cos(angle)*.25f);}
                mesh.lathe(0,0,detail?32:12,tile,profile);band(mesh,n,.254f,-.045f,.09f,13);
                if(detail){band(mesh,n,.251f,-.073f,.013f,8);band(mesh,n,.251f,.059f,.013f,8);}
            }
            case FREEZE -> {
                // A short cryogenic engine and open cage expose the luminous ice, instead of hiding it behind a dark sensor.
                mesh.lathe(0,0,n,tile,-.24f,.07f,-.205f,.11f,-.15f,.13f,-.055f,.13f,-.025f,.10f);
                nozzle(mesh,n,.065f,-.24f,.04f);fins(mesh,3,.12f,.30f,-.23f,.035f,.022f,8);
                band(mesh,n,.145f,-.055f,.026f,8);band(mesh,n,.128f,.145f,.024f,8);
                for(int i=0;i<3;i++) {
                    float angle=i*FastMath.TWO_PI/3;
                    mesh.box(FastMath.cos(angle)*.129f,FastMath.sin(angle)*.129f,.035f,.023f,.023f,.13f,10,angle);
                }
                if(detail){for(int i=0;i<4;i++)band(mesh,n,.138f,-.175f+i*.033f,.012f,8);fasteners(mesh,6,.147f,-.039f,.010f);}
            }
            case MINE -> {
                // Author along +Z and rotate onto +Y once at export; origin is the actual road contact plane.
                mesh.lathe(0,0,detail?32:12,10,.0f,.47f,.02f,.54f,.07f,.54f,.09f,.50f);
                mesh.lathe(0,0,detail?32:12,7,.065f,.47f,.10f,.51f,.18f,.49f,.23f,.43f,.258f,.32f);
                mesh.disk(.258f,.32f,detail?32:12,7);
                mesh.lathe(0,0,n,8,.23f,.175f,.26f,.175f,.28f,.13f,.284f,.001f);
                mesh.lathe(0,0,n,11,.27f,.082f,.325f,.069f,.335f,.001f);
                if(detail) {
                    for(int i=0;i<8;i++){float a=i*FastMath.TWO_PI/8;mesh.lathe(FastMath.cos(a)*.365f,FastMath.sin(a)*.365f,6,14,.238f,.027f,.256f,.027f,.263f,.016f);}
                    for(int i=0;i<4;i++){float a=i*FastMath.HALF_PI;mesh.box(FastMath.cos(a)*.47f,FastMath.sin(a)*.47f,.13f,.065f,.04f,.057f,8,a);}
                    mesh.box(.25f,0,.257f,.058f,.135f,.006f,12,0);
                }
                mesh.rotate(new Quaternion().fromAngleAxis(-FastMath.HALF_PI,Vector3f.UNIT_X));
            }
        }
        Geometry geometry=new Geometry(detail?"detail":"distance",mesh.mesh());geometry.setMaterial(materials.computeIfAbsent(style.atlas(),atlas->{
            Material material=SurfaceMaterials.lit(assets,ColorRGBA.White,44,.65f);
            for(var texture:OrdnanceStyle.textures(atlas))material.setTexture(texture.parameter(),texture.load(assets));
            return material;
        }));return geometry;
    }
    private Geometry signal(OrdnanceStyle style) {
        Author shape=new Author();
        if(style==OrdnanceStyle.MINE) {
            shape.lathe(-.22f,0,12,11,.259f,.034f,.273f,.034f,.28f,.001f);
            shape.rotate(new Quaternion().fromAngleAxis(-FastMath.HALF_PI,Vector3f.UNIT_X));
        } else if(style==OrdnanceStyle.FREEZE) {
            shape.crystal(8,6,-.09f,.075f,-.015f,.12f,.16f,.108f,.285f,.078f,.50f,.001f);
            shape.lathe(0,0,12,11,-.245f,.001f,-.243f,.032f,-.237f,.032f);
        } else {
            float radius=switch(style){case BALLISTIC->.105f;case POWER->.068f;case NAPALM,BALLISTIC_FALL->.041f;default->.024f;};
            float tail=style.nozzleZ()-.001f;
            shape.lathe(0,0,12,11,tail,.001f,tail+.003f,radius,tail+.01f,radius);
        }
        Geometry geometry=new Geometry("signal",shape.mesh());Material material=new Material(assets,"Common/MatDefs/Misc/Unshaded.j3md");
        if(style==OrdnanceStyle.FREEZE) {
            // Authored facet values keep the core dimensional even in shadow and with bloom disabled.
            var normals=geometry.getMesh().getFloatBuffer(VertexBuffer.Type.Normal);float[] colors=new float[normals.limit()/3*4];
            for(int vertex=0;vertex<colors.length/4;vertex++) {
                float facet=.62f+.38f*Math.max(0,normals.get(vertex*3)*.35f+normals.get(vertex*3+1)*.72f+normals.get(vertex*3+2)*.42f);
                colors[vertex*4]=facet*.62f;colors[vertex*4+1]=facet*.93f;colors[vertex*4+2]=facet;colors[vertex*4+3]=1;
            }
            geometry.getMesh().setBuffer(VertexBuffer.Type.Color,4,colors);material.setBoolean("VertexColor",true);
            material.setColor("Color",ColorRGBA.White);material.setColor("GlowColor",new ColorRGBA(.18f,.53f,.62f,1));
        } else {material.setColor("Color",style.signal());material.setColor("GlowColor",style.signal().mult(.35f));}
        geometry.setMaterial(material);return geometry;
    }
    private static void nozzle(Author mesh,int n,float radius,float tail,float length) {
        mesh.lathe(0,0,n,9,tail,radius,tail+.012f,radius*1.09f,tail+length,radius*.76f);
        mesh.lathe(0,0,n,8,tail,radius*1.02f,tail+.012f,radius*1.12f,tail+.025f,radius*1.03f);
    }
    private static void band(Author mesh,int n,float radius,float z,float width,int tile) {
        mesh.lathe(0,0,n,tile,z,radius*.985f,z+.004f,radius,z+width-.004f,radius,z+width,radius*.985f);
    }
    private static void fasteners(Author mesh,int count,float radius,float z,float size) {
        for(int i=0;i<count;i++){float angle=i*FastMath.TWO_PI/count;mesh.box(FastMath.cos(angle)*radius,FastMath.sin(angle)*radius,z,size,size*.38f,size,14,angle-FastMath.HALF_PI);}
    }
    private static void fins(Author mesh,int count,float root,float tip,float tail,float lead,float thickness,int tile) {
        for(int i=0;i<count;i++) {
            float angle=i*FastMath.TWO_PI/count;Quaternion pose=new Quaternion().fromAngleAxis(angle,Vector3f.UNIT_Z);
            Vector3f[] shape={new Vector3f(root,-thickness,lead),new Vector3f(tip,-thickness,tail+.03f),new Vector3f(root,-thickness,tail),
                    new Vector3f(root,thickness,lead),new Vector3f(tip,thickness,tail+.03f),new Vector3f(root,thickness,tail)};
            for(var p:shape)pose.multLocal(p);
            mesh.triangle(shape[0],shape[2],shape[1],tile);mesh.triangle(shape[3],shape[4],shape[5],tile);
            mesh.quad(shape[0],shape[1],shape[4],shape[3],tile);mesh.quad(shape[1],shape[2],shape[5],shape[4],tile);
            mesh.quad(shape[2],shape[0],shape[3],shape[5],tile);
        }
    }
    /** Explicit cylindrical UVs and smooth analytic normals; solid fin edges use face normals. */
    private static final class Author {
        final List<Float> p=new ArrayList<>(),n=new ArrayList<>(),uv=new ArrayList<>();
        void vertex(Vector3f point,Vector3f normal,int tile,float u,float v) {
            p.add(point.x);p.add(point.y);p.add(point.z);n.add(normal.x);n.add(normal.y);n.add(normal.z);
            uv.add(((tile%4)*CELL+GUTTER+u*(CELL-GUTTER*2))/SIZE);
            uv.add(1-((tile/4)*CELL+GUTTER+(1-v)*(CELL-GUTTER*2))/SIZE);
        }
        void lathe(float cx,float cy,int segments,int tile,float... profile) {
            int rings=profile.length/2;float start=profile[0],span=profile[profile.length-2]-start;
            for(int ring=0;ring<rings-1;ring++)for(int segment=0;segment<segments;segment++) {
                int[] rr={ring,ring,ring+1,ring,ring+1,ring+1},ss={segment,segment+1,segment+1,segment,segment+1,segment};
                for(int k=0;k<6;k++) {
                    int r=rr[k],s=ss[k];float a=s*FastMath.TWO_PI/segments,z=profile[r*2],radius=profile[r*2+1];
                    int before=Math.max(0,r-1),after=Math.min(rings-1,r+1);
                    float dz=profile[after*2]-profile[before*2],dr=profile[after*2+1]-profile[before*2+1];
                    Vector3f normal=new Vector3f(FastMath.cos(a)*dz,FastMath.sin(a)*dz,-dr).normalizeLocal();
                    vertex(new Vector3f(cx+FastMath.cos(a)*radius,cy+FastMath.sin(a)*radius,z),normal,tile,s/(float)segments,(z-start)/span);
                }
            }
        }
        void crystal(int segments,int tile,float... profile) {
            for(int ring=0;ring<profile.length/2-1;ring++)for(int segment=0;segment<segments;segment++) {
                float a=segment*FastMath.TWO_PI/segments,b=(segment+1)*FastMath.TWO_PI/segments;
                float rear=profile[ring*2],front=profile[(ring+1)*2],r0=profile[ring*2+1],r1=profile[(ring+1)*2+1];
                quad(new Vector3f(FastMath.cos(a)*r0,FastMath.sin(a)*r0,rear),new Vector3f(FastMath.cos(b)*r0,FastMath.sin(b)*r0,rear),
                        new Vector3f(FastMath.cos(b)*r1,FastMath.sin(b)*r1,front),new Vector3f(FastMath.cos(a)*r1,FastMath.sin(a)*r1,front),tile);
            }
        }
        void triangle(Vector3f a,Vector3f b,Vector3f c,int tile) {
            Vector3f normal=b.subtract(a).cross(c.subtract(a)).normalizeLocal();vertex(a,normal,tile,.08f,.1f);vertex(b,normal,tile,.92f,.1f);vertex(c,normal,tile,.5f,.9f);
        }
        void disk(float z,float radius,int segments,int tile) {
            for(int i=0;i<segments;i++) {
                float a=i*FastMath.TWO_PI/segments,b=(i+1)*FastMath.TWO_PI/segments;
                vertex(new Vector3f(0,0,z),Vector3f.UNIT_Z,tile,.5f,.5f);
                vertex(new Vector3f(FastMath.cos(a)*radius,FastMath.sin(a)*radius,z),Vector3f.UNIT_Z,tile,.5f+FastMath.cos(a)*.46f,.5f+FastMath.sin(a)*.46f);
                vertex(new Vector3f(FastMath.cos(b)*radius,FastMath.sin(b)*radius,z),Vector3f.UNIT_Z,tile,.5f+FastMath.cos(b)*.46f,.5f+FastMath.sin(b)*.46f);
            }
        }
        void quad(Vector3f a,Vector3f b,Vector3f c,Vector3f d,int tile) {
            Vector3f normal=b.subtract(a).cross(c.subtract(a)).normalizeLocal();
            Vector3f[] points={a,b,c,a,c,d};float[] coords={.06f,.06f,.94f,.06f,.94f,.94f,.06f,.06f,.94f,.94f,.06f,.94f};
            for(int i=0;i<6;i++)vertex(points[i],normal,tile,coords[i*2],coords[i*2+1]);
        }
        void box(float x,float y,float z,float hx,float hy,float hz,int tile,float angle) {
            Vector3f[] v={new Vector3f(-hx,-hy,-hz),new Vector3f(hx,-hy,-hz),new Vector3f(hx,hy,-hz),new Vector3f(-hx,hy,-hz),
                    new Vector3f(-hx,-hy,hz),new Vector3f(hx,-hy,hz),new Vector3f(hx,hy,hz),new Vector3f(-hx,hy,hz)};
            Quaternion pose=new Quaternion().fromAngleAxis(angle,Vector3f.UNIT_Z);for(var point:v)pose.multLocal(point).addLocal(x,y,z);
            for(int[] face:new int[][]{{0,3,2,1},{4,5,6,7},{0,1,5,4},{3,7,6,2},{1,2,6,5},{0,4,7,3}})quad(v[face[0]],v[face[1]],v[face[2]],v[face[3]],tile);
        }
        void rotate(Quaternion pose) {
            for(int i=0;i<p.size();i+=3) {Vector3f point=pose.mult(new Vector3f(p.get(i),p.get(i+1),p.get(i+2))),normal=pose.mult(new Vector3f(n.get(i),n.get(i+1),n.get(i+2)));
                p.set(i,point.x);p.set(i+1,point.y);p.set(i+2,point.z);n.set(i,normal.x);n.set(i+1,normal.y);n.set(i+2,normal.z);}
        }
        Mesh mesh() {
            Mesh mesh=new Mesh();mesh.setBuffer(VertexBuffer.Type.Position,3,BufferUtils.createFloatBuffer(array(p)));
            mesh.setBuffer(VertexBuffer.Type.Normal,3,BufferUtils.createFloatBuffer(array(n)));mesh.setBuffer(VertexBuffer.Type.TexCoord,2,BufferUtils.createFloatBuffer(array(uv)));
            MikktspaceTangentGenerator.generate(mesh);mesh.setStatic();mesh.updateBound();return mesh;
        }
        private static float[] array(List<Float> values){float[] result=new float[values.size()];for(int i=0;i<result.length;i++)result[i]=values.get(i);return result;}
    }
    private void textureAtlas(String atlas)throws Exception {
        BufferedImage color=new BufferedImage(SIZE,SIZE,BufferedImage.TYPE_INT_RGB),height=new BufferedImage(SIZE,SIZE,BufferedImage.TYPE_BYTE_GRAY),spec=new BufferedImage(SIZE,SIZE,BufferedImage.TYPE_INT_RGB);
        Random random=new Random(atlas.equals("mines")?0x4d494e4532303236L:0x5249464632303236L);
        for(int tile=0;tile<16;tile++) {
            Color base=PALETTE[tile];int ox=(tile%4)*CELL,oy=(tile/4)*CELL;
            if(atlas.equals("mines")&&tile<8)base=tile==7?new Color(93,104,67):PALETTE[(tile+7)%8];
            for(int y=0;y<CELL;y++)for(int x=0;x<CELL;x++) {
                double noise=random.nextGaussian()*2.8+Math.sin(y*.8)*.75;
                double brushed=tile==8||tile==13||tile==14?Math.sin(y*2.1)*2.1:0;
                double edge=Math.min(Math.min(x,CELL-1-x),Math.min(y,CELL-1-y));
                double grime=8*Math.exp(-edge/18.0)+2*Math.sin(x*.032)*Math.sin(y*.019);
                double soot=tile==9?15*(1-y/(double)CELL):0;
                int rgb=new Color(channel(base.getRed()+noise+brushed-grime-soot),channel(base.getGreen()+noise+brushed-grime-soot),channel(base.getBlue()+noise+brushed-grime-soot)).getRGB();
                color.setRGB(ox+x,oy+y,rgb);height.getRaster().setSample(ox+x,oy+y,0,channel(128+noise*.19));
                int shine=channel((tile==10?32:tile==11?218:tile>=8?155:83)+noise*1.8);spec.setRGB(ox+x,oy+y,new Color(shine,shine,shine).getRGB());
            }
            Graphics2D g=color.createGraphics(),h=height.createGraphics(),s=spec.createGraphics();
            for(Graphics2D graphics:List.of(g,h,s)){graphics.translate(ox,oy);graphics.setClip(GUTTER,GUTTER,CELL-GUTTER*2,CELL-GUTTER*2);graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);}
            // Broad seams are visible in the silhouettes' normal map; edge chips expose higher-specular bare metal.
            for(int y:new int[]{37,450}) {
                g.setColor(new Color(25,28,27,190));g.fillRect(0,y,CELL,3);g.setColor(new Color(180,184,167,110));g.fillRect(0,y+3,CELL,1);
                h.setColor(new Color(96,96,96));h.fillRect(0,y,CELL,3);s.setColor(new Color(45,45,45));s.fillRect(0,y,CELL,3);
            }
            for(int i=0;i<480;i++) {
                int x=random.nextInt(CELL),y=random.nextInt(CELL),length=2+random.nextInt(14);
                if(i<260)y=random.nextBoolean()?39+random.nextInt(8):442+random.nextInt(12);
                g.setColor(new Color(165+random.nextInt(45),169+random.nextInt(35),158+random.nextInt(35),70+random.nextInt(90)));
                g.drawLine(x,y,x+length,y+random.nextInt(3)-1);h.setColor(new Color(114,114,114));h.drawLine(x,y,x+length,y);
                s.setColor(new Color(175,175,175));s.drawLine(x,y,x+length,y);
            }
            for(int x:new int[]{31,251,473})for(int y:new int[]{58,429}) {
                g.setColor(new Color(29,33,32));g.fillOval(x-7,y-7,14,14);g.setColor(new Color(151,157,152));g.fillOval(x-5,y-5,10,10);
                g.setColor(new Color(43,49,48));g.drawLine(x-3,y,x+3,y);h.setColor(new Color(153,153,153));h.fillOval(x-6,y-6,12,12);
            }
            if(tile<8||tile==12||tile==15) {
            g.setColor(new Color(225,221,190,225));g.setFont(stencil.deriveFont(24f));g.drawString(LABELS[tile],30,191);
            g.setFont(stencil.deriveFont(14f));g.drawString("WR // LOT 0913  -  SERIAL 0047",30,214);
            g.drawString("SERVICE PANEL  /  KEEP CLEAR",30,234);
            // A real stencilled safety symbol and directional assembly marks, not flat weapon colours.
            g.setStroke(new BasicStroke(3));g.drawPolygon(new int[]{390,418,362},new int[]{286,333,333},3);g.drawLine(390,303,390,317);g.fillOval(388,323,4,4);
            g.fillPolygon(new int[]{55,67,79,71,71,63,63},new int[]{301,283,301,301,329,329,301},7);
            for(int line=0;line<26;line++){int width=1+random.nextInt(3);g.fillRect(30+line*5,365,width,34);}
            }
            if(tile==12)for(int stripe=-2;stripe<10;stripe++){g.setColor(new Color(24,29,26));g.fillPolygon(new int[]{stripe*72,stripe*72+34,stripe*72+104,stripe*72+70},new int[]{10,10,95,95},4);}
            if(tile==10)for(int y=16;y<500;y+=12){g.setColor(new Color(8,10,11,120));g.fillRect(0,y,512,4);h.setColor(new Color(105,105,105));h.fillRect(0,y,512,4);}
            if(tile==11){g.setColor(new Color(72,155,173,100));g.fillOval(116,102,240,240);g.setColor(new Color(180,221,220,110));g.drawArc(132,118,207,207,35,88);}
            g.dispose();h.dispose();s.dispose();
        }
        BufferedImage normal=new BufferedImage(SIZE,SIZE,BufferedImage.TYPE_INT_RGB);
        for(int y=0;y<SIZE;y++)for(int x=0;x<SIZE;x++) {
            int left=Math.max((x/CELL)*CELL,x-1),right=Math.min((x/CELL+1)*CELL-1,x+1),up=Math.max((y/CELL)*CELL,y-1),down=Math.min((y/CELL+1)*CELL-1,y+1);
            float nx=(height.getRaster().getSample(left,y,0)-height.getRaster().getSample(right,y,0))*.045f;
            // Pixel +Y points down; the UV's +V points up (OpenGL normal convention).
            float ny=(height.getRaster().getSample(x,down,0)-height.getRaster().getSample(x,up,0))*.045f;
            Vector3f v=new Vector3f(nx,ny,1).normalizeLocal();normal.setRGB(x,y,new Color(channel((v.x*.5+.5)*255),channel((v.y*.5+.5)*255),channel((v.z*.5+.5)*255)).getRGB());
        }
        for(String channel:List.of("diffuse","normal","specular")) {
            BufferedImage image=switch(channel){case "diffuse"->color;case "normal"->normal;default->spec;};
            String path="textures/ordnance/"+atlas+"-"+channel+".png";ImageIO.write(image,"png",output.resolve(path).toFile());
            record(path,"texture",Map.of("atlas",atlas,"channel",channel,"width",SIZE,"height",SIZE,"normalConvention","OpenGL +Y"));
        }
    }
    private static int channel(double value){return (int)Math.clamp(Math.round(value),0,255);}
    private void record(String path,String category,Map<String,Object> extra)throws Exception {
        Map<String,Object> record=new LinkedHashMap<>();record.put("path",path);record.put("category",category);record.put("sha256",hash(Files.readAllBytes(output.resolve(path))));record.putAll(new TreeMap<>(extra));records.add(record);
    }
    private static String hash(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
}
