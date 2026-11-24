package com.socialcoisa.rede.talentbank.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.socialcoisa.rede.feed.application.port.out.AuditLogRepository;
import com.socialcoisa.rede.feed.domain.UserId;
import com.socialcoisa.rede.media.application.port.out.MediaAssetRepository;
import com.socialcoisa.rede.media.application.port.out.MediaObjectStore;
import com.socialcoisa.rede.media.domain.MediaAsset;
import com.socialcoisa.rede.media.domain.MediaStorageProvider;
import com.socialcoisa.rede.shared.web.TestAuthHelper;
import com.socialcoisa.rede.shared.web.TestAuthHelper.AuthSession;
import com.socialcoisa.rede.talentbank.application.port.out.TalentBankEventRepository;

import tools.jackson.databind.ObjectMapper;

/**
 * A inscrição é da pessoa: ela pede, confirma, revoga — e o recrutador só enxerga o que ela
 * autorizou, enquanto a inscrição estiver viva.
 *
 * <p>Os cenários aqui cobrem a Consentimento e a cortesia do acesso: nada é liberado antes da
 * confirmação, a revogação corta na hora, o currículo sai pelo backend e nunca pelo endereço
 * público do storage, e nada muda de uma banca para outra.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class TalentBankEnrollmentTests {

	private static final byte[] PDF = "%PDF-1.4 currículo de teste".getBytes(StandardCharsets.UTF_8);

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private MediaAssetRepository assets;

	@Autowired
	private TalentBankEventRepository events;

	@Autowired
	private AuditLogRepository audit;

	@TestConfiguration
	static class CurrículoFakeStore {

		@Bean
		@Primary
		FakeObjectStore fakeObjectStore() {
			return new FakeObjectStore();
		}
	}

	/** O bucket de teste entrega o currículo para a chave que o asset aponta. */
	static class FakeObjectStore implements MediaObjectStore {

		@Override
		public StoredObject read(String key, String range) {
			return new StoredObject(new ByteArrayInputStream(PDF), PDF.length, "application/pdf", null, "\"etag\"");
		}

		@Override
		public void write(String key, String contentType, long length, java.io.InputStream body) {
			throw new UnsupportedOperationException("o teste nao escreve no bucket");
		}
	}

	@Test
	void aInscricaoEVoluntariaEPendeDaConfirmacaoDaPessoa() throws Exception {
		AuthSession recruiter = TalentBankTestSupport.recruiter(mockMvc, objectMapper, "tb_recrutador_consentimento");
		MvcResult created = TalentBankTestSupport.createBank(mockMvc, objectMapper, recruiter, "Banca do consentimento",
			List.of("PROFILE", "SKILLS"));
		String bankId = TalentBankTestSupport.idOf(created, objectMapper);
		publish(recruiter, bankId);

		AuthSession candidate = new TestAuthHelper(mockMvc, objectMapper).authenticate("tb_candidato_consentimento");

		// Antes de pedir, a banca mostra o recrutador e a finalidade — e nada do candidato.
		mockMvc.perform(get("/talent-banks/" + bankId).header("X-Session-Token", candidate.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.recruiter.userId").value(recruiter.userId()))
			.andExpect(jsonPath("$.myState").doesNotExist());

		MvcResult requested = mockMvc.perform(post("/talent-banks/" + bankId + "/enrollments")
				.header("X-Session-Token", candidate.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"sharedFields", List.of("PROFILE"),
					"note", "Quero conversar sobre a vaga."))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.state").value("PENDING"))
			.andExpect(jsonPath("$.sharedFields[0]").value("PROFILE"))
			.andExpect(jsonPath("$.consentedAt").doesNotExist())
			.andReturn();
		String enrollmentId = TalentBankTestSupport.idOf(requested, objectMapper);

		// Pendente: o recrutador não vê nenhum dado da pessoa.
		mockMvc.perform(get("/talent-banks/" + bankId + "/candidates").header("X-Session-Token", recruiter.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items").isEmpty());

		mockMvc.perform(get("/talent-banks/" + bankId + "/candidates/" + enrollmentId)
				.header("X-Session-Token", recruiter.token()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("ENROLLMENT_NOT_ACTIVE"));

		// Confirmada: o recrutador passa a ver o que foi autorizado — e só isso.
		mockMvc.perform(post("/talent-banks/" + bankId + "/enrollments/" + enrollmentId + "/confirm")
				.header("X-Session-Token", candidate.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.state").value("ACTIVE"))
			.andExpect(jsonPath("$.consentedAt").isNotEmpty());

		mockMvc.perform(get("/talent-banks/" + bankId + "/candidates").header("X-Session-Token", recruiter.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].sharedFields[0]").value("PROFILE"))
			.andExpect(jsonPath("$.items[0].resumeAvailable").value(false));

		mockMvc.perform(get("/talent-banks/" + bankId + "/candidates/" + enrollmentId)
				.header("X-Session-Token", recruiter.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.identity.username").isNotEmpty())
			.andExpect(jsonPath("$.skills").isEmpty());
	}

	@Test
	void oRecrutadorNaoInscreveNinguemSemAutorizacaoDaPessoa() throws Exception {
		AuthSession recruiter = TalentBankTestSupport.recruiter(mockMvc, objectMapper, "tb_recrutador_sem_forca");
		MvcResult created = TalentBankTestSupport.createBank(mockMvc, objectMapper, recruiter, "Banca sem forca",
			List.of("PROFILE"));
		String bankId = TalentBankTestSupport.idOf(created, objectMapper);
		publish(recruiter, bankId);

		// Não existe rota de recrutador para criar inscrição: o pedido é sempre da pessoa.
		mockMvc.perform(post("/talent-banks/" + bankId + "/candidates")
				.header("X-Session-Token", recruiter.token()))
			.andExpect(status().isMethodNotAllowed());

		// E o recrutador não vira candidato do próprio banco por baixo dos panos.
		mockMvc.perform(get("/talent-banks/" + bankId + "/candidates").header("X-Session-Token", recruiter.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items").isEmpty());
	}

	@Test
	void aPessoaParticipaDeVariosBancosAoMesmoTempo() throws Exception {
		AuthSession recruiterUm = TalentBankTestSupport.recruiter(mockMvc, objectMapper, "tb_recrutador_multiplex_um");
		AuthSession recruiterDois = TalentBankTestSupport.recruiter(mockMvc, objectMapper, "tb_recrutador_multiplex_dois");
		String bankUm = TalentBankTestSupport.idOf(
			TalentBankTestSupport.createBank(mockMvc, objectMapper, recruiterUm, "Banca numero um", List.of("PROFILE")),
			objectMapper);
		String bankDois = TalentBankTestSupport.idOf(
			TalentBankTestSupport.createBank(mockMvc, objectMapper, recruiterDois, "Banca numero dois",
				List.of("PROFILE")),
			objectMapper);
		publish(recruiterUm, bankUm);
		publish(recruiterDois, bankDois);

		AuthSession candidate = new TestAuthHelper(mockMvc, objectMapper).authenticate("tb_candidato_multiplex");
		String inscricaoUm = TalentBankTestSupport.idOf(enroll(candidate, bankUm, List.of("PROFILE")), objectMapper);
		String inscricaoDois = TalentBankTestSupport.idOf(enroll(candidate, bankDois, List.of("PROFILE")), objectMapper);
		confirm(candidate, bankUm, inscricaoUm);
		confirm(candidate, bankDois, inscricaoDois);

		mockMvc.perform(get("/talent-banks/mine/enrollments").header("X-Session-Token", candidate.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(2));

		// Cada recrutador vê só o seu inscrito.
		mockMvc.perform(get("/talent-banks/" + bankUm + "/candidates").header("X-Session-Token", recruiterUm.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(1));
		mockMvc.perform(get("/talent-banks/" + bankDois + "/candidates").header("X-Session-Token", recruiterDois.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(1));
	}

	@Test
	void oRecrutadorNaoAcessaOInscritoDeOutroBanco() throws Exception {
		AuthSession dono = TalentBankTestSupport.recruiter(mockMvc, objectMapper, "tb_dono_do_inscrito");
		AuthSession outro = TalentBankTestSupport.recruiter(mockMvc, objectMapper, "tb_curioso_do_inscrito");
		String bankId = TalentBankTestSupport.idOf(
			TalentBankTestSupport.createBank(mockMvc, objectMapper, dono, "Banca do inscrito", List.of("PROFILE")),
			objectMapper);
		publish(dono, bankId);

		AuthSession candidate = new TestAuthHelper(mockMvc, objectMapper).authenticate("tb_candidato_do_inscrito");
		MvcResult enrollment = enroll(candidate, bankId, List.of("PROFILE"));
		String enrollmentId = TalentBankTestSupport.idOf(enrollment, objectMapper);
		confirm(candidate, bankId, enrollmentId);

		mockMvc.perform(get("/talent-banks/" + bankId + "/candidates/" + enrollmentId)
				.header("X-Session-Token", outro.token()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("TALENT_BANK_NOT_FOUND"));

		mockMvc.perform(get("/talent-banks/" + bankId + "/candidates/" + enrollmentId + "/resume")
				.header("X-Session-Token", outro.token()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("TALENT_BANK_NOT_FOUND"));
	}

	@Test
	void oCurriculoSoSaiQuandoApexsoautoriza() throws Exception {
		AuthSession recruiter = TalentBankTestSupport.recruiter(mockMvc, objectMapper, "tb_recrutador_do_curriculo");
		String bankId = TalentBankTestSupport.idOf(
			TalentBankTestSupport.createBank(mockMvc, objectMapper, recruiter, "Banca do curriculo",
				List.of("PROFILE", "RESUME")),
			objectMapper);
		publish(recruiter, bankId);

		AuthSession semCurriculo = new TestAuthHelper(mockMvc, objectMapper).authenticate("tb_candidato_sem_curriculo");
		AuthSession candidate = new TestAuthHelper(mockMvc, objectMapper).authenticate("tb_candidato_do_curriculo");
		String mediaId = "media_11111111-2222-3333-4444-555555555555";
		assets.save(MediaAsset.pending(mediaId, new UserId(candidate.userId()), "curriculo.pdf", "application/pdf",
			PDF.length, "RESUME", "resumes/" + mediaId + "/file.pdf",
			"https://enois.exemplo.com/api/storage/resumes/" + mediaId + "/file.pdf", Instant.now(),
			Instant.now().plus(Duration.ofHours(1)), MediaStorageProvider.S3));
		mockMvc.perform(patch("/me/profile")
				.header("X-Session-Token", candidate.token())
				.header("Idempotency-Key", "resume-tb_curriculo")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"resumeUrl\":\"https://enois.exemplo.com/api/storage/resumes/" + mediaId
					+ "/file.pdf\",\"resumeName\":\"curriculo.pdf\"}"))
			.andExpect(status().isOk());

		// Sem o curriculum autorizado, o endpoint recusa.
		MvcResult semCurriculoInscricao = enroll(semCurriculo, bankId, List.of("PROFILE"));
		String semCurriculoId = TalentBankTestSupport.idOf(semCurriculoInscricao, objectMapper);
		confirm(semCurriculo, bankId, semCurriculoId);
		mockMvc.perform(get("/talent-banks/" + bankId + "/candidates/" + semCurriculoId + "/resume")
				.header("X-Session-Token", recruiter.token()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("RESUME_NOT_AUTHORIZED"));

		// Com o curriculum autorizado, o backend entrega o arquivo — sem endereco publico no corpo.
		MvcResult comCurriculo = enroll(candidate, bankId, List.of("PROFILE", "RESUME"));
		String comCurriculoId = TalentBankTestSupport.idOf(comCurriculo, objectMapper);
		confirm(candidate, bankId, comCurriculoId);
		mockMvc.perform(get("/talent-banks/" + bankId + "/candidates/" + comCurriculoId + "/resume")
				.header("X-Session-Token", recruiter.token()))
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PDF))
			.andExpect(header().string("Cache-Control", "private, no-store"))
			.andExpect(header().string("Content-Disposition",
				org.hamcrest.Matchers.containsString("attachment")));
	}

	@Test
	void aRevogacaoCortaOAcessoNaHora() throws Exception {
		AuthSession recruiter = TalentBankTestSupport.recruiter(mockMvc, objectMapper, "tb_recrutador_revoque");
		String bankId = TalentBankTestSupport.idOf(
			TalentBankTestSupport.createBank(mockMvc, objectMapper, recruiter, "Banca da revogacao", List.of("PROFILE")),
			objectMapper);
		publish(recruiter, bankId);

		AuthSession candidate = new TestAuthHelper(mockMvc, objectMapper).authenticate("tb_candidato_revoque");
		MvcResult enrollment = enroll(candidate, bankId, List.of("PROFILE"));
		String enrollmentId = TalentBankTestSupport.idOf(enrollment, objectMapper);
		confirm(candidate, bankId, enrollmentId);

		mockMvc.perform(delete("/talent-banks/" + bankId + "/enrollments/" + enrollmentId)
				.header("X-Session-Token", candidate.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.state").value("REVOKED_BY_CANDIDATE"))
			.andExpect(jsonPath("$.revokedAt").isNotEmpty());

		mockMvc.perform(get("/talent-banks/" + bankId + "/candidates").header("X-Session-Token", recruiter.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items").isEmpty());

		mockMvc.perform(get("/talent-banks/" + bankId + "/candidates/" + enrollmentId)
				.header("X-Session-Token", recruiter.token()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("ENROLLMENT_NOT_ACTIVE"));

		// Pedido novo depois da revogacao: a pessoa volta a decidir.
		MvcResult renewed = mockMvc.perform(post("/talent-banks/" + bankId + "/enrollments")
				.header("X-Session-Token", candidate.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("sharedFields", List.of("PROFILE")))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.state").value("PENDING"))
			.andReturn();
		assertThat(TalentBankTestSupport.idOf(renewed, objectMapper)).isEqualTo(enrollmentId);
	}

	@Test
	void idsManipuladosNaoAtravessamABanca() throws Exception {
		AuthSession recruiter = TalentBankTestSupport.recruiter(mockMvc, objectMapper, "tb_recrutador_idor");
		String bankId = TalentBankTestSupport.idOf(
			TalentBankTestSupport.createBank(mockMvc, objectMapper, recruiter, "Banca do idor", List.of("PROFILE")),
			objectMapper);
		publish(recruiter, bankId);

		AuthSession candidate = new TestAuthHelper(mockMvc, objectMapper).authenticate("tb_candidato_idor");
		AuthSession intruder = new TestAuthHelper(mockMvc, objectMapper).authenticate("tb_intruso_idor");
		MvcResult enrollment = enroll(candidate, bankId, List.of("PROFILE"));
		String enrollmentId = TalentBankTestSupport.idOf(enrollment, objectMapper);

		// O intruso tenta confirmar a inscricao de outra pessoa.
		mockMvc.perform(post("/talent-banks/" + bankId + "/enrollments/" + enrollmentId + "/confirm")
				.header("X-Session-Token", intruder.token()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("ENROLLMENT_NOT_FOUND"));

		// E a historia da inscricao de outra pessoa tambem nao abre.
		mockMvc.perform(get("/talent-banks/enrollments/" + enrollmentId + "/history")
				.header("X-Session-Token", intruder.token()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("ENROLLMENT_NOT_FOUND"));

		// Uma inscricao inexistente nao existe para ninguem.
		mockMvc.perform(get("/talent-banks/" + bankId + "/candidates/enrollment_que_nao_existe")
				.header("X-Session-Token", recruiter.token()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("ENROLLMENT_NOT_FOUND"));
	}

	@Test
	void aExclusaoDaPessoaApagaORecadoEGuardaOConsentimento() throws Exception {
		AuthSession recruiter = TalentBankTestSupport.recruiter(mockMvc, objectMapper, "tb_recrutador_exclusao");
		String bankId = TalentBankTestSupport.idOf(
			TalentBankTestSupport.createBank(mockMvc, objectMapper, recruiter, "Banca da exclusao", List.of("PROFILE")),
			objectMapper);
		publish(recruiter, bankId);

		AuthSession candidate = new TestAuthHelper(mockMvc, objectMapper).authenticate("tb_candidato_exclusao");
		MvcResult enrollment = enroll(candidate, bankId, List.of("PROFILE"));
		String enrollmentId = TalentBankTestSupport.idOf(enrollment, objectMapper);
		confirm(candidate, bankId, enrollmentId);

		mockMvc.perform(post("/talent-banks/" + bankId + "/enrollments/" + enrollmentId + "/erase")
				.header("X-Session-Token", candidate.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.state").value("REVOKED_BY_CANDIDATE"))
			.andExpect(jsonPath("$.note").doesNotExist())
			.andExpect(jsonPath("$.erasedAt").isNotEmpty())
			.andExpect(jsonPath("$.consentedAt").isNotEmpty());

		// O registro do consentimento continua: e ele que sustenta a guarda do historico.
		mockMvc.perform(get("/talent-banks/enrollments/" + enrollmentId + "/history")
				.header("X-Session-Token", candidate.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(org.hamcrest.Matchers.greaterThan(2)));

		mockMvc.perform(get("/talent-banks/" + bankId + "/candidates/" + enrollmentId)
				.header("X-Session-Token", recruiter.token()))
			.andExpect(status().isForbidden());
	}

	@Test
	void oEncerramentoPeloRecrutadorRespeitaOEstadoDaInscricao() throws Exception {
		AuthSession recruiter = TalentBankTestSupport.recruiter(mockMvc, objectMapper, "tb_recrutador_encerramento");
		String bankId = TalentBankTestSupport.idOf(
			TalentBankTestSupport.createBank(mockMvc, objectMapper, recruiter, "Banca do encerramento",
				List.of("PROFILE")),
			objectMapper);
		publish(recruiter, bankId);

		AuthSession candidate = new TestAuthHelper(mockMvc, objectMapper).authenticate("tb_candidato_encerramento");
		MvcResult enrollment = enroll(candidate, bankId, List.of("PROFILE"));
		String enrollmentId = TalentBankTestSupport.idOf(enrollment, objectMapper);
		confirm(candidate, bankId, enrollmentId);

		mockMvc.perform(post("/talent-banks/" + bankId + "/candidates/" + enrollmentId + "/close")
				.header("X-Session-Token", recruiter.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.state").value("CLOSED_BY_RECRUITER"));

		mockMvc.perform(get("/talent-banks/" + bankId + "/candidates/" + enrollmentId)
				.header("X-Session-Token", recruiter.token()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("ENROLLMENT_NOT_ACTIVE"));

		// Encerrado nao e revogavel pela pessoa: o pedido novo e o caminho dela.
		mockMvc.perform(delete("/talent-banks/" + bankId + "/enrollments/" + enrollmentId)
				.header("X-Session-Token", candidate.token()))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("ENROLLMENT_CLOSED"));

		mockMvc.perform(post("/talent-banks/" + bankId + "/enrollments")
				.header("X-Session-Token", candidate.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("sharedFields", List.of("PROFILE")))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.state").value("PENDING"));
	}

	@Test
	void asOperacoesSensiveisFicamRegistradas() throws Exception {
		AuthSession recruiter = TalentBankTestSupport.recruiter(mockMvc, objectMapper, "tb_recrututor_auditoria");
		MvcResult created = TalentBankTestSupport.createBank(mockMvc, objectMapper, recruiter, "Banca auditada",
			List.of("PROFILE"));
		String bankId = TalentBankTestSupport.idOf(created, objectMapper);
		publish(recruiter, bankId);

		AuthSession candidate = new TestAuthHelper(mockMvc, objectMapper).authenticate("tb_candidato_auditoria");
		MvcResult enrollment = enroll(candidate, bankId, List.of("PROFILE"));
		String enrollmentId = TalentBankTestSupport.idOf(enrollment, objectMapper);
		confirm(candidate, bankId, enrollmentId);
		mockMvc.perform(delete("/talent-banks/" + bankId + "/enrollments/" + enrollmentId)
				.header("X-Session-Token", candidate.token()))
			.andExpect(status().isOk());

		assertThat(audit.recentForTarget("talent_bank:" + bankId, 50))
			.extracting(AuditLogRepository.AuditRecord::action)
			.contains("TALENT_BANK_CREATED", "TALENT_BANK_VISIBILITY_CHANGED");

		assertThat(audit.recentForTarget("talent_bank:" + bankId + ":" + enrollmentId, 50))
			.extracting(AuditLogRepository.AuditRecord::action)
			.contains("TALENT_BANK_ENROLLMENT_REQUESTED", "TALENT_BANK_ENROLLMENT_CONFIRMED",
				"TALENT_BANK_ENROLLMENT_REVOKED");

		assertThat(events.findOfEnrollment(enrollmentId))
			.extracting(event -> event.action().name())
			.contains("REQUESTED", "CONFIRMED", "REVOKED_BY_CANDIDATE");
	}

	private void publish(AuthSession recruiter, String bankId) throws Exception {
		mockMvc.perform(patch("/talent-banks/" + bankId + "/visibility")
				.header("X-Session-Token", recruiter.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"discoverable\":true}"))
			.andExpect(status().isOk());
	}

	private MvcResult enroll(AuthSession candidate, String bankId, List<String> fields) throws Exception {
		return mockMvc.perform(post("/talent-banks/" + bankId + "/enrollments")
				.header("X-Session-Token", candidate.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("sharedFields", fields))))
			.andExpect(status().isOk())
			.andReturn();
	}

	private void confirm(AuthSession candidate, String bankId, String enrollmentId) throws Exception {
		mockMvc.perform(post("/talent-banks/" + bankId + "/enrollments/" + enrollmentId + "/confirm")
				.header("X-Session-Token", candidate.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.state").value("ACTIVE"));
	}
}
