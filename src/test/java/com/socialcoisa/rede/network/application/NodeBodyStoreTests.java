package com.socialcoisa.rede.network.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * O corpo que o nó guarda no próprio disco.
 *
 * <p>O que estes testes seguram, e por quê:</p>
 * <ul>
 * <li><b>escrita atômica</b>: um corpo pela metade com o nome final seria serviço de um objeto
 * corrompido com o catálogo dizendo que está tudo bem. O arquivo só aparece completo;</li>
 * <li><b>id inválido não sai da raiz</b>: `..` num id atravessaria o diretório e gravaria fora;</li>
 * <li><b>uso medido é o dos corpos</b>, não do diretório inteiro — temporários e lixo não podem
 * inflar o número que o nó reporta;</li>
 * <li><b>remover é só do corpo</b>: o diretório fica.</li>
 * </ul>
 */
class NodeBodyStoreTests {

	@TempDir
	Path root;

	private NodeBodyStore store;

	@BeforeEach
	void setUp() {
		store = new NodeBodyStore(root.toString());
	}

	private static InputStream body(String text) {
		return new ByteArrayInputStream(text.getBytes());
	}

	@Test
	void grava_le_e_mede_o_corpo() throws Exception {
		long gravados = store.write("sob_a", body("conteudo"));

		assertThat(gravados).isEqualTo(8);
		assertThat(store.has("sob_a")).isTrue();
		assertThat(new String(store.read("sob_a").readAllBytes())).isEqualTo("conteudo");
		assertThat(store.usedBytes()).isEqualTo(8);
	}

	@Test
	void corpo_novo_substitui_o_antigo_sem_deixar_resto() throws Exception {
		store.write("sob_a", body("versao-antiga-longa"));

		store.write("sob_a", body("nova"));

		assertThat(new String(store.read("sob_a").readAllBytes())).isEqualTo("nova");
		// Nenhum `.partial` sobra: acumularia disco de quem contribui a cada nova cópia.
		try (var arquivos = Files.list(root)) {
			assertThat(arquivos.map(p -> p.getFileName().toString()).filter(nome -> nome.endsWith(".partial")))
				.isEmpty();
		}
	}

	@Test
	void corpo_pela_metade_nao_aparece_com_o_nome_final() {
		// Fluxo que estoura no meio: o temporário é apagado e o objeto continua ausente. Um objeto
		// com o catálogo dizendo "cópia OK" e bytes truncados é pior do que cópia ausente.
		InputStream falhaNoMeio = new InputStream() {
			private int lidos = 0;

			@Override
			public int read() throws IOException {
				if (lidos++ < 3) {
					return 'x';
				}
				throw new IOException("rede caiu no meio do corpo");
			}
		};

		assertThatThrownBy(() -> store.write("sob_b", falhaNoMeio)).isInstanceOf(IOException.class);
		assertThat(store.has("sob_b")).isFalse();
		assertThat(store.usedBytes()).isZero();
	}

	@Test
	void id_com_barra_ou_ponto_ponto_nao_escapa_da_raiz() {
		assertThatThrownBy(() -> store.pathOf("../fora")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> store.pathOf("a/b")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> store.pathOf("")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> store.pathOf(null)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void uso_ignora_arquivos_que_nao_sao_corpo() throws Exception {
		store.write("sob_a", body("1234"));
		// Lixo de uma execução interrompida: não é corpo servível e não entra no medido.
		Files.writeString(root.resolve("lixo.txt"), "xxxxxxxxxx");

		assertThat(store.usedBytes()).isEqualTo(4);
	}

	@Test
	void remover_apaga_so_o_corpo_e_e_idempotente() throws Exception {
		store.write("sob_a", body("conteudo"));

		assertThat(store.remove("sob_a")).isTrue();
		assertThat(store.has("sob_a")).isFalse();
		// Remover de novo não é erro: o catálogo pode remover a mesma colocação duas vezes.
		assertThat(store.remove("sob_a")).isFalse();
	}

	@Test
	void sem_corpo_nao_le_e_nao_mente_sobre_o_uso() {
		assertThat(store.has("sob_a")).isFalse();
		assertThat(store.usedBytes()).isZero();
	}
}