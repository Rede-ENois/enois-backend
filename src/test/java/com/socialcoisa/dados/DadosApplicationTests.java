package com.socialcoisa.dados;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;

import com.google.protobuf.CodedOutputStream;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import com.socialcoisa.rede.identity.application.port.out.AuthSessionRepository;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.profile.domain.UserProfile;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(classes = DadosApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
	useMainMethod = SpringBootTest.UseMainMethod.ALWAYS,
	properties = "server.address=127.0.0.1")
class DadosApplicationTests {

	private static final byte[] CHAVE = new byte[32];

	@DynamicPropertySource
	static void configurarChave(DynamicPropertyRegistry registry) throws Exception {
		new SecureRandom().nextBytes(CHAVE);
		Path arquivo = Files.createTempFile("enois-dados-", ".key",
			PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
		arquivo.toFile().deleteOnExit();
		Files.write(arquivo, CHAVE);
		registry.add("rede.dados.chave-arquivo", arquivo::toString);
	}

	@Autowired
	ServletWebServerApplicationContext contexto;

	@LocalServerPort
	int porta;

	@Autowired
	UserProfileRepository perfis;

	@Autowired
	AuthSessionRepository sessoes;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	ObjectMapper json;

	@Test
	void autorizaSomenteReplicaDoAppEExpoeSomenteOperacoesDeDados() throws Exception {
		try (var cliente = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()) {
			assertThat(contexto.getBeansOfType(DataSource.class)).hasSize(1);
			assertThat(contexto.getBeansOfType(Flyway.class)).hasSize(1);
			assertThat(contexto.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class)).isEmpty();
			assertThat(contexto.getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping.class).getHandlerMethods().keySet())
				.flatExtracting(info -> info.getPatternValues())
				.containsExactlyInAnyOrder("/internal/data/v1/ready", "/internal/data/v1/posts/{postId}",
					"/internal/data/v1/posts", "/internal/data/v1/session",
					"/internal/data/v1/browser/{operacao:login|refresh|logout|verify}",
					"/internal/data/v1/browser/config", "/internal/data/v1/browser/signup-challenge",
					"/internal/data/v1/browser/signup", "/internal/data/v1/browser/email/verify",
					"/internal/data/v1/browser/email/resend", "/internal/data/v1/feed",
					"/internal/data/v1/notifications", "/internal/data/v1/notifications/unread-summary",
					"/internal/data/v1/notifications/{notificationId}/read", "/internal/data/v1/notifications/read-all",
					"/error", "/error");

			long agora = System.currentTimeMillis();
			var grant = grant(CHAVE, "enois-backend", "data", 1, agora + 10000);
			var pronto = consultar(cliente, porta, "/internal/data/v1/ready", grant);
			assertThat(pronto.statusCode()).isEqualTo(204);
			assertThat(pronto.headers().firstValue("X-Enois-Data-Protocol")).contains("1");
			assertThat(consultar(cliente, porta, "/internal/data/v1/ready", null).statusCode()).isEqualTo(401);
			assertThat(consultar(cliente, porta, "/internal/data/v1/browser/signup-challenge", null).statusCode())
				.isEqualTo(401);
			for (String caminho : new String[]{"/internal/data/v1/feed", "/internal/data/v1/notifications",
					"/internal/data/v1/notifications/unread-summary"}) {
				assertThat(consultar(cliente, porta, caminho, null).statusCode()).isEqualTo(401);
				var semSessao = consultar(cliente, porta, caminho, grant);
				assertThat(semSessao.statusCode()).isEqualTo(401);
				assertThat(semSessao.headers().firstValue("X-Enois-Data-Protocol")).contains("1");
			}
			for (String recusado : new String[] {
					grant(CHAVE, "outro-app", "data", 1, agora + 10000),
					grant(CHAVE, "enois-backend", "tasks", 1, agora + 10000),
					grant(CHAVE, "enois-backend", "data", 4, agora + 10000),
					grant(CHAVE, "enois-backend", "data", 1, agora - 1000)
			}) {
				assertThat(consultar(cliente, porta, "/internal/data/v1/ready", recusado).statusCode()).isEqualTo(401);
			}
			assertThat(consultar(cliente, porta, "/internal/data/v1/posts/j0-ausente", grant).statusCode()).isEqualTo(404);
			assertThat(consultar(cliente, porta, "/auth/me", grant).statusCode()).isEqualTo(404);
			assertThat(consultar(cliente, porta, "/actuator/health", null).statusCode()).isEqualTo(404);
		}
	}

	@Test
	void revogacaoFicaVisivelParaOutraReplica() throws Exception {
		String token = sessaoTeste();
		long expira = System.currentTimeMillis() + 10000;
		var replicaA = grant(CHAVE, "enois-backend", "data", 1, expira);
		var replicaB = grant(CHAVE, "enois-backend", "data", 1, expira + 1000);
		try (var clienteA = HttpClient.newHttpClient(); var clienteB = HttpClient.newHttpClient()) {
			assertThat(consultar(clienteA, porta, "/internal/data/v1/session", replicaA, token).statusCode()).isEqualTo(200);
			assertThat(consultar(clienteB, porta, "/internal/data/v1/session", replicaB, token).statusCode()).isEqualTo(200);
			sessoes.revoke(token, Instant.now());
			var resposta = consultar(clienteB, porta, "/internal/data/v1/session", replicaB, token);
			assertThat(resposta.statusCode()).isEqualTo(401);
			assertThat(resposta.headers().firstValue("X-Enois-Data-Protocol")).contains("1");
		}
	}

	@Test
	void publicaUmaVezEReproduzRespostaComOutraCredencialDeReplica() throws Exception {
		String token = sessaoTeste();
		long expira = System.currentTimeMillis() + 10000;
		var replicaA = grant(CHAVE, "enois-backend", "data", 1, expira);
		var replicaB = grant(CHAVE, "enois-backend", "data", 1, expira + 1000);
		String texto = "{\"type\":\"INSIGHT\",\"content\":\"Texto da migração.\",\"topic\":\"TECNOLOGIA\"}";
		try (var clienteA = HttpClient.newHttpClient(); var clienteB = HttpClient.newHttpClient()) {
			assertThat(publicar(clienteA, null, token, texto).statusCode()).isEqualTo(401);
			var original = publicar(clienteA, replicaA, token, texto);
			assertThat(original.statusCode()).isEqualTo(201);
			var repetida = publicar(clienteB, replicaB, token, texto);
			assertThat(repetida.statusCode()).isEqualTo(201);
			assertThat(repetida.body()).isEqualTo(original.body());
			assertThat(publicar(clienteB, replicaB, token, texto.replace("Texto", "Outro texto")).statusCode()).isEqualTo(409);
			String id = json.readTree(original.body()).get("id").asText();
			assertThat(consultar(clienteB, porta, "/internal/data/v1/posts/" + id, replicaB).statusCode()).isEqualTo(200);
			assertThat(jdbc.queryForObject("select count(*) from outbox_events where aggregate_id = ? and event_type = ?",
				Integer.class, id, "PostPublished")).isEqualTo(1);
		}
	}

	private String sessaoTeste() {
		String id = "j0sessao" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
		Instant agora = Instant.now();
		var perfil = UserProfile.create(id, id, "contato_" + id, "Sessão de teste", null, agora);
		perfil.markEmailVerified(agora);
		perfil.declareBirthDate(java.time.LocalDate.now().minusYears(30), agora);
		perfis.save(perfil);
		String token = UUID.randomUUID().toString();
		sessoes.save(token, UUID.randomUUID().toString(), id, agora, agora.plusSeconds(300), agora.plusSeconds(600), null);
		return token;
	}

	private HttpResponse<String> publicar(HttpClient cliente, String grant, String token, String texto) throws Exception {
		var pedido = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + porta + "/internal/data/v1/posts"))
			.timeout(Duration.ofSeconds(3))
			.header("Content-Type", "application/json")
			.header("Idempotency-Key", "publicacao-j0")
			.header("X-Session-Token", token)
			.POST(HttpRequest.BodyPublishers.ofString(texto));
		if (grant != null) {
			pedido.header("Authorization", "Bearer " + grant);
		}
		return cliente.send(pedido.build(), HttpResponse.BodyHandlers.ofString());
	}

	private static HttpResponse<String> consultar(HttpClient cliente, int porta, String caminho, String grant)
			throws Exception {
		return consultar(cliente, porta, caminho, grant, null);
	}

	private static HttpResponse<String> consultar(HttpClient cliente, int porta, String caminho, String grant, String sessao)
			throws Exception {
		var pedido = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + porta + caminho))
			.timeout(Duration.ofSeconds(3));
		if (grant != null) {
			pedido.header("Authorization", "Bearer " + grant);
		}
		if (sessao != null) {
			pedido.header("X-Session-Token", sessao);
		}
		return cliente.send(pedido.build(), HttpResponse.BodyHandlers.ofString());
	}

	private static String grant(byte[] chave, String app, String audiencia, int papel, long expira) throws Exception {
		var bytes = new ByteArrayOutputStream();
		var payload = CodedOutputStream.newInstance(bytes);
		payload.writeString(1, audiencia);
		payload.writeString(2, "replica-" + UUID.randomUUID());
		payload.writeString(3, "sessao-teste");
		payload.writeString(4, app);
		payload.writeTag(5, 2);
		payload.writeUInt32NoTag(1);
		payload.writeEnumNoTag(papel);
		payload.writeBool(8, true);
		payload.writeInt64(10, 1);
		payload.writeInt64(11, 1);
		payload.writeInt64(12, 1);
		payload.writeInt64(13, expira - 10000);
		payload.writeInt64(14, expira);
		payload.flush();
		var mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec(chave, "HmacSHA256"));
		var base = Base64.getUrlEncoder().withoutPadding();
		return base.encodeToString(bytes.toByteArray()) + "." + base.encodeToString(mac.doFinal(bytes.toByteArray()));
	}
}
