/**
 * TellusCN CDN —— TellusCN / Tellus 模组的统一数据源反向代理
 *
 * 部署位置：Cloudflare Worker `telluscn-cdn`，自定义域名 https://telluscn.winster.top
 *
 * 设计要点
 * --------
 * 1. **白名单转发**：每个路由只映射到固定的上游主机，避免变成一个开放代理
 *    （开放代理会被滥用、也会让账号被投诉）。
 * 2. **边缘缓存**：图片/瓦片类走 `caches.default` + `cf.cacheTtl`，命中后不再回源；
 *    带 `Range` 的请求（PMTiles 分块读）**不缓存**，直接透传。
 *    2026-10-11：已确认这是**平台限制**而非取舍——Worker 返回的 `206` 一律不被 Cloudflare 存储
 *    （官方文档 workers/cache/limitations），Cache API 的 put() 对 206 直接抛错。要让边缘帮我们切
 *    Range，唯一办法是**不转发 Range、回整个 200**，但那等于把 180 GB 的 PMTiles 整个拉过 Worker，
 *    代价远超收益。所以大文件不该走这个代理（直连 S3 约 7.4 MB/s，经镜像约 3.5 MB/s）。
 * 3. **Range 透传**：Overture 的 buildings/roads/water 是 PMTiles 单文件，
 *    客户端靠 Range 按需读块，代理必须原样转发 `Range` 并回 `206` + `Content-Range`。
 * 4. **必要请求头**：Nominatim / Overpass 的使用条款要求带可识别的 User-Agent，
 *    而 Java 客户端默认 UA 会被他们拒绝（403），这里统一补上。
 * 5. **CORS + OPTIONS**：浏览器/地图界面直接拉瓦片时要放行。
 *
 * 路由表（`?src=` 与路径前缀两种写法都支持）
 * ------------------------------------------------
 *   /health                               健康检查
 *   /elevation/{z}/{x}/{y}.png            terrarium 高程瓦片（AWS Open Data）
 *   /tiles/{z}/{x}/{y}.png                地图底图，默认 osm
 *   /tiles/{src}/{z}/{x}/{y}.png          指定底图源（见 TILE_SOURCES）
 *   /landcover/{path...}                  ESA WorldCover 10m 2021
 *   /copernicus30/{path...}               Copernicus DEM GLO-30
 *   /copernicus90/{path...}               Copernicus DEM GLO-90
 *   /usgs?bbox=...&size=...               USGS 3DEP Elevation ImageServer
 *   /japangsi/{path...}                   日本 GSI 高程
 *   /arcticdem/{path...}                  ArcticDEM 镶嵌
 *   /rema/{path...}                       REMA 镶嵌
 *   /overpass                             Overpass API（GET/POST 都转发）
 *   /geocoding?q=...                      Nominatim 地名解析
 *   /weather/{path...}                    Open-Meteo 天气
 *   /landmask/{path...}                   Tellus 陆表多边形
 *   /overture/{layer}/{path...}           Overture PMTiles（buildings/roads/water/sand）
 *   /s3/{bucket}/{path...}                白名单内的 S3 桶直通
 */

// arnis 的「Map Theme」清单（src/gui/js/bbox.js 的 tileThemes）——键名保持一致，
// 模组里 mirror.tile_source 直接用这些名字即可。
// 注意 arnis 的现代主题 openfreemap-liberty 是 MapLibre **矢量**样式，
// 栅格瓦片地图用不了，所以这里不列它（详见文档说明）。
const TILE_SOURCES = {
  // —— arnis 的主题名 ——
  'esri-imagery': (z, x, y) => `https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/${z}/${y}/${x}`,
  'opentopomap': (z, x, y) => `https://a.tile.opentopomap.org/${z}/${x}/${y}.png`,
  'stadia-bright': (z, x, y) => `https://tiles.stadiamaps.com/tiles/alidade_smooth/${z}/${x}/${y}.png`,
  'stadia-dark': (z, x, y) => `https://tiles.stadiamaps.com/tiles/alidade_smooth_dark/${z}/${x}/${y}.png`,
  // —— 行星影像（跑在地球之外时 arnis 用它当底图）——
  'moon': (z, x, y) => `https://cartocdn-gusc.global.ssl.fastly.net/opmbuilder/api/v1/map/named/opm-moon-basemap-v0-1/all/${z}/${x}/${y}.png`,
  'mars': (z, x, y) => `https://s3-eu-west-1.amazonaws.com/whereonmars.cartodb.net/viking_mdim21_global/${z}/${x}/${y}.png`,
  // 通用底图
  osm: (z, x, y) => `https://tile.openstreetmap.org/${z}/${x}/${y}.png`,
  osmde: (z, x, y) => `https://tile.openstreetmap.de/${z}/${x}/${y}.png`,
  osmfr: (z, x, y) => `https://a.tile.openstreetmap.fr/hot/${z}/${x}/${y}.png`,
  topo: (z, x, y) => `https://a.tile.opentopomap.org/${z}/${x}/${y}.png`,
  carto: (z, x, y) => `https://a.basemaps.cartocdn.com/light_all/${z}/${x}/${y}.png`,
  cartodark: (z, x, y) => `https://a.basemaps.cartocdn.com/dark_all/${z}/${x}/${y}.png`,
  // 注意：Esri 的路径顺序是 z/y/x
  esri: (z, x, y) => `https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/${z}/${y}/${x}`,
  // 国内可直连的影像/矢量（无需 key 的公开端点）
  amap: (z, x, y) => `https://webrd01.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x=${x}&y=${y}&z=${z}`,
  amapimg: (z, x, y) => `https://webst01.is.autonavi.com/appmaptile?style=6&x=${x}&y=${y}&z=${z}`,
};

// Overture 的瓦片包按发布版本分目录，旧版本会被删掉（上游源码里写死的
// 2026-02-18.0 已经 404，water/sand 用的 overturemaps-tiles-...-beta 桶更是
// 整个 AllAccessDisabled）。这里固定到当前发布版 2026-08-19.0，
// 换版本只要改这一处（或者传 ?release= 覆盖）。
// arnis 的高程源（src/elevation/providers）：Mapterhorn 是全球统一的新源，
// AWS terrarium 是旧源（tellus 用的那个），行星用 NASA PDS 的栅格。
const MAPTERHORN = 'https://tiles.mapterhorn.com';
const PLANETARY_DEM = {
  // 月球：LRO LOLA 全球栅格
  moon: 'https://pds-geosciences.wustl.edu/lro/lro-l-lola-3-rdr-v1/lrolol_1xxx/data/lola_gdr/cylindrical/img',
  // 火星：MGS MOLA 全球栅格
  mars: 'https://pds-geosciences.mars/mgs/mgs-m-mola-5-megdr-l3-v1/mgsl_300x/meg128',
};
// 火星的真实路径（上面那条是占位，见 ROUTE 里的行星段）
PLANETARY_DEM.mars = 'https://pds-geosciences.wustl.edu/mgs/mgs-m-mola-5-megdr-l3-v1/mgsl_300x/meg128';

const OVERTURE_RELEASE = '2026-08-19.0';
const OVERTURE_BASE = 'https://overturemaps-extras-us-west-2.s3.us-west-2.amazonaws.com/tiles';
const OVERTURE_LAYERS = {
  buildings: `${OVERTURE_BASE}/${OVERTURE_RELEASE}/buildings.pmtiles`,
  roads: `${OVERTURE_BASE}/${OVERTURE_RELEASE}/transportation.pmtiles`,
  water: `${OVERTURE_BASE}/${OVERTURE_RELEASE}/base.pmtiles`,
  sand: `${OVERTURE_BASE}/${OVERTURE_RELEASE}/base.pmtiles`,
  base: `${OVERTURE_BASE}/${OVERTURE_RELEASE}/base.pmtiles`,
  addresses: `${OVERTURE_BASE}/${OVERTURE_RELEASE}/addresses.pmtiles`,
  places: `${OVERTURE_BASE}/${OVERTURE_RELEASE}/places.pmtiles`,
  divisions: `${OVERTURE_BASE}/${OVERTURE_RELEASE}/divisions.pmtiles`,
};

/** /s3/<bucket>/... 允许直通的桶（防止被当成任意代理用）。
 *  注意两个桶的 URL 形式不一样：elevation-tiles-prod 是 path-style，
 *  其余几个在 eu-central-1，必须用 virtual-host 形式。 */
const S3_BUCKETS = {
  'elevation-tiles-prod': 'https://s3.amazonaws.com/elevation-tiles-prod',
  'esa-worldcover': 'https://esa-worldcover.s3.eu-central-1.amazonaws.com',
  'copernicus-dem-30m': 'https://copernicus-dem-30m.s3.eu-central-1.amazonaws.com',
  'copernicus-dem-90m': 'https://copernicus-dem-90m.s3.eu-central-1.amazonaws.com',
};

const UA = 'TellusCN-CDN/1.1 (+https://github.com/system32ovo/TellusCN)';
const DEFAULT_TTL = 86400;
const CACHE = caches.default;

/** 缓存安全的响应：只缓存 GET 的无 Range 成功响应。 */
function cacheKeyFor(request) {
  const url = new URL(request.url);
  return new Request(url.origin + url.pathname, { method: 'GET' });
}

function json(body, status = 200) {
  return new Response(JSON.stringify(body, null, 2), {
    status,
    headers: { 'content-type': 'application/json; charset=utf-8', 'access-control-allow-origin': '*' },
  });
}

function fail(status, message) {
  return new Response(`Source Error: ${status} ${message || ''}`.trim(), {
    status,
    headers: { 'access-control-allow-origin': '*', 'cache-control': 'no-store' },
  });
}

/**
 * 统一上游转发。
 *
 * @param {Request} request 原始请求
 * @param {string}  upstream 上游完整 URL
 * @param {object}  opts
 *        - contentType: 期望的 content-type（上游没给对时兜底）
 *        - ttl: 缓存秒数（0 = 不缓存）
 *        - headers: 额外请求头（UA 等）
 *        - forwardRange: 是否透传 Range
 */
async function proxy(request, upstream, opts = {}) {
  const { contentType, ttl = DEFAULT_TTL, headers = {}, forwardRange = true } = opts;
  const range = request.headers.get('Range');
  const isRange = forwardRange && range;

  // 1) 只对「可缓存的 GET」查缓存（带 Range 的不查，避免半截内容被缓存）
  if (request.method === 'GET' && !isRange && ttl > 0) {
    const hit = await CACHE.match(cacheKeyFor(request));
    if (hit) {
      const r = new Response(hit.body, hit);
      r.headers.set('X-Tellus-Cache', 'HIT');
      return r;
    }
  }

  const upHeaders = new Headers();
  upHeaders.set('User-Agent', UA);
  upHeaders.set('Accept-Encoding', 'gzip');
  if (isRange) {
    upHeaders.set('Range', range);
  }
  for (const [k, v] of Object.entries(headers)) {
    upHeaders.set(k, v);
  }

  // 2026-10-11: one edge-side retry for transient upstream trouble.
  //
  //   A single 5xx used to be handed straight to the client. The mod then had to back off
  //   (250 / 500 / 750 ms) and retry from the player's machine, and after 8 consecutive failures it
  //   opens that domain's circuit breaker for 120 s -- during which terrain silently falls back to
  //   approximate heights, which get baked into the saved world.
  //
  //   Retrying here is strictly cheaper: the edge sits closer to the origin, the retry costs the
  //   client nothing, and it happens before any of the mod's failure accounting. Only 5xx and 429
  //   are retried; a 404 is a definite answer and returns at once.
  const RETRY_STATUSES = new Set([429, 500, 502, 503, 504]);
  let upstreamResponse = null;
  let lastError = null;
  let attemptsUsed = 0;
  for (let attempt = 0; attempt < 2; attempt++) {
    attemptsUsed = attempt + 1;
    try {
      upstreamResponse = await fetch(upstream, {
        method: request.method === 'HEAD' ? 'HEAD' : 'GET',
        headers: upHeaders,
        redirect: 'follow',
        cf: ttl > 0 && !isRange ? { cacheTtl: ttl, cacheEverything: true } : undefined,
      });
    } catch (e) {
      lastError = e;
      upstreamResponse = null;
    }
    if (upstreamResponse && !RETRY_STATUSES.has(upstreamResponse.status)) {
      break;   // success, or a definite answer that must not be retried
    }
    if (attempt === 0) {
      await new Promise((resolve) => setTimeout(resolve, 150));
    }
  }
  if (!upstreamResponse) {
    return fail(502, `upstream fetch failed: ${lastError && lastError.message}`);
  }

  const outHeaders = new Headers();
  const passThrough = [
    'content-type', 'content-length', 'content-range', 'accept-ranges',
    'etag', 'last-modified', 'content-encoding',
  ];
  for (const name of passThrough) {
    const value = upstreamResponse.headers.get(name);
    if (value) {
      outHeaders.set(name, value);
    }
  }
  if (!outHeaders.has('content-type') && contentType) {
    outHeaders.set('content-type', contentType);
  }
  outHeaders.set('access-control-allow-origin', '*');
  outHeaders.set('access-control-expose-headers', 'content-range, accept-ranges, x-tellus-cache');
  outHeaders.set('x-tellus-upstream', new URL(upstream).host);
  outHeaders.set('x-tellus-attempts', String(attemptsUsed));
  outHeaders.set('X-Tellus-Cache', 'MISS');

  if (upstreamResponse.status >= 400) {
    // 上游报错时不要缓存，也不要把错误体的长度当成正常响应
    outHeaders.set('cache-control', 'no-store');
    const text = await upstreamResponse.text();
    return new Response(text.slice(0, 400), {
      status: upstreamResponse.status,
      headers: { 'content-type': 'text/plain; charset=utf-8', 'access-control-allow-origin': '*', 'cache-control': 'no-store' },
    });
  }

  // 2026-10-11: TTL is now stated once, with a stale-while-revalidate window.
  //   Two caching layers are at work and BOTH are wanted:
  //     - cf: { cacheTtl } on the upstream fetch  -> caches the SUBREQUEST (keyed by upstream URL)
  //     - this Cache-Control header               -> caches OUR response (keyed by our URL)
  //   They used to be written from the same `ttl` variable, which made it look as though one
  //   overrode the other. They do not; they act at different layers and are kept in sync here.
  //   The SWR window matters to the mod: a tile past its fresh window is served straight from
  //   cache while it refreshes in the background, instead of stalling the player on the origin.
  //   Note cache.put/match ignore stale-while-revalidate -- it is honoured by Cloudflare's own
  //   edge cache (the cf.cacheTtl layer), not by caches.default. Both layers exist, so the
  //   directive does take effect; it is just not the manual Cache API that acts on it.
  if (ttl > 0 && !isRange) {
    const swr = Math.max(60, Math.floor(ttl / 10));
    outHeaders.set('cache-control', `public, max-age=${ttl}, stale-while-revalidate=${swr}, immutable`);
  } else {
    // A 206 fragment must never be marked immutable: the same URL with a different Range
    // returns different bytes.
    outHeaders.set('cache-control', 'public, max-age=300');
  }

  const body = request.method === 'HEAD' ? null : await upstreamResponse.arrayBuffer();
  const response = new Response(body, { status: upstreamResponse.status, headers: outHeaders });

  // 2) 回填边缘缓存（只存完整响应）。
  //    大文件不做手动回填：response.clone() 会把流 tee 成两份，
  //    150 MB 的文件等于同时占两倍内存，照样撞 128 MB 上限。
  //    这类请求由上面 fetch 的 cf.cacheTtl 交给 Cloudflare 自己的边缘缓存。
  const declaredLen = Number(outHeaders.get('content-length') || 0);
  const smallEnough = declaredLen > 0 ? declaredLen <= CACHE_MAX_BYTES : true;
  // 2026-10-11: a 206 CANNOT be cached here. Cloudflare states this explicitly --
  //   "206 Partial Content returned by your Worker is not stored -- Workers Caching expects your
  //    Worker to return the full 200 response and does the range slicing itself."
  //   https://developers.cloudflare.com/workers/cache/limitations/
  // -- and the Cache API's put() throws on a 206 outright.
  //
  // An earlier attempt keyed the cache by path+Range and stored 206s. That cannot work at this
  // layer; verified live, two identical Range requests both returned X-Tellus-Cache: MISS. It is
  // reverted rather than left in, because a lookup that can never hit is pure overhead on the
  // hottest path (PMTiles directory reads).
  //
  // Letting Cloudflare slice ranges for us would mean NOT forwarding Range and returning a full
  // 200 -- which pulls the ENTIRE object through the Worker, and these PMTiles are 180 GB. Not a
  // worthwhile trade. Large files simply should not be routed through this proxy.
  const cacheableStatus = upstreamResponse.status === 200;
  if (request.method === 'GET' && !isRange && ttl > 0 && cacheableStatus && smallEnough) {
    const toCache = response.clone();
    toCache.headers.delete('x-tellus-cache');
    await CACHE.put(cacheKeyFor(request), toCache).catch(() => {});
  }

  return response;
}

function tileSourceFor(request, url) {
  const query = url.searchParams.get('src');
  if (query && TILE_SOURCES[query]) {
    return { name: query, fn: TILE_SOURCES[query] };
  }
  return { name: 'osm', fn: TILE_SOURCES.osm };
}

export default {
  async fetch(request) {
    const url = new URL(request.url);
    const path = url.pathname.replace(/\/+$/, '') || '/';

    if (request.method === 'OPTIONS') {
      return new Response(null, {
        status: 204,
        headers: {
          'access-control-allow-origin': '*',
          'access-control-allow-methods': 'GET, HEAD, POST, OPTIONS',
          'access-control-allow-headers': '*',
          'access-control-max-age': '86400',
        },
      });
    }

    if (path === '/' || path === '/health') {
      return json({
        status: 'ok',
        service: 'TellusCN Cloudflare Worker (unified data proxy)',
        version: '1.4.1',
        routes: [
          '/health', '/elevation/{z}/{x}/{y}.png',
          '/tiles/{z}/{x}/{y}.png', '/tiles/{src}/{z}/{x}/{y}.png',
          '/landcover/...', '/copernicus30/...', '/copernicus90/...',
          '/usgs?...', '/japangsi/...', '/arcticdem/...', '/rema/...',
          '/overpass', '/geocoding?q=...', '/weather/...', '/landmask/...',
          '/overture/{layer}/...', '/s3/{bucket}/...',
        ],
        tileSources: Object.keys(TILE_SOURCES),
        overtureLayers: Object.keys(OVERTURE_LAYERS),
      });
    }

    const rest = path.slice(1);

    // ---------------------------------------------------------- 高程瓦片
    if (path.startsWith('/elevation/')) {
      const tail = rest.slice('elevation/'.length);
      if (!/^\d+\/\d+\/\d+\.png$/.test(tail)) {
        return fail(400, 'expect /elevation/{z}/{x}/{y}.png');
      }
      return proxy(request, `https://s3.amazonaws.com/elevation-tiles-prod/terrarium/${tail}`, {
        contentType: 'image/png', ttl: 604800,
      });
    }

    // ---------------------------------------------------------- 地图底图
    if (path.startsWith('/tiles/')) {
      const parts = rest.split('/');
      let src = null;
      let coord = null;
      if (parts.length === 5 && TILE_SOURCES[parts[1]]) {
        src = parts[1];
        coord = parts.slice(2);
      } else if (parts.length === 4) {
        coord = parts.slice(1);
      }
      if (!coord || !/^\d+$/.test(coord[0]) || !/^\d+$/.test(coord[1]) || !/^\d+\.(png|jpg|jpeg|webp)$/.test(coord[2])) {
        return fail(400, 'expect /tiles/{z}/{x}/{y}.png or /tiles/{src}/{z}/{x}/{y}.png');
      }
      const z = Number(coord[0]);
      const x = Number(coord[1]);
      const y = Number(coord[2].split('.')[0]);
      if (z < 0 || z > 19) {
        return fail(400, 'zoom out of range');
      }
      const chosen = src ? { name: src, fn: TILE_SOURCES[src] } : tileSourceFor(request, url);
      return proxy(request, chosen.fn(z, x, y), { contentType: 'image/png', ttl: 604800 });
    }

    // ---------------------------------------------------------- 地表覆盖
    if (path.startsWith('/landcover/')) {
      const tail = rest.slice('landcover/'.length);
      return proxy(request, `https://esa-worldcover.s3.eu-central-1.amazonaws.com/v200/2021/map/${tail}`, {
        contentType: 'image/tiff', ttl: 604800,
      });
    }

    // --------------------------------------------------- Copernicus DEM
    if (path.startsWith('/copernicus30/')) {
      const tail = rest.slice('copernicus30/'.length);
      return proxy(request, `https://copernicus-dem-30m.s3.eu-central-1.amazonaws.com/${tail}`, {
        contentType: 'image/tiff', ttl: 604800,
      });
    }
    if (path.startsWith('/copernicus90/')) {
      const tail = rest.slice('copernicus90/'.length);
      return proxy(request, `https://copernicus-dem-90m.s3.eu-central-1.amazonaws.com/${tail}`, {
        contentType: 'image/tiff', ttl: 604800,
      });
    }

    // ------------------------------------------------------- USGS 3DEP
    if (path === '/usgs') {
      return proxy(request, `https://elevation.nationalmap.gov/arcgis/rest/services/3DEPElevation/ImageServer/exportImage${url.search}`, {
        contentType: 'image/png', ttl: 86400, forwardRange: false,
      });
    }

    // ---------------------------------------------------------- 日本 GSI
    if (path.startsWith('/japangsi/')) {
      const tail = rest.slice('japangsi/'.length);
      return proxy(request, `https://cyberjapandata.gsi.go.jp/xyz/${tail}`, {
        contentType: 'image/png', ttl: 604800,
      });
    }

    // ------------------------------------------------- ArcticDEM / REMA
    if (path.startsWith('/arcticdem/')) {
      const tail = rest.slice('arcticdem/'.length);
      return proxy(request, `https://pgc-opendata-dems.s3.us-west-2.amazonaws.com/arcticdem/mosaics/v4.1/${tail}`, {
        contentType: 'image/tiff', ttl: 604800,
      });
    }
    if (path.startsWith('/rema/')) {
      const tail = rest.slice('rema/'.length);
      return proxy(request, `https://pgc-opendata-dems.s3.us-west-2.amazonaws.com/rema/mosaics/v2.0/${tail}`, {
        contentType: 'image/tiff', ttl: 604800,
      });
    }

    // ------------------------------------ OpenFreeMap（arnis 的 "Modern" 矢量主题）
    // 矢量样式里的所有 URL 都指向 tiles.openfreemap.org；这里统一改写成我们自己的域名，
    // 于是客户端拿到 style/tilejson 后，后续的瓦片、字形、精灵图都会走我们的代理。
    if (path === '/openfreemap/style' || path === '/openfreemap/planet') {
      const upstream = path === '/openfreemap/style'
        ? 'https://tiles.openfreemap.org/styles/liberty'
        : 'https://tiles.openfreemap.org/planet';
      let body, status;
      try {
        const r = await fetch(upstream, { headers: { 'User-Agent': UA, 'Accept-Encoding': 'gzip' } });
        status = r.status;
        body = await r.text();
      } catch (e) {
        return fail(502, 'openfreemap fetch failed: ' + e.message);
      }
      if (status !== 200) {
        return fail(status, 'openfreemap upstream');
      }
      // 把上游 origin 整体换成本次请求的 origin + /openfreemap
      const rewritten = body.split('https://tiles.openfreemap.org').join(`${url.origin}/openfreemap`);
      return new Response(rewritten, {
        status: 200,
        headers: {
          'content-type': 'application/json; charset=utf-8',
          'access-control-allow-origin': '*',
          'cache-control': 'public, max-age=600',
        },
      });
    }

    // 其余 /openfreemap/* 原样透传：/styles/…/sprite、/fonts/…、/natural_earth/…、
    // /planet/<发布版本>/{z}/{x}/{y}.pbf（矢量瓦片本体）
    if (path.startsWith('/openfreemap/')) {
      const tail = rest.slice('openfreemap/'.length);
      if (!tail) {
        return fail(404, 'empty openfreemap path');
      }
      const isVectorTile = tail.endsWith('.pbf');
      return proxy(request, `https://tiles.openfreemap.org/${tail}`, {
        contentType: isVectorTile ? 'application/x-protobuf' : undefined,
        // 矢量瓦片按发布版本路径缓存，可以长缓存；字体/精灵图同理
        ttl: 604800,
      });
    }

    // ------------------------------------------------ Mapterhorn 高程瓦片
    if (path.startsWith('/mapterhorn/')) {
      const tail = rest.slice('mapterhorn/'.length);
      if (!/^\d+\/\d+\/\d+\.webp$/.test(tail)) {
        return fail(400, 'expect /mapterhorn/{z}/{x}/{y}.webp');
      }
      // 高程瓦片是 WebP（比 terrarium PNG 小很多），按二进制透传即可
      return proxy(request, `${MAPTERHORN}/${tail}`, { contentType: 'image/webp', ttl: 604800 });
    }

    // ------------------------------------------------------ 行星影像 / DEM
    // /planet/{moon|mars}/imagery/{z}/{x}/{y}.png   —— 行星底图（arnis 在地球外的唯一底图）
    // /planet/{moon|mars}/dem/{path...}             —— NASA PDS 全球高程栅格
    if (path.startsWith('/planet/')) {
      const parts = rest.split('/');
      const body = parts[1];
      const kind = parts[2];
      if (!(body === 'moon' || body === 'mars')) {
        return fail(404, 'planet must be moon or mars');
      }
      if (kind === 'imagery') {
        const tail = parts.slice(3).join('/');
        if (!/^\d+\/\d+\/\d+\.png$/.test(tail)) {
          return fail(400, 'expect /planet/{body}/imagery/{z}/{x}/{y}.png');
        }
        return proxy(request, TILE_SOURCES[body](...tail.split('/').map((v, i) => i === 2 ? Number(v.split('.')[0]) : Number(v))),
              { contentType: 'image/png', ttl: 604800 });
      }
      if (kind === 'dem') {
        const tail = parts.slice(3).join('/');
        if (!tail) {
          return fail(400, 'expect /planet/{body}/dem/{path...}');
        }
        return proxy(request, `${PLANETARY_DEM[body]}/${tail}`, {
          contentType: 'application/octet-stream', ttl: 604800,
        });
      }
      return fail(404, 'planet kind must be imagery or dem');
    }

    // ---------------------------------------------------------- Overpass
    if (path === '/overpass') {
      // arnis 自己维护了一个 Overpass 代理（api.arnismc.com），比直连公共实例通得多；
      // 它挂了再退到 overpass-api.de。
      const upstream = `https://api.arnismc.com/overpass/api/interpreter${url.search}`;
      if (request.method === 'POST') {
        const body = await request.arrayBuffer();
        const response = await fetch(upstream, {
          method: 'POST',
          headers: { 'User-Agent': UA, 'content-type': request.headers.get('content-type') || 'application/x-www-form-urlencoded' },
          body,
        });
        return new Response(await response.arrayBuffer(), {
          status: response.status,
          headers: {
            'content-type': response.headers.get('content-type') || 'application/json',
            'access-control-allow-origin': '*',
            'cache-control': 'no-store',
          },
        });
      }
      const primary = await proxy(request, upstream, { ttl: 0, contentType: 'application/json' });
      if (primary.status < 400) {
        return primary;
      }
      return proxy(request, `https://overpass-api.de/api/interpreter${url.search}`,
            { ttl: 0, contentType: 'application/json' });
    }

    // ---------------------------------------------------------- Nominatim
    if (path === '/geocoding') {
      // Nominatim 条款要求可识别 UA；同时限制 q 长度，避免被当成通用抓取入口
      const q = url.searchParams.get('q') || '';
      if (q.length === 0 || q.length > 200) {
        return fail(400, 'q 参数必填且不超过 200 字符');
      }
      const params = new URLSearchParams({
        q,
        format: 'jsonv2',
        limit: url.searchParams.get('limit') || '5',
        'accept-language': url.searchParams.get('lang') || 'zh-CN,zh,en',
      });
      return proxy(request, `https://nominatim.openstreetmap.org/search?${params}`, {
        ttl: 86400, contentType: 'application/json', forwardRange: false,
      });
    }

    // ------------------------------------------------------------ 天气
    if (path.startsWith('/weather')) {
      const tail = rest.slice('weather'.length); // 可能是 '' 或 '/forecast'
      return proxy(request, `https://api.open-meteo.com/v1${tail}${url.search}`, {
        ttl: 1800, contentType: 'application/json', forwardRange: false,
      });
    }

    // ---------------------------------------------------------- 陆表掩膜
    if (path.startsWith('/landmask/')) {
      const tail = rest.slice('landmask/'.length);
      return proxy(request, `https://github.com/Yucareux/Tellus-Land-Polygons/releases/download/v1.0.0/${tail}`, {
        contentType: 'application/octet-stream', ttl: 604800,
      });
    }

    // ---------------------------------------------------- Overture PMTiles
    if (path.startsWith('/overture/')) {
      const parts = rest.split('/');
      const layer = parts[1];
      if (!OVERTURE_LAYERS[layer]) {
        return fail(404, `unknown overture layer: ${layer} (可用: ${Object.keys(OVERTURE_LAYERS).join('/')})`);
      }
      // PMTiles 客户端用 Range 读块，必须原样透传；路径里剩下的部分拼到上游末尾
      //
      // ttl stays 0: PMTiles is read with Range and a 206 cannot be stored by the Cache API (see
      // proxy()). A TTL here would also put cf.cacheTtl on the subrequest, and these objects are
      // 180 GB -- asking the edge to hold that is worse than not caching.
      // The route exists for correctness; the mod's defaults do not send large files through it.
      const tail = parts.slice(2).join('/');
      const upstream = tail ? `${OVERTURE_LAYERS[layer]}/${tail}` : OVERTURE_LAYERS[layer];
      return proxy(request, upstream, { contentType: 'application/octet-stream', ttl: 0 });
    }

    // ------------------------------------------------------- S3 白名单
    if (path.startsWith('/s3/')) {
      const parts = rest.split('/');
      const bucket = parts[1];
      const base = S3_BUCKETS[bucket];
      if (!base) {
        return fail(403, `bucket not allowed: ${bucket}`);
      }
      const tail = parts.slice(2).join('/');
      return proxy(request, `${base}/${tail}${url.search}`, {
        contentType: 'application/octet-stream', ttl: 86400,
      });
    }

    return fail(404, `no route for ${path}`);
  },
};
