package game.wreckriff.ui;

import com.jme3.asset.AssetManager;
import com.jme3.material.Material;
import com.jme3.math.*;
import com.jme3.scene.*;
import game.wreckriff.arena.*;
import game.wreckriff.audio.UiCue;
import game.wreckriff.simulation.*;
import java.util.*;

/** Paused, authored world overview; roads and marker positions come from the live match. */
public final class TacticalMapView implements AutoCloseable {
    private static final ColorRGBA ROAD=new ColorRGBA(.39f,.48f,.53f,1),UPPER=new ColorRGBA(.32f,.68f,.81f,1),
            TRANSITION=new ColorRGBA(.93f,.64f,.29f,1),PLAYER=new ColorRGBA(.35f,1,.7f,1),ENEMY=new ColorRGBA(1,.36f,.29f,1);
    private final AssetManager assets;
    private final GameUi ui;
    private ArenaDefinition arena;
    private TacticalMapModel model;
    private MatchSession session;
    private WorldQuery world;
    private List<ArenaDefinition.Pickup> pickups=List.of();
    private Runnable back=()->{};
    private java.util.function.Consumer<UiCue> feedback=ignored->{};
    private int width=1280,height=720,levelIndex;
    private float scale=1;
    private List<Integer> levels=List.of();
    public TacticalMapView(AssetManager assets,Node gui) {this.assets=assets;ui=new GameUi(assets,gui);setVisible(false);}
    public void onFeedback(java.util.function.Consumer<UiCue> listener) {feedback=Objects.requireNonNull(listener);}
    public void show(ArenaDefinition arena,MatchSession session,WorldQuery world,List<ArenaDefinition.Pickup> pickups,Runnable back) {
        if(this.arena!=arena) {this.arena=arena;model=new TacticalMapModel(arena.bounds());levelIndex=0;levels=arena.surfaces().stream().map(ArenaDefinition.Surface::level).distinct().sorted().toList();}
        this.session=session;this.world=world;this.pickups=List.copyOf(pickups);this.back=back;setVisible(true);render();
    }
    public void resize(int pixelWidth,int pixelHeight,float scale) {
        if(pixelWidth<320||pixelHeight<240)return;
        int width=Math.round(pixelWidth/scale),height=Math.round(pixelHeight/scale);
        if(this.width==width&&this.height==height&&this.scale==scale)return;
        this.width=width;this.height=height;this.scale=scale;if(arena!=null&&visible())render();
    }
    public boolean visible() {return ui.root().getCullHint()!=Spatial.CullHint.Always;}
    public void setVisible(boolean value) {ui.root().setCullHint(value?Spatial.CullHint.Inherit:Spatial.CullHint.Always);}
    public void zoom(int direction) {change(()->model.zoomBy(direction>0?1.35f:1/1.35f));}
    public void pan(int x,int z) {change(()->model.pan(x,z));}
    public void cycle() {change(()->levelIndex=(levelIndex+1)%(levels.size()+1));}
    public void hover(float x,float y) {String before=selectedId();ui.hover(x/scale,y/scale);navigationFeedback(before);}
    public void click(float x,float y) {ui.click(x/scale,y/scale);}
    public void move(UiFocusModel.Direction direction) {String before=selectedId();ui.move(direction);navigationFeedback(before);}
    public void activate() {ui.activate();}
    public boolean focus(String id) {return ui.select(id);}
    public String selectedId() {return ui.selectedId();}
    public Map<String,Object> observation() {return Map.of("mapZoom",model.zoom(),"mapLevel",levelIndex,"mapCenterX",model.centerX(),"mapCenterZ",model.centerZ());}
    public void tab(boolean backwards) {move(backwards?UiFocusModel.Direction.LEFT:UiFocusModel.Direction.RIGHT);}
    private void navigationFeedback(String before) {
        if(selectedId()!=null&&!Objects.equals(before,selectedId()))feedback.accept(UiCue.NAVIGATE);
    }
    private void change(Runnable action) {
        var before=observation();action.run();render();
        if(!before.equals(observation()))feedback.accept(UiCue.CHANGE);
    }
    private boolean levelShown(String surfaceId) {
        return levelIndex==0||arena.surfaces().stream().anyMatch(s->s.id().equals(surfaceId)&&s.level()==levels.get(levelIndex-1));
    }
    private void render() {
        if(arena==null)return;
        ui.clearPreservingFocus();ui.usePixelCoordinates();ui.root().setLocalScale(scale);
        ui.rect("map-background",0,0,width,height,GameUi.INK,0);
        boolean compact=height<500;
        float margin=compact?12:20,available=width-2*margin;
        ui.text(arena.metadata().title(),margin,height-(compact?10:18),ui.fitTextSize(arena.metadata().title(),available,compact?22:28),GameUi.PAPER);
        String summary="БОЙ ПРИОСТАНОВЛЕН  /  ВРАГОВ: "+session.vehicles.stream().filter(v->v.id!=0&&v.alive()).count();
        ui.text(summary,margin,height-(compact?37:52),ui.fitTextSize(summary,available,compact?14:16),GameUi.MUTED);
        UiBounds area=new UiBounds(margin,compact?113:151,available,Math.max(60,height-(compact?170:230)));model.resize(area);
        ui.rect("map-terrain",area.x(),area.y(),area.width(),area.height(),new ColorRGBA(.065f,.085f,.093f,1),1);
        drawTerrain();drawRoads();drawDistrictBoundaries();
        List<UiBounds> labels=new ArrayList<>();
        for(var district:arena.districts()) {
            var p=model.project(district.center().x(),district.center().z());
            if(!model.visible(p))continue;
            var label=ui.text(district.title(),0,0,compact?11:14,GameUi.PAPER);
            float w=label.getLineWidth(),h=label.getLineHeight();boolean placed=false;
            for(float offset:new float[]{18,-10,34,-26,50}) {
                float x=Math.clamp(p.x()-w/2,area.x()+3,Math.max(area.x()+3,area.right()-w-3));
                float top=p.y()+offset;var bounds=new UiBounds(x,top-h,w,h);
                if(bounds.y()<area.y()+3||bounds.top()>area.top()-18||bounds.right()>area.right()-3)continue;
                if(labels.stream().anyMatch(b->b.x()<bounds.right()+4&&b.right()+4>bounds.x()&&b.y()<bounds.top()+3&&b.top()+3>bounds.y()))continue;
                label.setLocalTranslation(x,top,5);labels.add(bounds);placed=true;break;
            }
            if(!placed)label.removeFromParent();
        }
        for(var pickup:pickups) {
            var surface=arena.surfaceAt(pickup.position().vector(),0,2);
            if(levelIndex>0&&(surface.isEmpty()||!levelShown(surface.get().id())))continue;
            marker("pickup-"+pickup.id(),pickup.position().vector(),pickup.type()==ArenaDefinition.PickupType.REPAIR?PLAYER:TRANSITION,4,false);
        }
        for(var vehicle:session.vehicles)if(vehicle.alive()) {
            var road=world.roadContext(vehicle.id);
            if(levelIndex>0&&vehicle.id!=0&&road.known()&&!levelShown(road.surfaceId()))continue;
            marker("vehicle-"+vehicle.id,world.position(vehicle.id),vehicle.id==0?PLAYER:vehicle.boss?TRANSITION:ENEMY,vehicle.boss?9:7,true);
        }
        ui.text("СЕВЕР ^",area.right()-88,area.top()-9,14,GameUi.MUTED);
        if(compact) {
            ui.text("Вы / ремонт",margin,105,14,PLAYER);
            ui.text("Враги",margin+available*.37f,105,14,ENEMY);
            ui.text("Босс / боезапас",margin+available*.62f,105,14,TRANSITION);
        } else ui.text("Зелёный: вы / ремонт    Красный: враги    Золотой: босс / боезапас",margin,138,ui.fitTextSize("Зелёный: вы / ремонт    Красный: враги    Золотой: босс / боезапас",available,16),GameUi.MUTED);
        String level=levelIndex==0?"Все высоты":"Уровень "+levels.get(levelIndex-1);
        String controls=level+"   ·   "+String.format(Locale.ROOT,"%.1f×",model.zoom())+(compact?"   ·   Стрелки / колесо":"   ·   Стрелки: обзор   Колесо: масштаб");
        ui.text(controls,margin,compact?84:116,ui.fitTextSize(controls,available,14),GameUi.MUTED);
        float gap=6,bw=(available-gap*5)/6;
        float row=compact?42:58,panRow=compact?10:17;
        button("zoom-out","-",0,bw,gap,row,()->zoom(-1));button("zoom-in","+",1,bw,gap,row,()->zoom(1));
        button("fit",compact?"Всё":"Целиком",2,bw,gap,row,()->change(model::fit));
        button("player",compact?"Я":"К машине",3,bw,gap,row,()->change(()->{var p=world.position(0);model.center(p.x,p.z);}));
        button("level",compact?"Слой":"Высоты",4,bw,gap,row,this::cycle);
        button("back",compact?"Esc":"Назад",5,bw,gap,row,()->{back.run();feedback.accept(UiCue.BACK);});
        button("west","<",0,bw,gap,panRow,()->pan(-1,0));button("north","^",1,bw,gap,panRow,()->pan(0,1));
        button("south","v",2,bw,gap,panRow,()->pan(0,-1));button("east",">",3,bw,gap,panRow,()->pan(1,0));
        ui.text("Esc — закрыть",margin+4*(bw+gap),compact?37:44,14,GameUi.MUTED);
    }
    private void button(String id,String label,int column,float bw,float gap,float y,Runnable action) {
        boolean compact=height<500;
        ui.button("map:"+id,label,new UiBounds((compact?12:20)+column*(bw+gap),y,bw,compact?26:38),true,action);
    }
    private void marker(String id,Vector3f at,ColorRGBA color,float radius,boolean solid) {
        var p=model.project(at.x,at.z);var v=model.viewport();
        if(p.x()-radius<v.x()||p.x()+radius>v.right()||p.y()-radius<v.y()||p.y()+radius>v.top())return;
        ui.rect(id,p.x()-radius/2,p.y()-radius/2,radius,radius,color,4);
        if(!solid)ui.rect(id+"-center",p.x()-1,p.y()-1,2,2,GameUi.INK,4.1f);
    }
    private void drawRoads() {
        Map<Integer,ArenaDefinition.NavNode> nodes=new HashMap<>();arena.nodes().forEach(n->nodes.put(n.id(),n));
        List<Float> positions=new ArrayList<>(),colors=new ArrayList<>();
        Set<String> roadGeometry=new HashSet<>();arena.roads().forEach(r->roadGeometry.addAll(r.geometryIds()));
        for(var mesh:arena.meshes())if(roadGeometry.contains(mesh.id())&&arena.surfaces().stream().anyMatch(s->s.geometryId().equals(mesh.id())&&levelShown(s.id()))) {
            ColorRGBA color=mesh.vertices().stream().anyMatch(v->v.y()>2)?UPPER:ROAD;
            for(int i=0;i<mesh.indices().size();i+=3) {
                List<TacticalMapModel.Point> triangle=new ArrayList<>();
                for(int j=0;j<3;j++){var v=mesh.vertices().get(mesh.indices().get(i+j));triangle.add(model.project(v.x(),v.z()));}
                footprint(triangle,color,positions,colors);
            }
        }
        for(var edge:arena.edges()) {
            if(!arena.roads().isEmpty()&&edge.type()==ArenaDefinition.Transition.ROAD)continue;
            var from=nodes.get(edge.from());var to=nodes.get(edge.to());
            if(!levelShown(from.surfaceId())&&!levelShown(to.surfaceId()))continue;
            var a=from.position();var b=to.position();
            ColorRGBA color=edge.type()==ArenaDefinition.Transition.ROAD?(a.y()>2||b.y()>2?UPPER:ROAD):TRANSITION;
            model.clip(a.x(),a.z(),b.x(),b.z()).ifPresent(segment->{
                float dx=segment.to().x()-segment.from().x(),dy=segment.to().y()-segment.from().y(),length=(float)Math.sqrt(dx*dx+dy*dy);
                if(length<.01f)return;
                float half=Math.clamp(edge.width()*model.pixelsPerMeter()/2,1,8),nx=-dy/length*half,ny=dx/length*half;
                float x=segment.from().x(),y=segment.from().y(),xx=segment.to().x(),yy=segment.to().y();
                float[] corners={x+nx,y+ny,x-nx,y-ny,xx-nx,yy-ny,x+nx,y+ny,xx-nx,yy-ny,xx+nx,yy+ny};
                var v=model.viewport();
                for(int i=0;i<corners.length;i+=2) {
                    positions.add(Math.clamp(corners[i],v.x(),v.right()));positions.add(Math.clamp(corners[i+1],v.y(),v.top()));positions.add(0f);
                    colors.add(color.r);colors.add(color.g);colors.add(color.b);colors.add(1f);
                }
            });
        }
        if(positions.isEmpty())return;
        Mesh mesh=new Mesh();mesh.setBuffer(VertexBuffer.Type.Position,3,array(positions));mesh.setBuffer(VertexBuffer.Type.Color,4,array(colors));mesh.updateBound();
        Material material=new Material(assets,"Common/MatDefs/Misc/Unshaded.j3md");material.setBoolean("VertexColor",true);
        Geometry geometry=new Geometry("map-roads",mesh);geometry.setLocalTranslation(0,0,2);geometry.setMaterial(material);ui.root().attachChild(geometry);
    }
    private void drawDistrictBoundaries() {
        List<Float> positions=new ArrayList<>();
        for(var district:arena.districts())for(int i=0;i<district.boundary().size();i++) {
            var a=district.boundary().get(i);var b=district.boundary().get((i+1)%district.boundary().size());
            model.clip(a.x(),a.z(),b.x(),b.z()).ifPresent(s->{
                positions.add(s.from().x());positions.add(s.from().y());positions.add(0f);
                positions.add(s.to().x());positions.add(s.to().y());positions.add(0f);
            });
        }
        if(positions.isEmpty())return;
        Mesh mesh=new Mesh();mesh.setMode(Mesh.Mode.Lines);mesh.setBuffer(VertexBuffer.Type.Position,3,array(positions));mesh.updateBound();
        Material material=new Material(assets,"Common/MatDefs/Misc/Unshaded.j3md");material.setColor("Color",new ColorRGBA(.35f,.4f,.4f,1));
        Geometry geometry=new Geometry("map-district-boundaries",mesh);geometry.setMaterial(material);geometry.setLocalTranslation(0,0,2.2f);ui.root().attachChild(geometry);
    }
    private void drawTerrain() {
        List<Float> positions=new ArrayList<>(),colors=new ArrayList<>();
        Set<String> roads=new HashSet<>();arena.surfaces().forEach(s->{if(levelShown(s.id()))roads.add(s.geometryId());});
        for(var box:arena.boxes()) {
            boolean road=roads.contains(box.id());
            if(!box.collision()||levelIndex>0&&!road)continue;
            float hx=box.size().x()/2,hz=box.size().z()/2;
            double angle=Math.toRadians(box.yawDegrees());List<TacticalMapModel.Point> corners=new ArrayList<>();
            for(float[] c:new float[][]{{-hx,-hz},{hx,-hz},{hx,hz},{-hx,hz}}) {
                float x=box.center().x()+(float)(Math.cos(angle)*c[0]+Math.sin(angle)*c[1]);
                float z=box.center().z()+(float)(-Math.sin(angle)*c[0]+Math.cos(angle)*c[1]);corners.add(model.project(x,z));
            }
            footprint(corners,road?new ColorRGBA(.13f,.17f,.18f,1):new ColorRGBA(.22f,.25f,.25f,1),positions,colors);
        }
        for(var surface:arena.meshes())if(surface.collision()&&roads.contains(surface.id())) {
            ColorRGBA color=surface.vertices().stream().anyMatch(v->v.y()<0)?new ColorRGBA(.22f,.19f,.15f,1):new ColorRGBA(.16f,.23f,.24f,1);
            for(int i=0;i<surface.indices().size();i+=3) {
                List<TacticalMapModel.Point> triangle=new ArrayList<>();
                for(int j=0;j<3;j++){var p=surface.vertices().get(surface.indices().get(i+j));triangle.add(model.project(p.x(),p.z()));}
                footprint(triangle,color,positions,colors);
            }
        }
        if(positions.isEmpty())return;
        Mesh mesh=new Mesh();mesh.setBuffer(VertexBuffer.Type.Position,3,array(positions));mesh.setBuffer(VertexBuffer.Type.Color,4,array(colors));mesh.updateBound();
        Material material=new Material(assets,"Common/MatDefs/Misc/Unshaded.j3md");material.setBoolean("VertexColor",true);
        Geometry geometry=new Geometry("map-authored-terrain",mesh);geometry.setLocalTranslation(0,0,1.5f);geometry.setMaterial(material);ui.root().attachChild(geometry);
    }
    private void footprint(List<TacticalMapModel.Point> polygon,ColorRGBA color,List<Float> positions,List<Float> colors) {
        var clipped=model.clipPolygon(polygon);
        for(int i=1;i+1<clipped.size();i++)for(var p:List.of(clipped.getFirst(),clipped.get(i),clipped.get(i+1))) {
            positions.add(p.x());positions.add(p.y());positions.add(0f);
            colors.add(color.r);colors.add(color.g);colors.add(color.b);colors.add(1f);
        }
    }
    private static float[] array(List<Float> data) {float[] result=new float[data.size()];for(int i=0;i<result.length;i++)result[i]=data.get(i);return result;}
    @Override public void close() {ui.clear();ui.root().removeFromParent();arena=null;session=null;world=null;pickups=List.of();}
}
