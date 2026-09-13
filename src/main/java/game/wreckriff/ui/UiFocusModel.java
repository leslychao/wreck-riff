package game.wreckriff.ui;

import java.util.List;
import java.util.HashSet;

/** Focus is an element identity; rebuilding a screen cannot silently change the selected action. */
public final class UiFocusModel {
    public enum Direction {UP,DOWN,LEFT,RIGHT}
    public record Item(String id,UiBounds bounds,boolean enabled) {
        public Item {if(id==null||id.isBlank()||bounds==null)throw new IllegalArgumentException("Invalid focus item");}
    }
    private List<Item> items=List.of();
    private String selected;
    public String selectedId() {return selected;}
    public int selectedIndex() {for(int i=0;i<items.size();i++)if(items.get(i).id.equals(selected))return i;return -1;}
    public void replace(List<Item> replacement) {
        var ids=new HashSet<String>();
        for(Item item:replacement)if(!ids.add(item.id))throw new IllegalArgumentException("Duplicate UI focus id: "+item.id);
        items=List.copyOf(replacement);
        if(items.stream().noneMatch(item->item.enabled && item.id.equals(selected)))
            selected=items.stream().filter(Item::enabled).map(Item::id).findFirst().orElse(null);
    }
    public boolean select(String id) {
        if(items.stream().noneMatch(item->item.enabled && item.id.equals(id)))return false;
        selected=id;return true;
    }
    public void clearSelection() {selected=null;}
    public void cycle(int delta) {
        if(items.isEmpty())return;
        int index=selectedIndex();
        for(int i=0;i<items.size();i++) {
            index=Math.floorMod(index+Integer.signum(delta),items.size());
            if(items.get(index).enabled) {selected=items.get(index).id;return;}
        }
    }
    public void move(Direction direction) {
        int currentIndex=selectedIndex();if(currentIndex<0)return;
        UiBounds current=items.get(currentIndex).bounds;
        Item best=null;double bestScore=Double.POSITIVE_INFINITY;
        for(Item item:items) {
            if(!item.enabled||item.id.equals(selected))continue;
            float dx=item.bounds.centerX()-current.centerX(),dy=item.bounds.centerY()-current.centerY();
            float along=switch(direction){case RIGHT->dx;case LEFT->-dx;case UP->dy;case DOWN->-dy;};
            if(along<=.01f)continue;
            float edgeGap=switch(direction){
                case RIGHT->item.bounds.x()-current.right();case LEFT->current.x()-item.bounds.right();
                case UP->item.bounds.y()-current.top();case DOWN->current.y()-item.bounds.top();
            };
            if(edgeGap<-.01f)continue;
            // Wide rows align with every tab above them even when their centers are far apart.
            float across=(direction==Direction.UP||direction==Direction.DOWN)
                    ?Math.max(0,Math.max(current.x()-item.bounds.right(),item.bounds.x()-current.right()))
                    :Math.max(0,Math.max(current.y()-item.bounds.top(),item.bounds.y()-current.top()));
            double score=along+across*2;
            if(score<bestScore) {best=item;bestScore=score;}
        }
        if(best!=null)selected=best.id;
    }
}
