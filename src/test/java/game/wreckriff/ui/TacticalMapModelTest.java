package game.wreckriff.ui;

import game.wreckriff.arena.ArenaDefinition;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TacticalMapModelTest {
    @Test void clippedFootprintsRemainInsideTheMapWhenZoomedAndEntirelyOutsideShapesDisappear() {
        var map=map();map.zoomBy(4);
        var clipped=map.clipPolygon(java.util.List.of(map.project(-900,-700),map.project(900,-700),map.project(900,700),map.project(-900,700)));
        assertEquals(4,clipped.size());assertTrue(clipped.stream().allMatch(map::visible));
        assertTrue(map.clipPolygon(java.util.List.of(map.project(-900,-700),map.project(-800,-700),map.project(-800,-600))).isEmpty());
    }
    private TacticalMapModel map() {
        var map=new TacticalMapModel(new ArenaDefinition.Bounds(-900,900,-700,700,-40));
        map.resize(new UiBounds(20,150,900,700));return map;
    }
    @Test void wholeMapFitsAndKeepsNorthUpAcrossDifferentAspectRatios() {
        var map=map();assertEquals(new TacticalMapModel.Point(470,500),map.project(0,0));
        assertEquals(new TacticalMapModel.Point(20,150),map.project(-900,-700));
        assertEquals(new TacticalMapModel.Point(920,850),map.project(900,700));
        map.resize(new UiBounds(0,0,400,700));assertTrue(map.visible(map.project(-900,-700)));
        assertTrue(map.visible(map.project(900,700)));assertTrue(map.project(0,100).y()>map.project(0,0).y());
    }
    @Test void zoomAndPanClipRoadsWithoutDrawingAcrossControls() {
        var map=map();map.zoomBy(4);map.pan(1,1);
        var segment=map.clip(-900,0,900,0).orElseThrow();
        assertTrue(map.visible(segment.from()));assertTrue(map.visible(segment.to()));
        assertTrue(map.clip(-900,-700,-800,-700).isEmpty());
        map.zoomBy(100);assertEquals(8,map.zoom());map.zoomBy(.0001f);assertEquals(1,map.zoom());
        map.fit();assertEquals(new TacticalMapModel.Point(470,500),map.project(0,0));
    }
    @Test void playerCenterAndDegenerateSegmentsStayFinite() {
        var map=map();map.center(800,-600);assertEquals(new TacticalMapModel.Point(470,500),map.project(800,-600));
        var point=map.clip(800,-600,800,-600).orElseThrow();assertEquals(point.from(),point.to());
        map.zoomBy(Float.NaN);assertEquals(1,map.zoom());
    }
}
