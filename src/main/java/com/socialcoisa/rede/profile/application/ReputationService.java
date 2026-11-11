package com.socialcoisa.rede.profile.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.socialcoisa.rede.profile.application.port.out.ProfileExtensionRepository;
import com.socialcoisa.rede.profile.application.port.out.RecommendationRepository;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.profile.domain.ReputationGarden;
import com.socialcoisa.rede.profile.domain.UserProfile;

/**
 * Credibilidade do usuário — modelo de COMPONENTES (auditoria antifraude de 19/10/2026).
 *
 * <p>A nota exibida continua sendo um número só (0–1000), mas é a soma de componentes com
 * retornos decrescentes, para que nenhuma ação isolada — nem popularidade — leve ao teto:</p>
 *
 * <ul>
 *   <li><b>T — trajetória</b>: experiências/formação/certificações, com raiz quadrada (as
 *       primeiras valem mais que a décima).</li>
 *   <li><b>R — relações</b>: conexões aceitas e recomendações <b>não recíprocas</b> (par mútuo
 *       é troca, não confiança).</li>
 *   <li><b>C — contribuição</b>: pessoas DISTINTAS que reagiram/comentaram (não cliques),
 *       comunidades, eventos e mentoria concluída.</li>
 *   <li><b>K — consistência</b>: meses desde a criação da conta, com teto (conta velha não é
 *       vantagem infinita).</li>
 *   <li><b>I — integridade</b>: SÓ fato confirmado penaliza (APPROVED/AUTO_PENALIZED), uma vez
 *       por conteúdo denunciado, com teto. Denúncia pendente não é infração; descartada ou
 *       revertida por recurso não existe.</li>
 * </ul>
 *
 * <p>Conta verificada (e-mail OU segundo fator) continua sendo o requisito de identidade: sem
 * ela a credibilidade é zero. Identidade não é confiança — é pré-condição.</p>
 *
 * <p>The same pass also yields the profile garden ({@link ReputationGarden}): one stage per component
 * and a health level from the penalty. The garden is computed for every account, verified or not.</p>
 */
@Service
public class ReputationService {

	private static final int MAX_REPUTATION = 1000;
	/** Teto da penalidade total (o modelo antigo crescia sem limite). */
	private static final int MAX_PENALTY = 600;
	/** Teto de meses na consistência: 4 anos — depois disso o tempo não soma mais. */
	private static final int MAX_MONTHS = 48;
	private static final int MAX_COMMUNITIES_COUNTED = 25;

	private final UserProfileRepository profileRepository;
	private final ProfileExtensionRepository extensionRepository;
	private final RecommendationRepository recommendationRepository;
	/** Vagas excluídas com candidatos sem resposta (item 7 do backlog) — implementação no módulo de jobs. */
	private final com.socialcoisa.rede.profile.application.port.out.JobAbandonPenaltyReader jobAbandonPenalties;
	/** Notas de rodapé (fact-checks): aprovadas dão bônus; negadas descontam — o feed implementa. */
	private final com.socialcoisa.rede.profile.application.port.out.NoteCredibilityReader noteCredibility;
	private final Clock clock;

	public ReputationService(UserProfileRepository profileRepository, ProfileExtensionRepository extensionRepository,
			RecommendationRepository recommendationRepository,
			com.socialcoisa.rede.profile.application.port.out.JobAbandonPenaltyReader jobAbandonPenalties,
			com.socialcoisa.rede.profile.application.port.out.NoteCredibilityReader noteCredibility, Clock clock) {
		this.profileRepository = profileRepository;
		this.extensionRepository = extensionRepository;
		this.recommendationRepository = recommendationRepository;
		this.jobAbandonPenalties = jobAbandonPenalties;
		this.noteCredibility = noteCredibility;
		this.clock = clock;
	}

	public Counts countsFor(String userId) {
		var profile = profileRepository.findById(userId);

		int connections = profileRepository.countConnections(userId);
		int followers = profileRepository.countFollowers(userId);
		int experiences = extensionRepository.countExperiencesByUserId(userId);
		int educations = extensionRepository.countEducationsByUserId(userId);
		int certifications = extensionRepository.countCertificationsByUserId(userId);
		int uniqueEngagers = profileRepository.countUniqueEngagers(userId);
		int communities = profileRepository.countCommunitiesJoined(userId);
		int events = profileRepository.countEventsAttendedTotal(userId);
		int mentoring = profileRepository.countMentoringSessionsAsMentee(userId);
		int confirmedFacts = profileRepository.countConfirmedReportFactsAgainst(userId);
		int recommendations = recommendationRepository.findByRecommendedId(userId).size();
		int reciprocal = recommendationRepository.countReciprocal(userId);
		long months = profile.map(user -> monthsSince(user.createdAt())).orElse(0L);

		boolean identityVerified = profile.map(UserProfile::identityVerified).orElse(false);
		return score(new Inputs(connections, followers, experiences, educations, certifications, uniqueEngagers,
			communities, events, mentoring, confirmedFacts, recommendations, reciprocal, months,
			jobAbandonPenalties.countByUser(userId), noteCredibility.countDeniedByUser(userId),
			noteCredibility.countApprovedByUser(userId), identityVerified));
	}

	/**
	 * A mesma nota de {@link #countsFor}, para um CONJUNTO de contas, com uma consulta por métrica
	 * em vez de dezessete por conta. É o caminho do job do ranking, que recalcula todas as contas
	 * periodicamente: o custo dele não pode crescer com o número de contas.
	 *
	 * <p>A fórmula é a MESMA — {@link #score} é a fonte única dos dois caminhos. O que muda é só a
	 * origem dos números: aqui eles vêm agrupados por dono, uma consulta por métrica.</p>
	 */
	public Map<String, Integer> reputationsOf(Collection<String> userIds) {
		if (userIds.isEmpty()) {
			return Map.of();
		}
		var seeds = profileRepository.seedsOf(userIds);
		var connections = profileRepository.connectionCounts(userIds);
		var followers = profileRepository.followerCounts(userIds);
		var experiences = extensionRepository.experienceCounts(userIds);
		var educations = extensionRepository.educationCounts(userIds);
		var certifications = extensionRepository.certificationCounts(userIds);
		var engagers = profileRepository.uniqueEngagerCounts(userIds);
		var communities = profileRepository.communitiesJoinedCounts(userIds);
		var events = profileRepository.eventsAttendedTotalCounts(userIds);
		var mentoring = profileRepository.mentoringSessionCounts(userIds);
		var confirmedFacts = profileRepository.confirmedReportFactCounts(userIds);
		var recommendations = recommendationRepository.receivedCounts(userIds);
		var reciprocal = recommendationRepository.reciprocalCounts(userIds);
		var jobAbandons = jobAbandonPenalties.countsByUsers(userIds);
		var deniedNotes = noteCredibility.deniedCountsByUsers(userIds);
		var approvedNotes = noteCredibility.approvedCountsByUsers(userIds);

		var reputations = new HashMap<String, Integer>(seeds.size());
		for (var seed : seeds) {
			String id = seed.id();
			reputations.put(id, score(new Inputs(
				connections.getOrDefault(id, 0), followers.getOrDefault(id, 0),
				experiences.getOrDefault(id, 0), educations.getOrDefault(id, 0),
				certifications.getOrDefault(id, 0), engagers.getOrDefault(id, 0),
				communities.getOrDefault(id, 0), events.getOrDefault(id, 0),
				mentoring.getOrDefault(id, 0), confirmedFacts.getOrDefault(id, 0),
				recommendations.getOrDefault(id, 0), reciprocal.getOrDefault(id, 0),
				monthsSince(seed.createdAt()), jobAbandons.getOrDefault(id, 0),
				deniedNotes.getOrDefault(id, 0), approvedNotes.getOrDefault(id, 0),
				seed.identityVerified())).reputation());
		}
		return reputations;
	}

	/** Os números de uma conta, já coletados — o que a fórmula precisa, e nada mais. */
	private record Inputs(int connections, int followers, int experiences, int educations, int certifications,
			int uniqueEngagers, int communities, int events, int mentoring, int confirmedFacts,
			int recommendations, int reciprocal, long months, int jobAbandons, int deniedNotes,
			int approvedNotes, boolean identityVerified) {
	}

	/** A fórmula da credibilidade — fonte única do caminho individual e do caminho em lote. */
	private Counts score(Inputs in) {
		int connections = in.connections();
		int followers = in.followers();
		int experiences = in.experiences();
		int educations = in.educations();
		int certifications = in.certifications();
		int uniqueEngagers = in.uniqueEngagers();
		int communities = in.communities();
		int events = in.events();
		int mentoring = in.mentoring();
		int confirmedFacts = in.confirmedFacts();
		int recommendations = in.recommendations();
		int reciprocal = in.reciprocal();
		long months = in.months();

		// T — trajetória profissional.
		double trajectory = Math.min(90, 15 * Math.sqrt(experiences))
			+ Math.min(60, 12 * Math.sqrt(educations))
			+ Math.min(45, 7.5 * Math.sqrt(certifications));

		// R — relações: recomendações mútuas não entram; conexões com retorno decrescente.
		int validRecommendations = Math.max(0, recommendations - reciprocal);
		double relations = Math.min(180, 9 * Math.sqrt(connections))
			+ Math.min(120, 30 * Math.sqrt(validRecommendations));

		// C — contribuição: interagentes ÚNICOS (autointeração e comentar/apagar não rendem).
		double contribution = Math.min(225, 7.5 * Math.sqrt(uniqueEngagers))
			+ Math.min(90, 15 * Math.sqrt(Math.min(communities, MAX_COMMUNITIES_COUNTED)))
			+ Math.min(45, 7.5 * Math.sqrt(events))
			+ Math.min(60, 15 * Math.sqrt(mentoring));

		// K — consistência temporal (teto de meses; sem privilégio infinito para conta velha).
		double consistency = Math.min(120, 6 * Math.sqrt(Math.min(months, MAX_MONTHS) * 4.0));

		// I — integridade: uma penalidade por FATO confirmado distinto (denúncia), uma por vaga
		// excluída com candidatos sem resposta (item 7 do backlog: 25, depois 75, depois 150 —
		// teto próprio de 150, porque excluir vaga é infração leve comparada a fraude confirmada)
		// e uma por nota de rodapé NEGADA pela moderação (informação falsa; teto próprio de 150).
		// Nota aprovada — ou em análise — não muda a credibilidade: corrigir o post é o objetivo.
		int abandonPenalty = Math.min(150, 25 * in.jobAbandons() * (in.jobAbandons() + 1) / 2);
		// Nota de rodapé: negada é informação falsa (25/75/150, teto 150); aprovada é fact-check da
		// comunidade e soma +5 por nota, contando no máximo 5 notas (teto +25).
		int notePenalty = Math.min(150, 25 * in.deniedNotes() * (in.deniedNotes() + 1) / 2);
		int noteBonus = 5 * Math.min(in.approvedNotes(), 5);
		int penalty = Math.min(MAX_PENALTY,
			60 * in.confirmedFacts() * (in.confirmedFacts() + 1) / 2 + abandonPenalty + notePenalty);

		int raw = (int) Math.round(trajectory + relations + contribution + consistency) + noteBonus - penalty;
		int capped = Math.max(0, Math.min(MAX_REPUTATION, raw));

		ReputationGarden garden = ReputationGarden.of(trajectory, relations, contribution, consistency, penalty);
		return new Counts(in.connections(), in.followers(), in.identityVerified() ? capped : 0,
			in.identityVerified(), garden);
	}

	public int reputationOf(String userId) {
		return countsFor(userId).reputation();
	}

	private long monthsSince(Instant createdAt) {
		if (createdAt == null) {
			return 0;
		}
		return Math.max(0, Duration.between(createdAt, clock.instant()).toDays() / 30);
	}

	public record Counts(int connections, int followers, int reputation, boolean identityVerified,
			ReputationGarden garden) {
	}
}
