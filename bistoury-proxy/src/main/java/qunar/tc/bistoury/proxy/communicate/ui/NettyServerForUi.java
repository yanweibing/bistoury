package qunar.tc.bistoury.proxy.communicate.ui;

import com.google.common.base.Throwables;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketFrameAggregator;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.timeout.IdleStateHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qunar.tc.bistoury.proxy.communicate.NettyServer;
import qunar.tc.bistoury.proxy.communicate.SessionManager;
import qunar.tc.bistoury.proxy.communicate.agent.AgentConnectionStore;
import qunar.tc.bistoury.proxy.communicate.ui.command.CommunicateCommandStore;
import qunar.tc.bistoury.proxy.communicate.ui.handler.*;
import qunar.tc.bistoury.proxy.communicate.ui.handler.encryption.DefaultRequestEncryption;
import qunar.tc.bistoury.proxy.util.AppCenterServerFinder;
import qunar.tc.bistoury.proxy.web.dao.AppServerDao;
import qunar.tc.bistoury.serverside.agile.Conf;
import qunar.tc.bistoury.serverside.common.encryption.RSAEncryption;

/**
 * @author zhenyu.nie created on 2019 2019/5/16 11:33
 */
public class NettyServerForUi implements NettyServer {

    private static final Logger logger = LoggerFactory.getLogger(NettyServerForUi.class);

    private static final String RSA_PUBLIC_KEY = "/rsa-public-key.pem";

    private static final String RSA_PRIVATE_KEY = "/rsa-private-key.pem";

    private static final int DEFAULT_WRITE_LOW_WATER_MARK = 64 * 1024;

    private static final int DEFAULT_WRITE_HIGH_WATER_MARK = 128 * 1024;

    private static final EventLoopGroup BOSS = new NioEventLoopGroup(1, new ThreadFactoryBuilder().setNameFormat("ui-netty-server-boss").build());

    private static final EventLoopGroup WORKER = new NioEventLoopGroup(Runtime.getRuntime().availableProcessors(), new ThreadFactoryBuilder().setNameFormat("ui-netty-server-worker").build());

    private final int port;

    private UiConnectionStore uiConnectionStore;

    private AgentConnectionStore agentConnectionStore;

    private SessionManager sessionManager;

    private CommunicateCommandStore commandStore;

    private AppServerDao appServerDao;

    private volatile Channel channel;

    public NettyServerForUi(Conf conf,
                            CommunicateCommandStore commandStore,
                            UiConnectionStore uiConnectionStore,
                            AgentConnectionStore agentConnectionStore,
                            SessionManager sessionManager, AppServerDao appServerDao) {
        this.port = conf.getInt("server.port", -1);
        this.uiConnectionStore = uiConnectionStore;
        this.agentConnectionStore = agentConnectionStore;
        this.sessionManager = sessionManager;
        this.commandStore = commandStore;
        this.appServerDao = appServerDao;
    }

    @Override
    public void start() {
        ServerBootstrap bootstrap = new ServerBootstrap()
                .option(ChannelOption.SO_KEEPALIVE, true)
                .option(ChannelOption.SO_REUSEADDR, true)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                .option(ChannelOption.WRITE_BUFFER_LOW_WATER_MARK, DEFAULT_WRITE_LOW_WATER_MARK)
                .option(ChannelOption.WRITE_BUFFER_HIGH_WATER_MARK, DEFAULT_WRITE_HIGH_WATER_MARK)
                .channel(NioServerSocketChannel.class)
                .group(BOSS, WORKER)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) throws Exception {
                        ChannelPipeline pip = ch.pipeline();
                        pip.addLast(new IdleStateHandler(0, 0, 30 * 60 * 1000))
                                .addLast(new HttpServerCodec())
                                .addLast(new HttpObjectAggregator(1024 * 1024))
                                .addLast(new WebSocketServerProtocolHandler("/ws"))
                                .addLast(new WebSocketFrameAggregator(1024 * 1024 * 1024))
                                .addLast(new RequestDecoder(new DefaultRequestEncryption(new RSAEncryption(RSA_PUBLIC_KEY, RSA_PRIVATE_KEY))))
                                .addLast(new WebSocketEncoder())
                                .addLast(new TabHandler())
                                .addLast(new HostsValidatorHandler(new AppCenterServerFinder(appServerDao)))
                                .addLast(new UiRequestHandler(commandStore, uiConnectionStore, agentConnectionStore, sessionManager));
                    }
                });
        try {
            this.channel = bootstrap.bind(port).sync().channel();
            logger.info("client server startup successfully, port {}", port);
        } catch (Exception e) {
            logger.error("netty server for ui start fail", e);
            throw Throwables.propagate(e);
        }
    }

    @Override
    public boolean isActive() {
        return channel.isActive();
    }

    @Override
    public void stop() {
        try {
            BOSS.shutdownGracefully().sync();
            WORKER.shutdownGracefully().sync();
            channel.close();
        } catch (InterruptedException e) {
            logger.error("ui server close error", e);
        }
    }
}