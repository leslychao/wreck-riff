package game.wreckriff.ui;

/** Top-origin content offset, retained independently of a screen's scene nodes. */
public final class UiScrollModel {
    private float contentHeight,viewportHeight,offset;
    public float offset() {return offset;}
    public float maximumOffset() {return Math.max(0,contentHeight-viewportHeight);}
    public void resize(float content,float viewport) {
        if(!Float.isFinite(content)||!Float.isFinite(viewport)||content<0||viewport<0)throw new IllegalArgumentException("Invalid scroll extent");
        contentHeight=content;viewportHeight=viewport;offset=Math.clamp(offset,0,maximumOffset());
    }
    public void scroll(float delta) {if(Float.isFinite(delta))offset=Math.clamp(offset+delta,0,maximumOffset());}
    public void ensureVisible(float itemTop,float itemHeight) {
        if(itemTop<offset)offset=itemTop;
        else if(itemTop+itemHeight>offset+viewportHeight)offset=itemTop+itemHeight-viewportHeight;
        offset=Math.clamp(offset,0,maximumOffset());
    }
}
