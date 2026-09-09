package game.wreckriff.ui;

import java.util.Objects;

/** Debounces short disk writes and emits each asynchronous failure once without hiding unsaved state. */
public final class ProgressNotice {
    public record State(String announcement,String indicator,String detailToLog) { }
    private String warning="";
    private double pendingSince=-1;
    public State update(String latestWarning,boolean pending,boolean writable,double seconds) {
        Objects.requireNonNull(latestWarning);
        String announcement="",detail="";
        if(!latestWarning.equals(warning)) {
            if(!latestWarning.isBlank()) {
                detail=latestWarning;
                announcement=!writable?"Progress is read-only. Details in log.":pending?"Progress not saved. Details in log.":"Progress needs attention. Details in log.";
            } else if(!warning.isBlank()&&!pending)announcement="Progress saved.";
            warning=latestWarning;
        }
        if(pending&&pendingSince<0)pendingSince=seconds;
        if(!pending)pendingSince=-1;
        String indicator=!writable?"Progress is read-only":pending&&!warning.isBlank()?"Progress not saved":pending&&seconds-pendingSince>=.4?"Saving...":"";
        return new State(announcement,indicator,detail);
    }
}
