package org.geysermc.geyser.network.portal.nethernet;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.kastle.netty.channel.nethernet.NetherNetChannelFactory;
import dev.kastle.netty.channel.nethernet.signaling.NetherNetXboxSignaling;
import dev.kastle.webrtc.PeerConnectionFactory;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.util.concurrent.DefaultThreadFactory;
import org.geysermc.geyser.GeyserImpl;
import org.geysermc.geyser.configuration.PortalBridgeConfig;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;

public final class PortalNetherNetServer implements AutoCloseable {
    private static final String NETWORK_ID_FILENAME = "portal-nethernet-id.txt";
    private final GeyserImpl geyser;
    private final PortalBridgeConfig config;
    private final EventLoopGroup bossGroup = new NioEventLoopGroup(1, new DefaultThreadFactory("GeyserNetherNetBoss", true));
    private final EventLoopGroup workerGroup = new NioEventLoopGroup(0, new DefaultThreadFactory("GeyserNetherNetChild", true));
    private final GeyserNetherNetServerInitializer initializer;
    private final PeerConnectionFactory peerConnectionFactory;
    private final NetherNetXboxSignaling signaling;
    private Channel channel;

    public PortalNetherNetServer(GeyserImpl geyser, PortalBridgeConfig config) {
        this.geyser = geyser;
        this.config = config;
        this.initializer = new GeyserNetherNetServerInitializer(geyser);
        this.peerConnectionFactory = new PeerConnectionFactory();
        this.signaling = createSignaling(geyser, config);
    }

    private static NetherNetXboxSignaling createSignaling(GeyserImpl geyser, PortalBridgeConfig config) {
        String authHeader = resolveAuthHeader(geyser, config);
        if (!config.netherNetNetworkId().isBlank()) {
            return new NetherNetXboxSignaling(config.netherNetNetworkId(), authHeader);
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
            .childHandler(this.initializer);

        this.channel = bootstrap.bind(new InetSocketAddress(0)).syncUninterruptibly().channel();
        writeNetworkIdFile();
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
        deleteNetworkIdFile();
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

    private void writeNetworkIdFile() {
        Path path = networkIdFile();
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, this.signaling.getLocalNetworkId() + System.lineSeparator());
            if (config.debugLogging()) {
                this.geyser.getLogger().info("[proxy-bridge] Wrote NetherNet ID to " + path);
            }
        } catch (Exception exception) {
            this.geyser.getLogger().warning("[proxy-bridge] Failed to persist NetherNet ID to " + path + ": " + exception.getMessage());
        }
    }

    private void deleteNetworkIdFile() {
        Path path = networkIdFile();
        try {
            Files.deleteIfExists(path);
        } catch (Exception exception) {
            if (config.debugLogging()) {
                this.geyser.getLogger().warning("[proxy-bridge] Failed to remove NetherNet ID file " + path + ": " + exception.getMessage());
            }
        }
    }

    private Path networkIdFile() {
        return this.geyser.configDirectory().resolve(NETWORK_ID_FILENAME);
    }
}
