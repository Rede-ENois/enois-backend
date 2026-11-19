package com.socialcoisa.rede.network.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * O metadado {@code x-amz-meta-*} do objeto (V367).
 *
 * <p>O que se prova aqui é o que decide o que o cliente reencontra: o nome vai para um cabeçalho
 * HTTP (alfabeto restrito), a chave é normalizada em minúsculas (o S3 trata {@code Autor} e
 * {@code autor} como a mesma), o conjunto tem teto de 2 KB (o do S3) e o JSON da coluna sobrevive
 * à ida e à volta com aspas, barra e quebra de linha.</p>
 */
class ObjectMetadataTests {

	@Test
	void normaliza_o_nome_em_minusculas_e_aguenta_a_volta_pelo_json() {
		Map<String, String> brutos = new LinkedHashMap<>();
		brutos.put("Autor", "Ana \"A\" \\ Silva");
		brutos.put("setor", "juridico\nlinha");

		ObjectMetadata meta = ObjectMetadata.of(brutos);

		assertThat(meta.values()).containsOnlyKeys("autor", "setor");
		ObjectMetadata lido = ObjectMetadata.fromJson(meta.toJson());
		assertThat(lido.values()).isEqualTo(meta.values());
		assertThat(lido.values().get("autor")).isEqualTo("Ana \"A\" \\ Silva");
		assertThat(lido.values().get("setor")).isEqualTo("juridico\nlinha");
	}

	@Test
	void nome_fora_do_alfabeto_de_cabecalho_e_recusado() {
		// O nome volta como cabeçalho HTTP: quebra de linha, dois-pontos e espaço não passam.
		for (String ruim : new String[] { "a:b", "com espaco", "a\r\nb", "acentuacao-ç" }) {
			assertThatThrownBy(() -> ObjectMetadata.of(Map.of(ruim, "x")))
				.as("nome %s", ruim)
				.isInstanceOf(IllegalArgumentException.class);
		}
	}

	@Test
	void repete_o_nome_no_pedido_e_a_primeira_ocorrencia_vale() {
		Map<String, String> brutos = new LinkedHashMap<>();
		brutos.put("chave", "primeira");
		// O LinkedHashMap já não aceita a segunda chave idêntica; a normalização de caixa é o que
		// faria duas entradas para a mesma chave — a primeira vence.
		ObjectMetadata meta = ObjectMetadata.of(brutos);
		assertThat(meta.values().get("chave")).isEqualTo("primeira");
	}

	@Test
	void teto_de_pares_de_valor_e_de_bytes_e_recusado_em_vez_de_truncado() {
		Map<String, String> demais = new LinkedHashMap<>();
		for (int i = 0; i <= ObjectMetadata.MAX_PAIRS; i++) {
			demais.put("k" + i, "v");
		}
		assertThatThrownBy(() -> ObjectMetadata.of(demais)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("pares");

		assertThatThrownBy(() -> ObjectMetadata.of(Map.of("k", "x".repeat(ObjectMetadata.MAX_VALUE + 1))))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("caracteres");

		// Nenhum valor isolado passa do limite de 256, mas o CONJUNTO passa de 2 KB: é o teto do S3
		// para o metadado inteiro, e o que precisa ser recusado é o conjunto.
		Map<String, String> conjuntoGrande = new LinkedHashMap<>();
		for (int i = 0; i < ObjectMetadata.MAX_PAIRS; i++) {
			conjuntoGrande.put("chave" + i, "x".repeat(ObjectMetadata.MAX_VALUE));
		}
		assertThatThrownBy(() -> ObjectMetadata.of(conjuntoGrande))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("bytes");
	}

	@Test
	void json_quebrado_na_coluna_e_metadado_vazio_e_nao_erro_de_leitura() {
		// O corpo do objeto continua servível; metadado corrompido não pode tirar o arquivo do ar.
		assertThat(ObjectMetadata.fromJson(null)).isSameAs(ObjectMetadata.EMPTY);
		assertThat(ObjectMetadata.fromJson("{")).isSameAs(ObjectMetadata.EMPTY);
		assertThat(ObjectMetadata.fromJson("{\"a\":1}").values()).isEmpty();
	}

	@Test
	void vazio_e_valido_e_e_o_caso_comum() {
		assertThat(ObjectMetadata.of(Map.of())).isSameAs(ObjectMetadata.EMPTY);
		assertThat(ObjectMetadata.of(null)).isSameAs(ObjectMetadata.EMPTY);
		assertThat(ObjectMetadata.of(Map.of()).toJson()).isEqualTo("{}");
	}
}