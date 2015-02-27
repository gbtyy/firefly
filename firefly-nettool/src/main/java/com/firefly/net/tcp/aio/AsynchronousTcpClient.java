package com.firefly.net.tcp.aio;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.channels.AsynchronousChannelGroup;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.firefly.net.Client;
import com.firefly.net.Config;
import com.firefly.net.Decoder;
import com.firefly.net.Encoder;
import com.firefly.net.EventManager;
import com.firefly.net.Handler;
import com.firefly.net.event.DefaultEventManager;
import com.firefly.utils.log.Log;
import com.firefly.utils.log.LogFactory;

public class AsynchronousTcpClient implements Client {
	
	private static Log log = LogFactory.getInstance().getLog("firefly-system");
    private Config config;
    private AtomicInteger sessionId = new AtomicInteger(0);
    private AsynchronousChannelGroup group;
    private AsynchronousTcpWorker worker;
    private volatile boolean isInitialized = false;
    
    public AsynchronousTcpClient() {}
    
    public AsynchronousTcpClient(Config config) {
    	this.config = config;
    }
    
    public AsynchronousTcpClient(Decoder decoder, Encoder encoder, Handler handler) {
        config = new Config();
        config.setDecoder(decoder);
        config.setEncoder(encoder);
        config.setHandler(handler);
    }
    
    public AsynchronousTcpClient(Decoder decoder, Encoder encoder, Handler handler, int timeout) {
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
	public int connect(String host, int port) {
		int id = sessionId.getAndIncrement();
		connect(host, port, id);
		return id;
	}

	@Override
	public void connect(String host, int port, int id) {
		init();
		try {
			final AsynchronousSocketChannel socketChannel = AsynchronousSocketChannel.open(group);
			socketChannel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
			socketChannel.setOption(StandardSocketOptions.SO_KEEPALIVE, true);
			socketChannel.setOption(StandardSocketOptions.TCP_NODELAY, false);
			socketChannel.connect(new InetSocketAddress(host, port), id, new CompletionHandler<Void, Integer>(){

				@Override
				public void completed(Void result, Integer sessionId) {
					worker.registerChannel(socketChannel, sessionId);
				}

				@Override
				public void failed(Throwable t, Integer sessionId) {
					log.error("session {} connect error", t, sessionId);
				}});
		} catch (IOException e) {
			log.error("client connect error", e);
		}
	}

	@Override
	public void shutdown() {
		if(group != null)
			group.shutdown();
	}

}
