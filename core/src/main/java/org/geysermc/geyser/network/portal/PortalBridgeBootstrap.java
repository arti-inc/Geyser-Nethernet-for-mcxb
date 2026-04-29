/*
 * Copyright (c) 2019-2025 GeyserMC. http://geysermc.org
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 *
 * @author GeyserMC
 * @link https://github.com/GeyserMC/Geyser
 */

package org.geysermc.geyser.network.portal;

import com.google.gson.JsonObject;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.geysermc.geyser.GeyserImpl;
import org.geysermc.geyser.configuration.PortalBridgeConfig;
import org.geysermc.geyser.network.CIDRMatcher;
import org.geysermc.geyser.network.portal.nethernet.PortalNetherNetServer;
import org.geysermc.geyser.ping.GeyserPingInfo;
import org.geysermc.geyser.ping.IGeyserPingPassthrough;
import org.geysermc.geyser.translator.text.MessageTranslator;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Startup and trust bootstrap for portal-style Bedrock ingress.
 */
public final class PortalBridgeBootstrap implements AutoCloseable {
    private static final String SESSION_STATUS_FILENAME = "portal-session-status.json";
    private final GeyserImpl geyser;
    private final PortalBridgeConfig config;
    private final List<CIDRMatcher> trustedProxyMatchers;
    private final List<String> configuredRules;
    private @Nullable ScheduledExecutorService statusWriterExecutor;
    private @Nullable PortalNetherNetServer netherNetServer;

    public PortalBridgeBootstrap(GeyserImpl geyser) {
        this.geyser = geyser;
        this.config = geyser.config().advanced().bedrock().portalBridge();
        this.configuredRules = copyRules(this.config.trustedProxyIps());
        this.trustedProxyMatchers = parseTrustedProxyMatchers(this.configuredRules);
    }

    public void start() {
        geyser.getLogger().info("[proxy-bridge] Portal bridge enabled.");
        if (trustedProxyMatchers.isEmpty()) {
            geyser.getLogger().warning("[proxy-bridge] No trusted proxy rules are configured for SELF_SIGNED portal ingress.");
        }

        if (config.debugLogging()) {
            geyser.getLogger().info("[proxy-bridge] Trusted proxy rules: " + configuredRules.size());
        }

        if (config.xboxAuthHeader().isBlank() && config.xboxAuthHeaderFile().isBlank()) {
            geyser.getLogger().warning("[proxy-bridge] Xbox auth header source is not configured; NetherNet ingress will stay disabled.");
            return;
        }

        try {
            this.netherNetServer = new PortalNetherNetServer(geyser, config);
            this.netherNetServer.start();
            startStatusWriter();
        } catch (Throwable throwable) {
            geyser.getLogger().error("[proxy-bridge] Failed to start NetherNet ingress.", throwable);
            close();
        }
    }

    public boolean isTrustedProxy(@Nullable InetSocketAddress address) {
        if (address == null) {
            return false;
        }

        InetAddress inetAddress = address.getAddress();
        if (inetAddress == null) {
            return false;
        }

        for (CIDRMatcher matcher : trustedProxyMatchers) {
            if (matcher.matches(inetAddress)) {
                return true;
            }
        }
        return false;
    }

    public int trustedProxyRuleCount() {
        return trustedProxyMatchers.size();
    }

    @Override
    public void close() {
        if (this.statusWriterExecutor != null) {
            this.statusWriterExecutor.shutdownNow();
            this.statusWriterExecutor = null;
        }
        deleteStatusFile();
        if (this.netherNetServer != null) {
            this.netherNetServer.close();
            this.netherNetServer = null;
        }
    }

    private void startStatusWriter() {
        this.statusWriterExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "GeyserPortalStatusWriter");
            thread.setDaemon(true);
            return thread;
        });
        writeStatusFile();
        this.statusWriterExecutor.scheduleWithFixedDelay(this::writeStatusFile, 5, 5, TimeUnit.SECONDS);
    }

    private void writeStatusFile() {
        try {
            JsonObject root = new JsonObject();
            GeyserPingInfo pingInfo = resolvePingInfo();
            String bukkitMotd = resolveBukkitMotd();
            Integer bukkitPlayers = resolveBukkitOnlinePlayers();
            Integer bukkitMaxPlayers = resolveBukkitMaxPlayers();

            String primaryMotd = bukkitMotd != null ? bukkitMotd : geyser.config().motd().primaryMotd();
            String secondaryMotd = geyser.config().motd().secondaryMotd();
            int players = bukkitPlayers != null ? bukkitPlayers : geyser.onlineConnections().size();
            int maxPlayers = bukkitMaxPlayers != null ? bukkitMaxPlayers : geyser.config().motd().maxPlayers();

            if (geyser.config().motd().passthroughMotd() && pingInfo != null && pingInfo.getDescription() != null) {
                String[] motd = MessageTranslator.convertMessageLenient(pingInfo.getDescription()).split("\n");
                primaryMotd = (motd.length > 0 && !motd[0].isBlank()) ? motd[0].trim() : primaryMotd;
                secondaryMotd = (motd.length > 1 && !motd[1].isBlank()) ? motd[1].trim() : secondaryMotd;
            }

            if (secondaryMotd == null || secondaryMotd.isBlank() || "Another Geyser server.".equals(secondaryMotd)) {
                secondaryMotd = primaryMotd;
            }

            if (geyser.config().motd().passthroughPlayerCounts() && pingInfo != null && pingInfo.getPlayers() != null) {
                players = pingInfo.getPlayers().getOnline();
                maxPlayers = pingInfo.getPlayers().getMax();
            }

            root.addProperty("hostName", secondaryMotd);
            root.addProperty("worldName", primaryMotd);
            root.addProperty("players", players);
            root.addProperty("maxPlayers", maxPlayers);

            Path path = statusFile();
            Files.createDirectories(path.getParent());
            Files.writeString(path, root.toString() + System.lineSeparator());
        } catch (Exception exception) {
            if (config.debugLogging()) {
                geyser.getLogger().warning("[proxy-bridge] Failed to write session status file: " + exception.getMessage());
            }
        }
    }

    private void deleteStatusFile() {
        try {
            Files.deleteIfExists(statusFile());
        } catch (Exception exception) {
            if (config.debugLogging()) {
                geyser.getLogger().warning("[proxy-bridge] Failed to remove session status file: " + exception.getMessage());
            }
        }
    }

    private Path statusFile() {
        return this.geyser.configDirectory().resolve(SESSION_STATUS_FILENAME);
    }

    private @Nullable GeyserPingInfo resolvePingInfo() {
        IGeyserPingPassthrough pingPassthrough = geyser.getBootstrap().getGeyserPingPassthrough();
        if (pingPassthrough == null) {
            return null;
        }

        try {
            return pingPassthrough.getPingInformation(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        } catch (RuntimeException exception) {
            if (config.debugLogging()) {
                geyser.getLogger().warning("[proxy-bridge] Failed to resolve live ping info for session status file: " + exception.getMessage());
            }
            return null;
        }
    }

    private @Nullable String resolveBukkitMotd() {
        try {
            Class<?> bukkitClass = Class.forName("org.bukkit.Bukkit");
            Object motd = bukkitClass.getMethod("getMotd").invoke(null);
            if (motd instanceof String string && !string.isBlank()) {
                return string.trim();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private @Nullable Integer resolveBukkitOnlinePlayers() {
        try {
            Class<?> bukkitClass = Class.forName("org.bukkit.Bukkit");
            Object onlinePlayers = bukkitClass.getMethod("getOnlinePlayers").invoke(null);
            if (onlinePlayers instanceof Collection<?> collection) {
                return collection.size();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private @Nullable Integer resolveBukkitMaxPlayers() {
        try {
            Class<?> bukkitClass = Class.forName("org.bukkit.Bukkit");
            Object maxPlayers = bukkitClass.getMethod("getMaxPlayers").invoke(null);
            if (maxPlayers instanceof Integer integer) {
                return integer;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static List<String> copyRules(@Nullable List<String> configuredRules) {
        if (configuredRules == null || configuredRules.isEmpty()) {
            return List.of();
        }
        return List.copyOf(configuredRules);
    }

    private List<CIDRMatcher> parseTrustedProxyMatchers(List<String> rules) {
        List<CIDRMatcher> matchers = new ArrayList<>(rules.size());
        for (String entry : rules) {
            if (entry == null) {
                continue;
            }

            String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }

            try {
                matchers.add(new CIDRMatcher(trimmed));
            } catch (RuntimeException exception) {
                geyser.getLogger().warning("[proxy-bridge] Ignoring invalid trusted proxy rule: " + trimmed);
                if (geyser.config().debugMode() || config.debugLogging()) {
                    geyser.getLogger().debug("[proxy-bridge] Invalid trusted proxy rule parse failure", exception);
                }
            }
        }
        return List.copyOf(matchers);
    }
}
