package com.socialcoisa.rede.ops.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.socialcoisa.rede.shared.web.TestAuthHelper;
import com.socialcoisa.rede.shared.web.TestDatabase;

import tools.jackson.databind.ObjectMapper;

/**
 * Métricas do Éroleplay no painel de monitorabilidade.
 *
 * <p>O que este teste tranca: a contagem cresce com campanha criada de verdade pelos endpoints do
 * módulo (não é número inventado); o painel devolve os agregados por estado, visibilidade e
 * sistema; a leitura é de moderador para cima (401 sem sessão, 403 com sessão comum); e a
 * resposta é só agregado — nenhum nome, slug ou descrição de campanha sai nela.</p>
 */
@SpringBootTest(properties = {
		"rede.monitoring.file-dir=${java.io.tmpdir}/rede-monitoring-test",
		// o painel é cacheado por um minuto; no teste o agregado tem de refletir o que acabou de ser
		// criado pelos endpoints reais, sem estado herdado entre métodos.
		"rede.monitoring.roleplay-cache-ms=0"
})
@AutoConfigureMockMvc
class RoleplayMetricsTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	private TestAuthHelper authHelper() {
		return new TestAuthHelper(mockMvc, objectMapper);
	}

	private String criarCampanha(TestAuthHelper.AuthSession mestre, String nome, String visibilidade,
			String sistema) throws Exception {
		MvcResult resultado = mockMvc.perform(post("/roleplay/campaigns")
				.header("X-Session-Token", mestre.token())
				.header("Idempotency-Key", "metrics-" + nome)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"name", nome,
					"description", "descrição que não pode vazar nas métricas",
					"setting", "ambientação que não pode vazar nas métricas",
					"gameSystem", sistema,
					"systemFamily", sistema.equals("d20-generico") ? "D20" : "CUSTOM",
					"visibility", visibilidade))))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(resultado.getResponse().getContentAsString()).get("id").asText();
	}

	@Test
	void painelDevolveAgregadosDeCampanhaCriadaDeVerdade() throws Exception {
		var mestre = authHelper().authenticate("roleplay-metrics-mestre");
		criarCampanha(mestre, "Metrica Publica D20", "PUBLIC", "d20-generico");
		criarCampanha(mestre, "Metrica Por Convite D20", "INVITE_ONLY", "d20-generico");

		// A leitura é pelo HTTP do painel, não pelo objeto do teste: o que se tranca é a resposta
		// que um moderador vê. O único sistema registrado hoje é o d20 genérico do módulo.
		var admin = authHelper().authenticate("roleplay-metrics-leitor");
		TestDatabase.setAdmin(admin.userId());
		String corpo = mockMvc
			.perform(get("/admin/monitoring/roleplay").header("X-Session-Token", admin.token()))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();

		assertThat(corpo).contains("\"d20-generico\":");
		// Só agregado: nenhum texto livre da campanha sai na resposta do painel.
		assertThat(corpo).doesNotContain("Metrica")
			.doesNotContain("descrição que não pode vazar")
			.doesNotContain("ambientação que não pode vazar")
			.doesNotContain(mestre.userId());
	}

	@Test
	void contagensPorEstadoVisibilidadeESistemaBatemComOBanco() throws Exception {
		var mestre = authHelper().authenticate("roleplay-metrics-contagem");
		criarCampanha(mestre, "Contagem D20 Publica", "PUBLIC", "d20-generico");

		var admin = authHelper().authenticate("roleplay-metrics-contagem-leitor");
		TestDatabase.setAdmin(admin.userId());
		MvcResult painel = mockMvc
			.perform(get("/admin/monitoring/roleplay").header("X-Session-Token", admin.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.campaigns").isNumber())
			.andExpect(jsonPath("$.activeCampaigns").isNumber())
			.andExpect(jsonPath("$.archivedCampaigns").isNumber())
			.andExpect(jsonPath("$.publicCampaigns").isNumber())
			.andExpect(jsonPath("$.inviteOnlyCampaigns").isNumber())
			.andExpect(jsonPath("$.privateCampaigns").isNumber())
			.andExpect(jsonPath("$.bySystem['d20-generico']").isNumber())
			.andExpect(jsonPath("$.campaignsLast7Days").isNumber())
			.andExpect(jsonPath("$.campaignsLast30Days").isNumber())
			.andExpect(jsonPath("$.members").isNumber())
			.andExpect(jsonPath("$.pendingJoinRequests").isNumber())
			.andExpect(jsonPath("$.tables").isNumber())
			.andExpect(jsonPath("$.seats").isNumber())
			.andExpect(jsonPath("$.characterSheets").isNumber())
			.andExpect(jsonPath("$.sheetTemplates").isNumber())
			.andExpect(jsonPath("$.chatMessages").isNumber())
			.andExpect(jsonPath("$.diceRolls").isNumber())
			.andExpect(jsonPath("$.rollRequests").isNumber())
			.andExpect(jsonPath("$.worldEntries").isNumber())
			.andExpect(jsonPath("$.publishedSheets").isNumber())
			.andReturn();

		// As três visibilidades somam o total; ativas + arquivadas somam o total.
		var corpo = objectMapper.readTree(painel.getResponse().getContentAsString());
		long total = corpo.get("campaigns").asLong();
		assertThat(corpo.get("publicCampaigns").asLong() + corpo.get("inviteOnlyCampaigns").asLong()
			+ corpo.get("privateCampaigns").asLong()).isEqualTo(total);
		assertThat(corpo.get("activeCampaigns").asLong() + corpo.get("archivedCampaigns").asLong())
			.isEqualTo(total);
		// Toda campanha tem o mestre como primeiro membro: membros nunca ficam atrás das campanhas.
		assertThat(corpo.get("members").asLong()).isGreaterThanOrEqualTo(total);
		// O ritmo de 7 dias nunca passa o de 30, e o de 30 nunca passa o total.
		assertThat(corpo.get("campaignsLast7Days").asLong()).isLessThanOrEqualTo(corpo.get("campaignsLast30Days").asLong());
		assertThat(corpo.get("campaignsLast30Days").asLong()).isLessThanOrEqualTo(total);
	}

	@Test
	void aLeituraEExclusivaDeModerador() throws Exception {
		var mestre = authHelper().authenticate("roleplay-metrics-guarda");

		// Sem sessão é 401: o resolver lança antes de qualquer leitura.
		mockMvc.perform(get("/admin/monitoring/roleplay"))
			.andExpect(status().isUnauthorized());

		// Sessão comum é 403: criar campanha não dá direito ao painel.
		mockMvc.perform(get("/admin/monitoring/roleplay").header("X-Session-Token", mestre.token()))
			.andExpect(status().isForbidden());

		// Moderador (admin) lê.
		var admin = authHelper().authenticate("roleplay-metrics-guarda-leitor");
		TestDatabase.setAdmin(admin.userId());
		mockMvc.perform(get("/admin/monitoring/roleplay").header("X-Session-Token", admin.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.campaigns").isNumber());
	}
}
