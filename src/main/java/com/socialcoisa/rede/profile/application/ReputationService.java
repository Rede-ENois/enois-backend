package com.socialcoisa.rede.profile.application;

import org.springframework.stereotype.Service;

import com.socialcoisa.rede.profile.application.port.out.ProfileExtensionRepository;
import com.socialcoisa.rede.profile.application.port.out.RecommendationRepository;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;

/**
 * Calculo da credibilidade (reputacao) do usuario a partir da atividade e das denuncias.
 * Fonte unica usada tanto na exposicao do perfil quanto nas regras de negocio (ex.: criar vaga).
 */
@Service
public class ReputationService {

	/**
	 * Base da penalidade por denuncia. A penalidade e progressiva: a n-esima denuncia contra o
	 * usuario custa {@code BASE * n}, ou seja, o total descontado e {@code BASE * n(n+1)/2}
	 * (50, 150, 300, 500, ...). Reincidencia pesa cada vez mais.
	 */
	private static final int PENALTY_BASE_PER_REPORT = 50;

	private static final int MAX_REPUTATION = 1000;

	private final UserProfileRepository profileRepository;
	private final ProfileExtensionRepository extensionRepository;
	private final RecommendationRepository recommendationRepository;

	public ReputationService(UserProfileRepository profileRepository, ProfileExtensionRepository extensionRepository,
			RecommendationRepository recommendationRepository) {
		this.profileRepository = profileRepository;
		this.extensionRepository = extensionRepository;
		this.recommendationRepository = recommendationRepository;
	}

	public Counts countsFor(String userId) {
		int connections = profileRepository.countConnections(userId);
		int followers = profileRepository.countFollowers(userId);
		int experiences = extensionRepository.countExperiencesByUserId(userId);
		int educations = extensionRepository.countEducationsByUserId(userId);
		int certifications = extensionRepository.countCertificationsByUserId(userId);
		int postInteractions = profileRepository.countPostInteractionsReceived(userId);
		int communities = profileRepository.countCommunitiesJoined(userId);
		int events = profileRepository.countEventsAttended(userId);
		int mentoring = profileRepository.countMentoringSessionsAsMentee(userId);
		int reports = profileRepository.countActiveReportsAgainst(userId);
		int recommendations = recommendationRepository.findByRecommendedId(userId).size();
		int reportPenalty = PENALTY_BASE_PER_REPORT * reports * (reports + 1) / 2;
		int reputation = Math.max(0, Math.min(
			experiences * 10 + educations * 10 + certifications * 5 + connections + followers / 2
				+ postInteractions + communities * 5 + events * 3 + mentoring * 10
				- reportPenalty + recommendations * 20,
			MAX_REPUTATION));
		return new Counts(connections, followers, reputation);
	}

	public int reputationOf(String userId) {
		return countsFor(userId).reputation();
	}

	public record Counts(int connections, int followers, int reputation) {
	}
}