package com.socialcoisa.rede.moderation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import tools.jackson.databind.ObjectMapper;

import com.socialcoisa.rede.feed.application.port.out.PostRepository;
import com.socialcoisa.rede.feed.domain.PostId;
import com.socialcoisa.rede.moderation.application.ModerationService;
import com.socialcoisa.rede.moderation.application.SanctionService;
import com.socialcoisa.rede.moderation.application.port.out.ReportRepository;
import com.socialcoisa.rede.moderation.domain.Report;
import com.socialcoisa.rede.moderation.domain.ReportReason;
import com.socialcoisa.rede.moderation.domain.ReportStatus;
import com.socialcoisa.rede.moderation.domain.TargetType;
import com.socialcoisa.rede.profile.application.ReputationService;
import com.socialcoisa.rede.profile.application.RecommendationService;
import com.socialcoisa.rede.profile.application.port.in.ManageProfileExtensionUseCase;
import com.socialcoisa.rede.profile.application.port.out.RecommendationRepository;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

/**
 * Regras de JUSTIÇA da credibilidade (auditoria antifraude de 19/10):
 * denúncia pendente não penaliza, fato confirmado conta uma vez, recurso aceito devolve a nota,
 * autointeração não engaja, comentário apagado devolve o contador e recíproca não soma.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReputationFairnessTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private ReputationService reputation;

	@Autowired
	private UserProfileRepository profiles;

	@Autowired
	private ReportRepository reports;

	@Autowired
	private PostRepository posts;

	@Autowired
	private RecommendationService recommendations;

	@Autowired
	private RecommendationRepository recommendationRepository;

	@Autowired
	private ManageProfileExtensionUseCase extensions;

	@Autowired
	private SanctionService sanctions;

	private TestAuthHelper auth;

	@BeforeEach
	void setUp() {
		auth = new TestAuthHelper(mockMvc, objectMapper);
	}

	private String publish(TestAuthHelper.AuthSession session, String content) throws Exception {
		MvcResult result = mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", "pub-fair-" + content.hashCode())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("topic", "TECNOLOGIA", "type", "INSIGHT", "content", content,
					"tags", List.of(), "mediaIds", List.of()))))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}

	private void react(TestAuthHelper.AuthSession session, String postId, String type) throws Exception {
		mockMvc.perform(post("/feed/posts/" + postId + "/reactions")
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", "react-fair-" + session.userId() + postId + type)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("type", type))))
			.andExpect(status().isOk());
	}

	private String comment(TestAuthHelper.AuthSession session, String postId, String content) throws Exception {
		MvcResult result = mockMvc.perform(post("/feed/posts/" + postId + "/comments")
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", "comment-fair-" + session.userId() + postId + content.hashCode())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("content", content))))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}

	@Test
	void pendingReportsDoNotPenalizeAndConfirmedFactsAreDeduplicated() throws Exception {
		var author = auth.authenticate("user_fair_author");
		String postId = publish(author, "Post da auditoria de credibilidade.");
		int base = reputation.reputationOf(author.userId());

		var reporter1 = auth.authenticate("user_fair_reporter_1");
		var reporter2 = auth.authenticate("user_fair_reporter_2");
		Report primeira = reports.save(Report.create("report_fair_pending_1", reporter1.userId(), TargetType.POST,
			postId, ReportReason.SPAM, "teste", Instant.now()));
		reports.save(Report.create("report_fair_pending_2", reporter2.userId(), TargetType.POST, postId,
			ReportReason.SPAM, "teste", Instant.now()));

		// Denúncia PENDENTE não é infração: a nota do autor não se move.
		assertThat(reputation.reputationOf(author.userId())).isEqualTo(base);

		// Confirmadas contra o MESMO conteúdo: um fato só, uma penalidade só.
		primeira.approve("moderador_teste", "confirmado");
		reports.save(primeira);
		assertThat(profiles.countConfirmedReportFactsAgainst(author.userId())).isEqualTo(1);
		assertThat(reputation.reputationOf(author.userId())).isEqualTo(Math.max(0, base - 60));

		// Descartar o fato confirmado devolve tudo.
		primeira.dismiss("moderador_teste", "descartado apos revisao");
		reports.save(primeira);
		assertThat(profiles.countConfirmedReportFactsAgainst(author.userId())).isZero();
		assertThat(reputation.reputationOf(author.userId())).isEqualTo(base);
	}

	@Test
	void selfInteractionsDoNotCountAsEngagement() throws Exception {
		var author = auth.authenticate("user_fair_eng_author");
		var outro = auth.authenticate("user_fair_eng_other");
		String postId = publish(author, "Post para testar engajamento.");

		react(author, postId, "LIKE");
		comment(author, postId, "comentario do proprio autor");
		assertThat(profiles.countUniqueEngagers(author.userId())).isZero();

		react(outro, postId, "LIKE");
		comment(outro, postId, "comentario de outra pessoa");
		assertThat(profiles.countUniqueEngagers(author.userId())).isEqualTo(1);
	}

	@Test
	void deletingACommentReturnsTheCounter() throws Exception {
		var author = auth.authenticate("user_fair_del_author");
		var outro = auth.authenticate("user_fair_del_other");
		String postId = publish(author, "Post com comentario apagado.");

		String commentId = comment(outro, postId, "vou apagar este comentario");
		assertThat(posts.findById(new PostId(postId)).orElseThrow().counters().comments()).isEqualTo(1);

		mockMvc.perform(delete("/feed/posts/" + postId + "/comments/" + commentId)
				.header("X-Session-Token", outro.token())
				.header("Idempotency-Key", "del-comment-fair-1"))
			.andExpect(status().isOk());

		// Comentar/apagar em loop não pode inflar a interação recebida (nem a credibilidade).
		assertThat(posts.findById(new PostId(postId)).orElseThrow().counters().comments()).isZero();
	}

	@Test
	void reciprocalRecommendationsDoNotRaiseReputation() throws Exception {
		var pessoaA = auth.authenticate("user_fair_rec_a");
		var pessoaB = auth.authenticate("user_fair_rec_b");
		int antes = reputation.reputationOf(pessoaB.userId());

		recommendations.recommend(pessoaA.userId(), pessoaB.userId(), "trabalha bem");
		recommendations.recommend(pessoaB.userId(), pessoaA.userId(), "trabalha bem");

		assertThat(recommendationRepository.countReciprocal(pessoaB.userId())).isEqualTo(1);
		assertThat(reputation.reputationOf(pessoaB.userId())).isEqualTo(antes);
	}

	@Test
	void acceptedAppealDismissesTheReportAndRestoresReputation() throws Exception {
		var author = auth.authenticate("user_fair_appeal_author");
		var reporter = auth.authenticate("user_fair_appeal_reporter");
		// Currículo mínimo para a nota ficar acima da penalidade e a restauração ser visível.
		var inicio = java.time.LocalDate.of(2016, 1, 1);
		for (int i = 0; i < 5; i++) {
			extensions.addExperience(author.userId(), "Dev " + i, "Empresa " + i, null, null,
				inicio.plusYears(i), null);
			extensions.addEducation(author.userId(), "Universidade " + i, "Bacharelado", "Computação", null,
				inicio.plusYears(i), null);
			extensions.addCertification(author.userId(), "Certificação " + i, "Emissor", null,
				inicio.plusYears(i), null, null);
		}
		String postId = publish(author, "Post alvo do recurso.");
		int base = reputation.reputationOf(author.userId());

		Report confirmada = reports.save(Report.create("report_fair_appeal_1", reporter.userId(), TargetType.POST,
			postId, ReportReason.HARASSMENT, "teste", Instant.now()));
		confirmada.approve("moderador_teste", "confirmado");
		reports.save(confirmada);
		assertThat(reputation.reputationOf(author.userId())).isEqualTo(Math.max(0, base - 60));

		sanctions.restrictUser(author.userId(), confirmada.id(), "conteudo confirmado");
		var appeal = sanctions.appeal(author.userId(), "quero revisao humana do caso, por favor");
		sanctions.approveAppeal(appeal.id(), "revisor_humano");

		// O recurso aceito REVERTE o fato: a denúncia vira DISMISSED e a nota volta.
		assertThat(reports.findById(confirmada.id()).orElseThrow().status()).isEqualTo(ReportStatus.DISMISSED);
		assertThat(profiles.countConfirmedReportFactsAgainst(author.userId())).isZero();
		assertThat(reputation.reputationOf(author.userId())).isEqualTo(base);
	}
}
