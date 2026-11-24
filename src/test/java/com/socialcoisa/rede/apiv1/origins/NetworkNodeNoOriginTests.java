package com.socialcoisa.rede.apiv1.origins;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.junit.jupiter.api.Tag;
import org.springframework.test.web.servlet.MockMvc;

/**
 * O nó da rede não é navegador: ele fala com {@code /network/**} por curl, de dentro de um
 * container, e nunca manda {@code Origin}.
 *
 * <p>Antes desta exceção o gate de origem recusava o enroll com {@code 403 ORIGIN_REQUIRED} e o
 * bootstrap traduzia a recusa para "token inválido, expirado ou já usado?" — um participante com
 * chave boa recebia a mensagem errada e nenhum nó conseguia entrar. O gate continua valendo para o
 * resto: estas rotas autenticam por conta própria (token de provisionamento de uso único no
 * enroll; {@code X-Node-Id} + {@code X-Node-Key} no resto), como os webhooks e o gov.br.</p>
 */
@Tag("critico")
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
	"rede.origins.enforce=true",
	"rede.cors.allowed-origins=https://enois.ia.br"
})
class NetworkNodeNoOriginTests {

	private static final String ENROLL = """
			{"provisionToken":"npt_nao_existe","declared":{"cpuCores":1,"memoryMb":1,"diskMb":1,"bandwidthMbps":1}}
			""";

	@Autowired
	private MockMvc mockMvc;

	@Test
	void enrollSemOrigemChegaNoControllerECusaOToken() throws Exception {
		mockMvc.perform(post("/network/nodes/enroll").contentType(MediaType.APPLICATION_JSON).content(ENROLL))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("NODE_TOKEN_INVALID"));
	}

	@Test
	void heartbeatSemOrigemChegaNoControllerEExigeACredencialDoNo() throws Exception {
		mockMvc.perform(post("/network/nodes/heartbeat").contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isUnauthorized());
	}

	/** A configuração assinada é lida a cada ciclo pelo runtime — também por curl, sem Origin. */
	@Test
	void configSemOrigemChegaNoControllerEExigeACredencialDoNo() throws Exception {
		mockMvc.perform(get("/network/nodes/me/config"))
			.andExpect(status().isUnauthorized());
	}

	/**
	 * A exceção vale só para {@code /network/**}: uma rota de dados comum sem origem continua
	 * recusada. Sem esta asserção, liberar o filtro inteiro passaria nos testes acima.
	 */
	@Test
	void rotaComumSemOrigemContinuaRecusada() throws Exception {
		mockMvc.perform(get("/feed"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("ORIGIN_REQUIRED"));
	}
}