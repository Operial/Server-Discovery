package com.xaria.serverdiscovery.gui;

import com.xaria.serverdiscovery.ServerDiscoveryMod;
import com.xaria.serverdiscovery.api.DiscoveryCache;
import com.xaria.serverdiscovery.api.ServerMappingsClient;
import com.xaria.serverdiscovery.api.ServerDirectoryClient;
import com.xaria.serverdiscovery.api.ServerListing;
import com.xaria.serverdiscovery.config.DiscoveryConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.client.multiplayer.TransferState;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * The screen opened by the "Discovery" button on the vanilla Multiplayer
 * screen. Fetches {@link ServerListing}s from Lunar's curated mappings and a
 * small custom-schema API, merges and de-duplicates them, and shows them as
 * a grid of cards split into Online/Offline tabs.
 */
public class DiscoveryScreen extends Screen {

    private final Screen previousScreen;
    private DiscoveryConfig config;
    private DiscoveryServerList list;
    private EditBox searchBox;
    private Button sortButton;
    private Button onlineTabButton;
    private Button offlineTabButton;
    private Component statusMessage;
    private boolean addedAnyServer = false;
    private boolean loadComplete = false;

    public DiscoveryScreen(Screen previousScreen) {
        super(Component.translatable("serverdiscovery.screen.title"));
        this.previousScreen = previousScreen;
    }

    @Override
    protected void init() {
        this.config = DiscoveryConfig.load();

        int tabsY = 24;
        this.onlineTabButton = this.addRenderableWidget(Button.builder(Component.literal(""),
                button -> setShowOnline(true)).bounds(8, tabsY, 90, 18).build());
        this.offlineTabButton = this.addRenderableWidget(Button.builder(Component.literal(""),
                button -> setShowOnline(false)).bounds(100, tabsY, 90, 18).build());
        this.sortButton = this.addRenderableWidget(Button.builder(sortButtonLabel(), button -> cycleSortMode())
                .bounds(this.width - 8 - 100, tabsY - 2, 100, 20).build());

        int searchY = 46;
        int searchWidth = this.width - 16;
        this.searchBox = new EditBox(this.font, 8, searchY, searchWidth, 16,
                Component.translatable("serverdiscovery.search.hint"));
        this.searchBox.setHint(Component.translatable("serverdiscovery.search.hint"));
        this.searchBox.setResponder(query -> this.list.setSearchQuery(query));
        this.addRenderableWidget(this.searchBox);

        // NOTE (list dimensions): AbstractSelectionList's 5-arg constructor
        // is (Minecraft, width, height, y0, itemHeight) - height here is the
        // LIST'S OWN height, not the screen's.
        int listTop = 78;
        int listHeight = this.height - listTop - 36;
        this.list = new DiscoveryServerList(this, this.minecraft, this.width, listHeight, listTop, DiscoveryServerList.ROW_HEIGHT);
        this.addWidget(this.list);

        this.addRenderableWidget(Button.builder(Component.translatable("serverdiscovery.button.refresh"),
                button -> refresh()).bounds(this.width / 2 - 154, this.height - 28, 100, 20).build());
        this.addRenderableWidget(Button.builder(Component.translatable("serverdiscovery.button.exit"),
                button -> this.onClose()).bounds(this.width / 2 + 54, this.height - 28, 100, 20).build());

        updateTabButtons();
        this.setInitialFocus(this.searchBox);
        // A resize recreates this whole screen (Screen.resize -> init()),
        // which used to mean an unconditional fresh fetch every time - the
        // window-mode/minimize bug where the list emptied out and pinging
        // restarted from zero. Whatever's cached (this session or a
        // previous one - see DiscoveryCache) shows immediately regardless
        // of its age; each card still re-pings live in the background
        // regardless (see ServerCard.startPing), so this never shows
        // stale-forever data, just avoids waiting on nothing while a fetch
        // completes. A background refresh still runs whenever the cache
        // isn't fresh enough to skip it - but by then the list already has
        // a real baseline to protect itself with (see applyResult's
        // degraded-fetch guard), instead of a brand new, empty one. That
        // gap - stale cache expired AND the live refetch also failing -
        // used to compound into an empty screen on re-entry; it can't
        // anymore, because showing the cache no longer depends on whether
        // a refresh is also about to be attempted.
        List<ServerListing> cachedDirectory = DiscoveryCache.getDirectory();
        boolean directoryWasFresh = DiscoveryCache.isDirectoryFresh();
        if (cachedDirectory != null) {
            showCachedDirectory(cachedDirectory);
        }
        if (cachedDirectory == null || !directoryWasFresh) {
            refresh();
        }
    }

    private void showCachedDirectory(List<ServerListing> listings) {
        this.statusMessage = listings.isEmpty() ? Component.translatable("serverdiscovery.status.empty") : null;
        this.list.setListings(listings);
    }

    private void setShowOnline(boolean online) {
        this.list.setShowOnline(online);
        updateTabButtons();
    }

    private void updateTabButtons() {
        boolean online = this.list.isShowingOnline();
        this.onlineTabButton.setMessage(Component.translatable("serverdiscovery.tab.online",
                this.list.countByCategory(DiscoveryServerList.Category.ONLINE)));
        this.offlineTabButton.setMessage(Component.translatable("serverdiscovery.tab.offline",
                this.list.countByCategory(DiscoveryServerList.Category.OFFLINE)));
        this.onlineTabButton.active = !online;
        this.offlineTabButton.active = online;
    }

    private void cycleSortMode() {
        DiscoveryServerList.SortMode[] modes = DiscoveryServerList.SortMode.values();
        int next = (this.list.getSortMode().ordinal() + 1) % modes.length;
        this.list.setSortMode(modes[next]);
        this.sortButton.setMessage(sortButtonLabel());
    }

    private Component sortButtonLabel() {
        DiscoveryServerList.SortMode mode = this.list != null ? this.list.getSortMode() : DiscoveryServerList.SortMode.MOST_PLAYERS;
        return switch (mode) {
            case MOST_PLAYERS -> Component.translatable("serverdiscovery.sort.players");
            case NAME -> Component.translatable("serverdiscovery.sort.name");
            case RANDOM -> Component.translatable("serverdiscovery.sort.random");
        };
    }

    /** Called by DiscoveryServerList whenever counts/categories change (ping resolves, search, sort, tab switch). */
    public void onListStateChanged() {
        if (this.onlineTabButton != null) {
            updateTabButtons();
        }
        int total = this.list.getTotalCount();
        int resolved = this.list.getResolvedCount();
        if (!loadComplete && total > 0 && resolved >= total) {
            loadComplete = true;
        }
        if (total == 0) {
            this.statusMessage = null;
        } else if (resolved < total) {
            this.statusMessage = Component.translatable("serverdiscovery.status.checking", resolved, total);
        } else {
            this.statusMessage = null;
        }
    }

    private void refresh() {
        this.loadComplete = false;
        this.statusMessage = Component.translatable("serverdiscovery.status.loading");

        CompletableFuture<List<ServerListing>> mirrorFuture = ServerMappingsClient.fetch(this.config.mirrorServersUrl)
                .exceptionally(e -> {
                    ServerDiscoveryMod.LOGGER.warn("Server mappings mirror fetch failed", e);
                    return List.of();
                });

        List<CompletableFuture<List<ServerListing>>> customFutures = new ArrayList<>();
        customFutures.add(fetchCustom(this.config.apiUrl));
        for (String extra : this.config.additionalApiUrls) {
            customFutures.add(fetchCustom(extra));
        }
        CompletableFuture<List<ServerListing>> allCustom = CompletableFuture
                .allOf(customFutures.toArray(new CompletableFuture[0]))
                .thenApply(v -> customFutures.stream().map(CompletableFuture::join).flatMap(List::stream).toList());

        mirrorFuture.thenCombine(allCustom, DiscoveryScreen::dedupe)
                .whenComplete((merged, error) -> Minecraft.getInstance().execute(() -> applyResult(merged, error)));
    }

    private static CompletableFuture<List<ServerListing>> fetchCustom(String url) {
        return ServerDirectoryClient.fetch(url).exceptionally(e -> {
            ServerDiscoveryMod.LOGGER.warn("Directory fetch failed: {}", url, e);
            return List.of();
        });
    }

    /** Merges two source lists, keeping the first occurrence of each address (Lunar's entries win on overlap). */
    private static List<ServerListing> dedupe(List<ServerListing> primary, List<ServerListing> secondary) {
        Map<String, ServerListing> byKey = new LinkedHashMap<>();
        for (ServerListing listing : primary) {
            byKey.putIfAbsent(listing.dedupeKey(), listing);
        }
        for (ServerListing listing : secondary) {
            byKey.putIfAbsent(listing.dedupeKey(), listing);
        }
        return new ArrayList<>(byKey.values());
    }

    private void applyResult(List<ServerListing> listings, Throwable error) {
        if (error != null) {
            ServerDiscoveryMod.LOGGER.warn("Failed to load the server directory", error);
            this.statusMessage = Component.translatable("serverdiscovery.status.error");
            // Deliberately NOT clearing the list here anymore - it used to
            // wipe everything on any fetch error, which combined with
            // Refresh always doing a live fetch meant one bad attempt threw
            // away a perfectly good, already-displayed directory. Leaving
            // it alone just means Refresh didn't get fresher data this time.
            return;
        }

        int currentTotal = this.list.getTotalCount();
        if (currentTotal > 0 && listings.size() < currentTotal / 2) {
            // This fetch came back with under half of what's already
            // showing - in practice that's one source timing out on this
            // particular attempt (seen directly in logs: Lunar or the
            // custom API failing while the other succeeds), not the real
            // directory actually shrinking. Keep what's already displayed
            // rather than discarding known-good data over one bad fetch -
            // this is the "Refresh gets rid of all the offline saved
            // servers" bug.
            ServerDiscoveryMod.LOGGER.warn("New directory fetch returned {} servers vs {} already showing - keeping the current list",
                    listings.size(), currentTotal);
            this.statusMessage = null;
            return;
        }

        DiscoveryCache.putDirectory(listings);
        this.statusMessage = listings.isEmpty() ? Component.translatable("serverdiscovery.status.empty") : null;
        this.list.setListings(listings);
    }

    /** Called by a card's Join button. */
    public void joinServer(ServerData serverData) {
        TransferState noTransfer = new TransferState(Map.of(), Map.of(), false);
        ConnectScreen.startConnecting(this.previousScreen, this.minecraft,
                ServerAddress.parseString(serverData.ip), serverData, false, noTransfer);
    }

    /** Called by a card's Add button - mirrors vanilla's own "Add Server" flow. */
    public void addServerToFavorites(ServerListing listing) {
        ServerList serverList = new ServerList(this.minecraft);
        serverList.load();
        ServerData data = new ServerData(listing.name(), listing.address(), ServerData.Type.OTHER);
        serverList.add(data, false);
        serverList.save();
        this.addedAnyServer = true;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.list.refreshIfDirty();
        super.extractRenderState(guiGraphics, mouseX, mouseY, partialTick);
        this.list.extractWidgetRenderState(guiGraphics, mouseX, mouseY, partialTick);
        guiGraphics.centeredText(this.font, this.title, this.width / 2, 8, 0xFFFFFFFF);
        if (this.statusMessage != null) {
            guiGraphics.text(this.font, this.statusMessage, 8, 65, 0xFFAAAAAA);
        }
    }

    @Override
    public void onClose() {
        if (this.addedAnyServer) {
            // Force the Multiplayer screen to fully reinitialize - the same
            // path a window resize takes - so a server added here shows up
            // immediately instead of only the next time it's opened.
            this.previousScreen.resize(this.width, this.height);
        }
        this.minecraft.gui.setScreen(this.previousScreen);
    }
}
