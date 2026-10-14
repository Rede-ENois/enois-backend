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

	/** Credibilidade perdida por cada denuncia contra conteudo do usuario (post, comentario ou vaga). */
	private static final int PENALTY_PER_REPORT = 50;

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
		int reputation = Math.max(0, Math.min(
			experiences * 10 + educations * 10 + certifications * 5 + connections + followers / 2
				+ postInteractions + communities * 5 + events * 3 + mentoring * 10
				- reports * PENALTY_PER_REPORT + recommendations * 20,
			MAX_REPUTATION));
		return new Counts(connections, followers, reputation);
	}

	public int reputationOf(String userId) {
		return countsFor(userId).reputation();
	}

	public record Counts(int connections, int followers, int reputation) {
	}
}