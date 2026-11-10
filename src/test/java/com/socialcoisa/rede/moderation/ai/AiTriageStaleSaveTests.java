package com.socialcoisa.rede.moderation.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import com.sun.net.httpserver.HttpServer;
import com.socialcoisa.rede.moderation.application.port.out.ReportedContentReader;
import com.socialcoisa.rede.moderation.application.port.out.ReportedMediaReader;
import com.socialcoisa.rede.moderation.application.port.out.ReportRepository;
import com.socialcoisa.rede.moderation.domain.Report;
import com.socialcoisa.rede.moderation.domain.ReportReason;
import com.socialcoisa.rede.moderation.domain.ReportStatus;
import com.socialcoisa.rede.moderation.domain.TargetType;

/**
 * O palpite da IA não pode sobrescrever a decisão humana.
 *
 * <p>O laço da triagem lê a denúncia, chama o LLM (até 30s) e gravava o palpite no objeto lido
 * ANTES da chamada — e o save do adaptador grava TODOS os campos (a entidade não tem
 * {@code @Version}). Se uma pessoa do time revisar a denúncia durante a chamada do modelo, o save
 * do palpite devolvia a denúncia a PENDING e apagava a revisão: a IA desfazia o trabalho de quem
 * modera, sem erro nenhum no log.</p>
 *
 * <p>Prova: um LLM falso cujo handler resolve a denúncia (aprovação humana) no meio da chamada —
 * exatamente o que acontece quando o revisor é mais rápido que o modelo. No código antigo o
 * palpite sobrescreve a revisão; no novo, a gravação relê a denúncia e só anexa o palpite se ela
 * continua PENDING sem palpite.</p>
 */
class AiTriageStaleSaveTests {

	/** Palpite válido (HUMAN_REVIEW, confiança 0.9) em uma linha, para embutir na resposta. */
	private static final String VERDICT = "{\"categoria\":\"SPAM\",\"regra\":\"x\",\"gravidade\":\"LOW\","
		+ "\"confianca\":0.9,\"evidencias\":[\"t\"],\"contexto\":\"c\",\"ambiguo\":false,"
		+ "\"acao\":\"HUMAN_REVIEW\",\"justificativa\":\"j\"}";

	private HttpServer llm;
	private final HumanResolvesDuringCallRepository repo = new HumanResolvesDuringCallRepository();

	/** Porta 0: o próprio sistema escolhe, então duas suítes não brigam pela mesma porta. */
	@BeforeEach
	void startFakeLlm() throws IOException {
		llm = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		llm.createContext("/v1/chat/completions", exchange -> {
			// O revisor age ENQUANTO o modelo "pensa": entre a leitura do laço e a gravação.
			repo.humanApproves("mod_humano", "Revisado por uma pessoa: conteúdo ok");
			String content = VERDICT.replace("\"", "\\\"");
			byte[] body = ("{\"choices\":[{\"message\":{\"content\":\"" + content + "\"}}]}")
				.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, body.length);
			try (var out = exchange.getResponseBody()) {
				out.write(body);
			}
		});
		llm.start();
	}

	@AfterEach
	void stopFakeLlm() {
		llm.stop(0);
	}

	/**
	 * Repositório em memória fiel ao adaptador real: cada leitura devolve uma CÓPIA da linha
	 * (o adaptador chama {@code toDomain()}, que constrói um objeto novo), e o save substitui a
	 * linha inteira. É exatamente essa separação que permite o defeito: o objeto read no laço é
	 * uma foto anterior à chamada do LLM.
	 */
	static class HumanResolvesDuringCallRepository implements ReportRepository {

		final AtomicReference<Report> row = new AtomicReference<>();
		final List<String> savedStatuses = new ArrayList<>();

		/** Como o adaptador: cada leitura monta um objeto novo a partir da linha. */
		private Report copy(Report source) {
			return Report.restore(source.id(), source.reporterId(), source.targetType(), source.targetId(),
				source.reason(), source.description(), source.status(), source.createdAt(),
				source.reviewedBy(), source.reviewedAt(), source.resolutionNote(), source.aiTriage(),
				source.aiTriagedAt(), source.preTriage(), source.preTriageScore(), source.preTriagedAt());
		}

		@Override
		public Report save(Report report) {
			savedStatuses.add(report.status().name());
			row.set(copy(report));
			return report;
		}

		@Override
		public Optional<Report> findById(String id) {
			return Optional.ofNullable(row.get()).map(this::copy);
		}

		@Override
		public List<Report> findPendingWithoutAiTriage(int limit) {
			Report report = Report.create("rep_stale", "user_rep", TargetType.POST, "post_stale",
				ReportReason.SPAM, "spam", Instant.now());
			row.set(copy(report));
			return List.of(copy(report));
		}

		@Override
		public List<Report> findPendingWithoutPreTriage(int limit) {
			return List.of();
		}

		@Override
		public List<Report> findPendingPostsByPreTriageScore(int minScore, int limit) {
			return List.of();
		}

		@Override
		public long countAgainstTargetSince(String targetType, String targetId, Instant since) {
			return 0;
		}

		@Override
		public boolean existsByReporterAndTarget(String reporterId, String targetType, String targetId) {
			return false;
		}

		@Override
		public List<Report> findByStatus(ReportStatus status, int limit) {
			return List.of();
		}

		@Override
		public List<Report> findByReporter(String reporterId, int limit) {
			return List.of();
		}

		/** A revisão humana: aprova a LINHA (sai de PENDING) com nota gravada. */
		void humanApproves(String reviewerId, String note) {
			Report linha = copy(row.get());
			linha.approve(reviewerId, note);
			row.set(linha);
		}
	}

	private ModerationAiService service() {
		// Manager sobre H2 em memória: a transação da gravação roda de verdade (é o caminho de
		// produção, com a releitura dentro da transação).
		var manager = new DataSourceTransactionManager(
			new DriverManagerDataSource("jdbc:h2:mem:triage_stale;MODE=LEGACY"));
		return new ModerationAiService(repo, NO_CONTENT, NO_MEDIA, manager, llmUrl(), "chave-teste",
			"beta-llm/qwen3.8-27b", new tools.jackson.databind.ObjectMapper());
	}

	private static final ReportedContentReader NO_CONTENT =
		(targetType, targetId) -> Optional.empty();
	private static final ReportedMediaReader NO_MEDIA =
		(targetType, targetId, limit) -> List.of();

	private String llmUrl() {
		return "http://127.0.0.1:" + llm.getAddress().getPort() + "/v1";
	}

	@Test
	void aiVerdictNeverOverwritesTheHumanDecision() {
		int triaged = service().triagePendingReports(10, report -> true);

		// A decisão humana vence: a denúncia continua APPROVED com a nota do revisor.
		Report linha = repo.row.get();
		assertThat(linha.status())
			.as("o palpite da IA não pode devolver a denúncia a PENDING depois da revisão humana")
			.isEqualTo(ReportStatus.APPROVED);
		assertThat(linha.resolutionNote()).contains("Revisado por uma pessoa");

		// E nada foi gravado por cima da revisão (o save do palpite nem acontece).
		assertThat(repo.savedStatuses)
			.as("nenhum save pode acontecer quando a denúncia já foi revisada")
			.doesNotContain("PENDING");

		// O palpite tardio não conta como triado: segue para o histórico, não para o gate.
		assertThat(triaged).isZero();
	}
}
