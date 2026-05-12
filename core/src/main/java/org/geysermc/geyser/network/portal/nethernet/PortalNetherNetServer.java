package org.geysermc.geyser.network.portal.nethernet;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.kastle.netty.channel.nethernet.NetherNetChannelFactory;
import dev.kastle.netty.channel.nethernet.signaling.NetherNetXboxSignaling;
import dev.kastle.webrtc.PeerConnectionFactory;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.util.concurrent.DefaultThreadFactory;
import org.geysermc.geyser.GeyserImpl;
import org.geysermc.geyser.configuration.PortalBridgeConfig;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;

public final class PortalNetherNetServer implements AutoCloseable {
    private final GeyserImpl geyser;
    private final PortalBridgeConfig config;
    private final EventLoopGroup bossGroup = new NioEventLoopGroup(1, new DefaultThreadFactory("GeyserNetherNetBoss", true));
    private final EventLoopGroup workerGroup = new NioEventLoopGroup(0, new DefaultThreadFactory("GeyserNetherNetChild", true));
    private final GeyserNetherNetServerInitializer initializer;
    private final PeerConnectionFactory peerConnectionFactory;
    private final NetherNetXboxSignaling signaling;
    private final String configuredNetworkId;
    private Channel channel;

    public PortalNetherNetServer(GeyserImpl geyser, PortalBridgeConfig config, String configuredNetworkId) {
        this.geyser = geyser;
        this.config = config;
        this.configuredNetworkId = configuredNetworkId == null ? "" : configuredNetworkId;
        this.initializer = new GeyserNetherNetServerInitializer(geyser);
        this.peerConnectionFactory = new PeerConnectionFactory();
        this.signaling = createSignaling(geyser, config, this.configuredNetworkId);
    }

    private static NetherNetXboxSignaling createSignaling(GeyserImpl geyser, PortalBridgeConfig config, String configuredNetworkId) {
        String authHeader = resolveAuthHeader(geyser, config);
        if (!configuredNetworkId.isBlank()) {
            return new NetherNetXboxSignaling(configuredNetworkId, authHeader);
        }
        return new NetherNetXboxSignaling(authHeader);
    }

    private static String resolveAuthHeader(GeyserImpl geyser, PortalBridgeConfig config) {
        if (!config.xboxAuthHeader().isBlank()) {
            return config.xboxAuthHeader();
        }
        if (config.xboxAuthHeaderFile().isBlank()) {
            return "";
        }

        try {
            String json = Files.readString(Path.of(config.xboxAuthHeaderFile()));
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonObject minecraftSession = root.getAsJsonObject("minecraftSession");
            if (minecraftSession != null && minecraftSession.has("authorizationHeader")) {
                return minecraftSession.get("authorizationHeader").getAsString();
            }
            geyser.getLogger().warning("[proxy-bridge] minecraftSession.authorizationHeader was not found in " + config.xboxAuthHeaderFile());
        } catch (Exception exception) {
            geyser.getLogger().error("[proxy-bridge] Failed to read Xbox auth header file: " + config.xboxAuthHeaderFile(), exception);
        }
        return "";
    }

    public void start() {
        ServerBootstrap bootstrap = new ServerBootstrap()
            .group(this.bossGroup, this.workerGroup)
            .channelFactory(NetherNetChannelFactory.server(this.peerConnectionFactory, this.signaling))
            .childOption(ChannelOption.TCP_NODELAY, false)
            .childHandler(this.initializer);

        this.channel = bootstrap.bind(new InetSocketAddress(0)).syncUninterruptibly().channel();
        this.geyser.getLogger().info("[proxy-bridge] NetherNet ingress started with network ID " + this.signaling.getLocalNetworkId());
        if (config.debugLogging()) {
            this.geyser.getLogger().info("[proxy-bridge] NetherNet control channel bound through Xbox signaling and local server bootstrap.");
        }
    }

    @Override
    public void close() {
        if (this.channel != null) {
            this.channel.close().syncUninterruptibly();
            this.channel = null;
        }
        this.signaling.close();
        this.initializer.close();
        this.workerGroup.shutdownGracefully();
        this.bossGroup.shutdownGracefully();
        try {
            this.peerConnectionFactory.dispose();
        } catch (NullPointerException ignored) {
            // The native handle was never fully initialized.
        }
    }

    public String networkId() {
        return this.signaling.getLocalNetworkId();
    }
}
