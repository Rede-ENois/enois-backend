package com.socialcoisa.rede.profile.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

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
		int jobAbandons = jobAbandonPenalties.countByUser(userId);
		int abandonPenalty = Math.min(150, 25 * jobAbandons * (jobAbandons + 1) / 2);
		// Nota de rodapé: negada é informação falsa (25/75/150, teto 150); aprovada é fact-check da
		// comunidade e soma +5 por nota, contando no máximo 5 notas (teto +25).
		int deniedNotes = noteCredibility.countDeniedByUser(userId);
		int notePenalty = Math.min(150, 25 * deniedNotes * (deniedNotes + 1) / 2);
		int approvedNotes = noteCredibility.countApprovedByUser(userId);
		int noteBonus = 5 * Math.min(approvedNotes, 5);
		int penalty = Math.min(MAX_PENALTY,
			60 * confirmedFacts * (confirmedFacts + 1) / 2 + abandonPenalty + notePenalty);

		int raw = (int) Math.round(trajectory + relations + contribution + consistency) + noteBonus - penalty;
		int capped = Math.max(0, Math.min(MAX_REPUTATION, raw));

		boolean identityVerified = profile.map(UserProfile::identityVerified).orElse(false);
		ReputationGarden garden = ReputationGarden.of(trajectory, relations, contribution, consistency, penalty);
		return new Counts(connections, followers, identityVerified ? capped : 0, identityVerified, garden);
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
