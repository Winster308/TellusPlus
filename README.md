# Tellus Plus

[中文说明](README.zh_cn.md) · [English](README.md)

Tellus Plus is a fork of [Tellus](https://modrinth.com/mod/tellus) by [Yucareux](https://github.com/Yucareux), adapted for players in mainland China and extended with a genuinely 1:1 Earth scale mode, a fully localised interface, and a hardened download layer.

Tellus generates real-world terrain in Minecraft by streaming open geographic datasets into chunks as they are created. It aims to turn Minecraft into a playable, voxelised map of the Earth.

![Everest](docs/modrinth/icon-1024.png)

## What Tellus Plus adds over upstream Tellus

Everything below is a change relative to upstream. The terrain generation itself is upstream's work; this fork makes it reachable, stable and readable for a different audience.

### Interface is localised

Upstream ships English. Tellus Plus ships **English, Simplified Chinese and Spanish** (`en_us`, `zh_cn`, `es_es`), covering every player-facing string — not just the Mod Menu screen but the HUD, the terrain preview progress text, the command feedback and the error messages.

The interface strings that used to be hard-coded in the source (about 267 of them) now go through the translation system, so switching the game language switches all of it.

### Downloads are hardened

This is the area with the most work, because the failure modes are invisible until they have already damaged a world.

| Change | Why |
|---|---|
| **Single-flight de-duplication** | Concurrent requests for the same tile are merged into one download instead of each opening its own connection |
| **Transient-failure retry** | TLS handshakes cut short, socket timeouts, connection resets and 5xx/429 are retried with backoff. A definite answer (404) is not |
| **Per-domain circuit breaker** | A failing data source only silences itself. It used to trip a single global breaker that took healthy sources down with it, degrading large areas to fallback terrain |
| **Cancellation no longer counts as failure** | Cancellations are issued by the mod's own bookkeeping when the last waiter leaves. Counting them as server failures meant the breaker tripped on our own accounting |
| **Fixed a leaked in-flight entry** | A task cancelled while still queued never ran its cleanup, so its entry stayed in the in-flight map forever and every later request for that tile failed instantly. Measured: `deduped 67007` against `cancelled 66675` — almost every de-duplicated request was joining a dead entry |
| **Land-cover read timeout raised 8 s → 30 s** | An ESA WorldCover tile is a 100–150 MB GeoTIFF needing 30–75 s of transfer. The 8 s read timeout applied per read call, so a healthy download that stalled once mid-stream was aborted and counted as a failure. Eight in a row opened a 120 s circuit |

### Spawn no longer blocks on the network

Spawn selection used to sample land cover over the network for every candidate column. Measured on 2026-10-10: **18 minutes 40 seconds** spent in `Selecting global world spawn...`, with 1193 log lines of tile downloads in that window. The pool saturated, the breaker tripped, and every further sample paid the full exception path without ever succeeding.

Spawn sampling is now **local-only**: it reads cache and falls back to a defined default on a miss, and never submits a download. Measured afterwards: **3 minutes 44 seconds**.

### True 1:1 scale

Upstream supports scaling the Earth down. Tellus Plus additionally supports **1 block = 1 metre with the ocean uncompressed**, which requires a world taller than Minecraft's coordinate packing allows by default.

| | Value |
|---|---|
| Vertical range | `minY = -11040` to `top = 21599` |
| Span | 32640 blocks = 2040 sections per chunk |
| Mariana Trench | Y = −10970 |
| Mount Everest | Y = 8912 |
| Coordinate packing | Repacked to X25 / Y15 / Z24 = 64/64 bits |

**This is not free.** A 1:1 chunk has 2040 sections against vanilla's 24, so entering a world takes minutes and memory use is dominated by light data. See [Performance](#performance) for the numbers.

### A mirror proxy is included

Upstream connects directly to global data providers, which is slow or unreachable from mainland China. This fork ships a Cloudflare Workers reverse proxy (`worker/worker.js`) that whitelists specific upstream hosts, caches tiles at the edge, and retries transient upstream errors once before the client ever sees them.

The proxy is not a general-purpose forwarder: each route maps to one fixed upstream host, so it cannot be abused as an open proxy.

## Performance

A 1:1 world is heavy, and the cost is concentrated in two places that cannot be optimised away without giving up the scale.

| Item | Cost |
|---|---|
| `fill` per chunk | ~509 ms |
| Solid-rock fill (the largest single item) | ~140 ms, 981 sections × 4096 blocks = 4.02 M writes |
| Spawn area (625 chunks) | ~5 minutes |

The solid-rock fill is the price of the trench being genuinely at −10970: there must be 11040 blocks of rock beneath it. Reducing it means raising `minY`, which truncates the trench and is no longer 1:1.

### Recommended hardware

| | Minimum | Recommended | Comfortable |
|---|---|---|---|
| CPU | 4 cores / 4 threads | 4c/8t or 6 cores | 8 cores / 16 threads |
| RAM | 8 GB | 12 GB | 16–32 GB |
| Heap (`-Xmx`) | 4 GB | 6–8 GB | 10–12 GB |
| VRAM | 2 GB | 2 GB | 4 GB |
| Render distance | 8 | 12 | 16 |

Memory is dominated by light data: 2040 sections × 2 layers × 2048 B ≈ 8 MB per chunk.

## Requirements

- **Minecraft 1.21.9** with **Fabric Loader 0.19.5+**
- **Java 21**
- **Fabric API**
- An **active internet connection** — Tellus Plus does not work offline

**Recommended, and close to essential** at Earth scale:
- [Distant Horizons](https://modrinth.com/mod/distanthorizons) for terrain beyond vanilla view distance
- [Voxy](https://modrinth.com/mod/voxy) for real-time chunk ingestion

## Installation notes

- **Single-player first.** Create the world in single-player, then move the world directory to a server with the mod installed, so new chunks generate with Tellus Plus.
- **Server-side is required**, client-side is optional.
- **A 1:1 world must be created with this mod's world creation screen.** The height is written into `level.dat` and validated on load, so an existing world cannot be changed to 1:1 after the fact.

## Survival status

Some survival features are still missing. A survival world is possible, but upcoming updates may break it — for now Tellus Plus is better suited to testing and exploration than to long-term survival.

## Commands

| Command | Effect |
|---|---|
| `/tellus map` | Opens the GeoTP map UI (requires permission) |
| `/tellus weather` | Shows local Tellus weather and time |
| `/tellus storage` | Inspects and manages the preload ledger |

## Configuration

In-game settings are at **Mod Menu → Tellus Plus → Config**: data source, celestial body, map theme, cache usage and box-select deletion.

Layout parameters (world height, mirror routes) live in `config/telluscn-layout.properties`. The `telluscn` prefix is retained deliberately — renaming those files would break existing worlds and launch arguments.

## Credits and licence

Based on [Tellus](https://modrinth.com/mod/tellus) by Yucareux, and partially on Gegy's [Terrarium](https://modrinth.com/mod/terrarium).

Distributed under **LGPL-3.0-only**. See [LICENSE](LICENSE) and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

