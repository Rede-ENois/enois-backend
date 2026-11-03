package com.socialcoisa.rede.push.application;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Executor do envio de Web Push.
 *
 * <p>O envio faz rede para cada dispositivo e nao pode rodar na thread que criou a notificacao.
 * Pool pequeno (bulkhead) com fila limitada: se o servico de push ficar lento, as notificacoes
 * em excesso sao descartadas em vez de crescerem sem limite na memoria. Push e melhor esforco;
 * a caixa de entrada do app continua completa.</p>
 */
@Configuration(proxyBeanMethods = false)
class PushDeliveryConfig {

	private static final int CORE_THREADS = 2;
	private static final int MAX_THREADS = 4;
	private static final int QUEUE_CAPACITY = 500;

	@Bean(name = "pushExecutor", destroyMethod = "shutdown")
	ExecutorService pushExecutor() {
		return new ThreadPoolExecutor(CORE_THREADS, MAX_THREADS, 60, TimeUnit.SECONDS,
			new ArrayBlockingQueue<>(QUEUE_CAPACITY), runnable -> {
				Thread thread = new Thread(runnable, "web-push");
				thread.setDaemon(true);
				return thread;
			}, new ThreadPoolExecutor.AbortPolicy());
	}
}
