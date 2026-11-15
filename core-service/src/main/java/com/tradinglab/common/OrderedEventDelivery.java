package com.tradinglab.common;

import java.util.concurrent.Executor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Sends outbox events to Kafka on one thread, in the order they were handed over.
 *
 * <p>Spring Modulith delivers each event with {@code @Async}. With virtual threads on, that means
 * a new thread per event, and two sends can overtake each other: a test saw both PLACED events of
 * an account reach Kafka before its first FILLED. Keying by account only keeps order <em>inside</em>
 * Kafka, so the sender has to keep it on the way in.
 *
 * <p>One thread is the simplest way, and plenty at this scale, because a send only hands the
 * message to the Kafka client's buffer. A rare race remains: two transactions committing back to
 * back on different threads can hand their events over in either order. So consumers still mustn't
 * depend on exact order. The journal doesn't: it recomputes from the fills.
 *
 * <p>Events are safe while they wait here. They're already in {@code event_publication}, and
 * {@link OutboxRelay} retries any that fail.
 */
@Configuration(proxyBeanMethods = false)
class OrderedEventDelivery implements AsyncConfigurer {

	private final ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

	OrderedEventDelivery() {
		executor.setCorePoolSize(1);
		executor.setMaxPoolSize(1);
		executor.setThreadNamePrefix("outbox-");
		// Let queued events go out before shutdown. Anything left is resent after the restart.
		executor.setWaitForTasksToCompleteOnShutdown(true);
		executor.setAwaitTerminationSeconds(10);
	}

	@Bean
	ThreadPoolTaskExecutor outboxExecutor() {
		return executor;
	}

	@Override
	public Executor getAsyncExecutor() {
		return executor;
	}

}
