# Ceiling and bridge load paths

This source audit supplements the moving-camera review and native driving tests.
It checks the final authored geometry rather than a minimum number of details.
All dimensions below are metres. It does not grant owner or artistic acceptance.

| Structure | Actual load path and enclosure |
| --- | --- |
| Unfinished apartments | Four L-frame slab components connect through transverse beams at the real column rows. Slab components are traced to grounded canonical columns. Rebar is detail on the unfinished edge. |
| Concrete plant | Five sawtooth bays have upper/lower chords, verticals and diagonals sharing nodes. Longitudinal edge girders and the two existing column rows carry these trusses. Closed end wedges and full-height ridge glazing join the roof skins. |
| Warehouse | The six-metre gable profile determines every upper chord and web endpoint. Longitudinal girders bear on grounded posts at both loading sides. Both gables are 0.3-metre closed prisms, with visible inward, outward, lower and sloping faces. |
| Shopping passage | Curved ribs meet edge girders carried by columns within the established side-wall envelopes. Curved clerestory end panels are closed prisms rather than one-sided planes. The asymmetric road portals remain open. |
| Technical complex | The slab meets the canonical end walls and two additional grounded posts. Roof ventilation rests on the slab; heat-exchanger fins overlap their actual housing and stay inside its length. |
| Parking | Every elevated floor, circulation strip and ramp has the same 1.2-metre closed physical shell. Ramp openings remain cut out. Columns overlap the underside of the actual floors at levels 8 and 16. The model supplies exposed beams only, eliminating a duplicate broad slab. Level-16 driving surfaces all carry navigation level 2. |
| Circus | Radial cloth ribs meet a closed perimeter ring and the central mast. The ring bears on edge poles; tension stays connect the central mast to two grounded king poles. Missing perimeter poles correspond to vehicle doors, with the ring continuing across the openings. |
| Orbit pavilion | Upper and lower curved chords share web nodes. Edge girders carry the arches and meet columns in the original side-wall envelopes. The complete profile stays above the verified driving space. |
| Repair depot | Crane runways bear on the side walls and columns. Cross-girders span between runways, the trolley spans cross-girders, and the cable intentionally hangs from the hoist. Roof trusses use the actual five-metre gable profile; closed end prisms and eave beams join the walls. |

The three lake bridges retain their clipped, non-overlapping top surfaces and
1.2-metre shells. West timber pile pairs carry transverse caps; east steel piers
and north concrete piers carry their own caps. Piles/piers reach the lake bed at
Y=-18 and overlap caps at Y=1.3. Caps overlap the deck underside at Y=1.8 by 5 cm.
The three spans meet the island's existing surface without another pavement layer.

Construction's flat interchange spans have piers centred under their real deck.
Both inclined approaches use portals with feet outside the lower driving corridors.
The portal cap follows the actual ramp plane 1.2 metres below its top; the lowest
cap underside leaves at least eight metres for the lower route. These structural
caps are physical meshes, never navigation surfaces or duplicate road tops.

`test_structure_load_paths.py` follows connected physical members to grounded
solids, checks actual chord profiles and web endpoints, checks two-sided closed
gable edges, and verifies floor shells, ramp openings, column/slab attachment,
circus mast/ring connectivity and bridge bearing elevations.
`test_architecture_surface_details.py` checks outward faces, metric UVs and the
full footprint of low support vertices against every driving corridor plus four
metres. Native tests and the final moving-camera captures must additionally verify
the assembled collision model, visual joins, culling and camera movement.

Supply sockets are checked separately against actual overhead triangles and ramp
undersides. Moving a buried cache preserves its identity, ammunition type, quantity
and respawn interval; all start caches retain their tested positions. The nearest
construction boss socket is moved away from the entry point to preserve a truly
empty first simulation tick.
