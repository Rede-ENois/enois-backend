package com.socialcoisa.rede.profile.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.socialcoisa.rede.profile.application.port.out.JobAbandonPenaltyReader;
import com.socialcoisa.rede.profile.application.port.out.NoteCredibilityReader;
import com.socialcoisa.rede.profile.application.port.out.ProfileExtensionRepository;
import com.socialcoisa.rede.profile.application.port.out.RecommendationRepository;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.profile.domain.Recommendation;
import com.socialcoisa.rede.profile.domain.ReputationGarden;
import com.socialcoisa.rede.profile.domain.UserProfile;

/**
 * Modelo de componentes (auditoria 19/10): retornos decrescentes, recíprocas fora, só fato
 * confirmado penaliza e o gate de identidade continua valendo.
 */
@ExtendWith(MockitoExtension.class)
class ReputationServiceTests {

	private static final String USER = "user_reputation";
	private static final Instant AGORA = Instant.parse("2026-01-01T12:00:00Z");

	@Mock
	private UserProfileRepository profileRepository;

	@Mock
	private ProfileExtensionRepository extensionRepository;

	@Mock
	private RecommendationRepository recommendationRepository;

	@Mock
	private JobAbandonPenaltyReader jobAbandonPenalties;

	@Mock
	private NoteCredibilityReader noteCredibility;

	private ReputationService service;

	@BeforeEach
	void setUp() {
		service = new ReputationService(profileRepository, extensionRepository, recommendationRepository,
			jobAbandonPenalties, noteCredibility, Clock.fixed(AGORA, ZoneOffset.UTC));
	}

	@Test
	void abandoningJobsWithoutRespondingPenalizes() {
		UserProfile profile = profile(AGORA.minusSeconds(184L * 24 * 3600));
		profile.markEmailVerified(AGORA);
		when(profileRepository.findById(USER)).thenReturn(java.util.Optional.of(profile));
		stubActivity();
		when(jobAbandonPenalties.countByUser(USER)).thenReturn(1);

		// Componentes somam 86 (ver test acima); uma penalidade ativa desconta 25.
		assertThat(service.countsFor(USER).reputation()).isEqualTo(61);
	}

	@Test
	void approvedCommunityNoteGivesUpToFivePointsEach() {
		UserProfile profile = profile(AGORA.minusSeconds(184L * 24 * 3600));
		profile.markEmailVerified(AGORA);
		when(profileRepository.findById(USER)).thenReturn(java.util.Optional.of(profile));
		stubActivity();
		when(noteCredibility.countApprovedByUser(USER)).thenReturn(2);

		// 86 dos componentes + 2 fact-checks × 5 = 96.
		assertThat(service.countsFor(USER).reputation()).isEqualTo(96);
	}

	@Test
	void approvedCommunityNoteBonusIsCappedAtFiveNotes() {
		UserProfile profile = profile(AGORA.minusSeconds(184L * 24 * 3600));
		profile.markEmailVerified(AGORA);
		when(profileRepository.findById(USER)).thenReturn(java.util.Optional.of(profile));
		stubActivity();
		when(noteCredibility.countApprovedByUser(USER)).thenReturn(9);

		// O bônus conta no máximo 5 notas: 86 + 25 = 111.
		assertThat(service.countsFor(USER).reputation()).isEqualTo(111);
	}

	@Test
	void deniedCommunityNotePenalizes() {
		UserProfile profile = profile(AGORA.minusSeconds(184L * 24 * 3600));
		profile.markEmailVerified(AGORA);
		when(profileRepository.findById(USER)).thenReturn(java.util.Optional.of(profile));
		stubActivity();
		when(noteCredibility.countDeniedByUser(USER)).thenReturn(1);

		// Uma nota falsa negada desconta 25 dos 86 dos componentes.
		assertThat(service.countsFor(USER).reputation()).isEqualTo(61);
		assertThat(service.countsFor(USER).garden().health()).isEqualTo(1);
	}

	@Test
	void verifiedAccountScoresByComponents() {
		UserProfile profile = profile(AGORA.minusSeconds(184L * 24 * 3600)); // ~6 meses
		profile.markEmailVerified(AGORA);
		when(profileRepository.findById(USER)).thenReturn(java.util.Optional.of(profile));
		stubActivity();

		ReputationService.Counts counts = service.countsFor(USER);

		assertThat(counts.identityVerified()).isTrue();
		// T=15·√3=25,98 | R=9·√5=20,12 | C=7,5·√2=10,61 | K=6·√(6·4)=29,39 → 86
		assertThat(counts.reputation()).isEqualTo(86);
	}

	@Test
	void secondFactorAlsoCountsAsVerification() {
		UserProfile profile = profile(AGORA.minusSeconds(184L * 24 * 3600));
		profile.enableTotp(AGORA);
		when(profileRepository.findById(USER)).thenReturn(java.util.Optional.of(profile));
		stubActivity();

		assertThat(service.countsFor(USER).identityVerified()).isTrue();
		assertThat(service.countsFor(USER).reputation()).isEqualTo(86);
	}

	@Test
	void reputationIsZeroWithoutEmailVerificationOrSecondFactor() {
		UserProfile profile = profile(AGORA.minusSeconds(184L * 24 * 3600));
		when(profileRepository.findById(USER)).thenReturn(java.util.Optional.of(profile));
		stubActivity();

		ReputationService.Counts counts = service.countsFor(USER);

		assertThat(counts.identityVerified()).isFalse();
		assertThat(counts.reputation()).isZero();
		// As conexões continuam sendo contadas: o que zera é a credibilidade, não o perfil.
		assertThat(counts.connections()).isEqualTo(5);
		// The garden still grows: T 25.98/195 and K 29.39/120 reach stage 1; R and C stay seeds.
		assertThat(counts.garden()).isEqualTo(new ReputationGarden(1, 0, 0, 1, 2));
	}

	@Test
	void onlyConfirmedFactsPenalizeAndEachFactCostsOnce() {
		UserProfile profile = profile(AGORA.minusSeconds(184L * 24 * 3600));
		profile.markEmailVerified(AGORA);
		when(profileRepository.findById(USER)).thenReturn(java.util.Optional.of(profile));
		stubActivity();

		// Pendente NÃO entra: o service só olha fatos confirmados (countConfirmedReportFactsAgainst).
		when(profileRepository.countConfirmedReportFactsAgainst(USER)).thenReturn(1);
		assertThat(service.countsFor(USER).reputation()).isEqualTo(26); // 86 - 60

		when(profileRepository.countConfirmedReportFactsAgainst(USER)).thenReturn(3);
		assertThat(service.countsFor(USER).reputation()).isZero(); // 86 - 360, piso 0
	}

	@Test
	void reciprocalRecommendationsDoNotCount() {
		UserProfile profile = profile(AGORA.minusSeconds(184L * 24 * 3600));
		profile.markEmailVerified(AGORA);
		when(profileRepository.findById(USER)).thenReturn(java.util.Optional.of(profile));
		stubActivity();
		List<Recommendation> recebidas = java.util.stream.IntStream.range(0, 10)
			.mapToObj(i -> Recommendation.create("rec_" + i, "u_" + i, USER, "texto", AGORA))
			.toList();
		when(recommendationRepository.findByRecommendedId(USER)).thenReturn(recebidas);

		// Todas recíprocas → componente de recomendações = 0.
		when(recommendationRepository.countReciprocal(USER)).thenReturn(10);
		assertThat(service.countsFor(USER).reputation()).isEqualTo(86);

		// Nenhuma recíproca → as 10 valem (retorno decrescente: 30·√10 = 94,87).
		when(recommendationRepository.countReciprocal(USER)).thenReturn(0);
		assertThat(service.countsFor(USER).reputation()).isEqualTo(181);
	}

	private void stubActivity() {
		when(profileRepository.countConnections(USER)).thenReturn(5);
		when(profileRepository.countFollowers(USER)).thenReturn(4);
		when(profileRepository.countUniqueEngagers(USER)).thenReturn(2);
		when(profileRepository.countCommunitiesJoined(USER)).thenReturn(0);
		when(profileRepository.countEventsAttendedTotal(USER)).thenReturn(0);
		when(profileRepository.countMentoringSessionsAsMentee(USER)).thenReturn(0);
		when(profileRepository.countConfirmedReportFactsAgainst(USER)).thenReturn(0);
		when(extensionRepository.countExperiencesByUserId(anyString())).thenReturn(3);
		when(extensionRepository.countEducationsByUserId(anyString())).thenReturn(0);
		when(extensionRepository.countCertificationsByUserId(anyString())).thenReturn(0);
		when(recommendationRepository.findByRecommendedId(USER)).thenReturn(List.of());
		when(recommendationRepository.countReciprocal(USER)).thenReturn(0);
	}

	private UserProfile profile(Instant createdAt) {
		return UserProfile.create(USER, "pessoa", USER + "@test.com", "Pessoa", null, createdAt);
	}
}
