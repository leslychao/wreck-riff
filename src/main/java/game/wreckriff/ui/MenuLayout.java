package game.wreckriff.ui;

/** Pixel-space menu regions. Content scrolls; title, tabs and primary exit actions remain reachable. */
public record MenuLayout(float scale,float fontSize,UiBounds panel,UiBounds header,UiBounds tabs,UiBounds content,UiBounds footer,UiBounds status) {
    public static MenuLayout compute(int width,int height,float requestedScale,boolean hasTabs) {
        if(width<320||height<240||!Float.isFinite(requestedScale))throw new IllegalArgumentException("Invalid menu viewport");
        float s=Math.clamp(height/1080f,.8f,1.5f)*Math.clamp(requestedScale,.8f,1.5f);
        s=Math.min(s,(height-24)/440f);
        float margin=Math.max(12,24*s),padding=Math.max(12,20*s),panelWidth=Math.min(width-margin*2,1120*s);
        UiBounds panel=new UiBounds((width-panelWidth)/2,margin,panelWidth,height-margin*2);
        float font=Math.max(14,20*s),headerHeight=Math.max(66,80*s),tabHeight=hasTabs?Math.max(34,40*s):0;
        float footerHeight=Math.max(40,48*s),statusHeight=Math.max(26,32*s);
        UiBounds header=new UiBounds(panel.x()+padding,panel.top()-headerHeight,panel.width()-padding*2,headerHeight);
        UiBounds tabs=new UiBounds(header.x(),header.y()-tabHeight,header.width(),tabHeight);
        UiBounds status=new UiBounds(header.x(),panel.y()+8*s,header.width(),statusHeight);
        UiBounds footer=new UiBounds(header.x(),status.top()+8*s,header.width(),footerHeight);
        UiBounds content=new UiBounds(header.x(),footer.top()+12*s,header.width(),Math.max(24,tabs.y()-footer.top()-20*s));
        return new MenuLayout(s,font,panel,header,tabs,content,footer,status);
    }
}
