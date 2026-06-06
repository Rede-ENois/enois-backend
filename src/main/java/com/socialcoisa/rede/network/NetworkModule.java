package com.socialcoisa.rede.network;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.socialcoisa.rede.feed.application.port.out.ConnectionGraphRepository;
import com.socialcoisa.rede.network.application.NetworkService;
import com.socialcoisa.rede.network.adapters.out.graph.JpaConnectionGraphRepository;

@Configuration
public class NetworkModule {

	@Bean
	ConnectionGraphRepository connectionGraphRepository(NetworkService networkService) {
		return new JpaConnectionGraphRepository(networkService);
	}

}
