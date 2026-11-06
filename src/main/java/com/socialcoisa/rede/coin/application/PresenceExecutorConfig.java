package com.socialcoisa.rede.coin.application;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Where the daily presence of an action is recorded, after the action's transaction is over.
 *
 * <p>It used to run in the action's afterCommit, in a new transaction: the request still held its
 * own connection there and took a second one. A burst of actions on one post finishing together
 * held every pooled connection while each waited for a second, and the pool stalled until its
 * timeout. Here the request returns its connection first. One thread is enough (the work is a
 * primary-key read on most days) and keeps the writes to the treasury row in a single line.
 */
@Configuration
class PresenceExecutorConfig {

	private static final int QUEUE_CAPACITY = 2_000;

	@Bean(name = "presenceExecutor", destroyMethod = "shutdown")
	ExecutorService presenceExecutor() {
		return new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(QUEUE_CAPACITY),
			runnable -> {
				Thread thread = new Thread(runnable, "coin-presence");
				thread.setDaemon(true);
				return thread;
			}, new ThreadPoolExecutor.AbortPolicy());
	}
}
