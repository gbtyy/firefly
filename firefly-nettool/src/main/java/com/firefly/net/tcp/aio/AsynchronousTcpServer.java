package com.firefly.net.tcp.aio;

import static com.firefly.net.tcp.TcpPerformanceParameter.BACKLOG;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.channels.AsynchronousChannelGroup;
import java.nio.channels.AsynchronousServerSocketChannel;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.firefly.net.Config;
import com.firefly.net.Decoder;
import com.firefly.net.Encoder;
import com.firefly.net.EventManager;
import com.firefly.net.Handler;
import com.firefly.net.Server;
import com.firefly.net.event.DefaultEventManager;
import com.firefly.net.exception.NetException;
import com.firefly.utils.log.Log;
import com.firefly.utils.log.LogFactory;

public class AsynchronousTcpServer implements Server {
	
	private static Log log = LogFactory.getInstance().getLog("firefly-system");
	private Config config;
	private AtomicInteger id = new AtomicInteger();
	private AsynchronousTcpWorker worker;
	private AsynchronousChannelGroup group;
	private volatile boolean isInitialized = false;

	public AsynchronousTcpServer() {}
	
	public AsynchronousTcpServer(Config config) {
		this.config = config;
	}
	
	public AsynchronousTcpServer(Decoder decoder, Encoder encoder, Handler handler) {
		config = new Config();
		config.setDecoder(decoder);
		config.setEncoder(encoder);
		config.setHandler(handler);
	}
	
	public AsynchronousTcpServer(Decoder decoder, Encoder encoder, Handler handler, int timeout) {
		config = new Config();
		config.setDecoder(decoder);
		config.setEncoder(encoder);
		config.setHandler(handler);
		config.setTimeout(timeout);
	}
	
	private void init() {
		if(isInitialized)
			return;
		
		synchronized(this) {
			if(isInitialized)
				return;
			
			try {
				group = AsynchronousChannelGroup.withThreadPool(new ThreadPoolExecutor(
						config.getAsynchronousCorePoolSize(),
						config.getAsynchronousMaximumPoolSize(), 
						config.getAsynchronousPoolKeepAliveTime(), 
						TimeUnit.MILLISECONDS, 
						new LinkedTransferQueue<Runnable>(),
						new ThreadFactory(){
	
							@Override
							public Thread newThread(Runnable r) {
								return new Thread(r, "firefly asynchronous server thread");
							}
						}));
				EventManager eventManager = new DefaultEventManager(config);
				worker = new AsynchronousTcpWorker(config, eventManager);
			} catch (IOException e) {
				log.error("initialization server channel group error", e);
			}
			isInitialized = true;
		}
	}

	@Override
	public void setConfig(Config config) {
		this.config = config;
	}

	@Override
	public void start(String host, int port) {
		if (config == null)
			throw new NetException("server config is null");
		init();
		listen(bind(host, port));
	}
	
	private AsynchronousServerSocketChannel bind(String host, int port) {
		AsynchronousServerSocketChannel serverSocketChannel = null;
		try {
			log.info("asychronous I/O thread pool [{}], [{}], [{}]", 
					config.getAsynchronousCorePoolSize(),
					config.getAsynchronousMaximumPoolSize(),
					config.getAsynchronousPoolKeepAliveTime());
			serverSocketChannel = AsynchronousServerSocketChannel.open(group);
			serverSocketChannel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
			serverSocketChannel.bind(new InetSocketAddress(host, port), BACKLOG);
		} catch (Exception e) {
			log.error("ServerSocket bind error", e);
		}
		return serverSocketChannel;
	}
	
	private void listen(final AsynchronousServerSocketChannel serverSocketChannel) {
		serverSocketChannel.accept(id.getAndIncrement(), new CompletionHandler<AsynchronousSocketChannel, Integer>(){

			@Override
			public void completed(AsynchronousSocketChannel socketChannel, Integer sessionId) {
				try {
					worker.registerChannel(socketChannel, sessionId);
				} finally {
					listen(serverSocketChannel);
				}
			}

			@Override
			public void failed(Throwable exc, Integer id) {
				try {
					log.error("server accepts channel " + id + " error occurs", exc);
				} finally {
					listen(serverSocketChannel);
				}
			}} );
	}

	@Override
	public void shutdown() {
		if(group != null)
			group.shutdown();
	}

}
