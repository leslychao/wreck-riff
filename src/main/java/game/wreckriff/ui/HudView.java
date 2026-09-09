package game.wreckriff.ui;

import com.jme3.asset.AssetManager;
import com.jme3.font.BitmapFont;
import com.jme3.font.BitmapText;
import com.jme3.font.LineWrapMode;
import com.jme3.font.Rectangle;
import com.jme3.material.Material;
import com.jme3.material.RenderState;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.scene.shape.Quad;
import game.wreckriff.combat.AbilityId;
import game.wreckriff.combat.WeaponType;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Read-only presentation of a match snapshot. No simulation objects, timers or input mutations live here. */
public final class HudView implements AutoCloseable {
    public enum Effect {NONE,FROZEN,SHIELDED,IMMUNE}
    public record Vitals(float hp,float maximumHp,float turboFraction,Effect effect) {
        public Vitals {positive(maximumHp);finite(hp);finite(turboFraction);Objects.requireNonNull(effect);}
    }
    public record WeaponStatus(int ammo,float cooldownSeconds,float cooldownDurationSeconds) {
        public WeaponStatus {if(ammo<0)throw new IllegalArgumentException("Negative ammunition");nonnegative(cooldownSeconds);nonnegative(cooldownDurationSeconds);}
    }
    public record AbilityStatus(float activeSeconds,float cooldownSeconds,float cooldownDurationSeconds) {
        public AbilityStatus {nonnegative(activeSeconds);nonnegative(cooldownSeconds);nonnegative(cooldownDurationSeconds);}
    }
    public record BossStatus(String name,float hp,float maximumHp,int phase) {
        public BossStatus {Objects.requireNonNull(name);finite(hp);positive(maximumHp);if(phase<1||phase>3)throw new IllegalArgumentException("Boss phase must be 1..3");}
    }
    public record Snapshot(Vitals vitals,WeaponType selectedWeapon,Map<WeaponType,WeaponStatus> weapons,
                           Map<AbilityId,AbilityStatus> abilities,String objective,BossStatus boss,boolean locked,
                           String selectionHint,String notification,RadarProjection.Observer observer,
                           List<RadarProjection.Target> radarTargets) {
        public Snapshot {
            Objects.requireNonNull(vitals);Objects.requireNonNull(selectedWeapon);Objects.requireNonNull(observer);
            weapons=Map.copyOf(weapons);abilities=Map.copyOf(abilities);radarTargets=List.copyOf(radarTargets);
            objective=Objects.requireNonNullElse(objective,"");selectionHint=Objects.requireNonNullElse(selectionHint,"");
            notification=Objects.requireNonNullElse(notification,"");
        }
    }
    public record HelpItem(String binding,String description) {
        public HelpItem {Objects.requireNonNull(binding);Objects.requireNonNull(description);}
    }
    private enum Paint {PANEL,SLOT,SELECTED,INK,ACCENT,MUTED,GREEN,BLUE,RED,COOLDOWN}
    private static final WeaponStatus EMPTY_WEAPON=new WeaponStatus(0,0,0);
    private static final AbilityStatus READY_ABILITY=new AbilityStatus(0,0,0);
    private final Node root=new Node("compact-hud");
    private final Node markers=new Node("radar-markers");
    private final Node help=new Node("hud-help");
    private final BitmapFont font;
    private final VectorIcons icons=new VectorIcons();
    private final EnumMap<Paint,Material> materials=new EnumMap<>(Paint.class);
    private final EnumMap<WeaponType,Slot> weaponSlots=new EnumMap<>(WeaponType.class);
    private final EnumMap<AbilityId,Slot> abilitySlots=new EnumMap<>(AbilityId.class);
    private final Map<String,RadarMarkerView> radarMarkers=new HashMap<>();
    private final Quad unitQuad=new Quad(1,1);
    private HudLayout layout;
    private Snapshot lastSnapshot;
    private Geometry healthBar,turboBar,bossBar,effectIcon,targetBracket,noticePanel;
    private TextValue healthValue,objectiveValue,bossValue,hintValue,noticeValue,receiptValue,diagnosticsValue,specialValue;
    private String pickupReceipt="";
    private String specialProfile="rivet",specialName="Отбойник",specialBinding="C";
    private List<HelpItem> helpItems=List.of();
    private Set<WeaponType> pickupHighlights=Set.of();
    private int previousHealth=-1,previousMaximumHealth=-1;

    public HudView(AssetManager assets,Node guiNode) {
        font=assets.loadFont("fonts/wreck.fnt");
        for(Paint paint:Paint.values()) {
            Material material=new Material(assets,"Common/MatDefs/Misc/Unshaded.j3md");
            material.setColor("Color",color(paint));material.getAdditionalRenderState().setBlendMode(RenderState.BlendMode.Alpha);
            material.getAdditionalRenderState().setFaceCullMode(RenderState.FaceCullMode.Off);materials.put(paint,material);
        }
        guiNode.attachChild(root);
    }
    public Node root() {return root;}
    public HudLayout layout() {return layout;}
    public int radarMarkerCount() {return radarMarkers.size();}
    public void setPickupReceipt(String text) {
        if(pickupReceipt.equals(text))return;pickupReceipt=text;
        if(receiptValue!=null)receiptValue.set(text);
        if(noticePanel!=null)show(noticePanel,!text.isBlank()||lastSnapshot!=null&&!lastSnapshot.notification.isBlank());
    }
    public void setPickupHighlights(Set<WeaponType> types) {
        if(pickupHighlights.equals(types))return;pickupHighlights=Set.copyOf(types);
        weaponSlots.forEach((type,slot)->slot.pickup(pickupHighlights.contains(type)));
    }
    public void setDiagnostics(String text) {if(diagnosticsValue!=null)diagnosticsValue.set(text);}
    public void setSpecial(String profileId,String name,String binding) {
        if(specialProfile.equals(profileId)&&specialName.equals(name)&&specialBinding.equals(binding))return;
        boolean changedProfile=!specialProfile.equals(profileId);
        specialProfile=profileId;specialName=name;specialBinding=binding;
        if(specialValue!=null)specialValue.set(name+"  ["+binding+"]");
        Slot slot=abilitySlots.get(AbilityId.SPECIAL);if(changedProfile&&slot!=null)slot.icon.setMesh(icons.mesh(VectorIcons.special(profileId)));
    }
    public void setHelp(List<HelpItem> items) {
        List<HelpItem> next=List.copyOf(items);if(next.equals(helpItems))return;helpItems=next;
        if(layout!=null)buildHelp();
    }
    public void setVisible(boolean visible) {root.setCullHint(visible?Spatial.CullHint.Inherit:Spatial.CullHint.Always);}
    public void resize(int width,int height,float uiScale) {
        HudLayout next=HudLayout.compute(width,height,uiScale);
        if(next.equals(layout))return;
        layout=next;build();if(lastSnapshot!=null)update(lastSnapshot);
    }
    public void update(Snapshot snapshot) {
        lastSnapshot=Objects.requireNonNull(snapshot);
        if(layout==null)return;
        Vitals vitals=snapshot.vitals;
        int hp=(int)Math.ceil(Math.max(0,vitals.hp)),maxHp=(int)Math.ceil(vitals.maximumHp);
        if(hp!=previousHealth||maxHp!=previousMaximumHealth) {
            healthValue.set(hp+" / "+maxHp);previousHealth=hp;previousMaximumHealth=maxHp;
        }
        healthBar.setLocalScale(Math.clamp(vitals.hp/vitals.maximumHp,0,1),1,1);
        turboBar.setLocalScale(Math.clamp(vitals.turboFraction,0,1),1,1);
        healthBar.setMaterial(materials.get(vitals.hp/vitals.maximumHp<=.25f?Paint.RED:Paint.ACCENT));
        updateEffect(vitals.effect);
        for(var entry:weaponSlots.entrySet()) {
            WeaponStatus status=snapshot.weapons.getOrDefault(entry.getKey(),EMPTY_WEAPON);
            entry.getValue().update(status.ammo,status.cooldownSeconds,status.cooldownDurationSeconds,entry.getKey()==snapshot.selectedWeapon,false);
        }
        for(var entry:abilitySlots.entrySet()) {
            AbilityStatus status=snapshot.abilities.getOrDefault(entry.getKey(),READY_ABILITY);
            entry.getValue().update(-1,status.cooldownSeconds,status.cooldownDurationSeconds,false,status.activeSeconds>0);
        }
        objectiveValue.set(snapshot.boss==null?snapshot.objective:"");
        show(bossBar,snapshot.boss!=null);
        if(snapshot.boss!=null) {
            bossValue.set(snapshot.boss.name+"  "+switch(snapshot.boss.phase){case 1->"I";case 2->"II";default->"III";});
            bossBar.setLocalScale(Math.clamp(snapshot.boss.hp/snapshot.boss.maximumHp,0,1),1,1);
        } else bossValue.set("");
        targetBracket.setMaterial(materials.get(snapshot.locked?Paint.ACCENT:Paint.MUTED));
        float reticleSize=(snapshot.locked?28:18)*layout.scale();
        targetBracket.setLocalScale(reticleSize,reticleSize,1);targetBracket.setLocalTranslation(-reticleSize/2,-reticleSize/2,0);
        hintValue.set(snapshot.selectionHint);noticeValue.set(snapshot.notification);
        show(noticePanel,!snapshot.notification.isBlank()||!pickupReceipt.isBlank());
        updateRadar(snapshot.observer,snapshot.radarTargets);
    }

    private void build() {
        root.detachAllChildren();markers.detachAllChildren();weaponSlots.clear();abilitySlots.clear();radarMarkers.clear();
        previousHealth=previousMaximumHealth=-1;
        float s=layout.scale(),pad=8*s;
        panel("health-panel",layout.health());panel("weapons-panel",layout.weapons());panel("abilities-panel",layout.abilities());
        UiBounds health=layout.health();
        icon(root,"health-icon",VectorIcons.Icon.HEALTH,health.x()+pad,health.top()-30*s,21*s,Paint.INK);
        healthValue=text(root,"health-value",new UiBounds(health.x()+34*s,health.top()-31*s,health.width()-82*s,25*s),layout.fontSize(),Paint.INK);
        icon(root,"machine-gun",VectorIcons.Icon.MACHINE_GUN,health.right()-30*s,health.top()-30*s,21*s,Paint.INK);
        healthBar=bar(root,"health-fill",new UiBounds(health.x()+pad,health.top()-42*s,health.width()-pad*2,7*s),Paint.ACCENT);
        icon(root,"turbo-icon",VectorIcons.Icon.TURBO,health.x()+pad,health.y()+9*s,19*s,Paint.BLUE);
        turboBar=bar(root,"turbo-fill",new UiBounds(health.x()+34*s,health.y()+16*s,health.width()-pad-34*s,6*s),Paint.BLUE);
        effectIcon=icon(root,"effect",VectorIcons.Icon.FREEZE,health.right()-27*s,health.top()+5*s,22*s,Paint.BLUE);show(effectIcon,false);
        int i=0;for(WeaponType type:WeaponType.values())weaponSlots.put(type,new Slot("weapon-"+type.id(),layout.weaponSlots().get(i++),VectorIcons.weapon(type)));
        weaponSlots.forEach((type,slot)->slot.pickup(pickupHighlights.contains(type)));
        abilitySlots.put(AbilityId.FREEZE,new Slot("ability-freeze",layout.abilitySlots().get(0),VectorIcons.Icon.FREEZE));
        abilitySlots.put(AbilityId.SHIELD,new Slot("ability-shield",layout.abilitySlots().get(1),VectorIcons.Icon.SHIELD));
        abilitySlots.put(AbilityId.SPECIAL,new Slot("ability-special",layout.abilitySlots().get(2),VectorIcons.special(specialProfile)));
        specialValue=text(root,"special-binding",new UiBounds(layout.abilities().x(),layout.abilities().top()+5*s,layout.abilities().width(),28*s),Math.max(14,14*s),Paint.ACCENT);
        specialValue.set(specialName+"  ["+specialBinding+"]");
        panel("objective-panel",layout.objective());
        objectiveValue=text(root,"objective",inset(layout.objective(),pad),layout.fontSize(),Paint.INK);
        bossValue=text(root,"boss-name",new UiBounds(layout.objective().x()+pad,layout.objective().y()+15*s,layout.objective().width()-pad*2,28*s),layout.fontSize(),Paint.INK);
        bossBar=bar(root,"boss-health",new UiBounds(layout.objective().x()+pad,layout.objective().y()+8*s,layout.objective().width()-pad*2,5*s),Paint.RED);show(bossBar,false);
        targetBracket=icon(root,"target-reticle",VectorIcons.Icon.DIAMOND,0,0,18*s,Paint.MUTED);
        // Center the normalized reticle mesh so changing its size keeps the aim point fixed.
        Node reticleCenter=new Node("reticle-center");root.attachChild(reticleCenter);reticleCenter.attachChild(targetBracket);
        reticleCenter.setLocalTranslation(layout.width()/2f,layout.height()/2f,4);
        targetBracket.setLocalTranslation(-9*s,-9*s,0);
        UiBounds notice=layout.notification();noticePanel=panel("notice-panel",notice);show(noticePanel,false);
        noticeValue=text(root,"notification",new UiBounds(notice.x()+pad,notice.y()+29*s,notice.width()-pad*2,notice.height()-29*s-pad),layout.fontSize(),Paint.ACCENT);
        receiptValue=text(root,"pickup-receipt",new UiBounds(notice.x()+pad,notice.y()+5*s,notice.width()-pad*2,24*s),Math.max(14,16*s),Paint.GREEN);
        receiptValue.set(pickupReceipt);
        hintValue=text(root,"selection-hint",new UiBounds(layout.weapons().x(),layout.weapons().top()+8*s,layout.weapons().width(),29*s),layout.fontSize(),Paint.INK);
        diagnosticsValue=text(root,"diagnostics",new UiBounds(16*s,layout.height()/2f,Math.max(280,layout.radar().x()-32*s),layout.height()/2f-20*s),Math.max(14,14*s),Paint.INK);
        buildRadar();
        buildHelp();
    }
    private void updateEffect(Effect effect) {
        show(effectIcon,effect!=Effect.NONE);
        effectIcon.setMesh(icons.mesh(effect==Effect.FROZEN?VectorIcons.Icon.FREEZE:VectorIcons.Icon.SHIELD));
        effectIcon.setMaterial(materials.get(effect==Effect.IMMUNE?Paint.GREEN:Paint.BLUE));
    }
    private void buildRadar() {
        UiBounds radar=layout.radar();panel("radar-panel",radar);
        float radius=radar.width()/2-15*layout.scale();
        Geometry ring=radarRing(radius);ring.setLocalTranslation(radar.centerX(),radar.centerY(),2);root.attachChild(ring);
        icon(root,"radar-driver",VectorIcons.Icon.CHEVRON,radar.centerX()-6*layout.scale(),radar.centerY()-6*layout.scale(),12*layout.scale(),Paint.INK);
        root.attachChild(markers);
    }
    private Geometry radarRing(float radius) {
        // Geometry is merged once, without per-frame primitive allocation.
        float[] positions=new float[48*18];int p=0;
        for(int i=0;i<48;i++) {
            double a=i*Math.PI/24,b=(i+1)*Math.PI/24;
            float x1=(float)Math.cos(a)*radius,y1=(float)Math.sin(a)*radius,x2=(float)Math.cos(b)*radius,y2=(float)Math.sin(b)*radius;
            float inner=1-1.2f*layout.scale()/radius;
            for(float v:new float[]{x1,y1,0,x1*inner,y1*inner,0,x2,y2,0,x2,y2,0,x1*inner,y1*inner,0,x2*inner,y2*inner,0})positions[p++]=v;
        }
        com.jme3.scene.Mesh mesh=new com.jme3.scene.Mesh();mesh.setBuffer(com.jme3.scene.VertexBuffer.Type.Position,3,positions);mesh.updateBound();mesh.setStatic();
        Geometry geometry=new Geometry("radar-ring",mesh);geometry.setMaterial(materials.get(Paint.MUTED));return geometry;
    }
    private void updateRadar(RadarProjection.Observer observer,List<RadarProjection.Target> targets) {
        List<RadarProjection.Marker> projected=RadarProjection.project(observer,targets);
        var present=new HashSet<String>();
        for(RadarProjection.Marker marker:projected) {
            present.add(marker.key());radarMarkers.computeIfAbsent(marker.key(),RadarMarkerView::new).update(marker);
        }
        radarMarkers.entrySet().removeIf(entry->{if(present.contains(entry.getKey()))return false;entry.getValue().node.removeFromParent();return true;});
    }
    private final class RadarMarkerView {
        private final Node node=new Node();
        private final Geometry shape,lock,floor,direction;
        private final TextValue count;
        private int previousCount=-1;
        RadarMarkerView(String key) {
            node.setName(key);markers.attachChild(node);float s=layout.scale();
            shape=icon(node,key+"-shape",VectorIcons.Icon.DIAMOND,-5*s,-5*s,10*s,Paint.RED);
            direction=icon(node,key+"-direction",VectorIcons.Icon.CHEVRON,-9*s,-9*s,18*s,Paint.ACCENT);
            lock=icon(node,key+"-lock",VectorIcons.Icon.DIAMOND,-8*s,-8*s,16*s,Paint.ACCENT);
            floor=icon(node,key+"-floor",VectorIcons.Icon.CHEVRON,-4*s,8*s,8*s,Paint.INK);
            count=text(node,key+"-count",new UiBounds(7*s,-10*s,35*s,22*s),Math.max(14,14*s),Paint.INK);
        }
        void update(RadarProjection.Marker marker) {
            float s=layout.scale(),radius=layout.radar().width()/2-17*s;
            node.setLocalTranslation(layout.radar().centerX()+marker.x()*radius,layout.radar().centerY()+marker.y()*radius,5);
            shape.setMesh(icons.mesh(marker.boss()?VectorIcons.Icon.CROWN:marker.far()?VectorIcons.Icon.CHEVRON:VectorIcons.Icon.DIAMOND));
            Quaternion angle=new Quaternion().fromAngleAxis(-(float)Math.atan2(marker.x(),marker.y()),Vector3f.UNIT_Z);
            Quaternion shapeAngle=marker.far()&&!marker.boss()?angle:Quaternion.IDENTITY;
            centerIcon(direction,18*s,angle);show(direction,marker.far()&&marker.boss());
            shape.setMaterial(materials.get(marker.boss()?Paint.ACCENT:Paint.RED));
            centerIcon(shape,(marker.boss()?14:10)*s,shapeAngle);
            show(lock,marker.locked());show(floor,marker.floor()==RadarProjection.Floor.ABOVE||marker.floor()==RadarProjection.Floor.BELOW);
            floor.setLocalScale(8*s,marker.floor()==RadarProjection.Floor.BELOW?-8*s:8*s,1);
            floor.setLocalTranslation(-4*s,marker.floor()==RadarProjection.Floor.BELOW?-8*s:8*s,1);
            if(previousCount!=marker.count()){count.set(marker.count()>1?Integer.toString(marker.count()):"");previousCount=marker.count();}
        }
    }
    private final class Slot {
        private final Geometry plate,selection,icon,cooldown,active,shade,pickup;
        private final float shadeWidth,shadeHeight;
        private final TextValue amount,time;
        private int previousAmmo=Integer.MIN_VALUE,previousTenths=-1;
        Slot(String id,UiBounds bounds,VectorIcons.Icon symbol) {
            float s=layout.scale();
            plate=quad(root,id+"-plate",bounds,Paint.SLOT,2);
            selection=quad(root,id+"-selected",new UiBounds(bounds.x(),bounds.top()-3*s,bounds.width(),3*s),Paint.ACCENT,3);show(selection,false);
            icon=icon(root,id+"-icon",symbol,bounds.x()+bounds.width()/2-15*s,bounds.top()-36*s,30*s,Paint.INK);
            shadeWidth=bounds.width()-6*s;shadeHeight=31*s;
            shade=quad(root,id+"-cooldown-shade",new UiBounds(bounds.x()+3*s,bounds.top()-36*s,shadeWidth,shadeHeight),Paint.COOLDOWN,4.5f);show(shade,false);
            amount=text(root,id+"-ammo",new UiBounds(bounds.x()+5*s,bounds.y()+3*s,bounds.width()/2,21*s),layout.fontSize(),Paint.INK);
            time=text(root,id+"-cooldown-text",new UiBounds(bounds.x()+bounds.width()/2-1*s,bounds.y()+3*s,bounds.width()/2,21*s),Math.max(14,14*s),Paint.INK);
            cooldown=bar(root,id+"-cooldown",new UiBounds(bounds.x()+3*s,bounds.y()+2*s,bounds.width()-6*s,3*s),Paint.BLUE);
            active=icon(root,id+"-active",VectorIcons.Icon.DIAMOND,bounds.right()-11*s,bounds.top()-11*s,8*s,Paint.GREEN);show(active,false);
            pickup=quad(root,id+"-pickup",new UiBounds(bounds.x(),bounds.y(),3*s,bounds.height()),Paint.GREEN,5);show(pickup,false);
        }
        void pickup(boolean visible) {show(pickup,visible);}
        void update(int ammo,float seconds,float duration,boolean selected,boolean activeNow) {
            plate.setMaterial(materials.get(selected?Paint.SELECTED:Paint.SLOT));show(selection,selected);show(active,activeNow);
            icon.setMaterial(materials.get(activeNow?Paint.GREEN:ammo==0||seconds>0?Paint.MUTED:Paint.INK));
            if(previousAmmo!=ammo){amount.set(ammo<0?"":Integer.toString(ammo));previousAmmo=ammo;}
            int tenths=(int)Math.ceil(seconds*10);
            if(tenths!=previousTenths){time.set(tenths==0?"":tenths>=100?Integer.toString((int)Math.ceil(seconds)):tenths/10+"."+tenths%10);previousTenths=tenths;}
            float fraction=duration>0?Math.clamp(seconds/duration,0,1):0;
            show(cooldown,seconds>0);cooldown.setLocalScale(fraction,1,1);
            show(shade,seconds>0&&!activeNow);shade.setLocalScale(shadeWidth,shadeHeight*fraction,1);
        }
    }
    private void buildHelp() {
        help.detachAllChildren();help.removeFromParent();if(helpItems.isEmpty())return;
        float s=layout.scale(),fontSize=Math.max(14,Math.min(18*s,(layout.height()-32-68*s)/(helpItems.size()*2.15f))),rowHeight=fontSize*2.15f;
        float width=Math.min(layout.width()-32,760*s),height=Math.min(layout.height()-32,helpItems.size()*rowHeight+68*s);
        UiBounds bounds=new UiBounds((layout.width()-width)/2,(layout.height()-height)/2,width,height);
        root.attachChild(help);help.setLocalTranslation(0,0,20);
        quad(help,"help-panel",bounds,Paint.PANEL,0);
        text(help,"help-title",new UiBounds(bounds.x()+16*s,bounds.top()-36*s,width-32*s,25*s),fontSize,Paint.ACCENT).set("CONTROLS   /   F1");
        float bindingWidth=Math.min(250*s,width*.42f),top=bounds.top()-52*s;
        for(int i=0;i<helpItems.size();i++) {
            HelpItem item=helpItems.get(i);float rowTop=top-i*rowHeight;
            text(help,"help-binding-"+i,new UiBounds(bounds.x()+16*s,rowTop-rowHeight,bindingWidth,rowHeight),fontSize,Paint.ACCENT).set(item.binding);
            text(help,"help-description-"+i,new UiBounds(bounds.x()+24*s+bindingWidth,rowTop-rowHeight,width-bindingWidth-40*s,rowHeight),fontSize,Paint.INK).set(item.description);
        }
    }
    private Geometry panel(String name,UiBounds bounds) {return quad(root,name,bounds,Paint.PANEL,0);}
    private Geometry quad(Node parent,String name,UiBounds bounds,Paint paint,float depth) {
        Geometry geometry=new Geometry(name,unitQuad);geometry.setMaterial(materials.get(paint));
        geometry.setLocalTranslation(bounds.x(),bounds.y(),depth);geometry.setLocalScale(bounds.width(),bounds.height(),1);parent.attachChild(geometry);return geometry;
    }
    private Geometry bar(Node parent,String name,UiBounds bounds,Paint paint) {
        quad(parent,name+"-track",bounds,Paint.SLOT,1);
        Node anchor=new Node(name+"-anchor");parent.attachChild(anchor);anchor.setLocalTranslation(bounds.x(),bounds.y(),3);anchor.setLocalScale(bounds.width(),bounds.height(),1);
        return quad(anchor,name,new UiBounds(0,0,1,1),paint,0);
    }
    private Geometry icon(Node parent,String name,VectorIcons.Icon symbol,float x,float y,float size,Paint paint) {
        Geometry geometry=new Geometry(name,icons.mesh(symbol));geometry.setMaterial(materials.get(paint));geometry.setLocalTranslation(x,y,4);geometry.setLocalScale(size,size,1);parent.attachChild(geometry);return geometry;
    }
    private TextValue text(Node parent,String name,UiBounds bounds,float size,Paint paint) {
        BitmapText text=new BitmapText(font);text.setName(name);text.setSize(Math.max(14,size));text.setColor(color(paint));
        text.setBox(new Rectangle(0,0,bounds.width(),Math.max(bounds.height(),size*1.25f)));text.setLineWrapMode(LineWrapMode.Word);
        text.setLocalTranslation(bounds.x(),bounds.top(),6);parent.attachChild(text);return new TextValue(text);
    }
    private static final class TextValue {
        private final BitmapText text;private String current;
        private TextValue(BitmapText text) {this.text=text;}
        private void set(String value) {if(!Objects.equals(current,value)){text.setText(value);current=value;}}
    }
    private static UiBounds inset(UiBounds bounds,float pad) {return new UiBounds(bounds.x()+pad,bounds.y()+pad,Math.max(0,bounds.width()-pad*2),Math.max(0,bounds.height()-pad*2));}
    private static void centerIcon(Geometry geometry,float size,Quaternion rotation) {
        Vector3f center=rotation.mult(new Vector3f(size/2,size/2,0));
        geometry.setLocalScale(size,size,1);geometry.setLocalRotation(rotation);geometry.setLocalTranslation(-center.x,-center.y,0);
    }
    private static void show(Spatial spatial,boolean visible) {spatial.setCullHint(visible?Spatial.CullHint.Inherit:Spatial.CullHint.Always);}
    private static ColorRGBA color(Paint paint) {
        return switch(paint) {
            case PANEL->new ColorRGBA(.035f,.045f,.055f,.88f);case SLOT->new ColorRGBA(.11f,.13f,.15f,.95f);
            case SELECTED->new ColorRGBA(.31f,.16f,.075f,.98f);case INK->GameUi.PAPER;
            case ACCENT->GameUi.ACCENT;case MUTED->new ColorRGBA(.46f,.48f,.49f,1);
            case GREEN->new ColorRGBA(.43f,.94f,.61f,1);case BLUE->new ColorRGBA(.35f,.76f,.98f,1);case RED->new ColorRGBA(.98f,.32f,.25f,1);
            case COOLDOWN->new ColorRGBA(.025f,.035f,.05f,.72f);
        };
    }
    private static void finite(float value) {if(!Float.isFinite(value))throw new IllegalArgumentException("Non-finite HUD value");}
    private static void nonnegative(float value) {finite(value);if(value<0)throw new IllegalArgumentException("Negative HUD timer");}
    private static void positive(float value) {finite(value);if(value<=0)throw new IllegalArgumentException("HUD maximum must be positive");}
    @Override public void close() {root.removeFromParent();root.detachAllChildren();markers.detachAllChildren();radarMarkers.clear();weaponSlots.clear();abilitySlots.clear();lastSnapshot=null;}
}
