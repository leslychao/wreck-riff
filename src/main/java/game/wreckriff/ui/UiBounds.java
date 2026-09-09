package game.wreckriff.ui;

/** Pixel or logical bounds, with the same bottom-left origin as guiNode. */
public record UiBounds(float x,float y,float width,float height) {
    public UiBounds {
        if(!Float.isFinite(x)||!Float.isFinite(y)||!Float.isFinite(width)||!Float.isFinite(height)||width<0||height<0)
            throw new IllegalArgumentException("Invalid UI bounds");
    }
    public float right() {return x+width;}
    public float top() {return y+height;}
    public float centerX() {return x+width/2;}
    public float centerY() {return y+height/2;}
    public float area() {return width*height;}
    public boolean contains(float px,float py) {return px>=x && px<=right() && py>=y && py<=top();}
    public boolean overlaps(UiBounds other) {return x<other.right() && right()>other.x && y<other.top() && top()>other.y;}
}
