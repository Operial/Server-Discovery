package com.xaria.serverdiscovery;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entrypoint for Server Discovery.
 *
 * <p>This mod is entirely client-side: it adds a "Discovery" button to the
 * vanilla Multiplayer screen which opens a browser of servers pulled from a
 * remote directory (see {@link com.xaria.serverdiscovery.api.ServerDirectoryClient}).
 * There is nothing for it to do on a dedicated server, which is why
 * {@code fabric.mod.json} declares {@code "environment": "client"}.
 */
public class ServerDiscoveryMod implements ClientModInitializer {

    public static final String MOD_ID = "serverdiscovery";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitializeClient() {
        LOGGER.info("Server Discovery initialized");
    }
}
