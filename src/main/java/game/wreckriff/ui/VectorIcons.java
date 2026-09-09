package game.wreckriff.ui;

import com.jme3.scene.Mesh;
import com.jme3.scene.VertexBuffer;
import game.wreckriff.combat.AbilityId;
import game.wreckriff.combat.WeaponType;
import game.wreckriff.config.VehicleDefinition;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;

/** Original Wreck Riff vector artwork. Authored here; no downloaded font glyphs or image assets. */
public final class VectorIcons {
    public enum Icon {HOMING,POWER,MINE,NAPALM,BALLISTIC,CANNON,MACHINE_GUN,FREEZE,SHIELD,PULSE,GRINDER,DASH,HEALTH,TURBO,CHEVRON,DIAMOND,CROWN}
    private final EnumMap<Icon,Mesh> meshes=new EnumMap<>(Icon.class);
    public VectorIcons() {for(Icon icon:Icon.values())meshes.put(icon,draw(icon));}
    public Mesh mesh(Icon icon) {return meshes.get(icon);}
    public static Icon weapon(WeaponType type) {return Icon.valueOf(type.name());}
    public static Icon ability(AbilityId ability) {
        return switch(ability){case FREEZE->Icon.FREEZE;case SHIELD->Icon.SHIELD;case SPECIAL->Icon.PULSE;case NONE->throw new IllegalArgumentException("NONE has no ability icon");};
    }
    public static Icon special(String profileId) {return switch(VehicleDefinition.forId(profileId)){case RIVET->Icon.PULSE;case GRINDER->Icon.GRINDER;case SPARK->Icon.DASH;};}
    private static Mesh draw(Icon icon) {
        Pen pen=new Pen();
        switch(icon) {
            case HOMING -> {pen.path(.28f,.18f,.28f,.64f,.43f,.84f,.58f,.64f,.58f,.18f);pen.line(.28f,.4f,.12f,.2f);pen.line(.58f,.4f,.74f,.2f);pen.circle(.78f,.75f,.13f);pen.line(.78f,.57f,.78f,.93f);pen.line(.60f,.75f,.96f,.75f);}
            case POWER -> {pen.path(.24f,.14f,.24f,.68f,.44f,.92f,.64f,.68f,.64f,.14f,.24f,.14f);pen.line(.35f,.35f,.55f,.35f);pen.line(.35f,.46f,.55f,.46f);pen.line(.73f,.45f,.92f,.54f);pen.line(.75f,.29f,.94f,.25f);pen.line(.70f,.12f,.84f,.02f);}
            case MINE -> {pen.circle(.5f,.5f,.24f);for(int i=0;i<8;i++){double angle=i*Math.PI/4;pen.line(.5f+(float)Math.cos(angle)*.25f,.5f+(float)Math.sin(angle)*.25f,.5f+(float)Math.cos(angle)*.42f,.5f+(float)Math.sin(angle)*.42f);}pen.circle(.5f,.5f,.07f);}
            case NAPALM -> {pen.path(.5f,.91f,.67f,.66f,.65f,.51f,.82f,.64f,.84f,.37f,.73f,.17f,.51f,.09f,.29f,.17f,.16f,.37f,.20f,.61f,.35f,.45f,.33f,.67f,.5f,.91f);pen.path(.5f,.55f,.64f,.33f,.5f,.2f,.37f,.33f,.5f,.55f);}
            case BALLISTIC -> {pen.path(.1f,.56f,.25f,.79f,.43f,.91f,.61f,.79f);for(int i=0;i<3;i++){float x=.45f+i*.20f;pen.line(x,.66f,x,.25f);pen.path(x-.07f,.34f,x,.23f,x+.07f,.34f);}pen.line(.12f,.12f,.9f,.12f);}
            case CANNON -> {pen.circle(.63f,.6f,.26f);pen.line(.10f,.77f,.29f,.77f);pen.line(.04f,.60f,.26f,.60f);pen.path(.14f,.17f,.40f,.05f,.60f,.21f);pen.line(.60f,.21f,.60f,.10f);}
            case MACHINE_GUN -> {for(int i=0;i<3;i++){float x=.13f+i*.29f;pen.path(x,.13f,x,.66f,x+.10f,.88f,x+.20f,.66f,x+.20f,.13f,x,.13f);pen.line(x,.3f,x+.20f,.3f);}}
            case FREEZE -> {for(int i=0;i<6;i++){double angle=i*Math.PI/3;float x=(float)Math.cos(angle),y=(float)Math.sin(angle);pen.line(.5f,.5f,.5f+x*.41f,.5f+y*.41f);for(int side:new int[]{-1,1}){double branch=angle+side*.8;pen.line(.5f+x*.25f,.5f+y*.25f,.5f+x*.25f+(float)Math.cos(branch)*.15f,.5f+y*.25f+(float)Math.sin(branch)*.15f);}}}
            case SHIELD -> {pen.path(.5f,.93f,.86f,.78f,.81f,.41f,.66f,.19f,.5f,.07f,.34f,.19f,.19f,.41f,.14f,.78f,.5f,.93f);pen.path(.3f,.61f,.46f,.43f,.72f,.7f);}
            case PULSE -> {pen.line(.12f,.12f,.12f,.88f);pen.path(.32f,.2f,.62f,.5f,.32f,.8f);pen.path(.60f,.2f,.90f,.5f,.60f,.8f);}
            case GRINDER -> {pen.circle(.3f,.5f,.2f);pen.circle(.7f,.5f,.2f);for(float x:new float[]{.3f,.7f})for(int i=0;i<8;i++){double a=i*Math.PI/4;pen.line(x+(float)Math.cos(a)*.2f,.5f+(float)Math.sin(a)*.2f,x+(float)Math.cos(a)*.29f,.5f+(float)Math.sin(a)*.29f);}}
            case DASH -> {pen.path(.1f,.3f,.35f,.3f,.35f,.65f,.82f,.65f);pen.path(.65f,.83f,.84f,.65f,.65f,.47f);pen.circle(.15f,.18f,.08f);}
            case HEALTH -> {pen.polygon(.39f,.13f,.61f,.13f,.61f,.39f,.88f,.39f,.88f,.61f,.61f,.61f,.61f,.88f,.39f,.88f,.39f,.61f,.12f,.61f,.12f,.39f,.39f,.39f);}
            case TURBO -> {pen.path(.60f,.94f,.21f,.48f,.47f,.48f,.36f,.07f,.82f,.61f,.54f,.61f,.60f,.94f);}
            case CHEVRON -> pen.path(.13f,.27f,.5f,.75f,.87f,.27f);
            case DIAMOND -> pen.path(.5f,.05f,.95f,.5f,.5f,.95f,.05f,.5f,.5f,.05f);
            case CROWN -> {pen.path(.18f,.21f,.08f,.77f,.32f,.58f,.5f,.94f,.68f,.58f,.92f,.77f,.82f,.21f,.18f,.21f);pen.line(.22f,.35f,.78f,.35f);}
        }
        return pen.mesh();
    }
    private static final class Pen {
        private final List<Float> points=new ArrayList<>();
        private void triangle(float x1,float y1,float x2,float y2,float x3,float y3) {for(float value:new float[]{x1,y1,0,x2,y2,0,x3,y3,0})points.add(value);}
        private void line(float x1,float y1,float x2,float y2) {
            float length=(float)Math.hypot(x2-x1,y2-y1);if(length<.00001)return;
            float dx=-(y2-y1)/length*.027f,dy=(x2-x1)/length*.027f;
            triangle(x1+dx,y1+dy,x1-dx,y1-dy,x2+dx,y2+dy);triangle(x1-dx,y1-dy,x2-dx,y2-dy,x2+dx,y2+dy);
        }
        private void path(float... coords) {for(int i=2;i<coords.length;i+=2)line(coords[i-2],coords[i-1],coords[i],coords[i+1]);}
        private void polygon(float... coords) {path(coords);line(coords[coords.length-2],coords[coords.length-1],coords[0],coords[1]);}
        private void circle(float x,float y,float radius) {
            for(int i=0;i<24;i++){double a=i*Math.PI/12,b=(i+1)*Math.PI/12;line(x+(float)Math.cos(a)*radius,y+(float)Math.sin(a)*radius,x+(float)Math.cos(b)*radius,y+(float)Math.sin(b)*radius);}
        }
        private Mesh mesh() {
            float[] vertices=new float[points.size()];for(int i=0;i<vertices.length;i++)vertices[i]=points.get(i);
            Mesh mesh=new Mesh();mesh.setBuffer(VertexBuffer.Type.Position,3,vertices);mesh.setMode(Mesh.Mode.Triangles);mesh.updateBound();mesh.setStatic();return mesh;
        }
    }
}
