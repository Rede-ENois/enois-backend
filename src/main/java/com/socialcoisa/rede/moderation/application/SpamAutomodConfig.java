package com.socialcoisa.rede.moderation.application;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Executor do automod de spam: UMA thread daemon, fila propria e LIMITADA.
 *
 * Uma thread so de proposito: a analise chama o LLM (ate 30s) e nao deve competir com as threads
 * de requisicao. A fila era ilimitada, entao uma rajada de publicacoes crescia sem teto na
 * memoria. Quando a fila enche, o evento e descartado (automod e melhor esforco) em vez de
 * derrubar a publicacao ou estourar o heap.
 */
@Configuration(proxyBeanMethods = false)
public class SpamAutomodConfig {

	private static final Logger LOG = LoggerFactory.getLogger(SpamAutomodConfig.class);
	private static final int QUEUE_CAPACITY = 200;

	@Bean(name = "spamAutomodExecutor", destroyMethod = "shutdown")
	ExecutorService spamAutomodExecutor() {
		return new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
			new ArrayBlockingQueue<>(QUEUE_CAPACITY), runnable -> {
				Thread thread = new Thread(runnable, "automod-spam");
				thread.setDaemon(true);
				return thread;
			}, (runnable, executor) -> LOG.warn("fila do automod cheia; evento descartado"));
	}
}
