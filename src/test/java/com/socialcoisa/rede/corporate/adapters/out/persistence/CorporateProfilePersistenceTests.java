package com.socialcoisa.rede.corporate.adapters.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;

import com.socialcoisa.rede.corporate.domain.CorporateProfile;
import com.socialcoisa.rede.corporate.domain.CorporateProfileStatus;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * A unicidade do @ profissional é do BANCO (índice único), não só da interface: duas contas
 * tentando o mesmo @ ao mesmo tempo deixam uma delas com 409 USERNAME_TAKEN.
 */
@SpringBootTest
class CorporateProfilePersistenceTests {

	@Autowired
	private CorporateProfilePersistenceAdapter adapter;

	private final Instant now = Instant.now();

	@Test
	void salvaEEncontraPorUsername() {
		CorporateProfile salvo = adapter.save(
			CorporateProfile.create("user_corp_1", "mel.santos", "Mel Santos", now).activate(now));

		assertThat(adapter.findByUsername("mel.santos")).isPresent();
		assertThat(adapter.findByUserId("user_corp_1")).isPresent();
		assertThat(adapter.existsByUsername("mel.santos")).isTrue();
		assertThat(adapter.findByUsername("mel.santos").orElseThrow().status())
			.isEqualTo(CorporateProfileStatus.ACTIVE);
		assertThat(salvo.userId()).isEqualTo("user_corp_1");
	}

	@Test
	void usernameUnicoNoNamespaceProfissional() {
		adapter.save(CorporateProfile.create("user_corp_2", "nome.unico", "Primeira", now));

		// Outra conta com o mesmo @ profissional: o banco recusa e vira 409 amigável.
		assertThatThrownBy(() -> adapter.save(
				CorporateProfile.create("user_corp_3", "nome.unico", "Segunda", now.plusSeconds(1))))
			.isInstanceOfSatisfying(ApiException.class, e -> {
				assertThat(e.status()).isEqualTo(409);
				assertThat(e.code()).isEqualTo("USERNAME_TAKEN");
			})
			.hasMessageContaining("usuário profissional");
	}

	@Test
	void outraViolacaoDeGravacaoNaoViraConflitoDeUsername() {
		CorporateProfile perfil = adapter.save(CorporateProfile.create("user_corp_5", "local.longo", "Local Longo", now));

		// location é varchar(200): o banco recusa o valor, e isso não tem nada a ver com o @
		// (#294: um erro de gravação da capa aparecia como USERNAME_TAKEN).
		CorporateProfile comLocalGrandeDemais = perfil.withDetails(null, null, null, null, null,
			"x".repeat(201), null, null, now.plusSeconds(1));

		// A subclasse exata depende do driver; o que importa é o erro subir sem virar 409.
		assertThatThrownBy(() -> adapter.save(comLocalGrandeDemais))
			.isInstanceOf(DataAccessException.class)
			.isNotInstanceOf(ApiException.class);
	}

	@Test
	void namespaceProfissionalNaoColideComNadaDoSocial() {
		// Este @ pode existir no perfil social de outra pessoa sem conflito: o índice é da tabela
		// profissional (o namespace é próprio por decisão do plano).
		CorporateProfile salvo = adapter.save(
			CorporateProfile.create("user_corp_4", "qualquer.at", "Perfil Profissional", now));
		assertThat(salvo.username()).isEqualTo("qualquer.at");
	}
}
