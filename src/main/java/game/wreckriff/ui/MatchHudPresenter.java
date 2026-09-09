package game.wreckriff.ui;

import game.wreckriff.combat.AbilityId;
import game.wreckriff.combat.CombatRules;
import game.wreckriff.combat.WeaponType;
import game.wreckriff.combat.SpecialRules;
import game.wreckriff.simulation.MatchSession;
import game.wreckriff.simulation.WorldQuery;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.UUID;

/** Converts authoritative match observations into UI values; it never advances game state. */
public final class MatchHudPresenter {
    public record Targeting(int homing,int napalm,int ballistic) {
        public int forWeapon(WeaponType weapon) {
            return switch(weapon){case HOMING->homing;case NAPALM->napalm;case BALLISTIC->ballistic;default->-1;};
        }
    }
    private UUID matchId;
    private RadarProjection.Heading heading;
    private WeaponType previousWeapon;
    private String previousFireBinding="",selectionHint="";
    private double hintUntil;
    private int previousSecond=-1,previousRivals=-1;
    private MatchSession.Phase previousPhase;
    private String objective="";

    public HudView.Snapshot snapshot(MatchSession session,WorldQuery world,Targeting targeting,
                                    String fireBinding,String notification,double presentationSeconds) {
        if(!session.sessionId.equals(matchId)) {
            matchId=session.sessionId;heading=null;previousWeapon=null;previousSecond=previousRivals=-1;previousPhase=null;
        }
        var player=session.vehicle(0);
        heading=RadarProjection.Heading.fromRotation(world.rotation(player.id),heading);
        var position=world.position(player.id);var road=world.roadContext(player.id);
        var observer=new RadarProjection.Observer(position.x,position.z,heading,road.known()?road.level():RadarProjection.UNKNOWN_LEVEL);
        int locked=targeting.forWeapon(player.selectedWeapon);
        var targets=new ArrayList<RadarProjection.Target>();
        for(var state:session.vehicles) {
            if(state.player||!state.alive())continue;
            var targetPosition=world.position(state.id);var targetRoad=world.roadContext(state.id);
            targets.add(new RadarProjection.Target(state.id,targetPosition.x,targetPosition.z,
                    targetRoad.known()?targetRoad.level():RadarProjection.UNKNOWN_LEVEL,true,state.boss,state.id==locked));
        }
        var weapons=new EnumMap<WeaponType,HudView.WeaponStatus>(WeaponType.class);
        for(WeaponType type:WeaponType.values()) {
            var slot=player.weapon(type);
            weapons.put(type,new HudView.WeaponStatus(slot.ammo,seconds(slot.cooldownTicks),weaponCooldown(session.combatRules,type)));
        }
        var abilities=new EnumMap<AbilityId,HudView.AbilityStatus>(AbilityId.class);
        abilities.put(AbilityId.FREEZE,new HudView.AbilityStatus(0,seconds(player.abilityCooldown(AbilityId.FREEZE)),session.combatRules.control().freezeCooldownSeconds()));
        abilities.put(AbilityId.SHIELD,new HudView.AbilityStatus(seconds(player.shieldTicks),seconds(player.abilityCooldown(AbilityId.SHIELD)),session.combatRules.control().shieldCooldownSeconds()));
        abilities.put(AbilityId.SPECIAL,new HudView.AbilityStatus(seconds(player.specialRemainingTicks()),seconds(player.abilityCooldown(AbilityId.SPECIAL)),SpecialRules.cooldownSeconds(player.profileId)));
        HudView.Effect effect=player.shieldTicks>0?HudView.Effect.SHIELDED:player.frozenTicks>0?HudView.Effect.FROZEN:
                player.controlImmunityTicks>0?HudView.Effect.IMMUNE:HudView.Effect.NONE;
        if(previousWeapon!=player.selectedWeapon||!previousFireBinding.equals(fireBinding)) {
            previousWeapon=player.selectedWeapon;previousFireBinding=fireBinding;
            selectionHint=weaponName(player.selectedWeapon)+" / "+fireBinding;hintUntil=presentationSeconds+1.8;
        }
        int rivals=targets.size(),second=(int)session.seconds();
        if(rivals!=previousRivals||second!=previousSecond||session.phase!=previousPhase) {
            objective=session.phase==MatchSession.Phase.BOSS_ENTRY?"БОСС НА АРЕНЕ":session.phase==MatchSession.Phase.INTRO?"ПОБЕДИТЕ СОПЕРНИКОВ И БОССА":rivals+" RIVALS";
            if(session.maximumTicks()!=Long.MAX_VALUE) {
                long remaining=Math.max(0,(session.maximumTicks()-session.tick)/MatchSession.TICKS_PER_SECOND);
                objective=String.format(java.util.Locale.ROOT,"%02d:%02d  /  %d RIVALS",remaining/60,remaining%60,rivals);
            }
            previousRivals=rivals;previousSecond=second;previousPhase=session.phase;
        }
        HudView.BossStatus boss=null;
        if(session.bossParticipantId>=0&&session.containsParticipant(session.bossParticipantId)) {
            var state=session.vehicle(session.bossParticipantId);
            boss=new HudView.BossStatus(state.name,state.hp,state.maximumHp,session.bossMode);
        }
        return new HudView.Snapshot(new HudView.Vitals(player.hp,player.maximumHp,player.turbo/100f,effect),player.selectedWeapon,
                weapons,abilities,objective,boss,locked>=0,presentationSeconds<hintUntil?selectionHint:"",notification,observer,targets);
    }
    public static String weaponName(WeaponType type) {
        return switch(type){case HOMING->"Homing";case POWER->"Power";case MINE->"Mine";case NAPALM->"Napalm";case BALLISTIC->"Ballistic";case CANNON->"Cannon";};
    }
    private static float seconds(int ticks) {return ticks/(float)MatchSession.TICKS_PER_SECOND;}
    private static float weaponCooldown(CombatRules rules,WeaponType type) {
        return switch(type){case HOMING->rules.homing().cooldownSeconds();case POWER->rules.power().cooldownSeconds();
            case MINE->rules.mine().cooldownSeconds();case NAPALM->rules.napalm().cooldownSeconds();
            case BALLISTIC->rules.ballistic().cooldownSeconds();case CANNON->rules.cannon().cooldownSeconds();};
    }
}
