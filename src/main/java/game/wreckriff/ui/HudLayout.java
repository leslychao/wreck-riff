package game.wreckriff.ui;

import java.util.ArrayList;
import java.util.List;

/** Anchor-based HUD measured in framebuffer pixels; small windows reflow the arsenal. */
public record HudLayout(int width,int height,float scale,float fontSize,int weaponColumns,
                        UiBounds health,UiBounds weapons,UiBounds abilities,UiBounds objective,UiBounds radar,
                        UiBounds notification,List<UiBounds> weaponSlots,List<UiBounds> abilitySlots) {
    public HudLayout {weaponSlots=List.copyOf(weaponSlots);abilitySlots=List.copyOf(abilitySlots);}
    public List<UiBounds> panels() {return List.of(health,weapons,abilities,objective,radar);}

    public static HudLayout compute(int width,int height,float requestedScale) {
        if(width<320||height<240||!Float.isFinite(requestedScale))throw new IllegalArgumentException("Invalid HUD viewport");
        float scale=Math.clamp(height/1080f,.82f,1.5f)*Math.clamp(requestedScale,.8f,1.5f);
        // Increasing UI size on a small screen must preserve access to every control.
        scale=Math.min(scale,Math.min((width-28)/560f,(height-28)/310f));
        float gap=6*scale,margin=Math.max(12,18*scale),pad=6*scale;
        float slotWidth=52*scale,slotHeight=60*scale,healthWidth=236*scale;
        float abilityWidth=2*slotWidth+gap+pad*2;
        int columns=healthWidth+abilityWidth+6*slotWidth+5*gap+pad*2+margin*4<=width?6:3;
        float weaponWidth=columns*slotWidth+(columns-1)*gap+pad*2;
        int rows=6/columns;
        UiBounds weapons=new UiBounds(width-margin-weaponWidth,margin,weaponWidth,rows*slotHeight+(rows-1)*gap+pad*2);
        UiBounds abilities=new UiBounds(weapons.x()-gap-abilityWidth,margin,abilityWidth,slotHeight+pad*2);
        UiBounds health=new UiBounds(margin,margin,healthWidth,84*scale);
        float radarSize=168*scale;
        UiBounds radar=new UiBounds(width-margin-radarSize,height-margin-radarSize,radarSize,radarSize);
        float objectiveWidth=Math.min(360*scale,radar.x()-margin*3);
        float objectiveX=Math.min((width-objectiveWidth)/2,radar.x()-margin-objectiveWidth);
        UiBounds objective=new UiBounds(Math.max(margin,objectiveX),height-margin-48*scale,objectiveWidth,48*scale);
        UiBounds notification=new UiBounds(margin,Math.max(health.top(),weapons.top())+margin,
                Math.min(620*scale,width-margin*2),48*scale);
        List<UiBounds> slots=new ArrayList<>();
        for(int i=0;i<6;i++)slots.add(new UiBounds(weapons.x()+pad+(i%columns)*(slotWidth+gap),
                weapons.y()+pad+(rows-1-i/columns)*(slotHeight+gap),slotWidth,slotHeight));
        List<UiBounds> abilitySlots=List.of(new UiBounds(abilities.x()+pad,abilities.y()+pad,slotWidth,slotHeight),
                new UiBounds(abilities.x()+pad+slotWidth+gap,abilities.y()+pad,slotWidth,slotHeight));
        return new HudLayout(width,height,scale,Math.max(14,18*scale),columns,health,weapons,abilities,objective,radar,notification,slots,abilitySlots);
    }
}
