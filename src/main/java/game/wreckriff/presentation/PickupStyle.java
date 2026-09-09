package game.wreckriff.presentation;

import com.jme3.math.ColorRGBA;
import game.wreckriff.arena.ArenaDefinition.PickupType;
import game.wreckriff.combat.WeaponType;
import game.wreckriff.ui.VectorIcons.Icon;
import java.util.Locale;

/** Shared identity for the authored model, road symbol and receipt. It never grants resources. */
public enum PickupStyle {
    HOMING(PickupType.HOMING_AMMO,"homing-ammo",Icon.HOMING,0xffbd55,"Homing",WeaponType.HOMING),
    POWER(PickupType.POWER_AMMO,"power-ammo",Icon.POWER,0xff654e,"Power",WeaponType.POWER),
    MINE(PickupType.MINE_AMMO,"mine-ammo",Icon.MINE,0xd99c68,"Mine",WeaponType.MINE),
    NAPALM(PickupType.NAPALM_AMMO,"napalm-ammo",Icon.NAPALM,0xff992f,"Napalm",WeaponType.NAPALM),
    BALLISTIC(PickupType.BALLISTIC_AMMO,"ballistic-ammo",Icon.BALLISTIC,0x70a8ff,"Ballistic",WeaponType.BALLISTIC),
    CANNON(PickupType.CANNON_AMMO,"cannon-ammo",Icon.CANNON,0xe0e8f4,"Cannon",WeaponType.CANNON),
    REPAIR(PickupType.REPAIR,"repair",Icon.HEALTH,0x7ad58a,"Repair",null),
    TURBO(PickupType.TURBO_CELL,"turbo",Icon.TURBO,0x5be1df,"Turbo",null);

    private final PickupType type;
    private final String kind,name;
    private final Icon icon;
    private final int rgb;
    private final WeaponType weapon;
    PickupStyle(PickupType type,String kind,Icon icon,int rgb,String name,WeaponType weapon) {
        this.type=type;this.kind=kind;this.icon=icon;this.rgb=rgb;this.name=name;this.weapon=weapon;
    }
    public PickupType type(){return type;}
    public String kind(){return kind;}
    public Icon icon(){return icon;}
    public WeaponType weapon(){return weapon;}
    public String model(){return "models/pickups/"+name().toLowerCase(Locale.ROOT)+".j3o";}
    public ColorRGBA color(){return new ColorRGBA((rgb>>16&255)/255f,(rgb>>8&255)/255f,(rgb&255)/255f,1);}
    public String receipt(float amount) {
        String quantity=Math.abs(amount-Math.round(amount))<.001f?Integer.toString(Math.round(amount)):
                String.format(Locale.ROOT,"%.1f",amount);
        return name+" +"+quantity+(this==REPAIR?" HP":this==TURBO?"%":"");
    }
    public static PickupStyle of(PickupType type) {
        for(var style:values())if(style.type==type)return style;
        throw new IllegalArgumentException("Unknown pickup type: "+type);
    }
    public static PickupStyle ofKind(String kind) {
        for(var style:values())if(style.kind.equals(kind))return style;
        throw new IllegalArgumentException("Unknown pickup kind: "+kind);
    }
}
