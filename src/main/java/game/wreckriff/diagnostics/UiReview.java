package game.wreckriff.diagnostics;

import game.wreckriff.config.Configs;
import game.wreckriff.config.ProgressStore;
import game.wreckriff.config.SettingsStore;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.*;

/** Bounded driver of the existing application UI; capture completion is never owner acceptance. */
public final class UiReview {
    public static final int MAXIMUM_SECONDS=240,MAXIMUM_CASES=100;
    public enum Kind {ACTIVATE,FOCUS,DISPATCH,FIXTURE}
    public record Command(Kind kind,String value) { }
    public record Case(String id,String expectedPage,String expectedFocus,String fixture,List<Command> commands,String pendingReason,double settleSeconds) {
        public Case {commands=List.copyOf(commands);if(!Double.isFinite(settleSeconds)||settleSeconds<0||settleSeconds>12)throw new IllegalArgumentException("Invalid settle time");}
        public Case(String id,String expectedPage,String expectedFocus,String fixture,List<Command> commands,String pendingReason) {
            this(id,expectedPage,expectedFocus,fixture,commands,pendingReason,0);
        }
    }
    public record Observation(String screen,String pageId,String focus,float scroll,int width,int height,float scale,boolean visible,Map<String,Object> state) {
        public Observation {state=Map.copyOf(state);}
        public Observation(String screen,String pageId,String focus,float scroll,int width,int height,float scale,boolean visible) {
            this(screen,pageId,focus,scroll,width,height,scale,visible,Map.of());
        }
    }
    public interface Host {
        void command(Command command);
        Observation observe();
        String capture(String caseId);
    }
    public record Evidence(String caseId,String status,String reason,String fixture,List<Command> commands,
                           Observation observed,long settledDrawFrames,String capturePrefix,String captureFile) { }
    private final List<Case> cases;
    private final List<Evidence> evidence=new ArrayList<>();
    private final Path directory;
    private final int width,height;
    private final float scale;
    private int cursor;
    private long drawnFrames,startedFrame,capturedFrame;
    private double startedAt,caseStartedAt;
    private boolean started,caseStarted,complete,failed;
    private String capturePrefix;
    private Observation capturedObservation;
    public UiReview(Path directory,int width,int height,float scale) {this(directory,width,height,scale,catalogue());}
    UiReview(Path directory,int width,int height,float scale,List<Case> cases) {
        if(width<640||height<480||!Float.isFinite(scale)||scale<.8f||scale>1.5f||cases.isEmpty()||cases.size()>MAXIMUM_CASES)
            throw new IllegalArgumentException("Invalid UI review bounds");
        this.directory=directory;this.width=width;this.height=height;this.scale=scale;this.cases=List.copyOf(cases);
        if(cases.stream().map(Case::id).distinct().count()!=cases.size())throw new IllegalArgumentException("Duplicate UI review case");
    }
    /** Called after a rendered frame is observed at the next update. */
    public void drawnFrame() {drawnFrames++;}
    public boolean complete() {return complete;}
    public boolean failed() {return failed;}
    public List<Evidence> evidence() {return List.copyOf(evidence);}
    public void advance(Host host,double elapsed) throws IOException {
        if(complete)return;
        if(!started){started=true;startedAt=elapsed;write();}
        if(elapsed-startedAt>MAXIMUM_SECONDS){fail(host,"UI review exceeded 240 seconds");return;}
        Case next=cases.get(cursor);
        try {
            if(!caseStarted) {
                if(!next.pendingReason.isBlank()) {evidence.add(new Evidence(next.id,"PENDING",next.pendingReason,next.fixture,next.commands,host.observe(),0,"",""));next();return;}
                for(var command:next.commands)host.command(command);
                caseStarted=true;startedFrame=drawnFrames;caseStartedAt=elapsed;return;
            }
            if(elapsed-caseStartedAt>20){fail(host,"Case exceeded 20 seconds while waiting for rendering/capture");return;}
            if(capturePrefix!=null) {
                if(drawnFrames<=capturedFrame)return;
                Path capture=findCapture(capturePrefix);
                if(capture==null)return;
                verifyPng(capture,width,height);
                evidence.add(new Evidence(next.id,"CAPTURED","Human visual review pending",next.fixture,next.commands,capturedObservation,
                        capturedFrame-startedFrame,capturePrefix,directory.relativize(capture).toString().replace('\\','/')));
                next();return;
            }
            if(drawnFrames-startedFrame<2||elapsed-caseStartedAt<next.settleSeconds)return;
            var observed=host.observe();
            if(!observed.visible||observed.width!=width||observed.height!=height||Math.abs(observed.scale-scale)>.001f) {
                fail(host,"Requested framebuffer/scale unavailable: "+width+"x"+height+" @ "+scale+"; actual "+observed);return;
            }
            // Actual vehicle selection starts the staged loader. Wait for its real completion,
            // still bounded by the same 20-second case timeout; errors are never swallowed.
            if(next.expectedPage.startsWith("running:")&&observed.screen.equals("LOADING"))return;
            if(!observed.pageId.startsWith(next.expectedPage)||!next.expectedFocus.isBlank()&&!Objects.equals(next.expectedFocus,observed.focus)) {
                fail(host,"Unexpected page/focus, expected "+next.expectedPage+" / "+next.expectedFocus+"; actual "+observed);return;
            }
            verifyMatchTransition(next,observed);
            capturedObservation=observed;capturedFrame=drawnFrames;capturePrefix=host.capture(next.id);
            if(capturePrefix==null||!capturePrefix.matches("[A-Za-z0-9_.-]+"))throw new IllegalStateException("Invalid capture prefix");
        } catch(RuntimeException|IOException failure) {fail(host,failure.getClass().getSimpleName()+": "+failure.getMessage());}
    }
    private void verifyMatchTransition(Case next,Observation observed) {
        if(next.id.equals("actual-campaign-pause")&&(!(observed.state.get("simulationTick") instanceof Number tick)||tick.longValue()<=0))
            throw new IllegalStateException("Map review must pause an advancing combat simulation, after the countdown");
        if(next.id.startsWith("actual-map-")&&!evidence.isEmpty()) {
            var before=evidence.getLast().observed.state;
            if(!Objects.equals(before.get("sessionId"),observed.state.get("sessionId"))||!Objects.equals(before.get("simulationTick"),observed.state.get("simulationTick")))
                throw new IllegalStateException("Tactical map advanced or reconstructed the paused simulation");
        }
        if(next.id.startsWith("vehicles-")) {
            String preview=next.id.substring("vehicles-".length());
            if(!preview.equals(observed.state.get("previewVehicle"))||!"rivet".equals(observed.state.get("savedVehicle")))
                throw new IllegalStateException("Carousel browsing changed the saved chassis or skipped a preview");
        }
        if(!next.expectedPage.startsWith("running:"))return;
        String profile=next.expectedPage.substring("running:".length());
        if(!profile.equals(observed.state.get("activeVehicle"))||!profile.equals(observed.state.get("savedVehicle")))
            throw new IllegalStateException("Displayed, saved and active chassis must agree");
        if(next.id.startsWith("actual-repeat-accept-")||next.id.startsWith("actual-retry-")) {
            Object before=evidence.getLast().observed.state.get("sessionId"),after=observed.state.get("sessionId");
            boolean repeat=next.id.startsWith("actual-repeat-accept-");
            if(before==null||after==null||Objects.equals(before,after)!=repeat)
                throw new IllegalStateException(repeat?"Repeated accept started another match":"Retry did not start a new match");
        }
    }
    private void next() throws IOException {cursor++;caseStarted=false;capturePrefix=null;complete=cursor==cases.size();write();}
    private void fail(Host host,String reason) throws IOException {
        failed=true;complete=true;Case next=cases.get(Math.min(cursor,cases.size()-1));
        evidence.add(new Evidence(next.id,"FAIL",reason,next.fixture,next.commands,host.observe(),drawnFrames-startedFrame,
                Objects.requireNonNullElse(capturePrefix,""),""));write();
    }
    private Path findCapture(String prefix) throws IOException {
        Path captures=directory.resolve("captures");if(!Files.isDirectory(captures))return null;
        try(var files=Files.list(captures)) {
            var matches=files.limit(MAXIMUM_CASES+1L).filter(path->path.getFileName().toString().startsWith(prefix)&&path.toString().endsWith(".png")).toList();
            if(matches.size()>1)throw new IOException("Ambiguous capture prefix");return matches.isEmpty()?null:matches.getFirst();
        }
    }
    static void verifyPng(Path path,int width,int height) throws IOException {
        if(Files.size(path)<33||Files.size(path)>128L*1024*1024)throw new IOException("Invalid capture size");
        byte[] header;try(var in=Files.newInputStream(path)){header=in.readNBytes(24);}
        if(header.length!=24||ByteBuffer.wrap(header).getLong()!=0x89504e470d0a1a0aL||ByteBuffer.wrap(header,12,4).getInt()!=0x49484452
                ||ByteBuffer.wrap(header,16,4).getInt()!=width||ByteBuffer.wrap(header,20,4).getInt()!=height)throw new IOException("Capture PNG dimensions differ from framebuffer request");
    }
    private void write() throws IOException {
        var data=new LinkedHashMap<String,Object>();data.put("schemaVersion",1);data.put("mode","ui-review");
        data.put("status",failed?"FAIL":complete?"CAPTURES_COMPLETE_HUMAN_REVIEW_PENDING":"RUNNING");
        data.put("requestedFramebuffer",Map.of("width",width,"height",height,"uiScale",scale));
        data.put("ownerAcceptance","NOT_GRANTED");data.put("releaseEligible",false);
        data.put("fixturePolicy","Render-only snapshots plus native matches in an isolated profile; no synthetic outcomes or hardware-input claims");
        data.put("plannedCases",cases.size());data.put("cases",List.copyOf(evidence));
        data.put("remainingCaseIds",cases.subList(Math.min(cursor+(failed?1:0),cases.size()),cases.size()).stream().map(Case::id).toList());
        Files.createDirectories(directory);Files.writeString(directory.resolve("ui-review-manifest.json"),Configs.gson().toJson(data));
    }
    private static Command activate(String id){return new Command(Kind.ACTIVATE,id);}
    private static Command focus(String id){return new Command(Kind.FOCUS,id);}
    private static Command dispatch(String id){return new Command(Kind.DISPATCH,id);}
    private static Command fixture(String id){return new Command(Kind.FIXTURE,id);}
    public static List<Case> catalogue() {
        var result=new ArrayList<Case>();
        class Add {void page(String id,String page,String label,Command... commands){result.add(new Case(id,page,"",label,List.of(commands),""));}
            void focused(String id,String page,String selected,String label,Command... commands){result.add(new Case(id,page,selected,label,List.of(commands),""));}}
        var add=new Add();
        add.page("fresh-main","main","fresh zero-statistics profile",fixture("fresh"));
        add.page("fresh-maps","maps","fresh locked campaign",activate("maps"),focus("arena:"+ProgressStore.LEGACY_ARENA));
        for(String arena:ProgressStore.CAMPAIGN_ARENAS)add.page("fresh-maps-"+arena,"maps","fresh locked campaign; each card reachable",focus("arena:"+arena));
        add.page("fresh-statistics","statistics","fresh zero statistics",activate("back"),activate("stats"));
        add.page("fresh-statistics-bottom","statistics","fresh zero statistics",focus("record:"+ProgressStore.CAMPAIGN_ARENAS.getLast()));
        result.add(new Case("vehicles-rivet","vehicles","","actual carousel preview; saved profile unchanged",List.of(activate("back"),activate("new")),"",.4));
        result.add(new Case("vehicles-grinder","vehicles","","actual carousel browse; saved profile unchanged",List.of(dispatch("right")),"",.4));
        result.add(new Case("vehicles-spark","vehicles","","actual carousel browse; saved profile unchanged",List.of(dispatch("right")),"",.4));
        add.page("settings-video","settings:0","isolated saved settings",activate("back"),activate("settings"));
        for(int tab=1;tab<4;tab++)add.page("settings-tab-"+tab,"settings:"+tab,"actual settings tab action",activate("settings-tab:"+tab));
        add.page("controls-keyboard","controls:false","actual controls action",activate("bindings"));
        add.page("controls-keyboard-bottom","controls:false","keyboard navigation and retained scroll",focus("binding:"+SettingsStore.defaultKeys().lastEntry().getKey()));
        add.page("controls-gamepad","controls:true","mapped labels only; hardware input unverified",activate("gamepad"));
        add.page("controls-gamepad-bottom","controls:true","mapped labels only; hardware input unverified",focus("pad:Pause"));
        add.focused("video-keep-confirm","video-confirm","revert","actual VSync change and renderer restart",activate("back"),activate("settings-tab:0"),activate("vsync"));
        add.page("video-kept","settings:0","actual keep action",activate("keep"));
        add.focused("video-revert-confirm","video-confirm","revert","actual VSync change and renderer restart",activate("vsync"));
        add.page("video-reverted","settings:0","actual rollback action",activate("revert"));
        result.add(new Case("video-timeout-rollback","settings:0","","actual ten-second video rollback deadline",List.of(activate("vsync")),"",11));
        add.focused("confirm-first","confirm:","cancel","actual reset confirmation; no reset accepted",activate("reset"));
        add.focused("confirm-danger-focused","confirm:","accept","actual focus navigation",focus("accept"));
        add.focused("confirm-reopened","confirm:","cancel","same dialog reopened with safe initial focus",activate("cancel"),activate("reset"));
        add.page("credits","credits","actual credits action",activate("cancel"),activate("back"),activate("credits"));
        add.page("credits-bottom","credits","actual retained scroll",focus("licenses"));
        add.page("unlocked-main","main","SoakProfile unlock render fixture; zero measured wins",activate("back"),fixture("unlocked"));
        add.focused("unlocked-new-confirm","confirm:","cancel","actual new-campaign confirmation on render fixture",activate("new"));
        add.page("unlocked-maps","maps","SoakProfile all-arena/duel render fixture",activate("cancel"),activate("maps"),focus("arena:"+ProgressStore.LEGACY_ARENA));
        for(String arena:ProgressStore.CAMPAIGN_ARENAS)add.page("unlocked-maps-"+arena,"maps","SoakProfile all-arena/duel render fixture; each card reachable",focus("arena:"+arena+":boss"));
        add.page("unlocked-statistics","statistics","SoakProfile unlock fixture; zero measured results",activate("back"),activate("stats"),focus("outcomes"));
        add.page("unlocked-statistics-bottom","statistics","SoakProfile unlock fixture; zero measured results",focus("record:"+ProgressStore.CAMPAIGN_ARENAS.getLast()));
        add.page("loading","loading","render-only initial loading state",fixture("loading"));
        add.page("error","error","render-only recoverable error; no real failure injected",fixture("error"));
        add.page("error-return","main","actual error return-to-menu action",activate("menu"));
        for(String outcome:List.of("DEFEAT","DRAW","VICTORY"))add.page("results-"+outcome.toLowerCase(Locale.ROOT),"results:","render-only outcome; no combat outcome recorded",fixture("result:"+outcome));
        for(String profile:List.of("rivet","grinder","spark"))for(int variant=0;variant<6;variant++)
            add.page("hud-"+profile+"-"+variant,"hud","render-only HUD: six weapon selections, zero ammo, ability "+List.of("ready","active","cooldown").get(variant%3)+", radar floors/dead/far/boss, notifications",fixture("hud:"+profile+":"+variant));
        add.page("pause","pause","render-only session pause with visible reason",fixture("pause"));
        add.page("pause-settings","settings:0","actual settings overlay from paused render fixture",activate("settings"),activate("settings-tab:0"));
        add.page("pause-return","pause","actual back preserves pause reason",activate("back"));
        add.focused("pause-quit-confirm","confirm:","cancel","quit directly from pause; safe initial focus",activate("quit"));
        add.page("pause-quit-cancel","pause","cancel exit returns to the original pause",activate("cancel"));
        add.page("actual-launch-menu","main","real isolated progress; no synthetic result",fixture("actual-progress"));
        for(int index=0;index<3;index++) {
            String profile=List.of("rivet","grinder","spark").get(index);
            var commands=new ArrayList<Command>();commands.add(activate("maps"));commands.add(activate("arena:"+ProgressStore.LEGACY_ARENA));
            if(index>0)commands.add(dispatch("right"));commands.add(activate("vehicle:choose"));
            result.add(new Case("actual-start-"+profile,"running:"+profile,"","actual native match loading through vehicle selection; no combat outcome fixture",commands,"",.4));
            add.page("actual-repeat-accept-"+profile,"running:"+profile,"second accept must leave the same match active",dispatch("activate"));
            add.page("actual-retry-"+profile,"running:"+profile,"actual retry retains chassis",dispatch("pause"),activate("retry"),activate("accept"));
            add.page("actual-leave-"+profile,"main","actual leave closes native world",dispatch("pause"),activate("leave"),activate("accept"));
        }
        result.add(new Case("actual-campaign-start","running:spark","","actual new campaign after countdown in isolated profile",List.of(activate("new"),activate("vehicle:choose")),"",7));
        add.page("actual-campaign-pause","pause","pause the native campaign before map interaction",dispatch("pause"));
        add.focused("actual-campaign-quit-confirm","confirm:","cancel","native campaign exit describes the last checkpoint",activate("quit"));
        add.page("actual-campaign-quit-cancel","pause","cancel exit retains the native match",dispatch("back"));
        add.page("actual-map-open","tactical-map","actual authored roads, pickups and all living vehicles",activate("tactical-map"));
        add.page("actual-map-zoom-pan","tactical-map","zoom and pan while simulation remains paused",activate("map:zoom-in"),activate("map:east"),activate("map:north"));
        add.page("actual-map-height","tactical-map","filter real authored surface levels",activate("map:level"));
        add.page("actual-map-fit","tactical-map","restore complete map and all levels",activate("map:fit"));
        add.page("actual-map-return","pause","return to original pause without simulation changes",activate("map:back"));
        add.page("actual-campaign-resume","running:spark","resume original match after using the map",dispatch("pause"));
        add.page("actual-campaign-menu","main","actual checkpoint remains after leaving",dispatch("pause"),activate("leave"),activate("accept"));
        add.page("actual-campaign-continue","running:spark","actual checkpoint continuation retains chassis",activate("continue"));
        add.page("actual-campaign-finished-review","main","real match unloaded without recording a synthetic victory",dispatch("pause"),activate("leave"),activate("accept"));
        result.add(new Case("hardware-controller","","","",List.of(),"Requires a physically operated controller; virtual UI dispatch is not hardware proof"));
        return List.copyOf(result);
    }
}
