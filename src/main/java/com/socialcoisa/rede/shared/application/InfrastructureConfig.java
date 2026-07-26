package com.socialcoisa.rede.shared.application;

import java.time.Clock;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import com.socialcoisa.rede.feed.application.port.out.IdGenerator;
import com.socialcoisa.rede.feed.domain.PostId;
import com.socialcoisa.rede.identity.adapters.out.oauth.OAuthProperties;

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

	@Bean
	@ConfigurationProperties(prefix = "rede.oauth")
	OAuthProperties oAuthProperties() {
		return new OAuthProperties();
	}

	@Bean
	@ConditionalOnMissingBean(JavaMailSender.class)
	public JavaMailSender javaMailSender() {
		return new JavaMailSenderImpl();
	}
}
