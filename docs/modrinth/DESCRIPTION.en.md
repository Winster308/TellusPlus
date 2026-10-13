# Tellus Plus

Tellus Plus is a community fork of the Fabric mod [Tellus](https://modrinth.com/mod/tellus) that recreates real-world terrain in Minecraft by generating Earth-scale landscapes from geographic data. It focuses on realistic elevation, biome placement, and climate-driven time and weather, aiming to make the world feel like a playable map of our planet.

This fork adds a genuinely 1:1 Earth scale with the ocean left uncompressed, a fully localised interface, a hardened download layer, an edge mirror for players in China, and **world generation for the Moon and Mars** from real NASA planetary data.

## Copyright

- Original Author: Yucareux
- Tellus Plus (1:1 scale, localisation, mirror & CDN support): BlackHoleEra-Team
- License: LGPL-3.0

Inspired by Gegy's Terrarium: https://modrinth.com/mod/terrarium

**Survival note:** Some survival features are still missing (including certain structures and biomes). While a survival world is possible, upcoming updates may break those worlds; for now Tellus Plus is better suited to testing and exploration than long-term survival.

**Internet & data note:** Tellus Plus requires an active internet connection and will not work offline. It downloads terrain, land cover, climate, and weather data on demand; expect ongoing data usage that varies with how much of the world you explore.

**Server support note:** Tellus Plus must be installed on the server, but is not required on clients. Official server support is not available yet; for now you should create the world in singleplayer first, then move that world to the server (with Tellus Plus installed) so new chunks generate with Tellus Plus.

**1:1 world note:** A true 1:1 world must be created with this mod's world creation screen. The vertical range is written into `level.dat` and validated on load, so an existing world cannot be converted to 1:1 after the fact. Expect world creation to take several minutes.

*Note: generative AI was used during the creation of this mod.*

## Features

### Terrain generation

- **Earth-scale terrain** built from open geographic elevation data — the ground you walk on is a real place on Earth
- **The Moon and Mars as well** — real NASA planetary elevation (MOLA and LOLA rasters), with the matching base maps
- **True 1:1 scale** — 1 block = 1 metre with the ocean uncompressed, giving a 32,640-block vertical range
- **Highly customizable generation** — world scale, terrestrial and oceanic height multipliers, sea level, height offset, upper and lower world limits, and separate shoreline blending for rivers, lakes and oceans
- **Shoreline blending** that smooths river, lake and coastline edges into the surrounding terrain, with an option to suppress it on steep slopes so cliffs stay sharp
- **Biomes placed to match real-world climate regions**, driven by the Köppen-Geiger climate classification rather than procedural noise
- **Underground generation** — configurable cave generation, vanilla ore distribution and lava pools

### Interface

- **Built-in terrain preview screen** so you can see how your settings will look before committing to world creation
- **In-game map teleport UI** (GeoTP) for picking a real-world location and travelling there
- **Live progress reporting** for every long operation, including tile downloads, terrain sampling and each stage of the preview build
- **Three interface languages** — English, Simplified Chinese and Spanish
- **Tooltips on every configuration option**, with labels and explanations laid out separately so long English text does not crowd the controls

### World simulation

- **Real-time time** — syncs the day/night cycle to the actual local clock at your in-game location, so sunrise and sunset match the real world
- **Real-time weather** — pulls live conditions for your location and mirrors them in game (rain, thunder or snow) instead of Minecraft's default weather rolls
- **Historical snow coverage** *(in progress)* — tracks recent temperature and snowfall to decide where snow should persist, for more believable seasonal coverage

### Performance and compatibility

- **Distant Horizons integration** for rendering planet-scale terrain far beyond vanilla view distance
- **Voxy support** for real-time chunk ingestion
- **Edge mirror proxy** for fast, reliable data access in mainland China
- **Preload ledger** for managing bulk region pre-generation, with inspection and box-select deletion

## Getting started

Tellus Plus does not generate a world from a seed the way vanilla Minecraft does. It reads your position, converts it to latitude and longitude, and looks up the terrain for that place. That means a few steps are worth doing in order.

**1. Install the mod on the side that generates the world.**

For singleplayer, that is your client. For a server, it must be installed server-side; clients do not need it, though installing it on both sides gives you the map interface and the terrain preview.

**2. Create the world with this mod's world creation screen.**

Open **Create New World**, then **Customize World Generation**. This screen is where the world scale, height limits, sea level and **celestial body** are chosen, and it is the only place they can be set. The values are written into `level.dat` when the world is created and validated every time the world loads.

**2a. Choosing the Moon or Mars instead of Earth.**

Select the body in the same screen. Everything else works the same way, but two settings deserve attention first:

- **Height limits.** Olympus Mons rises about 21 km above the Mars datum and Hellas Planitia drops about 7 km below it, so a Mars world needs a taller range than a default Earth world. The 1:1 preset is a reasonable starting point.
- **Spawn coordinates.** The spawn latitude and longitude are read from the same fields; the defaults point at Everest, which does not exist on Mars. Set them to a feature you want to start near, or accept the fallback.

There is no land cover, climate or weather data off Earth, so those systems stay idle and biome placement uses the generic path.

**3. Use the preview before you commit.**

The preview screen renders the terrain around your chosen spawn using the settings you have selected, so you can see whether the scale and height multipliers produce the world you want. It is much faster to adjust settings here than to create a world and discover the problem afterwards.

**4. Expect the first world creation to take a while.**

The vertical range is written into the save, and the spawn area is generated before you arrive. At 1:1 this is minutes, not seconds. Later sessions load normally.

**5. Consider Distant Horizons.**

At Earth scale the vanilla view distance covers a vanishingly small fraction of the world. Distant Horizons is strongly recommended and is close to essential for comfortable exploration.

## What this fork adds over Tellus

### True 1:1 scale

Upstream supports scaling the Earth down — at 1:100, one hundred real metres collapse into a single block. Tellus Plus additionally supports **1 block = 1 metre with the ocean uncompressed**, so nothing is flattened or shrunk on the way in.

| | Value |
|---|---|
| Vertical range | `minY = -11040` to `top = 21599` |
| Span | 32,640 blocks — 2,040 sections per chunk |
| Sea level | Y = 64 |
| Mariana Trench | Y = −10970 |
| Mount Everest | Y = 8912 |
| Coordinate packing | Repacked to X25 / Y15 / Z24 = 64/64 bits |

**Why the coordinate packing had to change.** Minecraft stores a block position in a single 64-bit integer. The vanilla layout allocates 12 bits to Y, which is enough for the default 384-block world but nowhere near 32,640. Tellus Plus repacks the fields as 25 bits for X, 15 for Y and 24 for Z — exactly 64 bits, with no spare room and no sacrifice in horizontal range. A runtime log line confirms the layout in use: `packing repacked X25/Y15/Z24 = 64/64 bits`.

**This is not free.** A 1:1 chunk carries 2,040 sections against vanilla's 24, so entering a world takes minutes rather than seconds, and memory use is dominated by light data rather than blocks. The solid-rock fill beneath the ocean floor is the single most expensive operation in world generation, because the trench at Y −10970 genuinely needs 11,040 blocks of rock underneath it. Lowering that floor would truncate the trench and it would no longer be 1:1.

### The Moon and Mars

Upstream generates Earth and nothing else. Tellus Plus can generate **the Moon and Mars** as well, from the same NASA archives the planetary science community uses.

This is not a reskin. The elevation is real, read straight from the source rasters:

| Body | Dataset | Format | Coverage |
|---|---|---|---|
| **Mars** | MGS **MOLA** | `megt{lat}{N/S}{lon}hb.img`, 16 band files | ±88° latitude, 128 px/degree |
| **Moon** | LRO **LOLA** | `ldem_128.img`, one global file | ±90° latitude, 128 px/degree |

Both are read **row by row over HTTP Range requests** rather than downloaded whole, because the source rasters run from hundreds of megabytes to 2 GB — 23 KB per row on Mars, 92 KB on the Moon. Rows are kept in an LRU cache bounded by count, about 12 MB for Mars and 47 MB for the Moon.

**The coordinates were verified against known terrain, not assumed.** Latitude order and longitude origin were both checked through the mirror using Range requests:

| Feature | Sampled | Real |
|---|---|---|
| Mars — Olympus Mons (18.65 N, 226.2 E) | **+20,015 m** | ≈ +21.2 km |
| Mars — Hellas Planitia (42.4 S, 70.5 E) | **−6,044 m** | ≈ −7 km |
| Mars — northern lowlands (45 N, 110 E) | **−4,929 m** | ≈ −4.8 km |
| Moon — highest peak (5.4 N, 201.4 E) | **+10,730 m** | +10,790 m (60 m off) |
| Moon — South Pole–Aitken (53 S, 191 E) | **−6,926 m** | ≈ −6 to −9 km |

Olympus Mons is a 21 km mountain and Hellas Planitia a 7 km basin, so a Mars world needs a vertical range Earth does not — the height settings exist for exactly this, and the world creation screen accepts them.

The surface and map imagery switch with the body as well: **Moon** uses the OpenPlanetaryMap base map, **Mars** uses **Viking MDIM21**. The map theme list includes both, and the DEM layer switches from Earth's terrain tiles to the corresponding PDS raster.

**One consequence worth knowing before you start.** Earth is covered by land cover, climate classification, weather and OSM data; the Moon and Mars are not, because that data does not exist for them. Terrain and imagery are real, but biome placement falls back to the generic path rather than Köppen-Geiger regions, and real-time weather has nothing to read. Treat a planetary world as terrain exploration, not as a survival world.

### Localised interface

Upstream ships English only. Tellus Plus ships **English, Simplified Chinese and Spanish** (`en_us`, `zh_cn`, `es_es`).

The coverage is deliberately wider than the configuration screen. Everything a player can read is translated:

- The Mod Menu configuration screen, including every option label and its tooltip
- The on-screen HUD, including the minimap and the "where am I" readout
- The terrain preview screen — stage names, progress text and status messages
- Command feedback and error messages
- The world creation screen and the customisation list

Around **267 strings** that were previously hard-coded in the source now go through the translation system, so switching the game language switches all of it at once. The map theme names are translated too, including all eight built-in themes (OSM, Esri imagery, OpenTopoMap, Carto, OpenFreeMap Liberty, OpenFreeMap Dark, Moon and Mars).

### Terrain quality algorithms

Upstream's elevation handling is comparatively blunt. Tellus Plus ports **four elevation-repair algorithms** from [arnis](https://github.com/louis-e/arnis):

- **Bilateral physical threshold filtering** — rejects elevations that cannot physically exist. Upstream applies a threshold on one side only, so values that are too high can slip through.
- **5×5 MAD outlier removal** — erases isolated spikes by comparing each sample against the median absolute deviation of its neighbours. Upstream has no equivalent, so single bad samples survive into the terrain as thin towers.
- **3×3 Jacobi iterative hole filling** — fills gaps by repeatedly averaging from the surrounding neighbourhood until the values converge. Upstream fabricates a height instead, which is why data gaps used to appear as flat plateaus.
- **Data-validity gate** — decides whether a downloaded tile is actually *useful*, rather than merely whether the HTTP request succeeded.

**Why the fourth one matters most.** A request can return HTTP 200 and still carry an empty tile. Upstream treats that as a success: the tile is cached, sampling returns "no data", and the fallback path invents a height. Every column in that tile receives the same invented value, which is what produces **large square bumps** across the landscape. The gate checks the data itself, not the status code, so an empty tile is recognised as a failure and retried rather than baked into the world.

Each algorithm exists twice — a Rust kernel for speed and a pure-Java mirror for correctness. The native library is an accelerator, not a dependency, so behaviour is identical when it is missing or built for a different platform.

### Building materials

**26 material tables, 175 block entries and 20 categories.** When you enter a world, a self-check runs automatically and reports what it found:

```
material self-check passed: [building materials: 26 tables, 175 block entries, 20 categories]
```

### Hardened download layer

Upstream opens a separate connection per request and tracks failures with a single global counter. Tellus Plus reworks that layer:

| Change | Why it matters |
|---|---|
| **Single-flight de-duplication** | Concurrent requests for the same tile merge into one download instead of each opening its own connection. The pool reports `submitted`, `deduped`, `completed`, `failed`, `in-flight`, `queued`, `timed out` and `cancelled` so you can see what it is doing. |
| **Per-domain circuit breaker** | A failing data source silences only itself. Upstream's single global counter means one flaky mirror trips the breaker for every healthy source at once, degrading large areas to approximate terrain — which then gets written into your save. |
| **Transient-failure retry** | TLS handshakes cut short, socket timeouts, connection resets and 5xx/429 responses are retried with backoff. A definite answer such as 404 is not retried. |
| **Land-cover read timeout raised from 8 s to 30 s** | An ESA WorldCover tile is a 100–150 MB GeoTIFF needing 30–75 seconds to transfer. The 8-second read timeout applied to every individual read call, so a healthy download that stalled once mid-stream was aborted and counted as a failure. Eight of those in a row opened a 120-second circuit. |
| **Spawn no longer blocks on the network** | World spawn selection used to sample land cover over the network for every candidate column. Measured: **18 minutes 40 seconds**. It now reads only from cache and falls back to a defined default, and never submits a download. Measured afterwards: **3 minutes 44 seconds**. |
| **Preload back-pressure** | Bulk pre-generation used to flood the download pool, leaving the spawn search queued behind thousands of tasks. A high-water mark now throttles prefetch so interactive requests stay responsive. |

### Edge mirror proxy

Upstream connects directly to global data providers. From mainland China those hosts are slow or unreachable, which is the difference between a playable world and a world that never finishes loading.

Tellus Plus ships a **Cloudflare Workers reverse proxy** that:

- Whitelists specific upstream hosts and forwards only those routes
- Caches tiles at the edge, so repeat requests are served without touching the origin
- Retries transient upstream errors once at the edge, before the client ever sees them — which keeps a single origin hiccup from tripping the client's circuit breaker
- Reports an `x-tellus-attempts` header so you can see whether a retry happened
- Serves a `/health` endpoint for checking the mirror is alive

**It is not a general-purpose forwarder.** Each route maps to one fixed upstream host, so it cannot be abused as an open proxy. Range requests (used by the PMTiles reader) are passed straight through and deliberately not cached, because Cloudflare does not store a `206` response returned by a Worker.

The mirror routes can be configured, and the settings screen lets you switch between direct and mirrored access per data type.

### Configuration and quality of life

- **Tooltips on every option**, with labels and explanations on separate lines. English option names are substantially longer than Chinese ones, and a single crowded line is unreadable in either language.
- **Cache management** — inspect the size of each cache (OSM data, ESA WorldCover, Köppen climate, terrain tiles, ArcticDEM) and delete them individually or all at once to reclaim disk space.
- **Preload ledger** — inspect and manage bulk region pre-generation, including box-select deletion of regions you no longer want.
- **Renamed to Tellus Plus** (`tellusplus`), with a new icon rendered from vector artwork.

**One thing deliberately left alone:** the `telluscn` prefix in configuration filenames and `-Dtellus.*` system properties. Renaming those would invalidate existing worlds and launch scripts, so the old prefix remains even though the mod is now called Tellus Plus.

## Data sources and how they are used

Tellus Plus reads from several open datasets, each answering a different question about a place.

| Question | Dataset | What it gives |
|---|---|---|
| How high is the ground? | Terrain Tiles (global DEM) | Elevation in metres, used to build the terrain shape |
| What is the surface made of? | ESA WorldCover 2021 | Land-cover class at 10 m — water, forest, cropland, built-up, bare ground and so on |
| What is the climate like? | Köppen-Geiger classification | Climate group, used to place biomes |
| Where are the roads, buildings and water? | OpenStreetMap | Vector geometry for roads, buildings, rivers and lakes |
| What is the weather doing right now? | Open-Meteo | Live conditions for real-time weather and time |

**On the Moon and Mars, two more sources take over:**

| Question | Dataset | What it gives |
|---|---|---|
| How high is the ground on Mars? | MGS **MOLA** (`megt…hb.img`, 16 bands) | Elevation in metres relative to the Mars datum, 128 px/degree |
| How high is the ground on the Moon? | LRO **LOLA** (`ldem_128.img`) | Elevation in metres relative to the lunar datum, 128 px/degree |
| What does the surface look like? | OpenPlanetaryMap (Moon), **Viking MDIM21** (Mars) | The base map imagery used for the map view and surface colour |

Both planetary rasters are read row by row over HTTP Range requests and cached per row, because each file runs to hundreds of megabytes.

**How they combine.** Elevation decides the shape of the terrain. Land cover decides what sits on top of it and influences the biome. Climate classification decides which biome family applies. OpenStreetMap geometry is rasterised into the world so roads follow real routes and buildings appear where they exist. Weather data drives the optional real-time systems. Off Earth, the last four have no data to read and only elevation and imagery apply.

**Everything is downloaded on demand and cached.** Nothing is bundled with the mod, and nothing is fetched for parts of the world you never visit. The first time you explore a region it is downloaded; afterwards it is served from cache. The cache screen lists the size of each cache and lets you delete them individually.

## Multiplayer and servers

**Server-side installation is required.** The world generation happens on the server, so that is where the mod must be installed. Clients do not need it, though installing it client-side enables the map interface, the terrain preview and the HUD.

**There is no official server support yet.** The recommended path is:

1. Create the world in singleplayer with Tellus Plus installed
2. Generate a little terrain so the world is valid
3. Stop the game and move the world directory to the server
4. Install Tellus Plus on the server
5. Start the server — new chunks will generate with Tellus Plus

**Why this order matters.** The vertical range and the generator settings are written into the world save when the world is created. A server cannot invent them for an existing vanilla world, and it cannot change them afterwards.

**Performance on a server.** A 1:1 world is expensive to generate. If several players explore in different directions at once, the server will be generating many chunks concurrently. Consider pre-generating the regions you care about with the preload ledger before opening the server to players.

## Troubleshooting

### The world takes several minutes to load the first time

Expected at 1:1. The spawn area is generated before you arrive, and a 1:1 chunk carries 2,040 sections rather than vanilla's 24. Later sessions load normally.

### "Selecting global world spawn..." appears to hang

This was a real defect and it is fixed. World spawn selection used to generate up to 121 full chunks on the server thread while searching for a valid spot, producing over eleven minutes with no log output. Spawn resolution now reads from local cache and returns immediately.

If you still see a long pause here, check the log for a line beginning `initial spawn resolved from local cache`. Its absence means the fast path did not engage, and the log will also say why.

### Terrain has large square bumps

This was a real defect and it is fixed. It happened when a tile request returned HTTP 200 with an empty payload: the empty tile was cached as a success, sampling found no data, and a fallback height was invented for every column in the tile — producing a flat square raised above its surroundings.

The fix adds a data-validity gate that inspects the payload rather than the status code, so an empty tile is treated as a failure and retried.

### Downloads keep failing and terrain degrades to approximate shapes

Two causes, both addressed:

- **Read timeouts were too short.** An ESA WorldCover tile is a 100–150 MB GeoTIFF needing 30–75 seconds. The timeout was 8 seconds and applied per read call, so a healthy download that stalled once was aborted.
- **The circuit breaker was global.** One failing mirror silenced every data source at once. Breakers are now per-domain, so a single bad host cannot take down the rest.

If failures persist, try switching between direct and mirrored access in the settings, and check the mirror's `/health` endpoint if you are using it.

### The mod does not work offline

Correct — it does not, by design. Terrain, land cover, climate and weather are all fetched on demand. There is no bundled world data.

### I want to change the world scale or height limits on an existing world

Not possible. Those values are written into the save when the world is created and validated on load. Changing them requires creating a new world.

## Known limitations

**Ecology is incomplete.** Land vegetation, vegetation density, tree density, aquatic vegetation and village crops are present as settings but currently locked. They describe what will become configurable.

**Some structures do not generate correctly.** Deep Dark and certain ocean structures are the known cases. Vanilla structures can be toggled individually in the settings if one causes trouble.

**Caves and underground generation are still in progress.** Cave generation, ore distribution and lava pools work, but expect changes in future updates.

**The Moon and Mars have terrain, not ecology.** Real elevation and imagery, but no land cover, no climate classification and no weather — that data does not exist for those bodies. Biome placement falls back to the generic path. A planetary world is for terrain exploration; use Earth for survival.

**Survival is playable but not stable long-term.** Some survival features are still missing, and upcoming updates may break existing survival worlds. Testing and exploration are the better fit for now.

**The 1:1 scale cannot be optimised away.** The cost is inherent: a 2,040-section chunk and 11,040 blocks of rock beneath the deepest trench are what "1:1 with the ocean uncompressed" means. Reducing either would make it something other than 1:1.

## Performance

A 1:1 world is heavy, and the cost is concentrated in two places that cannot be optimised away without giving up the scale.

| Item | Cost |
|---|---|
| `fill` per chunk | ~509 ms |
| Solid-rock fill (the largest single item) | ~140 ms — 981 sections × 4096 blocks = 4.02 M writes |
| Spawn area (625 chunks) | ~5 minutes |

### Recommended hardware

| | Minimum | Recommended | Comfortable |
|---|---|---|---|
| CPU | 4 cores / 4 threads | 4c/8t or 6 cores | 8 cores / 16 threads |
| RAM | 8 GB | 12 GB | 16–32 GB |
| Heap (`-Xmx`) | 4 GB | 6–8 GB | 10–12 GB |
| VRAM | 2 GB | 2 GB | 4 GB |
| Render distance | 8 | 12 | 16 |

Memory is dominated by light data: 2,040 sections × 2 layers × 2048 B ≈ 8 MB per chunk.

## Distant Horizons Integration

Tellus Plus integrates with the Distant Horizons (DH) mod to render planet-scale terrain far beyond vanilla view distance. When DH is installed, Tellus Plus registers a DH world-generation override for Tellus worlds (DH API v4+), so distant terrain is built using Tellus data and settings instead of generic vanilla sampling.

- **Fast mode**: Tellus Plus provides a custom LOD generator that samples its elevation, land-cover, climate, and water data directly to build distant terrain quickly and consistently with your world settings.
- **Detailed mode**: Tellus Plus delegates to DH's chunk-based generator for far terrain, which is more accurate but significantly heavier on performance.

Because Tellus worlds are Earth-scale, DH is strongly recommended and is almost essential for comfortable exploration and long-distance views.

## Commands

- `/tellus map`: Opens the GeoTP map UI (requires gamemaster permissions).
- `/tellus weather`: Shows local Tellus weather and time information at your current position.
- `/tellus storage`: Inspects and manages the preload ledger.
- `/tellus config weather enable_realtime_time <true|false>`: Overrides the real-time time setting on the server (requires gamemaster permissions).
- `/tellus config weather enable_realtime_weather <true|false>`: Overrides the real-time weather setting on the server (requires gamemaster permissions).

More commands will be added over time.

<details>
  <summary>Settings</summary>

These options are available in the "Customize World Generation" screen when creating a Tellus world.

### World Settings

- **World Scale**: Controls how many real-world meters are represented by one block. Lower values create more detailed, larger worlds; higher values compress distances and features. Current limits are 1:1m to 1:500m per block, with larger scales planned up to 1:40km.
- **Terrestrial Height Scale**: Multiplier that converts elevation above sea level from meters to blocks. Higher values produce taller mountains and landforms.
- **Oceanic Height Scale**: Multiplier that converts elevation below sea level from meters to blocks. Higher values deepen oceans and trenches.
- **Height Offset**: Shifts all terrain up or down by a fixed number of blocks. Use this to raise or lower the entire world.
- **Sea Level**: Sets the waterline in blocks without shifting the terrain. Set to Automatic to track the height offset.
- **Max Altitude**: Upper world limit in blocks. Set to Automatic to let Tellus compute a safe cap based on your scale settings.
- **Min Altitude**: Lower world limit in blocks. Set to Automatic to let Tellus compute a safe floor based on your scale settings.
- **River/Lake Shoreline Blend**: Distance in blocks used to smooth river and lake edges into nearby terrain.
- **Ocean Shoreline Blend**: Distance in blocks used to smooth ocean coastlines into land.
- **Limit Shoreline Blend on Cliffs**: Prevents shoreline smoothing on steep slopes to preserve sharp cliffs.

### Ecological Settings (work in progress)

These options are currently locked and not adjustable yet. They describe what will be configurable in a future update.

- **Land Vegetation**: Will enable grasses, flowers, and small plants on land.
- **Land Vegetation Density**: Will control how dense land vegetation appears.
- **Tree Density**: Will control how many trees spawn in eligible biomes.
- **Aquatic Vegetation**: Will enable kelp and seagrass in water.
- **Crops in Villages**: Will add village farm plots during village generation.

### Geological Settings

The cave and underground generation system is still work in progress, so expect changes here.

- **Cave Generation**: Toggles underground cave generation.
- **Ore Distribution**: Enables vanilla ore distribution in Tellus worlds.
- **Lava Pools**: Enables underground lava pools.

### Structure Settings

This section lets you toggle vanilla structures and world features on or off, such as villages, temples, monuments, ruins, and underground features like Deep Dark and amethyst geodes. Some structures (notably Deep Dark and certain ocean structures) may not generate properly yet and are still work in progress.

### Real-Time Settings

- **Real-Time Time**: Syncs the in-game day/night cycle to real-world time based on your in-game location, so sunrise and sunset match that location's local clock.
- **Real-Time Weather**: Pulls live weather conditions for your location and mirrors them in-game (rain, thunder, or snow) instead of Minecraft's default weather rolls.
- **Historical Snow Coverage** (work in progress): Tracks recent temperature and snowfall data to decide if snow should appear and persist on the ground, creating more realistic seasonal snow coverage.

### Compatibility Settings

- **Distant Horizons Render Mode**: Fast uses Tellus's LOD generator to build simplified distant terrain quickly with lower cost. Detailed asks Distant Horizons to use full chunk generation for far terrain, which is more accurate but significantly slower and heavier. For most setups, keeping Fast LOD generation is recommended.
- **LOD Water Resolver**: Adds detailed water depth sampling to Distant Horizons fast LODs, improving coastlines, lakes, and ocean floors at distance but with extra cost. If you plan to play around water-heavy regions, it is suggested to keep this enabled; otherwise you can disable it for better performance.
- **Coming Soon**: Additional compatibility options are work in progress and currently unavailable.

### Cache

- **OSM data**: Cached map, road, and water tiles used by Tellus map and OSM features. Deleting will force re-downloads as needed.
- **ESA WorldCover**: Cached land cover tiles used for biome and vegetation lookups.
- **Koppen climate**: Cached climate raster used for biome climate classification.
- **Terrain tiles**: Cached elevation tiles used for terrain height sampling.
- **ArcticDEM**: Cached ArcticDEM index files used when the ArcticDEM DEM provider is selected.
- **Total**: Combined size of all Tellus caches (read-only).
- **Delete cache / Delete all cache**: Removes cached data to free disk space; data will be re-downloaded or rebuilt as needed.

</details>

<details>
  <summary>Data Sources</summary>

### ESA WorldCover 2021 (land cover)

- ESA WorldCover 2021 (10 m land cover, v200)
- (c) ESA WorldCover project / Contains modified Copernicus Sentinel data (2021)
- processed by ESA WorldCover consortium.
- License: CC BY 4.0
- https://creativecommons.org/licenses/by/4.0/
- https://doi.org/10.5281/zenodo.7254221
- In-game processing: reprojected to the world grid, resampled to blocks, and cached as tiles for fast lookup.

### Koppen-Geiger climate classification

- Source: Beck, H.E., Zimmermann, N.E., McVicar, T.R., et al. (2018).
- Present and future Koppen-Geiger climate classification maps at 1-km resolution (Scientific Data).
- License: CC BY 4.0
- https://creativecommons.org/licenses/by/4.0/
- Publication DOI: https://doi.org/10.1038/sdata.2018.214
- In-game processing: reprojected and resampled to match the world grid, cached for fast lookup.

### Terrain Tiles (global DEM tiles)

- Terrain Tiles (AWS Open Data Registry / Mapzen Terrarium)
- https://registry.opendata.aws/terrain-tiles
- Source attributions for Terrain Tiles:
- ArcticDEM terrain data: DEM(s) were created from DigitalGlobe, Inc. imagery and funded under National Science Foundation awards 1043681, 1559691, and 1542736.
- Australia terrain data (c) Commonwealth of Australia (Geoscience Australia) 2017.
- Austria terrain data (c) offene Daten Osterreichs - Digitales Gelandemodell (DGM) Osterreich.
- Canada terrain data contains information licensed under the Open Government Licence - Canada.
- Europe terrain data produced using Copernicus data and information funded by the European Union - EU-DEM layers.
- Global ETOPO1 terrain data U.S. National Oceanic and Atmospheric Administration.
- New Zealand terrain data Copyright 2011 Crown copyright (c) Land Information New Zealand and the New Zealand Government (All rights reserved).
- Norway terrain data (c) Kartverket.
- United Kingdom terrain data (c) Environment Agency copyright and/or database right 2015. All rights reserved.
- United States 3DEP (formerly NED) and global GMTED2010 and SRTM terrain data courtesy of the U.S. Geological Survey.

### Open-Meteo (weather)

- Weather data provided by Open-Meteo.com.
- https://open-meteo.com/
- License: CC BY 4.0
- https://creativecommons.org/licenses/by/4.0/
- Credit: "Weather data by Open-Meteo.com".
- https://doi.org/10.5281/ZENODO.7970649

### Mars MOLA (planetary elevation)

- Mars Orbiter Laser Altimeter (MOLA), NASA Planetary Data System (PDS).
- MGS MOLA Mission Experiment Gridded Data Records (MEGDR), `megt{lat}{N/S}{lon}hb.img`, 16 band files, 128 pixels per degree.
- https://pds.nasa.gov/
- Source: NASA / Goddard Space Flight Center. Courtesy of the MOLA Science Team.
- Read row by row over HTTP Range requests; row cache about 12 MB.

### Moon LOLA (planetary elevation)

- Lunar Orbiter Laser Altimeter (LOLA), NASA Planetary Data System (PDS).
- LOLA 128 pixels per degree global DEM, `ldem_128.img`, single global file.
- https://pds.nasa.gov/
- Source: NASA / Goddard Space Flight Center. Courtesy of the LOLA Science Team.
- Read row by row over HTTP Range requests; row cache about 47 MB.

### Planetary imagery (base maps)

- Moon: OpenPlanetaryMap base map.
- Mars: Viking MDIM21 (Mars Digital Image Model 2.1), NASA / USGS.
- https://www.openplanetary.org/
- https://astrogeology.usgs.gov/
- Used for the map view and surface colour when generating off Earth.

</details>
