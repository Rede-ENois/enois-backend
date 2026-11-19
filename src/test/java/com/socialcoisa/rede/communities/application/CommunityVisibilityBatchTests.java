package com.socialcoisa.rede.communities.application;

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

import com.socialcoisa.rede.communities.application.port.out.CommunityRepository;
import com.socialcoisa.rede.communities.domain.Community;
import com.socialcoisa.rede.shared.geo.CityState;
import com.socialcoisa.rede.shared.sanctions.SanctionGateReader;
import com.socialcoisa.rede.shared.security.SuspiciousText;

class CommunityVisibilityBatchTests {

	private static final Instant AGORA = Instant.parse("2024-06-01T12:00:00Z");

	private final CommunityRepository repository = mock(CommunityRepository.class);
	private final SanctionGateReader sanctions = mock(SanctionGateReader.class);
	private final CommunityService service = new CommunityService(repository, null, sanctions,
		new SuspiciousText("retirar"), Clock.fixed(AGORA, ZoneOffset.UTC), null, null);

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void filtraCadaListaComUmaConsultaDeSancao(boolean local) {
		List<Community> candidatas = List.of(
			comunidade("primeira", "Primeira", null, "livre"),
			comunidade("banida", "Banida", null, "banido"),
			comunidade("nome", "retirar", null, "texto"),
			comunidade("segunda", "Segunda", null, "livre"),
			comunidade("descricao", "Terceira", "retirar", "texto"));
		CityState lugar = CityState.of("Recife", "PE");
		when(repository.findAll(null, null, null, null, AGORA, 20)).thenReturn(candidatas);
		when(repository.findLocal(lugar.state(), lugar.cityKey(), 20)).thenReturn(candidatas);
		when(sanctions.banned("banido")).thenReturn(true);
		when(sanctions.bannedAmong(anyCollection())).thenReturn(Set.of("banido"));

		List<Community> resultado = local
			? service.listLocal(lugar, 20)
			: service.list(null, null, null, null, AGORA, 20);

		assertThat(resultado).containsExactly(candidatas.get(0), candidatas.get(3));
		verify(sanctions).bannedAmong(argThat(ids ->
			Set.copyOf(ids).equals(Set.of("livre", "banido", "texto"))));
		verifyNoMoreInteractions(sanctions);
	}

	@Test
	void listasVaziasNaoConsultamSancoes() {
		assertThat(service.list(null, null, null, null, AGORA, 20)).isEmpty();
		assertThat(service.listLocal(null, 20)).isEmpty();
		verifyNoInteractions(sanctions);
	}

	private static Community comunidade(String id, String nome, String descricao, String criador) {
		return Community.create(id, nome, descricao, null, null, null, null, criador, AGORA);
	}
}
