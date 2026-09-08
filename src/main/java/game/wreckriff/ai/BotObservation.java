package game.wreckriff.ai;

import com.jme3.math.Vector3f;
import java.util.List;

/** Information available to a driver: hidden enemies expose only old observations. */
public record BotObservation(long tick, List<Opponent> visible, List<Opponent> remembered) {
    public record Opponent(int id, Vector3f position, Vector3f velocity, long observedTick) {
        public Opponent { position=position.clone(); velocity=velocity.clone(); }
    }
    public BotObservation { visible=List.copyOf(visible); remembered=List.copyOf(remembered); }
    public Opponent visible(int id) { return visible.stream().filter(e->e.id==id).findFirst().orElse(null); }
    public Opponent known(int id) {
        Opponent current=visible(id);
        return current!=null?current:remembered.stream().filter(e->e.id==id).findFirst().orElse(null);
    }
}
