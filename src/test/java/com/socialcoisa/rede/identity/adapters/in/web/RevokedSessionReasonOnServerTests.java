package com.socialcoisa.rede.identity.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import com.socialcoisa.rede.shared.web.TestAuthHelper;

import org.junit.jupiter.api.Tag;
import tools.jackson.databind.ObjectMapper;

/**
 * O motivo da sessão encerrada chega ao cliente num servidor de verdade.
 *
 * <p>Os testes de MockMvc rodam na mesma thread que construiu o contexto; o defeito de origem era
 * justamente a dependência que o tratamento de exceções só alcançava no thread do boot e não no
 * thread do pedido. Aqui o pedido sai por HTTP de verdade, é atendido no thread do Tomcat e o que
 * se confere é o corpo da resposta — o caminho que a pessoa usando o app percorre.</p>
 */
@Tag("critico")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class RevokedSessionReasonOnServerTests {

	@LocalServerPort
	private int porta;

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	private final HttpClient cliente = HttpClient.newHttpClient();

	/** A conta nasce pelo caminho de teste (o token cru só existe no e-mail); o resto é HTTP real. */
	private String tokenDe(String conta) throws Exception {
		return new TestAuthHelper(mockMvc, objectMapper).authenticate(conta).token();
	}

	private HttpResponse<String> comSessao(String metodo, String caminho, String token, String corpo)
			throws Exception {
		var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + porta + caminho))
			.header("X-Session-Token", token)
			.header("Content-Type", MediaType.APPLICATION_JSON_VALUE);
		return cliente.send(
			(builder.method(metodo, corpo == null ? HttpRequest.BodyPublishers.noBody()
				: HttpRequest.BodyPublishers.ofString(corpo))).build(),
			HttpResponse.BodyHandlers.ofString());
	}

	@Test
	void oPedidoDeUmServidorRealRecebeOMotivoDaSessaoEncerrada() throws Exception {
		String token = tokenDe("sessao-com-motivo-servidor-real");

		var troca = comSessao("POST", "/auth/password/change", token,
			objectMapper.writeValueAsString(Map.of(
				"currentPassword", "Test1234!", "newPassword", "NovaSenha123")));
		assertThat(troca.statusCode()).as("troca de senha: %s", troca.body()).isEqualTo(200);

		var depois = comSessao("GET", "/auth/me", token, null);

		assertThat(depois.statusCode()).isEqualTo(401);
		assertThat(depois.body()).contains("\"code\":\"UNAUTHENTICATED\"");
		assertThat(depois.body()).contains("\"detail\":\"SESSION_REVOKED\"");
	}

	@Test
	void umTokenInventadoNoServidorRealContinuaSemMotivo() throws Exception {
		var resposta = comSessao("GET", "/auth/me", "token-que-nunca-existiu", null);

		assertThat(resposta.statusCode()).isEqualTo(401);
		assertThat(resposta.body()).contains("\"code\":\"UNAUTHENTICATED\"");
		assertThat(resposta.body()).doesNotContain("SESSION_REVOKED");
	}
}
