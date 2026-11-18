package com.socialcoisa.rede.profile.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.profile.domain.UserProfile;
import com.socialcoisa.rede.shared.sanctions.SanctionGateReader;

class ProfileVisibilityBatchTests {

	private static final Instant AGORA = Instant.parse("2024-06-01T12:00:00Z");

	private final UserProfileRepository repository = mock(UserProfileRepository.class);
	private final SanctionGateReader sanctions = mock(SanctionGateReader.class);
	private final ProfileService service = new ProfileService(repository, null, null, null, null,
		sanctions, null, null, null, null, null, 30, Clock.fixed(AGORA, ZoneOffset.UTC));

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void preservaASelecaoEAOrdemComUmaConsultaDeSancao(boolean vitrine) {
		List<UserProfile> candidatos = List.of(perfil("banido_1"), perfil("livre_1"),
			perfil("banido_2"), perfil("livre_2"), perfil("livre_3"), perfil("banido_3"));
		Set<String> banidos = Set.of("banido_1", "banido_2", "banido_3");
		Set<String> idsEsperados = Set.copyOf(candidatos.stream().map(UserProfile::id).toList());
		when(repository.searchByQuery("busca")).thenReturn(candidatos);
		when(repository.findNewestProfiles(6)).thenReturn(candidatos);
		when(sanctions.bannedAmong(anyCollection())).thenReturn(banidos);
		for (String id : banidos) {
			when(sanctions.banned(id)).thenReturn(true);
		}

		List<UserProfileView> resultado = vitrine ? service.getRanking(2) : service.search("busca");

		List<UserProfileView> esperados = List.of(candidatos.get(1), candidatos.get(3), candidatos.get(4))
			.stream().limit(vitrine ? 2 : 3).map(UserProfileView::from).toList();
		assertThat(resultado).containsExactlyElementsOf(esperados);
		verify(sanctions).bannedAmong(argThat(ids -> Set.copyOf(ids).equals(idsEsperados)));
		verifyNoMoreInteractions(sanctions);
	}

	@Test
	void listasVaziasNaoConsultamSancoes() {
		assertThat(service.search("vazia")).isEmpty();
		assertThat(service.getRanking(0)).isEmpty();
		verify(repository).findNewestProfiles(3);
		verifyNoInteractions(sanctions);
	}

	private static UserProfile perfil(String id) {
		return UserProfile.create(id, id, null, "Perfil do cenário", null, AGORA);
	}
}
