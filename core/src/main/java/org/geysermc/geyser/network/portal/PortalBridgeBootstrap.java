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

import org.checkerframework.checker.nullness.qual.Nullable;
import org.geysermc.geyser.GeyserImpl;
import org.geysermc.geyser.configuration.PortalBridgeConfig;
import org.geysermc.geyser.network.CIDRMatcher;
import org.geysermc.geyser.network.portal.nethernet.PortalNetherNetServer;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * Startup and trust bootstrap for portal-style Bedrock ingress.
 */
public final class PortalBridgeBootstrap implements AutoCloseable {
    private final GeyserImpl geyser;
    private final PortalBridgeConfig config;
    private final List<CIDRMatcher> trustedProxyMatchers;
    private final List<String> configuredRules;
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
        if (this.netherNetServer != null) {
            this.netherNetServer.close();
            this.netherNetServer = null;
        }
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
