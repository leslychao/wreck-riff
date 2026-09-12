package game.wreckriff.diagnostics;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ResourceRetentionTest {
    private final ResourceRetention.Key yard=new ResourceRetention.Key("dead-air-yard","LEGACY","ARENA_COMBAT","rivet","closed");
    private ResourceRetention.Sample sample(String stage,ResourceRetention.Key key,int bodies,int trackers,long buffers,int textures) {
        return new ResourceRetention.Sample(stage,12,1789000000000L,key,bodies,stage.equals("LOAD")?1:0,stage.equals("LOAD")?1:0,
                0,0,trackers,buffers,textures,0,false,5,5,stage.equals("UNLOAD"));
    }
    @Test void DifferentArenaPhaseProfileAndRestoredTopologyHaveIndependentLoadBaselines() {
        var retention=new ResourceRetention();
        retention.add(sample("LOAD",yard,45,130,1000,30),false);
        var restored=new ResourceRetention.Key("construction_17","ARENA","BOSS_ENTRY","spark","panel-open/barrier-active");
        retention.add(sample("LOAD",restored,29,130,1000,30),false);
        retention.add(sample("LOAD",yard,45,130,1000,30),false);
        assertFalse(retention.failed());
        retention.add(sample("LOAD",restored,30,130,1000,30),false);assertTrue(retention.failed());
    }
    @Test void UnloadLeaksFailImmediatelyButGlobalNativeAndGpuComparisonWaitsForAllContextsToWarm() {
        var retention=new ResourceRetention();
        retention.add(sample("UNLOAD",yard,0,100,1000,30),false);
        retention.add(sample("UNLOAD",yard,0,500,5000,90),false);
        assertFalse(retention.passed());assertFalse(retention.failed());
        retention.add(sample("UNLOAD",yard,1,100,1000,30),false);assertTrue(retention.failed());
    }
    @Test void RepeatedSmallIncreasesCannotMoveTheFixedWarmedBaseline() {
        var retention=new ResourceRetention();
        retention.add(sample("UNLOAD",yard,0,100,1000,30),true);
        for(int i=1;i<=3;i++)retention.add(sample("UNLOAD",yard,0,100+i,1000+i,30+i),true);
        assertTrue(retention.passed());
        retention.add(sample("UNLOAD",yard,0,165,1000,30),true);assertTrue(retention.failed());assertFalse(retention.passed());
        assertEquals("FAIL",retention.evidence().get("status"));
    }
    @Test void MissingTextureMeasurementAndUnsettledWriterRemainFailures() {
        var retention=new ResourceRetention();retention.add(sample("UNLOAD",yard,0,100,1000,30),true);
        retention.add(sample("UNLOAD",yard,0,100,1000,-1),true);assertTrue(retention.failed());
        var pending=new ResourceRetention.Sample("UNLOAD",12,1789000000000L,yard,0,0,0,0,0,100,1000,30,1,true,6,5,true);
        var other=new ResourceRetention();other.add(pending,false);assertTrue(other.failed());
    }
}
