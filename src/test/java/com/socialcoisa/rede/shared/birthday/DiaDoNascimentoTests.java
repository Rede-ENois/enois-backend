package com.socialcoisa.rede.shared.birthday;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.profile.domain.UserProfile;

/** A regra do selo "Aniversariante": quem entra, quem fica de fora e por quê. */
class DiaDoNascimentoTests {

	private static final ZoneId BRASILIA = ZoneId.of("America/Sao_Paulo");

	/** Relógio parado em 2026-11-23 10:00 de Brasília. */
	private static final Clock RELOGIO = Clock.fixed(Instant.parse("2026-11-23T13:00:00Z"), ZoneId.of("UTC"));

	private static final LocalDate HOJE = LocalDate.of(2026, 11, 23);

	/** Cadastro de quem nasceu em 1994: décadas depois do nascimento. */
	private static final Instant CADASTRO_ADULTO = LocalDate.of(2020, 3, 7).atStartOfDay(BRASILIA).toInstant();

	private final UserProfileRepository profiles = mock(UserProfileRepository.class);

	private final DiaDoNascimento aniversarios = new DiaDoNascimento(profiles, RELOGIO);

	private UserProfile perfil(LocalDate nascimento, Instant cadastro) {
		UserProfile profile = mock(UserProfile.class);
		when(profile.id()).thenReturn("u1");
		when(profile.birthDate()).thenReturn(nascimento);
		when(profile.createdAt()).thenReturn(cadastro);
		when(profile.isMinorOn(any())).thenAnswer(inv -> {
			LocalDate referencia = inv.getArgument(0);
			return nascimento != null && nascimento.plusYears(18).isAfter(referencia);
		});
		return profile;
	}

	private boolean aniversaria(UserProfile profile) {
		when(profiles.findById("u1")).thenReturn(Optional.of(profile));
		return aniversarios.fazAniversarioHoje("u1");
	}

	@Test
	void aniversariaQuandoONascimentoEODeHojeEAdulto() {
		assertThat(aniversaria(perfil(LocalDate.of(1994, 11, 23), CADASTRO_ADULTO))).isTrue();
	}

	@Test
	void diaDeNascimentoDiferenteNaoEntra() {
		assertThat(aniversaria(perfil(LocalDate.of(1994, 11, 24), CADASTRO_ADULTO))).isFalse();
	}

	@Test
	void semDataDeNascimentoNaoEntra() {
		assertThat(aniversaria(perfil(null, CADASTRO_ADULTO))).isFalse();
	}

	@Test
	void menorDeIdadeNaoEntra() {
		assertThat(aniversaria(perfil(LocalDate.of(2010, 11, 23), CADASTRO_ADULTO))).isFalse();
	}

	@Test
	void quemCompletaDezoitoHojeEntra() {
		Instant cadastro = LocalDate.of(2026, 5, 2).atStartOfDay(BRASILIA).toInstant();
		assertThat(aniversaria(perfil(LocalDate.of(2008, 11, 23), cadastro))).isTrue();
	}

	@Test
	void dataCopiadaNoCadastroNaoConta() {
		Instant cadastro = LocalDate.of(1990, 5, 10).atStartOfDay(BRASILIA).toInstant();
		assertThat(aniversaria(perfil(LocalDate.of(1990, 5, 10), cadastro))).isFalse();
	}

	@Test
	void quemNasceuNoMesmoDiaDoCadastroDecadasAntesContinuaAniversariando() {
		LocalDate nascimento = LocalDate.of(2026, 11, 23).minusYears(40);
		Instant cadastro = LocalDate.of(2026, 11, 23).atStartOfDay(BRASILIA).toInstant();
		assertThat(aniversaria(perfil(nascimento, cadastro))).isTrue();
	}

	@Test
	void aListaTrazSoQuemPassaPelasProvas() {
		UserProfile aniversariante = perfil(LocalDate.of(1994, 11, 23), CADASTRO_ADULTO);
		UserProfile outroDia = perfil(LocalDate.of(1990, 1, 1), CADASTRO_ADULTO);
		when(profiles.findAllByIds(Set.of("u1"))).thenReturn(List.of(aniversariante, outroDia));

		assertThat(aniversarios.aniversariantes(Set.of("u1"))).containsExactly("u1");
		assertThat(aniversarios.aniversariantes(List.of())).isEmpty();
	}

	@Test
	void oDiaSegueOHorarioDeBrasiliaENaoOUTC() {
		// 02:30 UTC de 23/11 ainda é 23:30 de 22/11 em Brasília (UTC-3): o "hoje" da rede é 22/11.
		Clock tardeNoBrasil = Clock.fixed(Instant.parse("2026-11-23T02:30:00Z"), ZoneId.of("UTC"));
		DiaDoNascimento aniversarios = new DiaDoNascimento(profiles, tardeNoBrasil);
		UserProfile profile = perfil(LocalDate.of(1994, 11, 22), CADASTRO_ADULTO);
		when(profiles.findById("u1")).thenReturn(Optional.of(profile));

		assertThat(aniversarios.hoje()).isEqualTo(LocalDate.of(2026, 11, 22));
		assertThat(aniversarios.fazAniversarioHoje("u1")).isTrue();
	}
}
