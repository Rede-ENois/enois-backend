package com.socialcoisa.rede.profile.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.shared.security.ActionThrottle;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * A atualizacao de perfil e uma acao de humano: o app so chama nos dialogos de edicao. Repeticao em
 * rajada so vem de script, e cada chamada carrega limpeza de HTML/CSS — o teto por conta precisa
 * responder antes de qualquer trabalho.
 */
class ProfileUpdateThrottleTests {

	private static final int TETO = 3;

	private static ProfileService service() {
		UserProfileRepository repository = mock(UserProfileRepository.class);
		when(repository.findById(any())).thenReturn(Optional.empty());
		return new ProfileService(repository, null, null, null, null, null, null, new ActionThrottle(), TETO,
			Clock.systemUTC());
	}

	private static UpdateProfileCommand comando() {
		return new UpdateProfileCommand("u1", "Nome", null, null, null, null, null, null, null, null, null,
			java.util.List.of(), java.util.List.of(), null, null, java.util.List.of(), null, null, null, null, null,
			null, null, null, null, null);
	}

	private static ApiException recusa(ProfileService service) {
		try {
			service.update(comando());
			return null;
		}
		catch (ApiException recusa) {
			return recusa;
		}
	}

	@Test
	void rajadaRecebeTetoPorConta() {
		ProfileService service = service();
		for (int i = 0; i < TETO; i++) {
			ApiException recusa = recusa(service);
			// Passou pelo teto e seguiu: o usuario nao existe neste repositorio falso.
			assertThat(recusa).isNotNull();
			assertThat(recusa.code()).isEqualTo("USER_NOT_FOUND");
		}

		ApiException rajada = recusa(service);
		assertThat(rajada).isNotNull();
		assertThat(rajada.code()).isEqualTo("ACTION_THROTTLED");
		assertThat(rajada.status()).isEqualTo(429);
	}

	@Test
	void contasDiferentesNaoDividemOTeto() {
		ProfileService service = service();
		for (int i = 0; i < TETO; i++) {
			recusa(service);
		}
		assertThat(recusa(service).code()).isEqualTo("ACTION_THROTTLED");

		ApiException outraConta = null;
		try {
			service.update(new UpdateProfileCommand("u2", null, null, null, null, null, null, null, null, null, null,
				java.util.List.of(), java.util.List.of(), null, null, java.util.List.of(), null, null, null, null, null,
				null, null, null, null, null));
		}
		catch (ApiException recusa) {
			outraConta = recusa;
		}
		assertThat(outraConta).isNotNull();
		assertThat(outraConta.code()).isEqualTo("USER_NOT_FOUND");
	}
}