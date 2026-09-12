package game.wreckriff.presentation;

import com.jme3.math.Vector3f;
import com.jme3.scene.Node;
import game.wreckriff.ai.BotController;
import game.wreckriff.ai.BotController.BossActionView;
import game.wreckriff.simulation.MatchSession;
import game.wreckriff.simulation.WorldQuery;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** One session's current boss warning. AI owns the action and every simulation tick. */
public final class BossActionPresentation implements AutoCloseable {
    public record Signal(int subjectId,long beganTick,Vector3f position,boolean active) {
        public Signal { position=Objects.requireNonNull(position).clone(); }
        @Override public Vector3f position() {return position.clone();}
    }
    private final MatchSession session;
    private final Supplier<Optional<BossActionView>> action;
    private final WorldQuery world;
    private final Consumer<Signal> signals;
    private int seenSubject=-1;
    private long seenBeganTick=-1;
    private Signal sounding;
    private Node model;
    private boolean glow=true,closed;

    public BossActionPresentation(MatchSession session,BotController bots,WorldQuery world,Consumer<Signal> signals) {
        this(session,source(session,bots),world,signals);
    }
    BossActionPresentation(MatchSession session,Supplier<Optional<BossActionView>> action,WorldQuery world,Consumer<Signal> signals) {
        this.session=Objects.requireNonNull(session);this.action=Objects.requireNonNull(action);
        this.world=Objects.requireNonNull(world);this.signals=Objects.requireNonNull(signals);
        // A restored current action remains visible; opening its scene is not another preparation event.
        var current=current();
        if(current!=null) {seenSubject=session.bossParticipantId;seenBeganTick=current.beganTick();}
    }
    private static Supplier<Optional<BossActionView>> source(MatchSession session,BotController bots) {
        Objects.requireNonNull(session);Objects.requireNonNull(bots);
        return ()->bots.bossAction(session.bossParticipantId);
    }
    public void setGlow(boolean enabled) {glow=enabled;}

    /** Call after the existing damage and boss-mode updates, including on paused render frames. */
    public void update(Map<Integer,Node> models) {
        if(closed)return;
        var current=current();int subject=session.bossParticipantId;
        if(sounding!=null&&(current==null||sounding.subjectId()!=subject||sounding.beganTick()!=current.beganTick()))cancel();
        if(current!=null&&(seenSubject!=subject||current.beganTick()>seenBeganTick)) {
            seenSubject=subject;seenBeganTick=current.beganTick();
            sounding=new Signal(subject,current.beganTick(),world.position(subject),true);signals.accept(sounding);
        }
        Node next=models.get(subject);
        if(model!=null&&model!=next)VehicleVisual.clearBossTelegraph(model,glow);
        model=next;
        if(model!=null) {
            if(current==null)VehicleVisual.clearBossTelegraph(model,glow);
            else VehicleVisual.updateBossTelegraph(model,session.tick,current.beganTick(),current.untilTick(),glow);
        }
    }
    private BossActionView current() {
        int subject=session.bossParticipantId;
        if(session.phase!=MatchSession.Phase.BOSS_COMBAT||session.outcome!=MatchSession.Outcome.NONE
                ||!session.containsParticipant(subject)||!session.vehicle(subject).alive())return null;
        return action.get().filter(view->"TELEGRAPH".equals(view.phase())&&view.beganTick()>=0
                &&view.untilTick()>view.beganTick()&&session.tick>=view.beganTick()&&session.tick<view.untilTick()).orElse(null);
    }
    private void cancel() {
        if(sounding==null)return;
        var previous=sounding;sounding=null;
        signals.accept(new Signal(previous.subjectId(),previous.beganTick(),previous.position(),false));
    }
    @Override public void close() {
        if(closed)return;closed=true;cancel();
        if(model!=null)VehicleVisual.clearBossTelegraph(model,glow);
        model=null;
    }
}
