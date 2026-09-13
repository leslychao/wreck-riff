package game.wreckriff.presentation;

import java.io.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VehicleRegionalDataTest {
    private static final List<Map<String,Integer>> COUNTS=List.of(Map.of("hood",3));
    private static String document(String region) {
        return "{\"schemaVersion\":1,\"lods\":[{\"hood\":["+region+(",{"+"\"indices\":[],\"delta\":[]}").repeat(7)+"]}]}";
    }
    @Test void loadsSparseRegionsWithoutJsonTreeAndPreservesAxisOrder() throws Exception {
        var regions=VehicleModelData.readRegions(new StringReader(document("{\"delta\":[0.1,-0.2,0.3],\"indices\":[2]}")),COUNTS);
        assertArrayEquals(new int[]{2},regions.getFirst().get("hood")[0].indices());
        assertArrayEquals(new float[]{.1f,-.2f,.3f},regions.getFirst().get("hood")[0].xyz());
        assertEquals(0,regions.getFirst().get("hood")[7].indices().length);
    }
    @Test void rejectsOutOfRangeRepeatedOrIncompleteVerticesBeforeGeometryChanges() {
        for(String region:List.of("{\"indices\":[3],\"delta\":[0,0,0]}","{\"indices\":[1,1],\"delta\":[0,0,0,0,0,0]}",
                "{\"indices\":[0],\"delta\":[0,0]}","{\"indices\":[0],\"delta\":[1e100,0,0]}"))
            assertThrows(IOException.class,()->VehicleModelData.readRegions(new StringReader(document(region)),COUNTS));
    }
    @Test void rejectsMissingPartsAndWrongSchema() {
        assertThrows(IOException.class,()->VehicleModelData.readRegions(new StringReader("{\"schemaVersion\":1,\"lods\":[{}]}"),COUNTS));
        assertThrows(IOException.class,()->VehicleModelData.readRegions(new StringReader("{\"schemaVersion\":2,\"lods\":[]}"),COUNTS));
    }
}
