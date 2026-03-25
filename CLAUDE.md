# Scottish Hill Nav — CLAUDE.md

## GitHub repository

**https://github.com/supermillie77/RidgeStep**

This is the authoritative source for all code. When making edits, always read the latest version
of the relevant file from this repo before making changes. Use the raw file URL pattern:
`https://raw.githubusercontent.com/supermillie77/RidgeStep/main/{filepath}`

e.g. `https://raw.githubusercontent.com/supermillie77/RidgeStep/main/app/src/main/java/com/example/scottishhillnav/MainActivity.kt`

Android walking navigation app for Scottish hills (Munros, Corbetts, Grahams). Built in Kotlin
targeting API 26+. Uses OSMDroid for map rendering, Overpass API for footpath data, Nominatim
for place search, and a bundled binary graph (scotland\_pack) for primary A\* routing.

---

## Key external resources

| Resource | URL | Use |
|---|---|---|
| WalkHighlands Munros | https://www.walkhighlands.co.uk/munros/ | Full Munro list, routes, car parking, pronunciation |
| WalkHighlands Corbetts | https://www.walkhighlands.co.uk/corbetts/ | Corbett list, routes, car parking |
| WalkHighlands Grahams | https://www.walkhighlands.co.uk/grahams/ | Graham list |
| WalkHighlands hill page pattern | `https://www.walkhighlands.co.uk/munros/{hill-slug}/` | Per-hill page: e.g. `/munros/ladhar-bheinn/`, `/munros/ben-nevis/` |
| WalkHighlands walk page pattern | `https://www.walkhighlands.co.uk/fortwilliam/bennevis.shtml` | Walk routes with car park grid refs |
| OSM Overpass API | https://overpass-api.de/api/interpreter | Footpath graph, car parks, ferry terminals |
| Nominatim search | https://nominatim.openstreetmap.org/search | Hill/place search, ferry terminal coordinates |
| DEM (SRTM) | https://dwtkns.com/srtm30m/ | 1 arc-sec elevation tiles (n56\_w005, n56\_w006 already bundled) |

**WalkHighlands hill pages** always include:
- Gaelic name pronunciation (e.g. "LAR-uh VEN" for Ladhar Bheinn)
- Recommended car park with OS grid reference and postcode
- Route grades (strenuous / moderate etc.)
- Typical ascent time, distance, total ascent in metres
- A GPX download link

When helping with hill-specific questions, fetch the WalkHighlands page for that hill first.

---

## Architecture overview

```
MainActivity.kt          — All UI, routing orchestration, map tap handling
hills/
  HillSearchService.kt   — Nominatim hill search + Overpass car-park / ferry detection
  HillRepository.kt      — Bundled hill list (summaries, not full route data)
  HillSuggestionService.kt — Autocomplete suggestions
routing/
  Graph.kt               — Node/Edge data structures, nearestNodeIds KD-style search
  AStarRouter.kt         — A* with capability masking (WALKING, SCRAMBLING etc.)
  OverpassGraphBuilder.kt — Downloads footpath network from Overpass as a Graph
  RouteCandidateGenerator.kt — Curated + divergence-anchor candidate generation
  RouteMetricsCalculator.kt  — Distance, ascent, descent, difficulty
  RouteCandidate.kt      — Data class returned to UI
  Capability.kt          — Bitmask for route access requirements
  PackInstaller.kt       — Reads bundled scotland_pack binary graph at startup
  GpxRouteImporter.kt    — Imports GPX files as snapped route candidates
  RouteOverlay.kt        — OSMDroid polyline overlay for active/inactive routes
Routes/
  BenNevisRouteFamilies.kt — Hardcoded Ben Nevis named routes (Tourist Path, CMD Arête, Ledge Route)
navigation/
  VoiceNavigator.kt      — TextToSpeech wrapper; fires instructions at trigger distances
  RouteIndex.kt          — Cumulative distance + ascent arrays for a route
  InstructionGenerator.kt — Generates turn-by-turn voice instructions
  ElevationProfileModel.kt — Data for elevation profile chart
  RouteTrackingEngine.kt — GPS progress tracking along route
ui/
  ElevationProfileView.kt   — Custom View drawing the elevation profile
  RouteGradientOverlay.kt   — Slope-coloured polyline overlay
  MapTapHandler.kt          — Processes OSMDroid map tap events
```

### Bundled graph (scotland\_pack)

Located at `app/src/main/assets/scotland_pack/`:

| File | Contents |
|---|---|
| `nodes.bin` | Node records: id (int), lat (double), lon (double), elevation (double) |
| `edges.bin` | Edge records: from (int), to (int), cost (double), requiredMask (int) |
| `nodes_v2.bin` | V2 elevation-enriched nodes (empty = V0 pack, use cumulative ascent proxy) |
| `pack_version.txt` | Integer version number |

Coverage: Highlands of Scotland — sufficient for Ben Nevis, Glen Coe, Cairngorms, etc. Remote
peninsulas (Knoydart, Ardnamurchan, Applecross) have **no bundled coverage** and fall through to
the Overpass download path.

### Routing flow

```
buildRoutes() → buildRouteForPoints()
  2 points  → buildStandardCandidates()
                 1. Try nearestConnectedNodeIds on bundledGraph
                 2. If nearest bundled node > 2 km away → fetch Overpass graph for midpoint+radius
                    → snap VSTART/VEND with virtual edges → A* → promote graph
                 3. Try curated candidates (Ben Nevis named routes)
                 4. Bundled A* direct
                 5. Straight-line fallback
  3+ points → buildWaypointRoute()
                 If all points covered by bundled graph → segment-by-segment bundled routing
                 If any point remote → fetch single Overpass graph for full bounding box
                    → snap each waypoint with virtual nodes (-10, -11, …) → A* per segment
                    → stitch segments → promote graph
```

### Car park / access detection (`HillSearchService.findNearbyCarParks`)

Two Overpass queries, then post-processing:

1. **Parking query** — `amenity=parking` nodes and ways within **30 km** of hill summit
2. **Ferry query** — `route=ferry` relations within 35 km + ferry terminals + settlements within 30 km
   - Relations provide authoritative `from`/`to` tags (e.g. "Mallaig" → "Inverie")
   - Terminal coordinates resolved against settlements; Nominatim fallback for unmatched names
   - Ferry pairs whose **closer** terminal is within **25 km** are included
   - Output label: `"Inverie (ferry from Mallaig)"` with `navLat/navLon` pointing to the road terminal
3. **Road-end settlements** — settlements 20–35 km away (beyond typical loch-barrier radius but
   reachable by road) added as fallback when no parking node exists nearby.
   - Settlements < 20 km excluded (may be across a loch — e.g. Arnisdale, Corran for Ladhar Bheinn)
4. **Dedup** — unnamed "Car park" entries within 100 m collapsed to one
5. **Enrichment** — nearest settlement within 5 km used as area label for unnamed car parks

Known access patterns for specific mountains:

| Mountain | Primary access | Notes |
|---|---|---|
| Ben Nevis | Visitor Centre car park, Fort William | Abundant OSM parking nodes |
| Ladhar Bheinn | Kinlochhourn road-end (~23.5 km E) | No bundled graph; Overpass required |
| Ladhar Bheinn | Inverie via Mallaig ferry (~14 km SW) | Ferry relation `from=Mallaig to=Inverie` |
| Buachaille Etive Mòr | Altnafeadh / White Corries | A82 lay-bys, well mapped |
| An Teallach | Dundonnell Hotel car park | Single-track from A832 |
| Schiehallion | Braes of Foss car park | Pay & Display, NT managed |

---

## Scottish Gaelic pronunciation reference

WalkHighlands includes per-hill pronunciation. Common patterns:

| Gaelic word | Meaning | Pronunciation |
|---|---|---|
| Beinn / Bheinn | Mountain | BEN / VEN |
| Sgùrr / Sgòrr | Peak / rocky peak | SKOOR / SKOR |
| Stob | Point / peak | STOP |
| Meall | Rounded hill | MYOWL |
| Creag | Crag / cliff | KRAYK |
| Aonach | Ridge / height | OO-nach |
| Bidean | Peak / pinnacle | BEE-jen |
| Gleann / Glen | Valley | GLYOWN / GLEN |
| Coire / Corrie | Hollow / cirque | CORR-uh |
| Allt | Stream / burn | OWLT |
| Lochan | Small loch | LOCH-an |
| Druim | Ridge | DROO-im |
| Cnoc | Knoll / hill | KROCK |
| Bealach | Pass / col | BYAL-ach |
| Dearg | Red | JER-ak |
| Dubh | Black / dark | DOO |
| Mòr / Mhòr | Big / great | MORE / WORE |
| Beag / Bheag | Small | BEK / VEK |
| Liath | Grey | LEE-uh |
| Ruadh | Red / reddish-brown | ROO-ugh |
| Garbh | Rough | GAR-uv |
| Odhar | Dun / drab | OO-ur |
| Fionn | White / fair | FYOWN |
| Geal | White / bright | GYAL |
| Mam | Rounded pass | MAM |
| Sròn | Nose / promontory | STRAWN |
| Tom | Hillock / knoll | TOWM |
| Caisteal | Castle | CASH-chul |
| Rannoch | Bracken moorland | RAN-ach |
| Cairn | Cairn / rocky summit | KARN |
| Ladhar | Hoof / fork | LAR (sometimes LAR-uh) |
| Knoydart | (Norse origin) | NOY-dart |
| Torridon | (Norse/Gaelic) | TORR-ih-dun |
| Applecross | (Norse: Aporcrosan) | AP-ul-cross |
| Quinag | (from cuinneag, churn) | KYOO-nyak |

**Key Gaelic mutation rules for TTS** (relevant to `VoiceNavigator.pronounce()`):
- Initial `Bh-` / `Mh-` → V sound (Bheinn = VEN, Mheall = VYOWL)
- Initial `Gh-` / `Dh-` → silent or Y sound (Ghleann = LYOWN)
- `-igh` → EE sound (Liathach = LEE-uh-ach)
- `-adh` / `-agh` at end → silent or very soft (ruadh = ROO-ugh)

---

## Key conventions in this codebase

- **Graph node IDs**: Bundled graph uses positive integers. Overpass nodes start at 1\_000\_000.
  Virtual snap nodes use negative IDs: -1 = VSTART, -2 = VEND (buildStandardCandidates),
  -10, -11, -12… (buildWaypointRoute remote branch). Never reuse these.
- **`bundledGraph`** is never mutated. `graph` is the promoted (live) graph and gets replaced
  each time a new route is built. Always use `bundledGraph` for coverage checks.
- **Remote area detection**: `haversine(point, nearestBundledNode) > 2_000.0` → fetch Overpass.
- **`CarPark.navLat / navLon`**: For ferry access entries, these hold the **road terminal** coords
  (the place to drive to). `lat/lon` hold the walk-start coords (the ferry arrival pier).
- **PLACE\_REGEX**: Only `city|town|village|hamlet|suburb|neighbourhood|quarter`. Excludes
  `locality`, `isolated_dwelling`, `farm` — these produce noise like "Folach".
- **`naturalFeatureWords`**: Gaelic words that identify peaks/corries/lochs so they are not
  treated as driveable settlements. Defined as object-level constant in `HillSearchService`.
- **TTS language**: British English (Locale.UK) preferred; falls back to Locale.ENGLISH then
  Locale.getDefault(). Audio routed through USAGE\_MEDIA so ring-mode mute doesn't silence it.
- **Curated routes**: Only active near Ben Nevis (both start AND end within 15 km of summit).
  Defined in `BenNevisRouteFamilies.kt` and registered via `RouteFamilyRegistry`.

---

## Files that should NOT be committed to GitHub

Add to `.gitignore`:
```
# Large DEM elevation tiles (download separately from dwtkns.com/srtm30m)
*.tif
*.hgt

# Bundled graph backups
*.bin.bak

# Android build outputs and local config
/build
app/build
local.properties
*.iml
.gradle
/.idea/workspace.xml
/.idea/caches
/.idea/libraries
/.idea/modules.xml
/.idea/navEditor.xml
/.idea/assetWizardSettings.xml
.DS_Store
hs_err_pid*.log
```

The bundled graph (`scotland_pack/edges.bin`, `nodes.bin`) **should** be committed — it is the
primary routing data and must be present for the app to function without network access.

---

## How to add this project to GitHub

See instructions below (separate section for user).
