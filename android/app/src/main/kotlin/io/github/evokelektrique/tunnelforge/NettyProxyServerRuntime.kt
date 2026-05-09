package io.github.evokelektrique.tunnelforge

import android.util.Log
import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelOption
import io.netty.channel.EventLoopGroup
import io.netty.channel.ChannelFactory
import io.netty.channel.ServerChannel
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.ByteToMessageDecoder
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpObjectAggregator
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpServerCodec
import io.netty.handler.codec.http.HttpVersion
import io.netty.handler.codec.socksx.SocksVersion
import io.netty.handler.codec.socksx.v5.DefaultSocks5CommandResponse
import io.netty.handler.codec.socksx.v5.DefaultSocks5InitialResponse
import io.netty.handler.codec.socksx.v5.Socks5AddressType
import io.netty.handler.codec.socksx.v5.Socks5CommandRequest
import io.netty.handler.codec.socksx.v5.Socks5CommandStatus
import io.netty.handler.codec.socksx.v5.Socks5CommandType
import io.netty.handler.codec.socksx.v5.Socks5InitialRequest
import io.netty.handler.codec.socksx.v5.Socks5AuthMethod
import io.netty.handler.codec.socksx.v5.Socks5CommandRequestDecoder
import io.netty.handler.codec.socksx.v5.Socks5InitialRequestDecoder
import io.netty.handler.codec.socksx.v5.Socks5ServerEncoder
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.ArrayDeque
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Netty-based HTTP CONNECT and SOCKS5 listener runtime.
 *
 * Netty event-loop threads handle protocol parsing and socket writes. Potentially blocking
 * transport operations run on [blockingExecutor], which keeps gVisor/native waits off the event
 * loops and makes shutdown a matter of closing channels plus interrupting transport work.
 */
class NettyProxyServerRuntime(
    private val config: ProxyRuntimeConfig,
    private val transport: ProxyTransport,
    private val connectTimeoutMs: Long = DEFAULT_CONNECT_TIMEOUT_MS,
    private val connectResponseTimeoutMs: Long = connectTimeoutMs,
    private val levelLogger: (Int, String) -> Unit = { _, _ -> },
) : LocalProxyRuntime {
    private val running = AtomicBoolean(false)
    private val bossGroup: EventLoopGroup = NioEventLoopGroup(1, daemonThreadFactory("netty-proxy-boss"))
    private val workerGroup: EventLoopGroup =
        NioEventLoopGroup(0, daemonThreadFactory("netty-proxy-io"))
    /** Runs DNS/native open/read loops that can block longer than a Netty event loop should. */
    private val blockingExecutor: ExecutorService =
        Executors.newCachedThreadPool { task -> Thread(task, "netty-proxy-transport").apply { isDaemon = true } }
    private val serverChannelFactory = ChannelFactory<ServerChannel> { NioServerSocketChannel() }
    private val serverChannels = mutableListOf<Channel>()
    private val activeChannels = AtomicInteger(0)
    private val acceptedChannels = AtomicInteger(0)
    private val failedChannels = AtomicInteger(0)
    private val pendingConnects = AtomicInteger(0)

    override fun start() {
        if (!config.httpEnabled && !config.socksEnabled) {
            throw IllegalArgumentException("Enable HTTP or SOCKS5 before starting proxy listeners")
        }
        if (config.httpEnabled && config.socksEnabled && config.httpPort == config.socksPort) {
            throw IllegalArgumentException("HTTP and SOCKS5 ports must differ")
        }
        if (!running.compareAndSet(false, true)) return
        val bindHosts = config.exposure.listenerBindAddresses()
        try {
            for (host in bindHosts) {
                if (config.httpEnabled) {
                    bind("http", host, config.httpPort) { HttpProxyChannelInitializer() }
                }
                if (config.socksEnabled) {
                    bind("socks5", host, config.socksPort) { SocksProxyChannelInitializer() }
                }
            }
            info("netty proxy runtime start binds=${bindHosts.joinToString("|")} ${runtimeDiagnostics()}")
        } catch (t: Throwable) {
            stop()
            throw t
        }
    }

    override fun stop() {
        if (!running.getAndSet(false)) return
        info("netty proxy runtime stop ${runtimeDiagnostics()}")
        for (channel in serverChannels.toList()) {
            channel.close().awaitUninterruptibly(1000)
        }
        serverChannels.clear()
        bossGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS)
        workerGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS)
        blockingExecutor.shutdownNow()
    }

    override fun endpointSummary(): String =
        config.exposure.endpointSummary(
            httpEnabled = config.httpEnabled,
            socksEnabled = config.socksEnabled,
        )

    override fun exposureInfo(): ProxyExposureInfo = config.exposure

    private fun bind(
        name: String,
        bindHost: String,
        port: Int,
        initializer: () -> ChannelInitializer<SocketChannel>,
    ) {
        val channel =
            ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channelFactory(serverChannelFactory)
                .option(ChannelOption.SO_BACKLOG, LISTENER_BACKLOG)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childHandler(initializer())
                .bind(InetSocketAddress(InetAddress.getByName(bindHost), port))
                .sync()
                .channel()
        serverChannels += channel
        info("netty listening on $name://$bindHost:$port")
    }

    private inner class HttpProxyChannelInitializer : ChannelInitializer<SocketChannel>() {
        override fun initChannel(ch: SocketChannel) {
            track(ch)
            ch.pipeline()
                .addLast(HTTP_CODEC, HttpServerCodec())
                .addLast(HTTP_AGGREGATOR, HttpObjectAggregator(MAX_HTTP_REQUEST_BYTES))
                .addLast(FRONTEND_HANDLER, HttpConnectHandler())
        }
    }

    private inner class HttpConnectHandler : SimpleChannelInboundHandler<FullHttpRequest>() {
        override fun channelRead0(ctx: ChannelHandlerContext, request: FullHttpRequest) {
            if (!request.method().name().equals("CONNECT", ignoreCase = true)) {
                writeHttpError(ctx, HttpResponseStatus.NOT_IMPLEMENTED, "Only HTTP CONNECT is supported by the Netty proxy frontend.")
                return
            }
            val target = parseConnectTarget(request.uri())
            if (target == null) {
                writeHttpError(ctx, HttpResponseStatus.BAD_REQUEST, "Invalid CONNECT target.")
                return
            }
            if (target.first.isUnsupportedIpv6Target()) {
                writeHttpError(ctx, HttpResponseStatus.NOT_IMPLEMENTED, "IPv6 targets are not supported in proxy mode.")
                return
            }
            val connectRequest = ProxyConnectRequest(target.first, target.second, "http-connect")
            debug("netty proxy request proto=http-connect target=${connectRequest.host}:${connectRequest.port} ${runtimeDiagnostics()}")
            val initialBytes =
                if (request.content().isReadable) {
                    val bytes = ByteArray(request.content().readableBytes())
                    request.content().getBytes(request.content().readerIndex(), bytes)
                    bytes
                } else {
                    ByteArray(0)
                }
            openTunnel(ctx, connectRequest, httpConnect = true, initialClientBytes = initialBytes)
        }

        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
            failedChannels.incrementAndGet()
            warn("netty http client error: ${cause.message ?: cause.javaClass.simpleName}")
            ctx.close()
        }
    }

    private inner class SocksProxyChannelInitializer : ChannelInitializer<SocketChannel>() {
        override fun initChannel(ch: SocketChannel) {
            track(ch)
            ch.pipeline()
                .addLast(SOCKS_ENCODER, Socks5ServerEncoder.DEFAULT)
                .addLast(SOCKS_DECODER_INITIAL, Socks5InitialRequestDecoder())
                .addLast(FRONTEND_HANDLER, SocksConnectHandler())
        }
    }

    private inner class SocksConnectHandler : SimpleChannelInboundHandler<Any>() {
        override fun channelRead0(ctx: ChannelHandlerContext, msg: Any) {
            when (msg) {
                is Socks5InitialRequest -> {
                    if (msg.version() != SocksVersion.SOCKS5) {
                        ctx.close()
                        return
                    }
                    ctx.writeAndFlush(DefaultSocks5InitialResponse(Socks5AuthMethod.NO_AUTH))
                    ctx.pipeline().replace(SOCKS_DECODER_INITIAL, SOCKS_DECODER_COMMAND, Socks5CommandRequestDecoder())
                }
                is Socks5CommandRequest -> {
                    if (msg.type() != Socks5CommandType.CONNECT) {
                        ctx.writeAndFlush(
                            DefaultSocks5CommandResponse(
                                Socks5CommandStatus.COMMAND_UNSUPPORTED,
                                Socks5AddressType.IPv4,
                            ),
                        ).addListener(ChannelFutureListener.CLOSE)
                        return
                    }
                    if (msg.dstAddr().isUnsupportedIpv6Target()) {
                        ctx.writeAndFlush(
                            DefaultSocks5CommandResponse(
                                Socks5CommandStatus.HOST_UNREACHABLE,
                                Socks5AddressType.IPv4,
                            ),
                        ).addListener(ChannelFutureListener.CLOSE)
                        return
                    }
                    openTunnel(ctx, ProxyConnectRequest(msg.dstAddr(), msg.dstPort(), "socks5-connect"), httpConnect = false)
                }
                else -> ctx.close()
            }
        }

        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
            failedChannels.incrementAndGet()
            warn("netty socks client error: ${cause.message ?: cause.javaClass.simpleName}")
            ctx.close()
        }
    }

    private fun openTunnel(
        ctx: ChannelHandlerContext,
        request: ProxyConnectRequest,
        httpConnect: Boolean,
        initialClientBytes: ByteArray = ByteArray(0),
    ) {
        if (httpConnect) {
            openHttpConnectTunnelEarly(ctx, request, initialClientBytes)
            return
        }
        blockingExecutor.execute {
            var session: ProxyTransportSession? = null
            var success = false
            var pendingReleased = false
            val openStartMs = System.currentTimeMillis()
            val pendingNow = pendingConnects.incrementAndGet()
            debug(
                "netty proxy open begin proto=${request.protocol} target=${request.host}:${request.port} " +
                    "pendingConnects=$pendingNow timeoutMs=${if (httpConnect) connectResponseTimeoutMs else connectTimeoutMs} ${runtimeDiagnostics()}",
            )
            try {
                session = transport.openTcpSession(request)
                val pendingSession = session
                ctx.channel().closeFuture().addListener {
                    pendingSession.close()
                }
                session.awaitConnected(
                    if (httpConnect) {
                        connectResponseTimeoutMs.coerceAtLeast(1L)
                    } else {
                        connectTimeoutMs.coerceAtLeast(1L)
                    },
                )
                success = true
                val establishedSession = session
                val elapsedMs = System.currentTimeMillis() - openStartMs
                val remainingPending = pendingConnects.decrementAndGet()
                pendingReleased = true
                debug(
                    "netty proxy open established proto=${request.protocol} sid=${establishedSession.descriptor.sessionId} " +
                        "target=${request.host}:${request.port} openMs=$elapsedMs pendingConnects=$remainingPending ${runtimeDiagnostics()}",
                )
                ctx.executor().execute {
                    if (!ctx.channel().isActive) {
                        establishedSession.close()
                        debug(
                            "netty proxy connect abandoned proto=${request.protocol} sid=${establishedSession.descriptor.sessionId} " +
                                "target=${request.host}:${request.port} reason=clientChannelInactive ${runtimeDiagnostics()}",
                        )
                        return@execute
                    }
                    val responseFuture = if (httpConnect) {
                        removeFrontendPipeline(ctx, httpConnect)
                        ctx.writeAndFlush(Unpooled.copiedBuffer("HTTP/1.1 200 Connection Established\r\n\r\n", StandardCharsets.US_ASCII))
                    } else {
                        ctx.writeAndFlush(DefaultSocks5CommandResponse(Socks5CommandStatus.SUCCESS, Socks5AddressType.IPv4))
                    }
                    responseFuture.addListener { future ->
                        if (!future.isSuccess || !ctx.channel().isActive) {
                            establishedSession.close()
                            ctx.close()
                            warn(
                                "netty proxy connect response failed proto=${request.protocol} sid=${establishedSession.descriptor.sessionId} " +
                                    "target=${request.host}:${request.port} cause=${future.cause()?.message ?: "channelInactive"} ${runtimeDiagnostics()}",
                            )
                            return@addListener
                        }
                        if (!httpConnect) {
                            removeFrontendPipeline(ctx, httpConnect)
                        }
                        ctx.pipeline().addLast(NettyTunnelRelayHandler(establishedSession))
                        ctx.channel().config().isAutoRead = true
                        ctx.read()
                        debug("netty proxy connect ok proto=${request.protocol} sid=${establishedSession.descriptor.sessionId} target=${request.host}:${request.port}")
                    }
                }
            } catch (e: IOException) {
                val elapsedMs = System.currentTimeMillis() - openStartMs
                val remainingPending =
                    if (pendingReleased) {
                        pendingConnects.get()
                    } else {
                        pendingReleased = true
                        pendingConnects.decrementAndGet()
                    }
                val closeSession = session
                closeSession?.close()
                val aborted = (e as? ProxyTransportException)?.failureReason == ProxyTransportFailureReason.clientAborted
                val failMessage =
                    "netty proxy fail proto=${request.protocol} phase=${if (success) "post-success" else "pre-success"} " +
                        "target=${request.host}:${request.port} openMs=$elapsedMs pendingConnects=$remainingPending error=${e.message} ${runtimeDiagnostics()}"
                if (!aborted) {
                    warn(failMessage)
                }
                ctx.executor().execute {
                    if (httpConnect) {
                        if (aborted) {
                            ctx.close()
                        } else if (shouldSuppressUpstreamHttpError(e)) {
                            warn(
                                "netty proxy suppress-error proto=${request.protocol} phase=${if (success) "post-success" else "pre-success"} " +
                                    "target=${request.host}:${request.port} reason=${failureReasonForLog(e)}",
                            )
                            ctx.close()
                        } else {
                            writeHttpError(ctx, statusFor(e), e.message ?: "Proxy connection failed.")
                        }
                    } else {
                        ctx.writeAndFlush(DefaultSocks5CommandResponse(socksStatusFor(e), Socks5AddressType.IPv4))
                            .addListener(ChannelFutureListener.CLOSE)
                    }
                }
            } catch (t: Throwable) {
                val elapsedMs = System.currentTimeMillis() - openStartMs
                val remainingPending =
                    if (pendingReleased) {
                        pendingConnects.get()
                    } else {
                        pendingReleased = true
                        pendingConnects.decrementAndGet()
                    }
                val closeSession = session
                closeSession?.close()
                failedChannels.incrementAndGet()
                warn(
                    "netty proxy fail proto=${request.protocol} phase=unexpected target=${request.host}:${request.port} " +
                        "openMs=$elapsedMs pendingConnects=$remainingPending error=${t.message ?: t.javaClass.simpleName} ${runtimeDiagnostics()}",
                )
                ctx.executor().execute { ctx.close() }
            } finally {
                if (!pendingReleased) {
                    pendingConnects.decrementAndGet()
                }
            }
        }
    }

    private fun openHttpConnectTunnelEarly(
        ctx: ChannelHandlerContext,
        request: ProxyConnectRequest,
        initialClientBytes: ByteArray,
    ) {
        val pendingRelay = PendingHttpConnectRelayHandler(request)
        ctx.pipeline().replace(FRONTEND_HANDLER, PENDING_HTTP_CONNECT_HANDLER, pendingRelay)
        removeFrontendPipeline(ctx, httpConnect = true)
        val relayCtx = ctx.pipeline().context(PENDING_HTTP_CONNECT_HANDLER) ?: ctx
        if (initialClientBytes.isNotEmpty()) {
            pendingRelay.bufferInitial(relayCtx, initialClientBytes)
        }
        relayCtx.writeAndFlush(Unpooled.copiedBuffer("HTTP/1.1 200 Connection Established\r\n\r\n", StandardCharsets.US_ASCII))
            .addListener { future ->
                if (!future.isSuccess) {
                    pendingRelay.fail(relayCtx, "earlyResponseWriteFailed", future.cause())
                    return@addListener
                }
                debug(
                    "netty proxy earlyConnectAccepted proto=${request.protocol} target=${request.host}:${request.port} " +
                        "bufferedBytes=${pendingRelay.bufferedByteCount()} capBytes=$PENDING_OPEN_BUFFER_BYTES ${runtimeDiagnostics()}",
                )
                if (ctx.channel().isActive) {
                    ctx.channel().config().isAutoRead = true
                    relayCtx.read()
                }
            }

        blockingExecutor.execute {
            var session: ProxyTransportSession? = null
            var pendingReleased = false
            val openStartMs = System.currentTimeMillis()
            val pendingNow = pendingConnects.incrementAndGet()
            debug(
                "netty proxy open begin proto=${request.protocol} target=${request.host}:${request.port} " +
                    "pendingConnects=$pendingNow timeoutMs=$connectTimeoutMs earlyConnect=true ${runtimeDiagnostics()}",
            )
            try {
                session = transport.openTcpSession(request)
                val pendingSession = session
                pendingRelay.setSession(pendingSession)
                pendingSession.awaitConnected(connectTimeoutMs.coerceAtLeast(1L))
                val elapsedMs = System.currentTimeMillis() - openStartMs
                val remainingPending = pendingConnects.decrementAndGet()
                pendingReleased = true
                debug(
                    "netty proxy earlyConnectOpenEstablished proto=${request.protocol} sid=${pendingSession.descriptor.sessionId} " +
                        "target=${request.host}:${request.port} openMs=$elapsedMs pendingConnects=$remainingPending " +
                        "bufferedBytes=${pendingRelay.bufferedByteCount()} ${runtimeDiagnostics()}",
                )
                ctx.executor().execute {
                    pendingRelay.establish(ctx, pendingSession)
                }
            } catch (e: IOException) {
                val elapsedMs = System.currentTimeMillis() - openStartMs
                val remainingPending =
                    if (pendingReleased) {
                        pendingConnects.get()
                    } else {
                        pendingReleased = true
                        pendingConnects.decrementAndGet()
                    }
                val closeSession = session
                closeSession?.close()
                val aborted = (e as? ProxyTransportException)?.failureReason == ProxyTransportFailureReason.clientAborted
                val message =
                    "netty proxy earlyConnectOpenFailed proto=${request.protocol} target=${request.host}:${request.port} " +
                        "openMs=$elapsedMs pendingConnects=$remainingPending reason=${failureReasonForLog(e)} " +
                        "error=${e.message} ${runtimeDiagnostics()}"
                if (!aborted) {
                    warn(message)
                }
                ctx.executor().execute {
                    pendingRelay.fail(ctx, failureReasonForLog(e), e)
                }
            } catch (t: Throwable) {
                val elapsedMs = System.currentTimeMillis() - openStartMs
                val remainingPending =
                    if (pendingReleased) {
                        pendingConnects.get()
                    } else {
                        pendingReleased = true
                        pendingConnects.decrementAndGet()
                    }
                val closeSession = session
                closeSession?.close()
                failedChannels.incrementAndGet()
                warn(
                    "netty proxy earlyConnectOpenFailed proto=${request.protocol} target=${request.host}:${request.port} " +
                        "openMs=$elapsedMs pendingConnects=$remainingPending reason=unexpected " +
                        "error=${t.message ?: t.javaClass.simpleName} ${runtimeDiagnostics()}",
                )
                ctx.executor().execute {
                    pendingRelay.fail(ctx, "unexpected", t)
                }
            } finally {
                if (!pendingReleased) {
                    pendingConnects.decrementAndGet()
                }
            }
        }
    }

    private inner class PendingHttpConnectRelayHandler(
        private val request: ProxyConnectRequest,
    ) : SimpleChannelInboundHandler<ByteBuf>() {
        private val lock = Any()
        private val pendingBuffers = ArrayDeque<ByteArray>()
        private var pendingBytes = 0
        private var session: ProxyTransportSession? = null
        private var closed = false
        private var established = false

        fun bufferedByteCount(): Int = synchronized(lock) { pendingBytes }

        fun bufferInitial(ctx: ChannelHandlerContext, bytes: ByteArray) {
            bufferBytes(ctx, bytes)
        }

        /*
         * Clients may send TLS bytes immediately after CONNECT before the tunneled TCP open
         * completes. Buffer a bounded amount, then replay it once the transport is established.
         */
        fun setSession(newSession: ProxyTransportSession) {
            val closeNow =
                synchronized(lock) {
                    session = newSession
                    closed
                }
            if (closeNow) {
                newSession.close()
            }
        }

        fun establish(ctx: ChannelHandlerContext, establishedSession: ProxyTransportSession) {
            val buffers =
                synchronized(lock) {
                    if (closed || !ctx.channel().isActive) {
                        closed = true
                        pendingBuffers.clear()
                        pendingBytes = 0
                        establishedSession.close()
                        return
                    }
                    established = true
                    val copy = ArrayList<ByteArray>(pendingBuffers)
                    pendingBuffers.clear()
                    pendingBytes = 0
                    copy
                }
            if (ctx.pipeline().get(PENDING_HTTP_CONNECT_HANDLER) != null) {
                ctx.pipeline().remove(PENDING_HTTP_CONNECT_HANDLER)
            }
            ctx.channel().config().isAutoRead = false
            ctx.pipeline().addLast(NettyTunnelRelayHandler(establishedSession))
            blockingExecutor.execute {
                try {
                    for (buffer in buffers) {
                        establishedSession.writeClientBytes(buffer, WRITE_TIMEOUT_MS)
                    }
                    ctx.executor().execute {
                        if (ctx.channel().isActive) {
                            ctx.channel().config().isAutoRead = true
                            ctx.read()
                        } else {
                            establishedSession.close()
                        }
                    }
                } catch (e: IOException) {
                    warn(
                        "netty proxy pending-open flush failed proto=${request.protocol} sid=${establishedSession.descriptor.sessionId} " +
                            "target=${request.host}:${request.port} error=${e.message}",
                    )
                    ctx.executor().execute { ctx.close() }
                }
            }
        }

        fun fail(ctx: ChannelHandlerContext, reason: String, cause: Throwable?) {
            val closeSession =
                synchronized(lock) {
                    if (closed) return
                    closed = true
                    pendingBuffers.clear()
                    pendingBytes = 0
                    session
                }
            closeSession?.close()
            debug(
                "netty proxy pending-open close proto=${request.protocol} target=${request.host}:${request.port} " +
                    "reason=$reason cause=${cause?.message ?: "none"} ${runtimeDiagnostics()}",
            )
            ctx.close()
        }

        override fun channelRead0(ctx: ChannelHandlerContext, msg: ByteBuf) {
            val bytes = ByteArray(msg.readableBytes())
            msg.readBytes(bytes)
            bufferBytes(ctx, bytes)
        }

        override fun channelInactive(ctx: ChannelHandlerContext) {
            val closeSession =
                synchronized(lock) {
                    if (closed) return
                    closed = true
                    pendingBuffers.clear()
                    pendingBytes = 0
                    session
                }
            closeSession?.close()
        }

        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
            fail(ctx, "pendingRelayError", cause)
        }

        private fun bufferBytes(ctx: ChannelHandlerContext, bytes: ByteArray) {
            if (bytes.isEmpty()) return
            val overflowSession =
                synchronized(lock) {
                    if (closed || established) return
                    if (pendingBytes + bytes.size > PENDING_OPEN_BUFFER_BYTES) {
                        closed = true
                        pendingBuffers.clear()
                        pendingBytes = 0
                        session
                    } else {
                        pendingBuffers.add(bytes)
                        pendingBytes += bytes.size
                        return
                    }
                }
            overflowSession?.close()
            warn(
                "netty proxy pendingOpenBufferOverflow proto=${request.protocol} target=${request.host}:${request.port} " +
                    "incomingBytes=${bytes.size} capBytes=$PENDING_OPEN_BUFFER_BYTES ${runtimeDiagnostics()}",
            )
            ctx.close()
        }
    }

    private inner class NettyTunnelRelayHandler(
        private val session: ProxyTransportSession,
    ) : SimpleChannelInboundHandler<ByteBuf>() {
        private val closed = AtomicBoolean(false)

        override fun handlerAdded(ctx: ChannelHandlerContext) {
            blockingExecutor.execute { pumpRemoteToClient(ctx) }
        }

        override fun channelRead0(ctx: ChannelHandlerContext, msg: ByteBuf) {
            val bytes = ByteArray(msg.readableBytes())
            msg.readBytes(bytes)
            ctx.channel().config().isAutoRead = false
            blockingExecutor.execute {
                try {
                    session.writeClientBytes(bytes, WRITE_TIMEOUT_MS)
                    ctx.executor().execute {
                        if (ctx.channel().isActive) {
                            ctx.channel().config().isAutoRead = true
                            ctx.read()
                        }
                    }
                } catch (e: IOException) {
                    warn("netty tunnel client->remote failed sid=${session.descriptor.sessionId} error=${e.message}")
                    close(ctx)
                }
            }
        }

        override fun channelInactive(ctx: ChannelHandlerContext) {
            close(ctx)
        }

        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
            warn("netty tunnel error sid=${session.descriptor.sessionId} error=${cause.message ?: cause.javaClass.simpleName}")
            close(ctx)
        }

        private fun pumpRemoteToClient(ctx: ChannelHandlerContext) {
            try {
                while (!closed.get() && ctx.channel().isActive) {
                    val chunk = session.readRemoteBytes(RELAY_BUFFER_BYTES, READ_TIMEOUT_MS) ?: continue
                    if (chunk.isEmpty()) break
                    ctx.writeAndFlush(Unpooled.wrappedBuffer(chunk)).sync()
                }
            } catch (e: IOException) {
                if (!closed.get()) {
                    warn("netty tunnel remote->client failed sid=${session.descriptor.sessionId} error=${e.message}")
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                close(ctx)
            }
        }

        private fun close(ctx: ChannelHandlerContext) {
            if (!closed.compareAndSet(false, true)) return
            session.close()
            ctx.close()
        }
    }

    private fun track(channel: Channel) {
        acceptedChannels.incrementAndGet()
        activeChannels.incrementAndGet()
        channel.closeFuture().addListener { activeChannels.decrementAndGet() }
    }

    private fun writeHttpError(
        ctx: ChannelHandlerContext,
        status: HttpResponseStatus,
        message: String,
    ) {
        val content = Unpooled.copiedBuffer(message, StandardCharsets.UTF_8)
        val response = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, content)
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes())
        response.headers().set(HttpHeaderNames.CONNECTION, "close")
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE)
    }

    private fun parseConnectTarget(raw: String): Pair<String, Int>? {
        val trimmed = raw.trim()
        val colon = trimmed.lastIndexOf(':')
        if (colon <= 0 || colon == trimmed.lastIndex) return null
        val host = trimmed.substring(0, colon).removePrefix("[").removeSuffix("]")
        val port = trimmed.substring(colon + 1).toIntOrNull() ?: return null
        if (port !in 1..65535) return null
        return host to port
    }

    private fun statusFor(error: IOException): HttpResponseStatus =
        when ((error as? ProxyTransportException)?.failureReason) {
            ProxyTransportFailureReason.localServiceUnavailable -> HttpResponseStatus.SERVICE_UNAVAILABLE
            ProxyTransportFailureReason.upstreamTimeout -> HttpResponseStatus.GATEWAY_TIMEOUT
            ProxyTransportFailureReason.dnsFailed -> HttpResponseStatus.BAD_GATEWAY
            ProxyTransportFailureReason.networkUnreachable -> HttpResponseStatus.BAD_GATEWAY
            else -> HttpResponseStatus.BAD_GATEWAY
        }

    private fun shouldSuppressUpstreamHttpError(error: IOException): Boolean {
        if (!config.suppressUpstreamHttpErrors) return false
        return when ((error as? ProxyTransportException)?.failureReason) {
            ProxyTransportFailureReason.upstreamConnectFailed,
            ProxyTransportFailureReason.upstreamTimeout,
            ProxyTransportFailureReason.dnsFailed,
            ProxyTransportFailureReason.networkUnreachable,
            -> true
            else -> false
        }
    }

    private fun failureReasonForLog(error: IOException): String =
        (error as? ProxyTransportException)?.failureReason?.name ?: "io"

    private fun socksStatusFor(error: IOException): Socks5CommandStatus =
        when ((error as? ProxyTransportException)?.failureReason) {
            ProxyTransportFailureReason.upstreamTimeout -> Socks5CommandStatus.TTL_EXPIRED
            ProxyTransportFailureReason.dnsFailed -> Socks5CommandStatus.HOST_UNREACHABLE
            ProxyTransportFailureReason.networkUnreachable -> Socks5CommandStatus.NETWORK_UNREACHABLE
            else -> Socks5CommandStatus.FAILURE
        }

    private fun removeFrontendPipeline(ctx: ChannelHandlerContext, httpConnect: Boolean) {
        val names =
            if (httpConnect) {
                listOf(FRONTEND_HANDLER, HTTP_AGGREGATOR, HTTP_CODEC)
            } else {
                listOf(FRONTEND_HANDLER, SOCKS_DECODER_COMMAND, SOCKS_DECODER_INITIAL, SOCKS_ENCODER)
            }
        for (name in names) {
            if (ctx.pipeline().get(name) != null) {
                ctx.pipeline().remove(name)
            }
        }
    }

    private fun runtimeDiagnostics(): String =
        "accepted=${acceptedChannels.get()} active=${activeChannels.get()} pendingConnects=${pendingConnects.get()} failed=${failedChannels.get()}"

    private fun debug(message: String) = levelLogger(Log.DEBUG, message)
    private fun info(message: String) = levelLogger(Log.INFO, message)
    private fun warn(message: String) = levelLogger(Log.WARN, message)

    private companion object {
        init {
            configureNettyForAndroid()
        }

        private fun configureNettyForAndroid() {
            setNettyDefault("io.netty.noUnsafe", "true")
            setNettyDefault("io.netty.tryReflectionSetAccessible", "false")
        }

        private fun setNettyDefault(name: String, value: String) {
            if (System.getProperty(name) == null) {
                System.setProperty(name, value)
            }
        }

        private fun daemonThreadFactory(name: String): ThreadFactory =
            ThreadFactory { task -> Thread(task, name).apply { isDaemon = true } }

        private const val LISTENER_BACKLOG = 1024
        private const val MAX_HTTP_REQUEST_BYTES = 16 * 1024
        private const val DEFAULT_CONNECT_TIMEOUT_MS = 10_000L
        private const val PENDING_OPEN_BUFFER_BYTES = 64 * 1024
        private const val RELAY_BUFFER_BYTES = 8192
        private const val READ_TIMEOUT_MS = 1000
        private const val WRITE_TIMEOUT_MS = 30_000
        private const val HTTP_CODEC = "http-codec"
        private const val HTTP_AGGREGATOR = "http-aggregator"
        private const val SOCKS_ENCODER = "socks5-encoder"
        private const val SOCKS_DECODER_INITIAL = "socks5-initial-decoder"
        private const val SOCKS_DECODER_COMMAND = "socks5-command-decoder"
        private const val FRONTEND_HANDLER = "proxy-frontend-handler"
        private const val PENDING_HTTP_CONNECT_HANDLER = "pending-http-connect-handler"
    }
}

private fun String.isUnsupportedIpv6Target(): Boolean =
    contains(':') && toIpv4LiteralOrNull() == null

private fun String.toLogToken(): String =
    lowercase(Locale.US).replace(Regex("[^a-z0-9_.:-]+"), "-")
