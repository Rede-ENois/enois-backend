package com.socialcoisa.rede.wrapped.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.socialcoisa.rede.shared.web.TestAuthHelper;
import com.socialcoisa.rede.shared.web.TestDatabase;
import com.socialcoisa.rede.wrapped.application.WrappedFilaConstrutor;
import com.socialcoisa.rede.wrapped.application.WrappedService;
import com.socialcoisa.rede.wrapped.application.port.out.WrappedFilaRepository;
import com.socialcoisa.rede.wrapped.domain.WrappedEstado;

import tools.jackson.databind.ObjectMapper;

/**
 * O Énóis Wrapped de ponta a ponta: histórico no banco, fila construída, passo do serviço e leitura
 * no endpoint.
 *
 * O centro dos testes é o que o briefing exige: a retrospectiva é INDIVIDUAL e o backend garante o
 * isolamento (a rota não aceita conta de parâmetro); contas diferentes veem históricos diferentes;
 * conta já processada não é recalculada; a reserva vencida devolve a conta à fila; e o histórico
 * respeita o intervalo do ano e o status ativo do conteúdo.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(WrappedFlowTests.RelogioNaJanela.class)
class WrappedFlowTests {

	private static final int ANO = 2026;
	/** 27/12/2026, dentro da exibição (26/12 a 01/01): a leitura é exercitável. */
	private static final Instant AGORA = Instant.parse("2026-12-27T17:00:00Z");

	@TestConfiguration
	static class RelogioNaJanela {
		@Bean
		@Primary
		java.time.Clock relogioDoTeste() {
			return java.time.Clock.fixed(AGORA, java.time.ZoneId.of("America/Sao_Paulo"));
		}
	}

	@Autowired
	private MockMvc mockMvc;
	@Autowired
	private ObjectMapper objectMapper;
	@Autowired
	private JdbcTemplate jdbc;
	@Autowired
	private WrappedService service;
	@Autowired
	private WrappedFilaConstrutor construtor;
	@Autowired
	private WrappedFilaRepository fila;

	@Test
	void endpointExigeSessao() throws Exception {
		mockMvc.perform(get("/wrapped/2026")).andExpect(status().isUnauthorized());
	}

	@Test
	void anoDiferenteDoConfiguradoNaoTemRetrospectiva() throws Exception {
		var sessao = autenticar();
		mockMvc.perform(get("/wrapped/2025").header("X-Session-Token", sessao.token()))
				.andExpect(status().isOk()).andExpect(jsonPath("status").value("NOT_FOUND"));
	}

	@Test
	void contaSemLinhaNaFilaNaoVazaRetrospectiva() throws Exception {
		var sessao = autenticar();
		mockMvc.perform(get("/wrapped/2026").header("X-Session-Token", sessao.token()))
				.andExpect(status().isOk()).andExpect(jsonPath("status").value("NAO_ELEGIVEL"));
	}

	@Test
	void isolamentoCadaContaVeSoOProprioHistorico() throws Exception {
		var ana = autenticar();
		var bia = autenticar();
		publicarEm(ana, "programacao de java", Instant.parse("2026-03-10T15:00:00Z"));
		publicarEm(bia, "jardinagem no quintal", Instant.parse("2026-04-10T15:00:00Z"));
		processar(ana.userId(), bia.userId());

		String jsonAna = retrospectiva(ana);
		String jsonBia = retrospectiva(bia);

		assertThat(jsonAna).contains("programacao de java").doesNotContain("jardinagem");
		assertThat(jsonBia).contains("jardinagem no quintal").doesNotContain("programacao");
	}

	@Test
	void historicoForaDoAnoNaoConta() throws Exception {
		var sessao = autenticar();
		publicarEm(sessao, "assunto de 2025", Instant.parse("2025-06-01T12:00:00Z"));
		processar(sessao.userId());

		mockMvc.perform(get("/wrapped/2026").header("X-Session-Token", sessao.token()))
				.andExpect(status().isOk()).andExpect(jsonPath("status").value("READY"))
				.andExpect(jsonPath("dadosInsuficientes").value(true));
	}

	@Test
	void conteudoRemovidoNaoEntraNaRetrospectiva() throws Exception {
		var sessao = autenticar();
		publicarEm(sessao, "rust na producao", Instant.parse("2026-05-01T12:00:00Z"));
		String removido = publicarEm(sessao, "conteudo denunciado", Instant.parse("2026-05-02T12:00:00Z"));
		jdbc.update("update posts set status = 'DELETED' where id = ?", removido);
		processar(sessao.userId());

		String json = retrospectiva(sessao);
		assertThat(json).contains("rust na producao").doesNotContain("denunciado");
	}

	@Test
	void contaSemHistoricoSaiComDadosInsuficientes() throws Exception {
		var sessao = autenticar();
		processar(sessao.userId());

		mockMvc.perform(get("/wrapped/2026").header("X-Session-Token", sessao.token()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("status").value("READY"))
				.andExpect(jsonPath("dadosInsuficientes").value(true));
	}

	@Test
	void filaPriorizaMaiorCredibilidade() throws Exception {
		var alta = autenticar();
		var baixa = autenticar();
		TestDatabase.updateReputation(alta.userId(), 900);
		TestDatabase.updateReputation(baixa.userId(), 10);
		construtor.construir(ANO, Instant.now());

		var ordem = fila.proximas(ANO, 5000);
		assertThat(ordem).contains(alta.userId(), baixa.userId());
		assertThat(ordem.indexOf(alta.userId())).isLessThan(ordem.indexOf(baixa.userId()));
	}

	@Test
	void processarDuasVezesNaoRecalculaContaConcluida() throws Exception {
		var sessao = autenticar();
		publicarEm(sessao, "um assunto qualquer", Instant.parse("2026-02-02T12:00:00Z"));
		processar(sessao.userId());

		var antes = service.retrospectiva(sessao.userId(), ANO).orElseThrow();
		processar(sessao.userId());
		var depois = service.retrospectiva(sessao.userId(), ANO).orElseThrow();

		assertThat(depois).isEqualTo(antes);
		assertThat(fila.buscar(sessao.userId(), ANO).orElseThrow().estado()).isEqualTo(WrappedEstado.DONE);
	}

	@Test
	void reservaVencidaVoltaParaAFila() throws Exception {
		var sessao = autenticar();
		construtor.construir(ANO, Instant.now());

		Instant agora = Instant.now();
		assertThat(fila.reservar(sessao.userId(), ANO, agora.minusSeconds(120), agora.minusSeconds(60))).isTrue();
		assertThat(service.retrospectiva(sessao.userId(), ANO)).isEmpty();
		assertThat(service.recuperarReservas(ANO)).isGreaterThanOrEqualTo(1);
		assertThat(fila.buscar(sessao.userId(), ANO).orElseThrow().estado()).isEqualTo(WrappedEstado.PENDING);
	}

	private void processar(String... userIds) {
		construtor.construir(ANO, Instant.now());
		List<String> alvo = List.of(userIds);
		int guarda = 0;
		while (aguardando(alvo) && guarda++ < 5000) {
			if (!service.processarProxima(ANO)) {
				break;
			}
		}
	}

	private boolean aguardando(List<String> alvo) {
		for (String userId : alvo) {
			var estado = fila.buscar(userId, ANO).map(r -> r.estado()).orElse(null);
			if (estado == WrappedEstado.PENDING || estado == WrappedEstado.PROCESSING) {
				return true;
			}
		}
		return false;
	}

	private String retrospectiva(TestAuthHelper.AuthSession sessao) throws Exception {
		return mockMvc.perform(get("/wrapped/2026").header("X-Session-Token", sessao.token()))
				.andExpect(status().isOk()).andExpect(jsonPath("status").value("READY"))
				.andReturn().getResponse().getContentAsString();
	}

	/** Insere um post ACTIVE da conta na data do cenário (o histórico do ano vem deste banco). */
	private String publicarEm(TestAuthHelper.AuthSession sessao, String texto, Instant quando) {
		String id = "post_" + UUID.randomUUID().toString().replace("-", "");
		jdbc.update("insert into posts (id, author_id, type, content, media_spoiler, community_id,"
				+ " repost_of, repost_of_author, repost_with_opinion, created_at, visibility, reactions, comments,"
				+ " saves, shares, reposts, status, author_identity, topic)"
				+ " values (?, ?, 'INSIGHT', ?, false, null, null, null, false, ?, 'PUBLIC', 0, 0, 0, 0, 0,"
				+ " 'ACTIVE', 'PROFILE', 'TECNOLOGIA')",
				id, sessao.userId(), texto, java.sql.Timestamp.from(quando));
		return id;
	}

	private TestAuthHelper.AuthSession autenticar() throws Exception {
		String usuario = "wrapped" + UUID.randomUUID().toString().substring(0, 8);
		return new TestAuthHelper(mockMvc, objectMapper).authenticate(usuario);
	}
}
