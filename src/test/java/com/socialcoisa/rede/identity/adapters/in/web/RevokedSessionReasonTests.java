package com.socialcoisa.rede.identity.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.socialcoisa.rede.shared.web.TestAuthHelper;
import com.socialcoisa.rede.shared.web.TestDatabase;

import tools.jackson.databind.ObjectMapper;

/**
 * A sessão que morre porque a pessoa encerrou diz POR QUE morreu.
 *
 * Trocar a senha revoga todas as sessões da conta. O 401 seguinte é correto, mas chegava ao app
 * sem essa informação: qualquer 401 de sessão virava "sua sessão expirou" na tela, e quem tinha
 * acabado de trocar a senha era mandado a achar que a conta tinha um problema.
 *
 * O detalhe só aparece para token que o banco CONHECE e que foi encerrado — token inventado
 * continua respondendo igual a sessão vencida, para a resposta não contar nada a quem sonda.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RevokedSessionReasonTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private com.socialcoisa.rede.identity.application.port.out.AuthSessionRepository sessoes;

	@Autowired
	private org.springframework.transaction.support.TransactionTemplate transacao;

	private TestAuthHelper authHelper() {
		return new TestAuthHelper(mockMvc, objectMapper);
	}

	/**
	 * Vencer a sessao pela TABELA, como uma sessao que passou do prazo: o relogio do contexto
	 * compartilhado pode nao ser o de verdade, e mexer nele aqui faria o teste reagir a outro teste.
	 */
	private void vencerNaTabela(String sessionToken) {
		transacao.executeWithoutResult(ignorado -> {
			int linhas = com.socialcoisa.rede.shared.web.TestDatabase.expirarSessao(sessionToken, Instant.now());
			if (linhas != 1) {
				throw new IllegalStateException("Esperava 1 sessao ativa, achei " + linhas + ".");
			}
		});
	}

	/**
	 * Sessao viva que o proprio pedido acaba de encerrar. Antes do conserto o 401 nao carregava isso
	 * em nenhum caminho: nem o da troca de senha (que revoga durante o pedido) nem o da sessao
	 * encerrada por fora — os dois leem a mesma informacao.
	 */
	@Test
	void aSessaoEncerradaPelaTrocaDeSenhaDizQueFoiEncerrada() throws Exception {
		var session = authHelper().authenticate("sessao-com-motivo-troca-de-senha");

		mockMvc.perform(post("/auth/password/change")
				.header("X-Session-Token", session.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"currentPassword", "Test1234!", "newPassword", "NovaSenha123"))))
			.andExpect(status().isOk());

		mockMvc.perform(get("/auth/me").header("X-Session-Token", session.token()))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
			.andExpect(jsonPath("$.detail").value("SESSION_REVOKED"));
	}

	@Test
	void tokenInventadoNaoGanhaMotivo() throws Exception {
		var session = authHelper().authenticate("sessao-com-motivo-token-inventado");

		String body = mockMvc.perform(get("/auth/me").header("X-Session-Token", "token-que-nunca-existiu"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
			.andReturn().getResponse().getContentAsString();

		assertThat(body).doesNotContain("SESSION_REVOKED").doesNotContain("detail");
		// E a sessão de verdade continua valendo: a recusa acima não mexeu em nada.
		mockMvc.perform(get("/auth/me").header("X-Session-Token", session.token()))
			.andExpect(status().isOk());
	}

	@Test
	void sessaoVencidaPeloTempoNaoDizQueFoiEncerrada() throws Exception {
		var session = authHelper().authenticate("sessao-com-motivo-vencida");
		TestDatabase.expirarSessao(session.token(), Instant.now().minusSeconds(60));

		mockMvc.perform(get("/auth/me").header("X-Session-Token", session.token()))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
			.andExpect(jsonPath("$.detail").doesNotExist());
	}

	/** Uma sessão encerrada pode ser de OUTRA conta: o motivo não pode vazar de uma requisição para a próxima. */
	@Test
	void oMotivoNaoSobreviveAProximaRequisicao() throws Exception {
		var encerrada = authHelper().authenticate("sessao-com-motivo-vazamento");
		transacao.executeWithoutResult(ignorado -> sessoes.revokeAllByUser(encerrada.userId(), Instant.now()));

		String primeira = mockMvc.perform(get("/auth/me").header("X-Session-Token", encerrada.token()))
			.andExpect(status().isUnauthorized())
			.andReturn().getResponse().getContentAsString();
		assertThat(primeira).as("o teste só prova o vazamento se a primeira resposta trouxer o motivo")
			.contains("SESSION_REVOKED");

		String body = mockMvc.perform(get("/auth/me").header("X-Session-Token", "outro-token-inexistente"))
			.andExpect(status().isUnauthorized())
			.andReturn().getResponse().getContentAsString();

		assertThat(body).doesNotContain("SESSION_REVOKED");
	}
}
