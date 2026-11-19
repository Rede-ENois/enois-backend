package com.socialcoisa.dados;

import java.util.Map;

import jakarta.servlet.Filter;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ControllerAdvice;

import com.socialcoisa.rede.RedeApplication;
import com.socialcoisa.rede.emotes.application.EmoteCatalogScheduler;
import com.socialcoisa.rede.jobs.application.ExternalJobRefreshScheduler;
import com.socialcoisa.rede.shared.outbox.AgendamentoOutboxDados;

@SpringBootConfiguration(proxyBeanMethods = false)
@EnableAutoConfiguration
@AutoConfigurationPackage(basePackages = "com.socialcoisa.rede")
@ComponentScan(basePackages = "com.socialcoisa.rede", excludeFilters = {
	@ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class),
	@ComponentScan.Filter(type = FilterType.REGEX,
		pattern = "com\\.socialcoisa\\.rede\\.shared\\.web\\.SecurityWebMvcConfig"),
	@ComponentScan.Filter(type = FilterType.ANNOTATION, classes = {Controller.class, ControllerAdvice.class}),
	@ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = {
		RedeApplication.class, Filter.class, EmoteCatalogScheduler.class, ExternalJobRefreshScheduler.class
	})
})
@Import({CredencialDados.class, DadosController.class, ErrosDados.class, AgendamentoOutboxDados.class})
public class DadosApplication {

	public static void main(String[] args) {
		iniciar(args);
	}

	static ConfigurableApplicationContext iniciar(String... args) {
		SpringApplication app = new SpringApplication(DadosApplication.class);
		app.setDefaultProperties(Map.of(
			"spring.web.resources.add-mappings", "false",
			"management.endpoints.web.exposure.exclude", "*"
		));
		app.addListeners((ApplicationEnvironmentPreparedEvent event) -> {
			var env = event.getEnvironment();
			if (env.getActiveProfiles().length == 0) {
				throw new IllegalStateException("Defina SPRING_PROFILES_ACTIVE para o serviço de dados.");
			}
			if (!env.getProperty("server.ssl.enabled", Boolean.class, false) &&
					!env.getProperty("server.address", "").matches("127\\.0\\.0\\.1|::1")) {
				throw new IllegalStateException("O serviço de dados exige HTTPS ou um listener em loopback.");
			}
		});
		return app.run(args);
	}
}
