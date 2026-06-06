package com.socialcoisa.rede.shared.application;

import java.time.Clock;
import java.util.UUID;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.socialcoisa.rede.feed.application.port.out.IdGenerator;
import com.socialcoisa.rede.feed.domain.PostId;

@Configuration
public class InfrastructureConfig {

	@Bean
	Clock systemClock() {
		return Clock.systemUTC();
	}

	@Bean
	IdGenerator idGenerator() {
		return () -> new PostId("post_" + UUID.randomUUID().toString().replace("-", ""));
	}
}
