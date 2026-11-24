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
import org.springframework.test.web.servlet.MockMvc;

import com.socialcoisa.rede.doacoes.application.port.out.DoacaoRepository;
import com.socialcoisa.rede.doacoes.domain.DoacaoStatus;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

import org.junit.jupiter.api.Tag;
import tools.jackson.databind.ObjectMapper;

/**
 * O caminho inteiro da doação: pedir o código, confirmar com o comprovante, receber o selo — e o
 * que NÃO pode acontecer (o mesmo comprovante em duas contas, comprovante sem identificador, o
 * valor errado).
 */
@Tag("critico")
@SpringBootTest
@AutoConfigureMockMvc
class DoacaoFlowTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private DoacaoRepository doacoes;

	@Autowired
	private org.springframework.jdbc.core.JdbcTemplate jdbc;

	@Test
	void doadorPedeOCodigoEConfirmaComOComprovante() throws Exception {
		var doador = auth("doador_" + System.nanoTime());

		var codigo = mockMvc.perform(post("/doacoes/pix")
				.header("X-Session-Token", doador.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("amountCents", 2500))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.txid").isNotEmpty())
			.andExpect(jsonPath("$.amountCents").value(2500))
			.andReturn();

		String copiaECola = objectMapper.readTree(codigo.getResponse().getContentAsString())
			.get("pixCopyAndPaste").asText();
		String txid = objectMapper.readTree(codigo.getResponse().getContentAsString()).get("txid").asText();
		String doacaoId = objectMapper.readTree(codigo.getResponse().getContentAsString()).get("id").asText();

		// O copia e cola é o BR Code com o txid da pessoa e a chave de CNPJ.
		assertThat(copiaECola).contains(txid);
		assertThat(copiaECola).contains("51839682000161");
		assertThat(copiaECola).startsWith("000201");

		// O comprovante sem identificador não confirma.
		mockMvc.perform(post("/doacoes/{id}/comprovante", doacaoId)
				.header("X-Session-Token", doador.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("comprovante", "R$ 25,00"))))
			.andExpect(status().isUnprocessableEntity());

		// Com o identificador fim a fim, confirma.
		mockMvc.perform(post("/doacoes/{id}/comprovante", doacaoId)
				.header("X-Session-Token", doador.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("comprovante",
					"13/11/2026 Pix recebido R$ 25,00 id: EA1B2C3D4E5F6A7B8C9D0E1F2A3B4C5D"))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.situacao").value("CONFIRMADA"));

		// E o selo aparece para a própria conta.
		mockMvc.perform(get("/doacoes/minhas").header("X-Session-Token", doador.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.selo.apoiador").value(true));
	}

	@Test
	void oComprovanteEhLidoEDescartadoNaoGuardado() throws Exception {
		var doador = auth("descarte_" + System.nanoTime());
		String id = abreCodigo(doador.token(), 3000);
		String e2e = "E" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 31).toUpperCase();
		// Marca de texto que só existe no comprovante colado.
		String marca = "comprovante-descartado-" + System.nanoTime();

		mockMvc.perform(post("/doacoes/{id}/comprovante", id)
				.header("X-Session-Token", doador.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("comprovante",
					"13/11/2026 Pix recebido R$ 30,00 id:" + e2e + " " + marca))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.situacao").value("CONFIRMADA"));

		// O que fica é o resultado: a linha da doação e o identificador usado. O texto, não.
		Integer linhasComTexto = jdbc.queryForObject(
			"select count(*) from doacoes where id = ? and cast(user_id as char) like ? ", Integer.class,
			id, "%" + marca + "%");
		assertThat(linhasComTexto).isZero();
		Integer ids = jdbc.queryForObject("select count(*) from doacoes_pix_ids where doacao_id = ? and pix_id = ?",
			Integer.class, id, e2e);
		assertThat(ids).isEqualTo(1);
		Integer naColunaDeIdentificador = jdbc.queryForObject(
			"select count(*) from doacoes where id = ? and cast(status as char) like ?", Integer.class, id,
			"%" + marca + "%");
		assertThat(naColunaDeIdentificador).isZero();
	}

	@Test
	void oMesmoComprovanteNaoServeParaDuasContas() throws Exception {
		var um = auth("e2e_um_" + System.nanoTime());
		var dois = auth("e2e_dois_" + System.nanoTime());
		// 32 caracteres (o formato do E2E), únicos por execução: o mesmo comprovante para as duas.
		// 32 caracteres (o formato do E2E), iguais para os dois comprovantes desta execução.
		String e2e = "E" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 31).toUpperCase();

		String idUm = abreCodigo(um.token(), 1500);
		String idDois = abreCodigo(dois.token(), 1500);

		mockMvc.perform(post("/doacoes/{id}/comprovante", idUm)
				.header("X-Session-Token", um.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("comprovante",
					"13/11/2026 Pix recebido R$ 15,00 id:" + e2e))))
			.andExpect(status().isOk());

		mockMvc.perform(post("/doacoes/{id}/comprovante", idDois)
				.header("X-Session-Token", dois.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("comprovante",
					"13/11/2026 Pix recebido R$ 15,00 id:" + e2e))))
			.andExpect(status().isConflict());
	}

	@Test
	void comprovanteComValorErradoNaoConfirma() throws Exception {
		var doador = auth("valor_errado_" + System.nanoTime());
		String id = abreCodigo(doador.token(), 5000);

		mockMvc.perform(post("/doacoes/{id}/comprovante", id)
				.header("X-Session-Token", doador.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("comprovante",
					"13/11/2026 Pix recebido R$ 10,00 id: EA1B2C3D4E5F6A7B8C9D0E1F2A3B4C5D"))))
			.andExpect(status().isUnprocessableEntity());
	}

	@Test
	void umaDoacaoDeOutraContaNaoApareceNemPodeSerConfirmada() throws Exception {
		var um = auth("alheio_um_" + System.nanoTime());
		var dois = auth("alheio_dois_" + System.nanoTime());
		String id = abreCodigo(um.token(), 2000);

		mockMvc.perform(post("/doacoes/{id}/comprovante", id)
				.header("X-Session-Token", dois.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("comprovante",
					"13/11/2026 Pix recebido R$ 20,00 id: EA1B2C3D4E5F6A7B8C9D0E1F2A3B4C5D"))))
			.andExpect(status().isNotFound());
	}

	@Test
	void pedirUmNovoCodigoApagaOAnterior() throws Exception {
		var doador = auth("novo_codigo_" + System.nanoTime());

		String primeiro = abreCodigo(doador.token(), 1000);
		String segundo = abreCodigo(doador.token(), 2000);

		assertThat(doacoes.findById(primeiro).orElseThrow().status()).isEqualTo(DoacaoStatus.RECUSADA);
		assertThat(doacoes.findById(segundo).orElseThrow().status()).isEqualTo(DoacaoStatus.ABERTA);
	}

	private String abreCodigo(String token, int centavos) throws Exception {
		var result = mockMvc.perform(post("/doacoes/pix")
				.header("X-Session-Token", token)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("amountCents", centavos))))
			.andExpect(status().isOk())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}

	private TestAuthHelper.AuthSession auth(String name) throws Exception {
		return new TestAuthHelper(mockMvc, objectMapper).authenticate(name);
	}
}
