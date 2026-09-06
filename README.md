# Server Discovery

A Fabric mod for Minecraft 26.2 that adds a **Discovery** button to the vanilla
Multiplayer screen (top-right corner). It opens a grid of server cards merged
from two sources - Lunar Client's curated server directory and a small
custom API - split into **Online** / **Offline** tabs, with:

- A thumbnail per card (Lunar's background image, then its logo, then a live
  ping favicon, then a generic fallback picture, in that order).
- Live ping and player count, and a **Join** / **Add** button per card - Add
  works like vanilla's own "Add Server" screen, and the Multiplayer screen
  updates immediately to show it (no back-out-and-reopen needed).
- A **search box** (name or address) and a **sort button** cycling Most
  Players / Name / Random.
- Duplicate servers (same address from both sources) are merged into one card.

**Why pinging is eager, not lazy:** an earlier version of this mod only
pinged a card once it actually scrolled into view. That broke sorting by
player count for anything not already on screen - an always-full server
like Hypixel would never get pinged (and therefore never sort to the top)
unless you searched its name into view first, which is also why it would
"disappear" again on Refresh. Online/Offline categorization has the exact
same requirement: you can't sort a server into a bucket you haven't pinged
it to determine. So every card starts pinging the moment the list loads,
through a bounded pool (`api/NetworkExecutor.java`, 32 at a time) rather
than firing everything at once - with a few thousand servers in Lunar's
dataset, this can take a couple of minutes to fully settle, and the header
shows live progress while it does. Card *thumbnails* stay lazy (only
fetched once a card is actually scrolled into view) since unlike ping,
that's cosmetic, not something that needs to be true for the whole
dataset at once.

**Why results are cached (`api/DiscoveryCache.java`):** resizing the
window, or just re-opening Discovery, used to mean redoing that whole
multi-thousand-server scan from zero every time - resizing in particular
recreates the entire screen (`Screen.resize` calls `init()` again), which
was wiping the in-progress list and re-triggering everything. Both the
merged directory and each server's last ping result are now cached to
`config/serverdiscovery-cache.json` and shown immediately on a resize or a
fresh open, however old they are, while every card still quietly re-pings
live in the background to keep that state honest - age only decides
whether a background refresh is *also* worth doing (a 15-minute-old
directory triggers one; a 15-second-old one doesn't), never whether to
show what's already known. Hitting **Refresh** always does a live fetch
regardless. Several follow-on bugs from this same area, also fixed:

- Only *successful* pings were being cached at first - a server marked
  offline had to pay the full timeout again on every single re-scan
  (re-entering Discovery, resizing, Refresh), which is why offline counts
  kept looking like they were starting over. Failures are cached too now.
- A `Refresh` (or a resize, which triggers the same fetch) that happened
  to land on a partially-failed attempt - one source timing out while the
  other succeeds, which real logs from this project showed happening -
  would silently replace a full, healthy cached directory with whatever
  smaller, degraded result came back, discarding everything already
  known. `applyResult` now keeps the current list instead of replacing it
  with something under half its size, and no longer wipes the list at all
  on an outright fetch error.
- Following on from that: the directory cache used to stop being usable
  at all once its 15-minute TTL passed (`getDirectory()` returned null
  outright), so a re-entry landing just outside that window, combined
  with a live refetch that *also* failed, had nothing to fall back to -
  which read as "0 online servers" on re-entry, confirmed in a real log
  showing exactly that failed-refetch/stale-cache combination. Retrieval
  no longer has an age limit at all - a resize or re-entry always shows
  the last known directory, however old, and age now only controls
  whether a background refresh is attempted alongside it
  (`isDirectoryFresh()`), so a bad refetch has something real to fall
  back on via the guard above instead of an empty list.
- A handful of addresses can have DNS lookups that hang rather than fail
  outright (`InetAddress.getByName` has no built-in timeout). An earlier
  fix here just wrapped the ping in `CompletableFuture.orTimeout` - that
  stopped the *calling* code from waiting forever, but did nothing about
  the underlying pool thread, which stayed wedged on the hung lookup
  regardless. With enough bad addresses that silently exhausted the whole
  bounded ping pool, and everything queued behind them "completed" via
  the timeout without ever actually being attempted - which is what a
  real log from this project showed: only ~40 of ~2200 servers ever
  went online, even freshly, even with the cache file manually cleared
  to `{}`, because it was never a caching problem to begin with. DNS
  resolution now runs on its own, much larger, separate pool
  (`api/DnsExecutor.java`, 128 threads) via `SlpClient.resolve(host)`,
  and only an already-resolved address is handed to `NetworkExecutor`
  for the actual ping (`SlpClient.pingResolved(...)`) - a hung lookup can
  now only ever tie up a DNS-pool thread, not one everything else
  depends on to get pinged at all.
- A related but distinct bug, found from a log where the directory fetch,
  the default thumbnail fetch, and the custom API *all* failed with
  "connect timed out" at the exact same moment - right as ~2200 pings
  were saturating `NetworkExecutor`, which `api/HttpUtil.java`'s
  `HttpClient` was *also* configured to use for its own callback
  dispatch. `HttpClient` is asynchronous, but it still needs its executor
  to run completion callbacks ("the connection succeeded, continue") -
  with every worker thread tied up in a blocking ping `Socket` call, those
  callbacks could queue behind thousands of them and never get a turn
  within the timeout, even if the TCP connection itself went through
  fine elsewhere. `HttpUtil` now has its own small, dedicated pool (8
  threads - a handful of HTTP fetches never need more), fully isolated
  from ping and DNS traffic.

Also fixed: `NativeImage.read` only understands PNG, confirmed by a real
crash log - it threw `Bad PNG Signature` trying to decode the default
thumbnail, which is a `.jpg` URL. `gui/ImageUtil.java` now falls back to
the JDK's own `ImageIO` (which reads JPEG/GIF/BMP) and re-encodes as PNG
before handing it to `NativeImage`, used for every image this project
decodes, not just the default thumbnail, in case a Lunar-hosted logo or
background is ever misnamed the same way.

Not included, on purpose: sorting by "rating" or server age, and automatic
PVP/SMP/Minigame categories/tags. Neither the Server List Ping protocol nor
Lunar's dataset expose that information, so building it would mean guessing.
Descriptions are still shown; the PVP/SMP/etc-style tags just aren't.

## Requirements to build

- **JDK 25** (Minecraft 26.2 requires it - `java -version` should say 25).
- An internet connection the first time you build, so Gradle can download
  Minecraft, Fabric Loader and Fabric API.

The version numbers in `gradle.properties` were current for Minecraft 26.2
at the time this was put together. If Gradle can't resolve one of them (this
happens - Fabric ships updates often), generate a fresh set at
<https://fabricmc.net/develop/template/> for Minecraft 26.2 and copy the
`minecraft_version` / `loader_version` / `loom_version` / `fabric_api_version`
values it gives you into this project's `gradle.properties`. Nothing else
needs to change.

## Building

```bash
./gradlew build
```

The finished jar shows up at `build/libs/serverdiscovery-1.0.0.jar`.

## Installing into Prism Launcher

1. Open Prism Launcher and select your Fabric instance.
2. **Edit Instance → Mods → Add** and pick the jar from `build/libs/`.
3. Make sure **Fabric API** is also in that instance's mods list - Server
   Discovery depends on it but doesn't bundle it.
4. Launch the instance and open **Multiplayer** - the Discovery button is in
   the top-right corner.

## Where the server list comes from

`config/serverdiscovery.json` (created the first time you run the game with
the mod installed) controls two sources, merged and de-duplicated by address:

- **`mirrorServersUrl`** (blank by default - fill in your own once set up) -
  points at a small, self-hosted static mirror of
  [Lunar Client's server mappings](https://github.com/LunarClient/ServerMappings)
  (a real, community-curated directory of hundreds of servers - names,
  addresses, descriptions). Deliberately **not** Lunar's own CDN: this mod
  never contacts Lunar Client's infrastructure at runtime, only whatever
  URL you host yourself. See `mirror-site/README.md` in this project for
  the one-time GitHub Pages setup (a few minutes, no cost). Left blank,
  this source is skipped entirely - no data, no network call. No
  logos/backgrounds come from this source either (mirroring images for
  ~2500 servers would run into the gigabytes) - cards use each server's
  own live favicon instead, falling back to the shared default thumbnail.
- **`apiUrl`** (default `https://minecraft.multiplayerservers.net/api/v1/servers`) -
  the small custom API used by the open-source
  [ServerBrowser](https://github.com/ExcessiveAmountsOfZombies/ServerBrowser)
  mod. Worth knowing: that directory reads as curated for a particular
  modpack community rather than a general list.
- **`additionalApiUrls`** - an array, empty by default, for any other lists
  in that same small schema you want to add later.

To point `apiUrl` (or an entry in `additionalApiUrls`) at your own list, it
should return JSON in one of these shapes:

```json
[
  {
    "serverName": "Example SMP",
    "ipAddress": "play.example.com",
    "port": 0,
    "description": "A friendly survival server",
    "tags": ["smp", "vanilla"]
  }
]
```

`name`/`ip`/`address`/`host` are also accepted as alternate keys for
`serverName`/`ipAddress`, and the array can be wrapped in `{"servers": [...]}`
instead of being top-level, in case your own API is shaped differently.
`port`, `description` and `tags` are all optional.

## Minecraft 26.2 API notes

This version shipped while a fair amount of internal client code was being
reworked (rendering especially - `GuiGraphics` became `GuiGraphicsExtractor`
with a different method set). Everything in this project has been checked
against the real 26.2 classes via `javap` against the actual mapped jar in
your Gradle cache, not guessed. Worth knowing if you ever update Minecraft
further and something stops compiling again:

- The multiplayer screen is `JoinMultiplayerScreen`, not `MultiplayerScreen`.
- `GuiGraphicsExtractor` has `text(...)`/`centeredText(...)` instead of
  `drawString`/`drawCenteredString`. For drawing a texture, the one this
  project actually uses now is `blit(RenderPipelines.GUI_TEXTURED, texture,
  x, y, u, v, width, height, textureWidth, textureHeight, color)` - u/v/
  textureWidth/textureHeight are **pixel** values (0,0 is the texture's
  top-left corner; textureWidth/textureHeight are its real pixel size,
  matching the classic pre-26.x `blit(x,y,u,v,width,height,textureWidth,
  textureHeight)` convention), and `color` is an ARGB tint - `0xFFFFFFFF`
  for "no tint". This project shipped with two different wrong guesses
  before landing here (a same-named but different 9-argument overload with
  no `RenderPipeline` and no tint exists too, and neither treating its u/v
  as normalized 0-1 fractions nor as raw pixel values against *that*
  overload actually draws a texture correctly - it isn't a general-purpose
  "draw this texture" call). What's here now is confirmed against
  real, currently-published code (TerraformersMC/ModMenu's own icon
  rendering), not inferred - if a future version renames this again, check
  how an actively-maintained GUI-heavy mod on GitHub does it before
  guessing from the method's argument types alone.
- Widgets (`Button`, list widgets) render via `extractWidgetRenderState(...)`;
  `Screen` itself uses `extractRenderState(...)` - two different names for
  what used to both just be `render(...)`. `AbstractButton`'s version of
  that method is `protected`, not `public`, unlike the list widget's -
  `mixin/AbstractButtonInvoker.java` is a small Mixin `@Invoker` interface
  that bridges to it so a manually-positioned button (like the ones on each
  card here) can still be told to render itself.
- `ServerData`'s 3rd constructor argument is a `ServerData.Type` enum, and
  its favicon accessor is `getIconBytes()`/`setIconBytes(byte[])` (raw
  bytes, not a base64 string).
- `ConnectScreen.startConnecting` takes two extra trailing arguments for the
  server-transfer feature: a boolean and a `TransferState` (a record, not an
  enum - `new TransferState(Map.of(), Map.of(), false)` for a normal,
  nothing-carried-over connection).
- Pinging a server no longer goes through `ServerStatusPinger` in this
  project at all - `api/SlpClient.java` implements the Server List Ping
  protocol directly (it's a stable wire protocol, unlike the internal Java
  API around it, which changed shape twice while this was being built). It
  also resolves DNS *before* starting its timer rather than as a side effect
  of `new InetSocketAddress(String, int)` inside the timed section - folding
  hostname-lookup time into "ping" was why displayed numbers were reading
  several seconds high.
- `HttpUtil`'s `HttpClient` is explicitly given `NetworkExecutor` rather
  than left to default to the JVM's shared common pool. A real play
  session's log showed this mod's fetches sitting for minutes behind
  unrelated mods' own network calls (auth lookups, crash-report uploads)
  that were also timing out - blocking network work sharing a pool with
  everything else the game and 200+ other mods might be doing is fragile
  regardless of whose code it is.
- `AbstractSelectionList`'s (the list widget's) constructor is
  `(Minecraft, width, height, y0, itemHeight)` - `height` is the *list's
  own* height, not the screen's. Passing the screen's full height there (an
  earlier version of this project did) was the cause of the Refresh/Exit
  buttons being unclickable - the list was claiming almost the whole screen.
- `EditBox.setHint(Component)` (the greyed-out placeholder text in the
  search box) is the one line added for search/sort that isn't javap-
  confirmed - EditBox itself wasn't touched by the diagnostic pass. Very
  unlikely to have moved (it's long-standing, widely-used API), but if it's
  the one thing that doesn't compile, that's why.

One already-fixed gotcha worth mentioning: the reference project's own
"add to favorites" code creates a `ServerList` and saves it *without*
loading the existing one first, which would wipe out your current server
list. `DiscoveryScreen.addServerToFavorites` calls `serverList.load()` first
so your existing servers are kept.
