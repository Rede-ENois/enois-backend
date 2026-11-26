package com.socialcoisa.rede.figurinhas.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.identity.application.IdentityVerificationService;
import com.socialcoisa.rede.profile.application.ReputationService;
import com.socialcoisa.rede.shared.web.ApiException;

/** O piso de reputação do envio: 100, contado só para quem verificou a identidade. */
class FigurinhaEnvioPolicyTests {

	private static final String PESSOA = "user-1";

	private final ReputationService reputations = mock(ReputationService.class);
	private final IdentityVerificationService identity = mock(IdentityVerificationService.class);
	private final FigurinhaEnvioPolicy policy = new FigurinhaEnvioPolicy(reputations, identity);

	@Test
	void exatamenteCemDeReputacaoJaLiberaOEnvio() {
		quemVerificou(100);

		FigurinhaEnvioPolicy.Decisao decisao = policy.exigir(PESSOA);

		assertThat(decisao.pode()).isTrue();
		assertThat(decisao.reputacao()).isEqualTo(100);
		assertThat(decisao.reputacaoMinima()).isEqualTo(FigurinhaEnvioPolicy.REPUTACAO_MINIMA);
		assertThat(decisao.motivo()).isNull();
	}

	@Test
	void noventaENoveAindaNaoLibera() {
		quemVerificou(99);

		assertThat(policy.avaliar(PESSOA).pode()).isFalse();
		assertThatThrownBy(() -> policy.exigir(PESSOA)).isInstanceOfSatisfying(ApiException.class, erro -> {
			assertThat(erro.status()).isEqualTo(403);
			assertThat(erro.code()).isEqualTo("LOW_REPUTATION");
			assertThat(erro.getMessage()).contains("100").contains("99");
		});
	}

	@Test
	void semIdentidadeVerificadaARecusaFalaDeIdentidadeENaoDeReputacao() {
		// O cálculo da reputação devolve 0 para quem não verificou; a recusa tem de dizer o motivo
		// certo, senão a pessoa acha que só precisa "postar mais".
		when(identity.isVerified(PESSOA)).thenReturn(false);
		when(reputations.reputationOf(PESSOA)).thenReturn(1000);

		FigurinhaEnvioPolicy.Decisao decisao = policy.avaliar(PESSOA);

		assertThat(decisao.pode()).isFalse();
		assertThat(decisao.identidadeVerificada()).isFalse();
		assertThat(decisao.motivo()).isEqualTo("IDENTITY_REQUIRED");
		assertThatThrownBy(() -> policy.exigir(PESSOA)).isInstanceOf(ReputacaoIndisponivelException.class)
			.satisfies(erro -> {
				ApiException api = (ApiException) erro;
				assertThat(api.status()).isEqualTo(403);
				assertThat(api.code()).isEqualTo("REPUTACAO_INDISPONIVEL");
				assertThat(api.getMessage()).contains("identidade");
			});
	}

	private void quemVerificou(int reputacao) {
		when(identity.isVerified(PESSOA)).thenReturn(true);
		when(reputations.reputationOf(PESSOA)).thenReturn(reputacao);
	}
}
