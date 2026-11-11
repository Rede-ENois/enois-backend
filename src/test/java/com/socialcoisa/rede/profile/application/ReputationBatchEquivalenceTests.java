package com.socialcoisa.rede.profile.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import com.socialcoisa.rede.profile.application.port.in.ManageProfileExtensionUseCase;
import com.socialcoisa.rede.shared.web.TestAuthHelper;
import com.socialcoisa.rede.shared.web.TestDatabase;

import tools.jackson.databind.ObjectMapper;

/**
 * REGRA Nº 1: a lotização do job do ranking não muda a nota que o usuário observa. Para as
 * MESMAS contas, o mesmo estado, o caminho em lote tem de devolver EXATAMENTE o que o caminho
 * individual (fonte das telas e das políticas) devolve — inclusive o gate de identidade.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReputationBatchEquivalenceTests {

	@Autowired
	private org.springframework.test.web.servlet.MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private ReputationService reputation;

	@Autowired
	private ManageProfileExtensionUseCase extensions;


	private TestAuthHelper auth;

	@Test
	void batchReputationEqualsIndividualReputationForTheSameAccounts() throws Exception {
		auth = new TestAuthHelper(mockMvc, objectMapper);

		// Uma conta com trajetória (componente T), outra sem nada, ambas verificadas.
		var comTrajetoria = auth.authenticate("user_batcheq_traj");
		var inicio = java.time.LocalDate.of(2018, 3, 1);
		for (int i = 0; i < 4; i++) {
			extensions.addExperience(comTrajetoria.userId(), "Dev " + i, "Empresa " + i, null, null,
				inicio.plusYears(i), null);
		}
		extensions.addEducation(comTrajetoria.userId(), "Universidade", "Bacharelado", "Computação", null,
			inicio, null);
		extensions.addCertification(comTrajetoria.userId(), "Certificação", "Emissor", null, inicio, null, null);

		var semNada = auth.authenticate("user_batcheq_vazio");

		// E uma NÃO verificada: o gate zera a nota nos dois caminhos.
		var naoVerificada = auth.authenticate("user_batcheq_noverif");
		TestDatabase.clearEmailVerification(naoVerificada.userId());

		var ids = List.of(comTrajetoria.userId(), semNada.userId(), naoVerificada.userId());

		Map<String, Integer> emLote = reputation.reputationsOf(ids);
		assertThat(emLote.keySet()).containsExactlyInAnyOrderElementsOf(ids);

		for (String id : ids) {
			Integer individual = reputation.reputationOf(id);
			assertThat(emLote.get(id))
				.as("a nota em lote tem de ser igual à individual para %s", id)
				.isEqualTo(individual);
		}

		// A nota verificada com trajetória tem de ser maior que zero (o teste precisa exercitar
		// a fórmula, não o chão zero) e a não verificada tem de ficar em zero nos DOIS caminhos.
		assertThat(emLote.get(comTrajetoria.userId())).isPositive();
		assertThat(emLote.get(naoVerificada.userId())).isZero();
	}
}
