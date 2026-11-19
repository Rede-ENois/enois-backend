package com.socialcoisa.replica;

import java.net.InetAddress;
import java.util.Map;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.info.ProjectInfoAutoConfiguration;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.error.ErrorMvcAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import com.socialcoisa.rede.shared.web.OperationalController;

@Configuration(proxyBeanMethods = false)
@Import({OperationalController.class, ReplicaVersionController.class, ProntidaoReplicaController.class,
	ClienteDados.class, ApiReplicaController.class, CorsReplica.class})
@ImportAutoConfiguration({
	ProjectInfoAutoConfiguration.class,
	TomcatServletWebServerAutoConfiguration.class,
	DispatcherServletAutoConfiguration.class,
	WebMvcAutoConfiguration.class,
	ErrorMvcAutoConfiguration.class,
	JacksonAutoConfiguration.class,
	HttpMessageConvertersAutoConfiguration.class
})
public class ReplicaApplication {

	public static void main(String[] args) {
		iniciar(args);
	}

	static ConfigurableApplicationContext iniciar(String... args) {
		SpringApplication app = new SpringApplication(ReplicaApplication.class);
		app.setDefaultProperties(Map.of(
			"spring.config.name", "replica",
			"server.address", "127.0.0.1",
			"server.port", "8081",
			"spring.web.resources.add-mappings", "false"
		));
		app.addListeners((ApplicationEnvironmentPreparedEvent event) -> {
			InetAddress endereco = Binder.get(event.getEnvironment()).bind("server.address", InetAddress.class).orElse(null);
			if (endereco == null || !endereco.isLoopbackAddress()) {
				throw new IllegalStateException("A réplica Java exige server.address em loopback.");
			}
			var ambiente = event.getEnvironment();
			if (!ambiente.getProperty("rede.replica.context-socket", "").isBlank()
					&& !ambiente.getProperty("server.ssl.enabled", Boolean.class, false)) {
				throw new IllegalStateException("A réplica atribuída exige HTTPS no listener local.");
			}
		});
		return app.run(args);
	}
}
