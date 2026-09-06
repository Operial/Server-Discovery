package com.xaria.serverdiscovery.api;

import java.util.List;
import java.util.Locale;

/**
 * One entry in the directory - enough to identify a server, connect to it,
 * and (optionally) show a nicer thumbnail than a live ping's favicon.
 *
 * <p>Image priority when rendering a card (see DiscoveryServerList):
 * {@link #backgroundUrl()} (a proper landscape thumbnail, Lunar-only) then
 * {@link #logoUrl()} (Lunar's square logo) then whatever favicon the live
 * ping returns, then a generic fallback image if none of those exist.
 */
public final class ServerListing {

    private final String name;
    private final String ip;
    private final int port;
    private final String description;
    private final List<String> tags;
    private final String logoUrl;
    private final String backgroundUrl;

    public ServerListing(String name, String ip, int port, String description, List<String> tags,
                          String logoUrl, String backgroundUrl) {
        this.name = name;
        this.ip = ip;
        this.port = port;
        this.description = description;
        this.tags = tags;
        this.logoUrl = logoUrl;
        this.backgroundUrl = backgroundUrl;
    }

    public String name() {
        return name;
    }

    /** Host and, if a non-default port was given, ":port" appended. */
    public String address() {
        if (port > 0 && port != 25565) {
            return ip + ":" + port;
        }
        return ip;
    }

    public String description() {
        return description;
    }

    public List<String> tags() {
        return tags;
    }

    /** Nullable - Lunar's square logo for this server. */
    public String logoUrl() {
        return logoUrl;
    }

    /** Nullable - Lunar's landscape background/banner for this server, when it has one. */
    public String backgroundUrl() {
        return backgroundUrl;
    }

    /** Normalized key for de-duplication across sources: lowercase host, explicit default port. */
    public String dedupeKey() {
        String host = ip.toLowerCase(Locale.ROOT);
        int resolvedPort = port > 0 ? port : 25565;
        return host + ":" + resolvedPort;
    }
}
