package game.wreckriff.diagnostics;

import game.wreckriff.combat.AbilityId;
import game.wreckriff.combat.WeaponType;
import game.wreckriff.config.ProgressStore;
import game.wreckriff.config.VehicleDefinition;
import game.wreckriff.ui.HudView;
import game.wreckriff.ui.RadarProjection;
import java.util.*;

/** Render-only inputs. They never enter ProgressStore, MatchRuntime or a combat transaction. */
public final class UiReviewFixtures {
    private UiReviewFixtures() {}
    public static ProgressStore.Snapshot fresh() {
        String first=ProgressStore.CAMPAIGN_ARENAS.getFirst();
        return new ProgressStore.Snapshot(ProgressStore.SCHEMA_VERSION,0,0,new ProgressStore.Stats(0,0,0,0,0,0),
                new ProgressStore.Campaign(first,Set.of(first),Set.of(),null),null,Map.of());
    }
    public static HudView.Snapshot hud(String profileId,int variant) {
        if(variant<0||variant>=6)throw new IllegalArgumentException("Unknown HUD fixture");
        var profile=VehicleDefinition.forId(profileId);var weapons=new EnumMap<WeaponType,HudView.WeaponStatus>(WeaponType.class);
        for(var weapon:WeaponType.values())weapons.put(weapon,new HudView.WeaponStatus(variant==5?0:weapon.ordinal()==variant?0:12+weapon.ordinal(),variant==2?2:0,4));
        var abilities=new EnumMap<AbilityId,HudView.AbilityStatus>(AbilityId.class);
        for(var ability:List.of(AbilityId.FREEZE,AbilityId.SHIELD,AbilityId.SPECIAL))abilities.put(ability,
                new HudView.AbilityStatus(variant%3==1?3:0,variant%3==2?8:0,12));
        var observer=new RadarProjection.Observer(0,0,new RadarProjection.Heading(0,1),1);
        var radar=List.of(new RadarProjection.Target(1,-15,25,1,true,false,false),
                new RadarProjection.Target(2,20,12,2,true,false,true),new RadarProjection.Target(3,10,-20,0,true,false,false),
                new RadarProjection.Target(4,-22,-12,RadarProjection.UNKNOWN_LEVEL,true,false,false),
                new RadarProjection.Target(5,100,100,1,true,true,false),new RadarProjection.Target(6,104,104,1,true,false,false),
                new RadarProjection.Target(7,5,5,1,false,false,false));
        return new HudView.Snapshot(new HudView.Vitals(profile.maximumHp()*(variant==5?.08f:.73f),profile.maximumHp(),.42f,
                variant%3==1?HudView.Effect.SHIELDED:variant%3==2?HudView.Effect.FROZEN:HudView.Effect.NONE),
                WeaponType.values()[variant],weapons,abilities,"Уничтожьте противников: 4 / 6",
                variant>=3?new HudView.BossStatus("Хранитель последнего перекрёстка",725,1800,1+variant%3):null,
                true,"RMB",variant%2==0?"Контрольная точка сохранена. Босс уже приближается к арене.":"",observer,radar);
    }
}
