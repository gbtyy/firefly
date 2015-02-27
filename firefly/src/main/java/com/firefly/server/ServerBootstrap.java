package com.firefly.server;

import java.io.File;

import com.firefly.mvc.web.WebContext;
import com.firefly.mvc.web.servlet.HttpServletDispatcherController;
import com.firefly.mvc.web.view.TemplateView;
import com.firefly.net.Server;
import com.firefly.net.tcp.aio.AsynchronousTcpServer;
import com.firefly.net.tcp.nio.TcpServer;
import com.firefly.server.http.Config;
import com.firefly.server.http.HttpDecoder;
import com.firefly.server.http.HttpEncoder;
import com.firefly.server.http.HttpHandler;
import com.firefly.server.http.SSLDecoder;
import com.firefly.server.http.SSLEncoder;
import com.firefly.utils.VerifyUtils;
import com.firefly.utils.log.Log;
import com.firefly.utils.log.LogFactory;

public class ServerBootstrap {
	private static Log log = LogFactory.getInstance().getLog("firefly-system");

	public static void start(String serverHome, String host, int port) {
		Config config = new Config(serverHome, host, port);
		start(config);
	}

	public static void start(String configFileName, String serverHome, String host, int port) {
		Config config = new Config(serverHome, host, port);
		config.setConfigFileName(configFileName);
		start(config);
	}
	
	public static void start(Config config) {
		long start = System.currentTimeMillis();
		WebContext context = new ServerAnnotationWebContext(config);
		try {
			Config c = context.getBean(Config.class);
			init(context, c == null ? config : c);
		} catch (Throwable e) {
			log.error("firefly init error", e);
		}
		long end = System.currentTimeMillis();
		log.info("firefly startup in {} ms", (end - start));
	}
	
	public static void start(String configFileName) {
		long start = System.currentTimeMillis();
		WebContext context = new ServerAnnotationWebContext(configFileName);
		try {
			init(context, context.getBean(Config.class));
		} catch (Throwable e) {
			log.error("firefly init error", e);
		}
		long end = System.currentTimeMillis();
		log.info("firefly startup in {} ms", (end - start));
	}
	
	private static void init(WebContext context, Config config) throws Throwable {		
		HttpServletDispatcherController controller = new HttpServletDispatcherController(context);
		config.setEncoding(context.getEncoding());
		
		File tempdir = null;
		if(VerifyUtils.isEmpty(config.getTempdir())) {
			tempdir = new File(TemplateView.getViewPath(), "_firefly_tmpdir");
			config.setTempdir(tempdir.getAbsolutePath());
		} else {
			tempdir = new File(config.getTempdir());
		}
		
		if(!tempdir.exists()) {
			tempdir.mkdirs();
		}
		
		log.info("firefly server tempdir [{}]", config.getTempdir());
		log.info("keep alive [{}]", config.isKeepAlive());
		log.info("net processor type [{}]", config.getNetProcessorType());
		log.info("max connection timeout [{}]", config.getMaxConnectionTimeout());
		if(config.isSecure()) {
			log.info("enable SSL");
			Server server = null;
			switch (config.getNetProcessorType()) {
			case "nio":
				server = new TcpServer(
						new SSLDecoder(new HttpDecoder(config)), 
						new SSLEncoder(), 
						new HttpHandler(controller, config), 
						config.getMaxConnectionTimeout());
				server.start(config.getHost(), config.getPort());
				break;
			case "aio":
				config.setRequestHandler("currentThread");
				com.firefly.net.Config netConfig = new com.firefly.net.Config();
				netConfig.setDecoder(new SSLDecoder(new HttpDecoder(config)));
				netConfig.setEncoder(new SSLEncoder());
				netConfig.setHandler(new HttpHandler(controller, config));
				netConfig.setTimeout(config.getMaxConnectionTimeout());
				netConfig.setAsynchronousMaximumPoolSize(config.getAsynchronousMaximumPoolSize());
				netConfig.setAsynchronousPoolKeepAliveTime(config.getAsynchronousPoolKeepAliveTime());
				server = new AsynchronousTcpServer();
				server.setConfig(netConfig);
				server.start(config.getHost(), config.getPort());
				break;
			default:
				break;
			}
		} else {
			Server server = null;
			switch (config.getNetProcessorType()) {
			case "nio":
				server = new TcpServer(
						new HttpDecoder(config), 
						new HttpEncoder(), 
						new HttpHandler(controller, config), 
						config.getMaxConnectionTimeout());
				server.start(config.getHost(), config.getPort());
				break;
			case "aio":				
				config.setRequestHandler("currentThread");
				com.firefly.net.Config netConfig = new com.firefly.net.Config();
				netConfig.setDecoder(new HttpDecoder(config));
				netConfig.setEncoder(new HttpEncoder());
				netConfig.setHandler(new HttpHandler(controller, config));
				netConfig.setTimeout(config.getMaxConnectionTimeout());
				netConfig.setAsynchronousMaximumPoolSize(config.getAsynchronousMaximumPoolSize());
				netConfig.setAsynchronousPoolKeepAliveTime(config.getAsynchronousPoolKeepAliveTime());
				server = new AsynchronousTcpServer();
				server.setConfig(netConfig);
				server.start(config.getHost(), config.getPort());
				break;
			default:
				break;
			}
		}
	}
}
