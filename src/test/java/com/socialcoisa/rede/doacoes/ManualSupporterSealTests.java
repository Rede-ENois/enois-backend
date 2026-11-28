package com.socialcoisa.rede.doacoes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.socialcoisa.rede.doacoes.application.port.out.DoacaoRepository;
import com.socialcoisa.rede.doacoes.domain.DoacaoStatus;
import com.socialcoisa.rede.shared.web.TestAuthHelper;
import com.socialcoisa.rede.shared.web.TestDatabase;

import tools.jackson.databind.ObjectMapper;

/**
 * O selo de apoiador registrado à mão por um administrador — para quando o dinheiro chegou e o
 * sistema não reconheceu o comprovante.
 *
 * <p>O que estes testes fixam é o que impede o botão de virar um "selo de graça": exige
 * administrador, exige motivo, só registra um por conta, recusa quem já é apoiador por doação de
 * verdade, e a linha registrada <b>não é dinheiro</b> — valor zero, origem {@code MANUAL}, fora de
 * qualquer total de recebido.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class ManualSupporterSealTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private DoacaoRepository doacoes;

	@Autowired
	private JdbcTemplate jdbc;

	@Test
	void adminRegistraOSeloEPerfilPublicoPassaADizerApoiador() throws Exception {
		String nome = "selo_manual_" + System.nanoTime();
		var pessoa = auth(nome);
		var admin = admin("admin_sele_" + System.nanoTime());

		// Antes: ninguém.
		mockMvc.perform(get("/users/{username}/apoiador", usernameOf(nome)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.apoiador").value(false));

		mockMvc.perform(post("/admin/doacoes/sele/{u}", usernameOf(nome))
				.header("X-Session-Token", admin.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(
					Map.of("reason", "Pix de 25/11 chegou na conta e o comprovante não foi reconhecido"))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.doacao.origem").value("MANUAL"))
			.andExpect(jsonPath("$.doacao.status").value("CONFIRMADA"))
			.andExpect(jsonPath("$.doacao.amountCents").value(0));

		mockMvc.perform(get("/users/{username}/apoiador", usernameOf(nome)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.apoiador").value(true));
		mockMvc.perform(get("/doacoes/minhas").header("X-Session-Token", pessoa.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.selo.apoiador").value(true))
			// O selo aparece em `selo`; o EXTRATO não ganha uma doação de R$ 0,00 que a pessoa não fez.
			.andExpect(jsonPath("$.doacoes").isEmpty());

		// A linha não é dinheiro: valor zero, origem manual, quem registrou fica gravado.
		var linha = doacoes.byUser(pessoa.userId()).get(0);
		assertThat(linha.amountCents()).as("selo manual não inventa valor").isZero();
		assertThat(linha.origem()).isEqualTo(DoacaoStatus.Origem.MANUAL);
		assertThat(linha.decidedBy()).isEqualTo(admin.userId());
	}

	@Test
	void naoRegistraODozeASegundoSelo() throws Exception {
		String nome = "selo_dois_" + System.nanoTime();
		var pessoa = auth(nome);
		var admin = admin("admin_dois_" + System.nanoTime());

		registra(admin.token(), usernameOf(nome)).andExpect(status().isOk());
		registra(admin.token(), usernameOf(nome))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("SUPPORTER_SEAL_ALREADY_GRANTED"));

		long manuais = doacoes.byUser(pessoa.userId()).stream()
			.filter(d -> d.origem() == DoacaoStatus.Origem.MANUAL).count();
		assertThat(manuais).as("um selo por conta").isEqualTo(1);
	}

	@Test
	void quemJaEhApoiadorPorDoacaoConfirmadaNaoRecebeSeloManual() throws Exception {
		String nome = "selo_real_" + System.nanoTime();
		var doador = auth(nome);
		var admin = admin("admin_real_" + System.nanoTime());

		var codigo = mockMvc.perform(post("/doacoes/pix")
				.header("X-Session-Token", doador.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("amountCents", 2500))))
			.andExpect(status().isOk())
			.andReturn();
		String id = objectMapper.readTree(codigo.getResponse().getContentAsString()).get("id").asText();
		mockMvc.perform(post("/doacoes/{id}/comprovante", id)
				.header("X-Session-Token", doador.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("comprovante",
					"13/11/2026 Pix recebido R$ 25,00 id: EF1B2C3D4E5F6A7B8C9D0E1F2A3B4C5D"))))
			.andExpect(status().isOk());

		registra(admin.token(), usernameOf(nome))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("SUPPORTER_ALREADY_CONFIRMED"));
	}

	@Test
	void semMotivoNaoRegistra() throws Exception {
		String nome = "selo_sem_motivo_" + System.nanoTime();
		var pessoa = auth(nome);
		var admin = admin("admin_sem_motivo_" + System.nanoTime());

		registra(admin.token(), usernameOf(nome), "curto")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("REASON_REQUIRED"));
		registra(admin.token(), usernameOf(nome), "")
			.andExpect(status().isBadRequest());

		assertThat(doacoes.byUser(pessoa.userId())).as("nada gravado").isEmpty();
	}

	@Test
	void quemNaoEhAdminNaoRegistraSelo() throws Exception {
		String nome = "selo_naoadmin_" + System.nanoTime();
		var pessoa = auth(nome);
		var outra = auth("selo_naoadmin_outra_" + System.nanoTime());

		registra(outra.token(), usernameOf(nome)).andExpect(status().isForbidden());
		mockMvc.perform(post("/admin/doacoes/sele/{u}", usernameOf(nome)).header("X-Session-Token", "chave-invalida")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("reason", "motivo qualquer com dez ou mais"))))
			.andExpect(status().isUnauthorized());

		assertThat(doacoes.byUser(pessoa.userId())).isEmpty();
	}

	@Test
	void usuarioInexistenteEh404EOSeloNaoSobraEmNinguem() throws Exception {
		var admin = admin("admin_fantasma_" + System.nanoTime());

		registra(admin.token(), "ninguem_com_esse_nome_" + System.nanoTime())
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
	}

	// ---- apoio ----

	/** O @ da conta: o cadastro do harness deriva o username do nome, com hífen no lugar do "_". */
	private static String usernameOf(String nome) {
		return nome.replace('_', '-');
	}


	private org.springframework.test.web.servlet.ResultActions registra(String adminToken, String username)
		throws Exception {
		return registra(adminToken, username, "Pix recebido na conta e o comprovante não foi reconhecido");
	}

	private org.springframework.test.web.servlet.ResultActions registra(String adminToken, String username,
			String reason) throws Exception {
		return mockMvc.perform(post("/admin/doacoes/sele/{u}", username)
			.header("X-Session-Token", adminToken)
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(Map.of("reason", reason))));
	}

	private TestAuthHelper.AuthSession auth(String name) throws Exception {
		return new TestAuthHelper(mockMvc, objectMapper).authenticate(name);
	}

	private TestAuthHelper.AuthSession admin(String name) throws Exception {
		var session = auth(name);
		TestDatabase.setAdmin(session.userId());
		return session;
	}

	/**
	 * O resumo público conta DINHEIRO confirmado. A linha de selo manual é `CONFIRMADA` com valor
	 * zero, e antes desta correção entrava no `confirmed` de `GET /doacoes/resumo`.
	 */
	@Test
	void oResumoPublicoNaoContaOSeloManualComoDoacaoConfirmada() throws Exception {
		String nome = "selo_resumo_" + System.nanoTime();
		var pessoa = auth(nome);
		var admin = admin("admin_resumo_" + System.nanoTime());

		long antes = doacoes.countByStatus(DoacaoStatus.CONFIRMADA)
			- doacoes.countByStatusAndOrigem(DoacaoStatus.CONFIRMADA, DoacaoStatus.Origem.MANUAL);
		registra(admin.token(), usernameOf(nome)).andExpect(status().isOk());

		// A linha manual existe e está CONFIRMADA...
		assertThat(doacoes.byUser(pessoa.userId()).stream()
			.filter(d -> d.origem() == DoacaoStatus.Origem.MANUAL && d.status() == DoacaoStatus.CONFIRMADA)
			.count()).isEqualTo(1);
		// ...e não mexe no número de doações de dinheiro, nem no resumo público.
		long depois = doacoes.countByStatus(DoacaoStatus.CONFIRMADA)
			- doacoes.countByStatusAndOrigem(DoacaoStatus.CONFIRMADA, DoacaoStatus.Origem.MANUAL);
		assertThat(depois).as("selo manual não é doação de dinheiro").isEqualTo(antes);
		// E o resumo público devolve esse número, não o que inclui a linha manual (antes + 1).
		mockMvc.perform(get("/doacoes/resumo"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.confirmed").value(antes));
	}
}