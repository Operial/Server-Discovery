package com.xaria.serverdiscovery.gui;

import com.mojang.blaze3d.platform.NativeImage;
import com.xaria.serverdiscovery.ServerDiscoveryMod;
import com.xaria.serverdiscovery.api.DiscoveryCache;
import com.xaria.serverdiscovery.api.DnsExecutor;
import com.xaria.serverdiscovery.api.HttpUtil;
import com.xaria.serverdiscovery.api.NetworkExecutor;
import com.xaria.serverdiscovery.api.ServerListing;
import com.xaria.serverdiscovery.api.SlpClient;
import com.xaria.serverdiscovery.mixin.AbstractButtonInvoker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * A grid of server cards, split into Online/Offline categories.
 *
 * <p>Pinging is EAGER and bounded, not lazy-on-render: every card starts
 * pinging (via the shared {@link com.xaria.serverdiscovery.api.NetworkExecutor})
 * the moment the directory loads, regardless of whether it's currently scrolled into view. That's a
 * deliberate change from an earlier version of this file, which only pinged
 * a card once it actually rendered - which meant sorting by player count
 * was broken for anything not already on screen (an always-full server
 * like Hypixel would never get pinged, and therefore never sort to the top,
 * unless you searched its name into view first). Categorizing into Online/
 * Offline has the same requirement: you can't know which bucket a card
 * belongs in without having pinged it, so every card needs a ping
 * regardless of visibility.
 *
 * <p>Card *images* stay lazy (fetched only once a card actually renders) -
 * unlike ping/category, they're cosmetic, and eagerly fetching thousands of
 * images at once is exactly the kind of request storm that made icons not
 * show up before.
 */
public class DiscoveryServerList extends ObjectSelectionList<DiscoveryServerList.Entry> {

    private static final Logger LOGGER = LoggerFactory.getLogger("serverdiscovery/list");
    public static final int ROW_HEIGHT = 96;
    private static final int CARD_TARGET_WIDTH = 170;
    private static final int CARD_GAP = 6;
    private static final int IMAGE_HEIGHT = 52;
    private static final long RESORT_THROTTLE_MS = 300;

    public enum SortMode {
        MOST_PLAYERS, NAME, RANDOM
    }

    public enum Category {
        PENDING, ONLINE, OFFLINE
    }

    private final DiscoveryScreen screen;
    private final List<ServerCard> allCards = new ArrayList<>();
    private SortMode sortMode = SortMode.MOST_PLAYERS;
    private String searchQuery = "";
    private boolean showOnline = true;
    private volatile boolean dirty = false;
    private long lastResortTime = 0;

    public DiscoveryServerList(DiscoveryScreen screen, Minecraft minecraft, int width, int height, int y0, int itemHeight) {
        super(minecraft, width, height, y0, itemHeight);
        this.screen = screen;
    }

    @Override
    public int getRowWidth() {
        return this.width - 24;
    }

    @Override
    protected void extractSelection(GuiGraphicsExtractor guiGraphics, Entry entry, int index) {
        // Deliberately empty: the base class draws a full-row highlight
        // behind whichever entry is "selected", but a row here can hold
        // several unrelated cards side by side - that highlight would cover
        // all of them instead of just the one the user clicked, which is
        // the "clicking Add highlights the whole row" bug. Each card
        // already gets its own visual feedback from its own rendering, so
        // suppressing the generic one is correct here, not a lost feature.
    }

    /** Replaces the whole directory (called on initial load / manual refresh) and starts pinging every card. */
    public void setListings(List<ServerListing> listings) {
        this.allCards.clear();
        for (ServerListing listing : listings) {
            ServerCard card = new ServerCard(listing);
            this.allCards.add(card);
            card.startPing();
        }
        applyFilterAndSort();
    }

    public void setSortMode(SortMode mode) {
        this.sortMode = mode;
        applyFilterAndSort();
    }

    public SortMode getSortMode() {
        return sortMode;
    }

    public void setSearchQuery(String query) {
        this.searchQuery = query == null ? "" : query;
        applyFilterAndSort();
    }

    public void setShowOnline(boolean showOnline) {
        this.showOnline = showOnline;
        applyFilterAndSort();
    }

    public boolean isShowingOnline() {
        return showOnline;
    }

    public int getTotalCount() {
        return allCards.size();
    }

    public int getResolvedCount() {
        int count = 0;
        for (ServerCard card : allCards) {
            if (card.category() != Category.PENDING) {
                count++;
            }
        }
        return count;
    }

    public int countByCategory(Category category) {
        int count = 0;
        for (ServerCard card : allCards) {
            if (card.category() == category) {
                count++;
            }
        }
        return count;
    }

    /** Called once per frame by DiscoveryScreen - coalesces many ping completions into one re-sort. */
    public void refreshIfDirty() {
        if (!dirty) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastResortTime < RESORT_THROTTLE_MS) {
            return;
        }
        dirty = false;
        lastResortTime = now;
        applyFilterAndSort();
    }

    private void applyFilterAndSort() {
        String query = searchQuery.trim().toLowerCase(Locale.ROOT);
        Category wanted = showOnline ? Category.ONLINE : Category.OFFLINE;

        List<ServerCard> visible = new ArrayList<>();
        for (ServerCard card : allCards) {
            if (card.category() != wanted) {
                continue;
            }
            if (query.isEmpty()
                    || card.listing.name().toLowerCase(Locale.ROOT).contains(query)
                    || card.listing.address().toLowerCase(Locale.ROOT).contains(query)) {
                visible.add(card);
            }
        }
        switch (sortMode) {
            case MOST_PLAYERS -> visible.sort(Comparator.comparingInt(ServerCard::onlinePlayers).reversed());
            case NAME -> visible.sort(Comparator.comparing(c -> c.listing.name().toLowerCase(Locale.ROOT)));
            case RANDOM -> visible.sort(Comparator.comparingDouble(c -> c.randomKey));
        }

        int columns = Math.max(1, (getRowWidth() + CARD_GAP) / (CARD_TARGET_WIDTH + CARD_GAP));
        List<Entry> rows = new ArrayList<>();
        for (int i = 0; i < visible.size(); i += columns) {
            rows.add(new Entry(visible.subList(i, Math.min(i + columns, visible.size())), columns));
        }
        this.replaceEntries(rows);
        this.screen.onListStateChanged();
    }

    /** Per-server state: ping/category, images, and its own Join/Add buttons. Survives re-sorts and re-grouping. */
    public class ServerCard {

        final ServerListing listing;
        private final ServerData joinTarget;
        private final Button joinButton;
        private final Button addButton;
        private final Identifier imageId;
        private final double randomKey = Math.random();

        private boolean imageFetchStarted = false;
        private byte[] lastUploadedSource;
        private int imageWidth = 1;
        private int imageHeight = 1;
        private volatile long pingMillis = -2; // -2 = pending, -1 = offline, >=0 = ms
        private volatile int onlinePlayers = -1;
        private volatile int maxPlayers = -1;
        private volatile byte[] favoriteIconBytes; // from the live ping's favicon
        private volatile byte[] fetchedImageBytes; // from Lunar's background/logo

        ServerCard(ServerListing listing) {
            this.listing = listing;
            this.joinTarget = new ServerData(listing.name(), listing.address(), ServerData.Type.OTHER);
            this.imageId = Identifier.fromNamespaceAndPath(ServerDiscoveryMod.MOD_ID,
                    "cardimg/" + Integer.toHexString(listing.address().hashCode()));

            this.joinButton = Button.builder(Component.translatable("serverdiscovery.button.join"),
                    button -> screen.joinServer(joinTarget)).bounds(0, 0, 40, 16).build();
            this.addButton = Button.builder(Component.translatable("serverdiscovery.button.add"),
                    button -> {
                        screen.addServerToFavorites(listing);
                        button.setMessage(Component.translatable("serverdiscovery.button.added"));
                        button.active = false;
                    }).bounds(0, 0, 40, 16).build();
        }

        Category category() {
            if (pingMillis == -2) {
                return Category.PENDING;
            }
            return pingMillis >= 0 ? Category.ONLINE : Category.OFFLINE;
        }

        int onlinePlayers() {
            return onlinePlayers;
        }

        void startPing() {
            String address = listing.address();
            // A recent cached result (from a prior open this session, or a
            // previous game session on disk) shows immediately instead of
            // "..." while the live check below runs - see DiscoveryCache.
            DiscoveryCache.PingEntry cached = DiscoveryCache.getPing(address);
            if (cached != null) {
                pingMillis = cached.pingMillis();
                onlinePlayers = cached.onlinePlayers();
            }
            ServerAddress serverAddress = ServerAddress.parseString(address);
            String host = serverAddress.getHost();
            int port = serverAddress.getPort();
            // DNS resolution runs on its own, separately-sized pool
            // (DnsExecutor) and only the already-resolved address is handed
            // to NetworkExecutor for the actual ping - see DnsExecutor's
            // class comment for why this split matters. A DNS lookup that
            // hangs can now only ever tie up a DNS-pool thread; it can't
            // also consume one of the ping-pool's threads and starve
            // everything queued behind it.
            CompletableFuture.supplyAsync(() -> {
                try {
                    return SlpClient.resolve(host);
                } catch (Exception e) {
                    return null;
                }
            }, DnsExecutor.INSTANCE)
                    .orTimeout(5, TimeUnit.SECONDS)
                    .exceptionally(e -> null)
                    .thenComposeAsync(resolved -> {
                        if (resolved == null) {
                            return CompletableFuture.<SlpClient.Result>completedFuture(null);
                        }
                        return CompletableFuture.supplyAsync(() -> {
                            try {
                                return SlpClient.pingResolved(resolved, host, port);
                            } catch (Exception e) {
                                return null;
                            }
                        }, NetworkExecutor.INSTANCE);
                    }, NetworkExecutor.INSTANCE)
                    // DNS gets up to 5s (above); ping itself is bounded by
                    // SlpClient's own internal connect/read timeouts (a few
                    // seconds). This outer ceiling covers both stages
                    // back-to-back rather than restarting a fresh clock
                    // after DNS resolves - orTimeout's deadline runs from
                    // when it's called, not from when the prior stage
                    // finishes, so it needs to be big enough for the worst
                    // case of both added together.
                    .orTimeout(12, TimeUnit.SECONDS)
                    .whenComplete((result, error) -> Minecraft.getInstance().execute(() -> {
                        if (result == null || error != null) {
                            // A single failed live check doesn't erase a
                            // recent, presumably-still-good cached value -
                            // only fall back to "offline" when there was
                            // nothing to fall back on.
                            if (cached == null) {
                                pingMillis = -1;
                            }
                            // Cache the failure too, not just successes -
                            // otherwise an offline server can never
                            // fast-path from cache and always pays the
                            // full timeout again on every re-scan (Refresh,
                            // resizing, re-opening Discovery).
                            DiscoveryCache.putPing(address, -1, -1);
                        } else {
                            pingMillis = result.pingMillis();
                            onlinePlayers = result.onlinePlayers();
                            maxPlayers = result.maxPlayers();
                            favoriteIconBytes = result.iconBytes();
                            DiscoveryCache.putPing(address, result.pingMillis(), result.onlinePlayers());
                        }
                        DiscoveryServerList.this.dirty = true;
                    }));
        }

        void ensureImageFetchStarted() {
            if (imageFetchStarted) {
                return;
            }
            imageFetchStarted = true;
            String primary = listing.backgroundUrl();
            String secondary = listing.logoUrl();
            if (primary == null && secondary == null) {
                return; // falls back to a live favicon (once pinged) or the default thumbnail
            }
            CompletableFuture<byte[]> primaryFetch = primary != null
                    ? HttpUtil.fetchBytes(primary).exceptionally(e -> null)
                    : CompletableFuture.completedFuture(null);
            primaryFetch.thenCompose(bytes -> {
                if (bytes != null) {
                    return CompletableFuture.completedFuture(bytes);
                }
                return secondary != null ? HttpUtil.fetchBytes(secondary).exceptionally(e -> null)
                        : CompletableFuture.completedFuture(null);
            }).thenAccept(bytes -> {
                if (bytes != null) {
                    Minecraft.getInstance().execute(() -> this.fetchedImageBytes = bytes);
                }
            });
        }

        /** Lunar image if we have one, else a live favicon, else null (caller shows the default thumbnail). */
        byte[] effectiveImageBytes() {
            if (fetchedImageBytes != null) {
                return fetchedImageBytes;
            }
            return favoriteIconBytes;
        }

        void uploadImageIfNeeded(byte[] bytes) {
            if (bytes == lastUploadedSource) {
                return;
            }
            try {
                NativeImage image = ImageUtil.decode(bytes);
                Minecraft.getInstance().getTextureManager().register(imageId,
                        new DynamicTexture(() -> "serverdiscovery card " + imageId, image));
                // This texture is a standalone image, not a slice of a
                // shared atlas - blit's u/v/uWidth/vHeight are pixel
                // offsets into the source texture (matching the classic
                // blit(x,y,u,v,width,height,textureWidth,textureHeight)
                // convention, just with floats instead of ints), so
                // "the whole image" means uWidth/vHeight equal to its
                // actual pixel size, not 1 (which samples a single pixel
                // stretched across the card - the solid-color-rectangle bug).
                imageWidth = Math.max(1, image.getWidth());
                imageHeight = Math.max(1, image.getHeight());
            } catch (Exception e) {
                LOGGER.debug("Could not decode image for {}", listing.address(), e);
            }
            lastUploadedSource = bytes;
        }
    }

    /** One grid row: up to `columns` cards laid out side by side. */
    public class Entry extends ObjectSelectionList.Entry<Entry> {

        private final List<ServerCard> cards;
        private final int columns;

        Entry(List<ServerCard> cards, int columns) {
            this.cards = cards;
            this.columns = columns;
        }

        private int cardWidth(int rowWidth) {
            return (rowWidth - (columns - 1) * CARD_GAP) / columns;
        }

        @Override
        public void extractContent(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, boolean hovering, float partialTick) {
            int index = DiscoveryServerList.this.children().indexOf(this);
            int left = DiscoveryServerList.this.getRowLeft();
            int top = DiscoveryServerList.this.getRowTop(index);
            int rowWidth = DiscoveryServerList.this.getRowWidth();
            int cardWidth = cardWidth(rowWidth);
            int cardHeight = ROW_HEIGHT - 4;

            for (int i = 0; i < cards.size(); i++) {
                int x = left + i * (cardWidth + CARD_GAP);
                renderCard(guiGraphics, cards.get(i), x, top, cardWidth, cardHeight, mouseX, mouseY, partialTick);
            }
        }

        private void renderCard(GuiGraphicsExtractor guiGraphics, ServerCard card, int x, int y, int width, int height,
                                 int mouseX, int mouseY, float partialTick) {
            Minecraft minecraft = Minecraft.getInstance();
            card.ensureImageFetchStarted();

            drawImage(guiGraphics, card, x, y, width, IMAGE_HEIGHT);
            guiGraphics.fillGradient(x, y + IMAGE_HEIGHT - 16, x + width, y + IMAGE_HEIGHT, 0x00000000, 0xC0000000);

            int textWidth = Math.max(10, width - 4);
            Component name = Component.literal(minecraft.font.plainSubstrByWidth(card.listing.name(), textWidth));
            guiGraphics.text(minecraft.font, name, x + 2, y + IMAGE_HEIGHT - 11, 0xFFFFFFFF);

            String subtitle = card.listing.description();
            if (subtitle == null || subtitle.isBlank()) {
                subtitle = card.listing.address();
            }
            Component subtitleTrimmed = Component.literal(minecraft.font.plainSubstrByWidth(subtitle, textWidth));
            guiGraphics.text(minecraft.font, subtitleTrimmed, x + 2, y + IMAGE_HEIGHT + 2, 0xFF808080);

            renderPingBadge(guiGraphics, card, x, y, width);

            int buttonY = y + height - 16;
            int addX = x + width - 40;
            int joinX = addX - 2 - 40;
            card.addButton.setPosition(addX, buttonY);
            card.joinButton.setPosition(joinX, buttonY);

            if (card.onlinePlayers >= 0) {
                String playersText = card.maxPlayers > 0
                        ? card.onlinePlayers + "/" + card.maxPlayers
                        : String.valueOf(card.onlinePlayers);
                int playersTextWidth = minecraft.font.width(playersText);
                guiGraphics.text(minecraft.font, playersText, joinX - 4 - playersTextWidth, buttonY + 4, 0xFF55FF55);
            }

            // Join/Add are managed manually (not via addRenderableWidget), and
            // their render method is protected - AbstractButtonInvoker
            // bridges to it. See that mixin for why.
            ((AbstractButtonInvoker) (Object) card.addButton).serverdiscovery$extractWidgetRenderState(guiGraphics, mouseX, mouseY, partialTick);
            ((AbstractButtonInvoker) (Object) card.joinButton).serverdiscovery$extractWidgetRenderState(guiGraphics, mouseX, mouseY, partialTick);
        }

        private void drawImage(GuiGraphicsExtractor guiGraphics, ServerCard card, int x, int y, int width, int height) {
            byte[] bytes = card.effectiveImageBytes();
            Identifier textureId;
            int sourceWidth;
            int sourceHeight;
            if (bytes != null) {
                card.uploadImageIfNeeded(bytes);
                textureId = card.imageId;
                sourceWidth = card.imageWidth;
                sourceHeight = card.imageHeight;
            } else {
                DefaultThumbnail.ensureRequested();
                if (!DefaultThumbnail.isReady()) {
                    guiGraphics.fill(x, y, x + width, y + height, 0xFF303030);
                    return;
                }
                textureId = DefaultThumbnail.ID;
                sourceWidth = DefaultThumbnail.getWidth();
                sourceHeight = DefaultThumbnail.getHeight();
            }
            // CONFIRMED against real, published, currently-working code
            // (TerraformersMC/ModMenu's icon rendering, which targets this
            // same 26.x GuiGraphicsExtractor) rather than guessed: the
            // no-RenderPipeline 9-arg overload this file used before is a
            // different, narrower thing than a general "draw this texture"
            // call - the one ModMenu actually uses for icons takes a
            // RenderPipeline and a trailing tint colour, and u/v/
            // textureWidth/textureHeight are genuinely pixel values (0,0 is
            // the texture's top-left corner; textureWidth/textureHeight are
            // its real pixel size), matching the classic
            // blit(x,y,u,v,width,height,textureWidth,textureHeight)
            // convention. 0xFFFFFFFF is "no tint" (opaque white).
            guiGraphics.blit(RenderPipelines.GUI_TEXTURED, textureId, x, y, 0.0f, 0.0f,
                    width, height, sourceWidth, sourceHeight, 0xFFFFFFFF);
        }

        private void renderPingBadge(GuiGraphicsExtractor guiGraphics, ServerCard card, int x, int y, int width) {
            Minecraft minecraft = Minecraft.getInstance();
            long ping = card.pingMillis;
            String text;
            int color;
            if (ping == -2) {
                text = "...";
                color = 0xFF808080;
            } else if (ping < 0) {
                text = "--";
                color = 0xFFFF5555;
            } else {
                text = ping + "ms";
                if (ping < 150) {
                    color = 0xFF55FF55;
                } else if (ping < 400) {
                    color = 0xFFFFFF55;
                } else {
                    color = 0xFFFF5555;
                }
            }
            int textWidth = minecraft.font.width(text);
            int badgeRight = x + width - 2;
            guiGraphics.fill(badgeRight - textWidth - 12, y + 2, badgeRight, y + 12, 0x90000000);
            guiGraphics.fill(badgeRight - textWidth - 10, y + 5, badgeRight - textWidth - 4, y + 11, color);
            guiGraphics.text(minecraft.font, text, badgeRight - textWidth, y + 3, 0xFFFFFFFF);
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
            for (ServerCard card : cards) {
                if (card.joinButton.isMouseOver(event.x(), event.y())) {
                    return card.joinButton.mouseClicked(event, doubleClick);
                }
                if (card.addButton.isMouseOver(event.x(), event.y())) {
                    return card.addButton.mouseClicked(event, doubleClick);
                }
            }
            return false;
        }

        @Override
        public Component getNarration() {
            return Component.literal("Server row");
        }
    }
}
