package com.socialcoisa.rede.feed;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.socialcoisa.rede.feed.adapters.out.network.InMemoryConnectionGraphRepository;
import com.socialcoisa.rede.feed.application.port.out.ConnectionGraphRepository;

@Configuration
class FeedConfig {

	@Bean
	@ConditionalOnMissingBean(ConnectionGraphRepository.class)
	ConnectionGraphRepository inMemoryConnectionGraph() {
		return new InMemoryConnectionGraphRepository();
	}

}
