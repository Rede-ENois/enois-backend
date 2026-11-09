package com.socialcoisa.rede.identity.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.identity.application.port.out.DiditVerificationGrantRepository;
import com.socialcoisa.rede.identity.application.port.out.ReleaseRepository;
import com.socialcoisa.rede.identity.domain.ReleasePolicy;
import com.socialcoisa.rede.identity.domain.ReleaseRound;
import com.socialcoisa.rede.identity.domain.ReleaseRoundState;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Liberação progressiva da verificação de identidade.
 *
 * <p>A credibilidade ordena a fila, não decide aprovação: a rodada pega os primeiros da fila,
 * respeitando a capacidade real do mês, e entrega convites. Quem não foi chamado não é punido —
 * continua com a credibilidade intacta e recebe a mensagem de "está chegando".</p>
 *
 * <p>A rodada é idempotente por construção: cada convite é uma linha com chave primária no
 * usuário, então rodar duas vezes (ou um retry após falha) não entrega o mesmo convite de novo, e
 * a consulta de candidatos já exclui quem tem convite, selo ou verificação em andamento.</p>
 */
@Service
public class IdentityReleaseService {

	private static final Logger LOG = LoggerFactory.getLogger(IdentityReleaseService.class);
	private static final int MAX_AUDIT_ROUNDS = 50;

	private final ReleaseRepository releases;
	private final DiditVerificationGrantRepository grants;
	private final DiditCapacityService capacity;
	private final DiditConfig config;
	private final Clock clock;

	public IdentityReleaseService(ReleaseRepository releases, DiditVerificationGrantRepository grants,
			DiditCapacityService capacity, DiditConfig config, Clock clock) {
		this.releases = releases;
		this.grants = grants;
		this.capacity = capacity;
		this.config = config;
		this.clock = clock;
	}

	public boolean granted(String userId) {
		return grants.hasGrant(userId);
	}

	public void consume(String userId) {
		grants.markConsumed(userId, clock.instant());
	}

	public ReleasePolicy policy() {
		return releases.policy(clock.instant()).sanitized();
	}

	/**
	 * Elegibilidade da conta para iniciar a verificacao agora. Nunca julga a pessoa: informa se a
	 * vez ja chegou, e nao expoe a nota de credibilidade nem a posicao no ranking.
	 */
	public Eligibility eligibility(String userId) {
		if (!config.configured()) {
			return new Eligibility(false, "UNAVAILABLE", capacity.remaining());
		}
		if (grants.hasGrant(userId)) {
			return new Eligibility(true, "RELEASED", capacity.remaining());
		}
		return new Eligibility(false, "WAITING", capacity.remaining());
	}

	/**
	 * Roda uma rodada. Devolve a rodada gravada — inclusive quando nao libera ninguem, para o
	 * painel mostrar que a tentativa existiu e por que parou.
	 */
	@Transactional
	public ReleaseRound runRound(String actorId) {
		Instant now = clock.instant();
		ReleasePolicy policy = releases.policy(now).sanitized();
		if (policy.paused()) {
			return persist(now, policy, 0, List.of(), ReleaseRoundState.PAUSED,
				"Rodada nao executada: liberacao pausada pela administracao.");
		}
		if (!config.configured()) {
			return persist(now, policy, 0, List.of(), ReleaseRoundState.PAUSED,
				"Rodada nao executada: integracao de identidade sem credenciais.");
		}
		if (releases.inFlight() >= policy.maxSimultaneous()) {
			return persist(now, policy, 0, List.of(), ReleaseRoundState.PAUSED,
				"Rodada nao executada: limite de verificacoes simultaneas atingido.");
		}
		// A capacidade e do mes e inclui a reserva: a rodada nao pode consumi-la.
		int usable = capacity.remaining() - reserved(policy);
		if (usable <= 0) {
			return persist(now, policy, 0, List.of(), ReleaseRoundState.PAUSED,
				"Rodada nao executada: capacidade do ciclo esgotada.");
		}
		int batch = Math.min(policy.usersPerRound(), usable);
		int eligible = releases.candidates(policy.minReputation(), batch + 1).size();
		List<ReleaseRepository.Candidate> chosen = releases.candidates(policy.minReputation(), batch);
		if (chosen.isEmpty()) {
			return persist(now, policy, eligible, List.of(), ReleaseRoundState.COMPLETED,
				"Rodada executada: nenhum candidato elegivel no momento.");
		}

		String roundId = "round_" + UUID.randomUUID().toString().replace("-", "");
		for (ReleaseRepository.Candidate candidate : chosen) {
			try {
				grants.grant(candidate.userId(), roundId, candidate.reputation(), now);
			}
			catch (RuntimeException failure) {
				// Uma conta que falhou nao pode derrubar a rodada inteira: os convites ja dados
				// continuam validos e a proxima rodada volta a olhar o resto da fila.
				LOG.warn("Nao foi possivel liberar o convite de um candidato: {}", failure.getMessage());
			}
		}
		// Só entra na faixa quem realmente recebeu convite nesta rodada.
		int released = (int) chosen.stream().filter(c -> grants.hasGrant(c.userId())).count();
		if (released == 0) {
			return persist(now, policy, eligible, List.of(), ReleaseRoundState.PAUSED,
				"Rodada nao executada: nenhum convite pode ser gravado.");
		}
		LOG.info("Rodada de liberacao {} liberou {} convites (minimo de credibilidade {})", roundId,
			released, policy.minReputation());
		int rankStart = 1;
		int rankEnd = released;
		return persistRound(new ReleaseRound(roundId, now, eligible, released, policy.minReputation(),
			rankStart, rankEnd, ReleaseRoundState.COMPLETED, 0,
			"Rodada executada" + (actorId == null ? " automaticamente." : " manualmente pela administracao.")));
	}

	/**
	 * Rodada automatica, na frequencia definida pela administracao. Roda no maximo uma vez a cada
	 * intervalo configurado — sem cron agressivo — e nunca quando o painel esta pausado.
	 *
	 * <p>`nextRoundAt` e gravado ANTES de rodar: mesmo que a rodada falhe no meio, o intervalo vale
	 * e uma rodada quebrada nao vira mutirao de tentativas.</p>
	 */
	@Transactional
	public ReleaseRound runAutomaticRound() {
		Instant now = clock.instant();
		ReleasePolicy policy = releases.policy(now).sanitized();
		if (policy.paused()) {
			return null;
		}
		if (policy.nextRoundAt() != null && policy.nextRoundAt().isAfter(now)) {
			return null;
		}
		releases.savePolicy(new ReleasePolicy(policy.usersPerRound(), policy.intervalMinutes(),
			policy.minReputation(), policy.reservedQuotaPercent(), policy.maxSimultaneous(), false,
			now.plus(java.time.Duration.ofMinutes(policy.intervalMinutes()))));
		return runRound(null);
	}

	@Transactional
	public ReleasePolicy updatePolicy(ReleasePolicy next) {
		ReleasePolicy safe = next.sanitized();
		releases.savePolicy(safe);
		return safe;
	}

	public List<ReleaseRound> history() {
		return releases.rounds(MAX_AUDIT_ROUNDS);
	}

	public Overview overview() {
		ReleasePolicy policy = policy();
		return new Overview((int) releases.totalUsers(), (int) releases.releasedUsers(), releases.inFlight(),
			capacity.startedThisCycle(), capacity.remaining(), capacity.estimatedSpentUsd(),
			capacity.budgetUsd(), config.monthlyVerificationLimit(), policy,
			releases.rounds(1).stream().findFirst().orElse(null));
	}

	public void requireAdminConfigured() {
		if (!config.configured()) {
			throw new ApiException(503, "IDENTITY_PROVIDER_UNAVAILABLE",
				"A verificacao de identidade ainda nao esta configurada.");
		}
	}

	private int reserved(ReleasePolicy policy) {
		return (int) Math.ceil(capacity.budgetLimitCount() * policy.reservedQuotaPercent() / 100.0);
	}

	private ReleaseRound persist(Instant now, ReleasePolicy policy, int eligible,
			List<ReleaseRepository.Candidate> chosen, ReleaseRoundState state, String note) {
		return persistRound(new ReleaseRound("round_" + UUID.randomUUID().toString().replace("-", ""), now,
			eligible, chosen.size(), policy.minReputation(), 0, 0, state, 0, note));
	}

	private ReleaseRound persistRound(ReleaseRound round) {
		releases.saveRound(round);
		return round;
	}

	public record Eligibility(boolean released, String status, int remainingCapacity) {
	}

	public record Overview(int totalUsers, int releasedUsers, int inFlight, int startedThisCycle,
			int remainingCapacity, java.math.BigDecimal estimatedSpentUsd, java.math.BigDecimal budgetUsd,
			int monthlyLimit, ReleasePolicy policy, ReleaseRound lastRound) {
	}
}