package com.socialcoisa.replica;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import javax.sql.DataSource;

import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.json.JsonMapper;

class ReplicaApplicationTests {

	@Test
	void sobeComSondasEIsolamentoDeDados() throws Exception {
		try (var contexto = ReplicaApplication.iniciar("--server.port=0",
				"--spring.datasource.url=jdbc:mariadb://localhost:1/replica",
				"--spring.flyway.enabled=true",
				"--rede.replica.cors-origins=https://frontend.example.invalid")) {
			assertThat(contexto.getBeansOfType(DataSource.class)).isEmpty();
			assertThat(contexto.getBeansOfType(JdbcOperations.class)).isEmpty();
			assertThat(contexto.getBeansOfType(EntityManagerFactory.class)).isEmpty();
			assertThat(contexto.getBeansOfType(PlatformTransactionManager.class)).isEmpty();
			assertThat(contexto.getBeansOfType(Flyway.class)).isEmpty();
			assertThat(contexto.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class)).isEmpty();
			assertThat(contexto.getBean(RequestMappingHandlerMapping.class).getHandlerMethods().keySet())
				.flatExtracting(info -> info.getPatternValues())
				.containsExactlyInAnyOrder("/health", "/version", "/readyz", "/feed/posts/{postId}", "/feed/posts",
					"/auth/me", "/auth/login", "/auth/logout", "/auth/refresh", "/auth/2fa/verify",
					"/auth/config", "/auth/signup-challenge", "/auth/signup", "/auth/email/verify",
					"/auth/email/resend", "/feed", "/notifications", "/notifications/unread-summary",
					"/notifications/{notificationId}/read", "/notifications/read-all", "/error", "/error");

			int porta = ((ServletWebServerApplicationContext) contexto).getWebServer().getPort();
			var json = JsonMapper.builder().build();
			try (var cliente = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()) {
				var vida = consultar(cliente, porta, "/health");
				assertThat(vida.statusCode()).isEqualTo(200);
				assertThat(json.readTree(vida.body()).get("status").asText()).isEqualTo("UP");
				var versao = consultar(cliente, porta, "/version");
				assertThat(versao.statusCode()).isEqualTo(200);
				var corpo = json.readTree(versao.body());
				assertThat(corpo.get("version").asText()).isNotBlank().isNotEqualTo("desconhecida");
				assertThat(corpo.get("artefato").asText()).isNotBlank();
				assertThat(corpo.has("flyway")).isFalse();
				assertThat(consultar(cliente, porta, "/auth/me").statusCode()).isEqualTo(503);
				assertThat(consultar(cliente, porta, "/network/nodes").statusCode()).isEqualTo(404);
				assertThat(consultar(cliente, porta, "/readyz").statusCode()).isEqualTo(503);
				assertThat(consultar(cliente, porta, "/feed/posts/j0-sem-dados").statusCode()).isEqualTo(503);
				var preflight = preflight(cliente, porta, "https://frontend.example.invalid");
				assertThat(preflight.statusCode()).isEqualTo(200);
				assertThat(preflight.headers().firstValue("Access-Control-Allow-Credentials")).contains("true");
				assertThat(preflight.headers().firstValue("Access-Control-Allow-Origin")).contains("https://frontend.example.invalid");
				assertThat(preflight(cliente, porta, "https://outro.example.invalid").statusCode()).isEqualTo(403);
				var escrita = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + porta + "/feed/posts"))
					.header("Cookie", "enois_session=sample")
					.header("Content-Type", "application/json")
					.POST(HttpRequest.BodyPublishers.ofString("{}"));
				var semCsrf = cliente.send(escrita.build(), HttpResponse.BodyHandlers.ofString());
				assertThat(semCsrf.statusCode()).isEqualTo(403);
				assertThat(json.readTree(semCsrf.body()).get("code").asText()).isEqualTo("CSRF_REQUIRED");
				var comCsrf = cliente.send(escrita.header("X-CSRF-Protection", "1").build(),
					HttpResponse.BodyHandlers.ofString());
				assertThat(comCsrf.statusCode()).isEqualTo(503);
			}
		}
	}

	private HttpResponse<String> preflight(HttpClient cliente, int porta, String origem) throws Exception {
		var requisicao = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + porta + "/feed/posts/j0-sem-dados"))
			.method("OPTIONS", HttpRequest.BodyPublishers.noBody())
			.header("Origin", origem)
			.header("Access-Control-Request-Method", "GET")
			.header("Access-Control-Request-Headers", "X-Session-Token")
			.timeout(Duration.ofSeconds(3))
			.build();
		return cliente.send(requisicao, HttpResponse.BodyHandlers.ofString());
	}

	@Test
	void recusaEnderecoForaDeLoopback() {
		assertThatThrownBy(() -> ReplicaApplication.iniciar("--server.address=0.0.0.0"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("loopback");
	}

	private HttpResponse<String> consultar(HttpClient cliente, int porta, String caminho) throws Exception {
		var requisicao = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + porta + caminho))
			.timeout(Duration.ofSeconds(3))
			.build();
		return cliente.send(requisicao, HttpResponse.BodyHandlers.ofString());
	}
}
