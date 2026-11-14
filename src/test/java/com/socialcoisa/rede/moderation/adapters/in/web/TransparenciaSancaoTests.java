package com.socialcoisa.rede.moderation.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;

import com.socialcoisa.rede.moderation.application.port.out.SanctionRepository;
import com.socialcoisa.rede.moderation.domain.UserSanction;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:transparencia705;MODE=MariaDB;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
	"rede.moderation.suspension.grace-days=3",
	"rede.moderation.suspension.expiry-initial-delay-ms=2000000000"
})
@AutoConfigureMockMvc(print = org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint.NONE)
class TransparenciaSancaoTests {
	static final Instant AGORA = Instant.parse("2026-11-14T12:00:00Z");
	@org.springframework.test.context.bean.override.mockito.MockitoBean
	com.socialcoisa.rede.shared.web.TestDatabaseSupport ponteCompartilhada;
	@Autowired MockMvc mvc;
	@Autowired ObjectMapper json;
	@Autowired SanctionRepository sancoes;
	@Autowired com.socialcoisa.rede.moderation.application.port.out.ReportRepository denuncias;
	@Autowired com.socialcoisa.rede.moderation.application.port.out.BanContentArchiveRepository arquivo;
	@Autowired org.springframework.jdbc.core.JdbcTemplate banco;
	@Autowired com.socialcoisa.rede.moderation.application.SuspensionExpiryWorker vencimento;
	@Autowired com.socialcoisa.rede.moderation.application.port.out.AppealRepository recursos;

	@Test
	void textoDoProprioAlvoFicaIndisponivelQuandoRemovidoSemArquivo() throws Exception {
		var titular = autenticar("transp705_texto");
		var publicacao = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/feed/posts")
			.header("X-Session-Token", titular.token()).header("Idempotency-Key", "transp705_texto_post")
			.contentType(org.springframework.http.MediaType.APPLICATION_JSON)
			.content(json.writeValueAsString(java.util.Map.of("topic", "TECNOLOGIA", "type", "INSIGHT", "content", "Texto sintetico para revisao.",
				"tags", java.util.List.of(), "mediaIds", java.util.List.of()))))
			.andExpect(status().isCreated()).andReturn();
		String alvo = json.readTree(publicacao.getResponse().getContentAsString()).path("id").asText();
		denuncias.save(com.socialcoisa.rede.moderation.domain.Report.create("report_transp705_texto", "privado705",
			com.socialcoisa.rede.moderation.domain.TargetType.POST, alvo,
			com.socialcoisa.rede.moderation.domain.ReportReason.SPAM, "relato privado", AGORA));
		sancoes.save(UserSanction.create("sanc_transp705_texto", titular.userId(), "report_transp705_texto", "Publicacao revisada.", AGORA));
		mvc.perform(get("/me/sanctions").header("X-Session-Token", titular.token()))
			.andExpect(status().isOk()).andExpect(jsonPath("$.sanctions[0].evidence.kind").value("TEXT"))
			.andExpect(jsonPath("$.sanctions[0].evidence.text").value("Texto sintetico para revisao."));
		banco.update("delete from posts where id = ?", alvo);
		mvc.perform(get("/me/sanctions").header("X-Session-Token", titular.token()))
			.andExpect(status().isOk()).andExpect(jsonPath("$.sanctions[0].evidence.kind").value("UNAVAILABLE"))
			.andExpect(jsonPath("$.sanctions[0].evidence.text").isEmpty());
	}

	@Test
	void prazoPassadoNaoCriaNovaRegraDeRecusaDoRecurso() throws Exception {
		var titular = autenticar("transp705_tardio");
		var sancao = sancoes.save(UserSanction.suspended("sanc_transp705_tardio", titular.userId(), "registro_antigo705",
			"Conduta repetida.", AGORA.minus(java.time.Duration.ofDays(4))));
		mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/me/sanctions/appeals")
			.header("X-Session-Token", titular.token()).contentType(org.springframework.http.MediaType.APPLICATION_JSON)
			.content("{\"message\":\"Solicito revisao antes da conversao.\"}"))
			.andExpect(status().isOk());
		vencimento.expireSuspensions();
		mvc.perform(get("/me/sanctions").header("X-Session-Token", titular.token()))
			.andExpect(status().isOk()).andExpect(jsonPath("$.sanctions[0].type").value("SUSPENDED"))
			.andExpect(jsonPath("$.sanctions[0].evidence.kind").value("UNAVAILABLE"))
			.andExpect(jsonPath("$.sanctions[0].canAppeal").value(false))
			.andExpect(jsonPath("$.appeals[0].status").value("PENDING"));
		assertThat(sancoes.findById(sancao.id()).orElseThrow().isActive()).isTrue();
	}

	@org.junit.jupiter.params.ParameterizedTest
	@org.junit.jupiter.params.provider.ValueSource(strings = {"proposal:decisao705", "automod_spam_705", "AUTO:HONEYPOT:705", "dispute_705"})
	void identificaOrigemComportamentalSemEscolherPublicacaoArbitraria(String referencia) throws Exception {
		String sufixo = referencia.replaceAll("[^a-zA-Z0-9]", "").toLowerCase(java.util.Locale.ROOT);
		var titular = autenticar("transp705_" + sufixo);
		sancoes.save(UserSanction.create("sanc_beh705_" + sufixo, titular.userId(), referencia, "Conduta da conta.", AGORA));
		mvc.perform(get("/me/sanctions").header("X-Session-Token", titular.token()))
			.andExpect(status().isOk()).andExpect(jsonPath("$.sanctions[0].evidence.kind").value("BEHAVIOR"));
	}

	@org.junit.jupiter.params.ParameterizedTest
	@org.junit.jupiter.params.provider.CsvSource({"1,false,SUSPENDED", "0,false,SUSPENDED", "-1,false,BAN", "-1,true,SUSPENDED"})
	void vencimentoPreservaPoliticaRealSemLiberacaoAutomatica(long segundos, boolean pendente, String tipoFinal) throws Exception {
		String sufixo = (segundos < 0 ? "menos" : "mais") + Math.abs(segundos) + pendente;
		var titular = autenticar("transp705_limite_" + sufixo);
		var sancao = sancoes.save(UserSanction.suspended("sanc_lim705_" + sufixo, titular.userId(), "admin:705",
			"Conduta repetida.", AGORA.minus(java.time.Duration.ofDays(3)).plusSeconds(segundos)));
		if (pendente) {
			recursos.save(com.socialcoisa.rede.moderation.domain.Appeal.create("appl_lim705_" + sufixo, sancao.id(),
				titular.userId(), "Solicito revisao da decisao.", AGORA.minusSeconds(10)));
		}
		mvc.perform(get("/me/sanctions").header("X-Session-Token", titular.token()))
			.andExpect(status().isOk()).andExpect(jsonPath("$.sanctions[0].type").value("SUSPENDED"))
			.andExpect(jsonPath("$.sanctions[0].deadline.kind").value("APPEAL"))
			.andExpect(jsonPath("$.sanctions[0].deadline.at").value(AGORA.plusSeconds(segundos).toString()))
			.andExpect(jsonPath("$.sanctions[0].canAppeal").value(!pendente));
		vencimento.expireSuspensions();
		assertThat(sancoes.findById(sancao.id()).orElseThrow().type().name()).isEqualTo(tipoFinal);
	}

	@Test
	void descreveMidiaDeChamadaSemReproduzirCapturaNemRevelarRelatoOuParticipantes() throws Exception {
		var titular = autenticar("transp705_midia");
		var denuncia = com.socialcoisa.rede.moderation.domain.Report.create("report_transp705_midia",
			"denunciante_midia705", com.socialcoisa.rede.moderation.domain.TargetType.CALL, "call705",
			com.socialcoisa.rede.moderation.domain.ReportReason.SPAM, "relato_midia705", AGORA);
		denuncias.save(denuncia);
		banco.update("insert into call_reports(report_id,call_id,reported_user_id,media_id,participants,reported_at) values(?,?,?,?,?,?)",
			denuncia.id(), "call705", titular.userId(), "media_captura_privada705", "[\"participante_privado705\"]", java.sql.Timestamp.from(AGORA));
		sancoes.save(UserSanction.create("sanc_transp705_midia", titular.userId(), denuncia.id(), "Conduta na chamada.", AGORA));
		var resposta = mvc.perform(get("/me/sanctions").header("X-Session-Token", titular.token()))
			.andExpect(status().isOk()).andExpect(jsonPath("$.sanctions[0].evidence.kind").value("MEDIA"))
			.andExpect(jsonPath("$.sanctions[0].evidence.text").isNotEmpty()).andReturn();
		assertThat(resposta.getResponse().getContentAsString()).doesNotContain("relato_midia705", "denunciante_midia705",
			"media_captura_privada705", "participante_privado705", "cdnUrl", "objectKey");
	}

	@Test
	void mostraSomenteTrechoSeguroDoAlvoArquivadoSemDadosDeTerceiros() throws Exception {
		var titular = autenticar("transp705_arquivo");
		var denuncia = com.socialcoisa.rede.moderation.domain.Report.create("report_transp705_arquivo",
			"denunciante_privado705", com.socialcoisa.rede.moderation.domain.TargetType.POST, "post_arquivado705",
			com.socialcoisa.rede.moderation.domain.ReportReason.SPAM, "relato_privado705", AGORA);
		denuncia.attachAiTriage("classificador_privado705", AGORA);
		denuncias.save(denuncia);
		sancoes.save(UserSanction.create("sanc_transp705_arquivo", titular.userId(), denuncia.id(),
			"Publicacao contraria as diretrizes.", AGORA));
		arquivo.archive("sanc_transp705_arquivo", titular.userId(), "POST", "post_arquivado705",
			json.writeValueAsString(java.util.Map.of("content", "<script>segredo705()</script><b>Trecho seguro</b> " + "x".repeat(800))), AGORA);
		arquivo.archive("sanc_transp705_arquivo", titular.userId(), "MESSAGE", "mensagem705",
			"{\"text\":\"mensagem_privada705\"}", AGORA);

		var resposta = mvc.perform(get("/me/sanctions").header("X-Session-Token", titular.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.sanctions[0].evidence.kind").value("TEXT"))
			.andExpect(jsonPath("$.sanctions[0].deadline.kind").value("NONE"))
			.andExpect(jsonPath("$.sanctions[0].deadline.at").isEmpty()).andReturn();
		String corpo = resposta.getResponse().getContentAsString();
		assertThat(corpo).doesNotContain("denunciante_privado705", "relato_privado705", "classificador_privado705",
			"segredo705", "<b>", "mensagem_privada705");
		assertThat(json.readTree(corpo).path("sanctions").get(0).path("evidence").path("text").asText())
			.startsWith("Trecho seguro ").hasSizeLessThanOrEqualTo(500);
	}

	@TestConfiguration
	static class Configuracao {
		@Bean @Primary Clock relogioTransparencia() {
			return Clock.fixed(AGORA, ZoneOffset.UTC);
		}
	}

	private TestAuthHelper.AuthSession autenticar(String nome) throws Exception {
		String email = nome + "@example.invalid";
		mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/auth/signup")
			.header("Idempotency-Key", "signup-" + nome).contentType(org.springframework.http.MediaType.APPLICATION_JSON)
			.content(json.writeValueAsString(java.util.Map.of("name", nome, "role", "USER", "email", email,
				"password", "Test1234!", "acceptedTerms", true, "birthDate", "1990-05-10"))))
			.andExpect(status().isCreated());
		String confirmacao = "confirmacao-sintetica705-" + nome;
		banco.update("update pending_signups set token_hash = ? where email = ?",
			com.socialcoisa.rede.shared.security.Hashes.sha256Hex(confirmacao), email);
		var resposta = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/auth/email/verify")
			.contentType(org.springframework.http.MediaType.APPLICATION_JSON)
			.content(json.writeValueAsString(java.util.Map.of("token", confirmacao))))
			.andExpect(status().isOk()).andReturn();
		String id = json.readTree(resposta.getResponse().getContentAsString()).path("user").path("id").asText();
		return new TestAuthHelper.AuthSession(TestAuthHelper.sessionToken(resposta), TestAuthHelper.refreshToken(resposta), id);
	}

	@Test
	void informaPrazoDeRecursoSemInventarTerminoOuEvidencia() throws Exception {
		var titular = autenticar("transp705_prazo");
		sancoes.save(UserSanction.suspended("sanc_transp705_prazo", titular.userId(), "admin:decisao705",
			"Conduta repetida contraria as diretrizes.", AGORA.minusSeconds(3600)));

		mvc.perform(get("/me/sanctions").header("X-Session-Token", titular.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.serverNow").value(AGORA.toString()))
			.andExpect(jsonPath("$.sanctions[0].deadline.kind").value("APPEAL"))
			.andExpect(jsonPath("$.sanctions[0].deadline.at").value("2026-11-17T11:00:00Z"))
			.andExpect(jsonPath("$.sanctions[0].evidence.kind").value("BEHAVIOR"))
			.andExpect(jsonPath("$.sanctions[0].canAppeal").value(true))
			.andExpect(jsonPath("$.sanctions[0].reason").value("Conduta repetida contraria as diretrizes."));
		assertThat(sancoes.findActiveByUser(titular.userId()).orElseThrow().type().name()).isEqualTo("SUSPENDED");
	}
}
