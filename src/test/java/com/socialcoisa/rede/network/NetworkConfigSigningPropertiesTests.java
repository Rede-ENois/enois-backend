package com.socialcoisa.rede.network;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * O par Ed25519 da config assinada tem de chegar ao Control Plane por variavel de ambiente.
 *
 * <p><b>O defeito que estes testes fixam:</b> {@code NetworkConfigService} le
 * {@code rede.network.config.signing-key} e {@code rede.network.config.signing-public-key}, mas
 * {@code application.properties} nao mapeava nenhuma variavel nesses nomes — diferente de
 * {@code NETWORK_CONFIG_TTL_SECONDS} e {@code NETWORK_CONFIG_ALLOWED_ROUTES}, que ja mapeavam.
 * O efeito em campo: um operador exportava a chave no servico, a resposta continuava
 * {@code signed:false}, e o no (que recusa config nao assinada) autenticava e nunca executava.
 * A causa ficava longe do sintoma, porque nada no deploy falhava.</p>
 *
 * <p>A assercao e sobre o mapeamento, nao sobre o servico: provar que o servico assina com uma
 * chave recebida como argumento ja esta em {@code NetworkConfigServiceTests}. Aqui a pergunta e
 * outra — <b>a configuracao escrita em {@code application.properties} resolve a variavel de
 * ambiente?</b> — e por isso o teste le o arquivo de verdade e aplica a resolucao de propriedades
 * do Spring sobre um ambiente com as variaveis exportadas.</p>
 */
class NetworkConfigSigningPropertiesTests {

	private static Properties applicationProperties() throws Exception {
		// Nao pelo classpath: src/test/resources/application.properties sombreia o do main nos testes
		// (dito no proprio arquivo de teste). O que se audita aqui e o arquivo que o binario publica.
		Path file = Path.of("src/main/resources/application.properties");
		try (InputStream stream = Files.newInputStream(file)) {
			Properties properties = new Properties();
			properties.load(stream);
			return properties;
		}
	}

	/** O ambiente do boot: o arquivo do projeto e as variaveis exportadas, nada do ambiente do build. */
	private static StandardEnvironment environmentWith(String... nameValue) throws Exception {
		StandardEnvironment environment = new StandardEnvironment();
		// O ambiente do build nao pode vazar para o teste: vale so o que o teste exporta.
		environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
		environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
		environment.getPropertySources()
			.addLast(new PropertiesPropertySource("application.properties", applicationProperties()));
		Map<String, Object> exported = new LinkedHashMap<>();
		for (int i = 0; i < nameValue.length; i += 2) {
			exported.put(nameValue[i], nameValue[i + 1]);
		}
		environment.getPropertySources()
			.addFirst(new SystemEnvironmentPropertySource("test-env", exported));
		return environment;
	}

	/** Resolve ${VAR:default} como o boot faz — e a mesma pergunta que o servico faz. */
	private static String resolved(StandardEnvironment environment, String key) {
		String raw = environment.getProperty(key);
		if (raw != null && raw.contains("${")) {
			return environment.resolvePlaceholders(raw);
		}
		return raw;
	}

	@Test
	void chave_privada_exportada_chega_na_propriedade_que_o_servico_assina() throws Exception {
		String privada = Base64.getEncoder().encodeToString(
			KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPrivate().getEncoded());

		String value = resolved(environmentWith("REDE_NETWORK_CONFIG_SIGNING_KEY", privada),
			"rede.network.config.signing-key");

		assertThat(value)
			.as("a chave privada tem de sair do ambiente, nao do default vazio")
			.isEqualTo(privada);
	}

	@Test
	void chave_publica_exportada_chega_na_propriedade_que_o_servico_publica() throws Exception {
		KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
		String publica = Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());

		String value = resolved(environmentWith("REDE_NETWORK_CONFIG_SIGNING_PUBLIC_KEY", publica),
			"rede.network.config.signing-public-key");

		assertThat(value).isEqualTo(publica);
	}

	@Test
	void sem_as_variaveis_a_propriedade_resolve_vazio_e_o_no_recusa_config_nao_assinada() throws Exception {
		assertThat(resolved(environmentWith(), "rede.network.config.signing-key")).isEmpty();
		assertThat(resolved(environmentWith(), "rede.network.config.signing-public-key")).isEmpty();
	}

	@Test
	void a_propriedade_existe_no_arquivo_sem_o_mapeamento_a_variavel_seria_ignorada_em_silencio() throws Exception {
		Properties arquivo = applicationProperties();

		assertThat(arquivo.getProperty("rede.network.config.signing-key"))
			.as("mapeamento ausente: exportar a chave nao chegaria ao servico (o defeito de campo)")
			.isNotNull();
		assertThat(arquivo.getProperty("rede.network.config.signing-public-key")).isNotNull();
	}
}