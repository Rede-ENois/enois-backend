package com.socialcoisa.rede.network.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Cifra em repouso dos corpos de nó (brief §10).
 *
 * <p>O requisito do brief é literal: "a retirada física do disco" não pode revelar o conteúdo. O
 * teste que prova isso não é "a leitura funciona" — é <b>ler o arquivo do disco com
 * {@code Files.readAllBytes} e não achar o conteúdo em lugar nenhum</b>. O round-trip sozinho passaria
 * com o arquivo em claro.</p>
 */
class NodeBodyEncryptionTests {

	private static final byte[] KEY = chave(7);

	@TempDir
	Path root;

	@Test
	void corpo_no_disco_nao_contem_o_conteudo_em_claro() throws Exception {
		var store = new NodeBodyStore(root.toString(), KEY);
		String segredo = "conteudo confidencial do participante";
		store.write("sob_1", new ByteArrayInputStream(segredo.getBytes(StandardCharsets.UTF_8)));

		byte[] noDisco = Files.readAllBytes(store.pathOf("sob_1"));
		// O requisito do §10, conferido diretamente: os bytes do texto não estão no arquivo.
		assertThat(new String(noDisco, StandardCharsets.ISO_8859_1)).doesNotContain(segredo);
		assertThat(noDisco).startsWith("ENOISENC1".getBytes(StandardCharsets.US_ASCII));
		assertThat(store.encryptionAtRest()).isTrue();

		try (InputStream in = store.read("sob_1")) {
			assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo(segredo);
		}
	}

	/** Bytes binários (vídeo): o fluxo não pode depender de UTF-8 nem carregar tudo em memória. */
	@Test
	void corpo_binario_grande_faz_round_trip_exato() throws Exception {
		var store = new NodeBodyStore(root.toString(), KEY);
		byte[] corpo = new byte[3 * 1024 * 1024];
		new Random(42).nextBytes(corpo);

		long gravados = store.write("sob_bin", new ByteArrayInputStream(corpo));
		assertThat(gravados).isEqualTo(corpo.length);
		try (InputStream in = store.read("sob_bin")) {
			assertThat(in.readAllBytes()).isEqualTo(corpo);
		}
	}

	/**
	 * Disco adulterado é recusado, não servido.
	 *
	 * <p>Esta é a diferença entre cifra e máscara: o GCM autentica. Quem tem acesso físico ao disco
	 * pode trocar bytes — e a leitura tem de falhar, não entregar conteúdo forjado.</p>
	 */
	@Test
	void corpo_adulterado_no_disco_falha_ao_ler() throws Exception {
		var store = new NodeBodyStore(root.toString(), KEY);
		store.write("sob_2", new ByteArrayInputStream("integridade importa".getBytes(StandardCharsets.UTF_8)));

		byte[] arquivo = Files.readAllBytes(store.pathOf("sob_2"));
		arquivo[arquivo.length - 1] ^= 0x01;
		Files.write(store.pathOf("sob_2"), arquivo);

		assertThatThrownBy(() -> {
			try (InputStream in = store.read("sob_2")) {
				in.readAllBytes();
			}
		}).isInstanceOf(IOException.class);
	}

	/**
	 * Chave diferente não decifra: o corpo de uma instalação não vaza em outra.
	 *
	 * <p>Também é o que impede "trocar a chave e continuar lendo o disco": quem troca a chave perde os
	 * corpos, e o erro é explícito em vez de servir lixo.</p>
	 */
	@Test
	void chave_diferente_nao_decifra() throws Exception {
		var escritor = new NodeBodyStore(root.toString(), KEY);
		escritor.write("sob_3", new ByteArrayInputStream("so existe com a chave certa".getBytes(StandardCharsets.UTF_8)));

		var outro = new NodeBodyStore(root.toString(), chave(9));
		assertThatThrownBy(() -> {
			try (InputStream in = outro.read("sob_3")) {
				in.readAllBytes();
			}
		}).isInstanceOf(IOException.class);
	}

	/**
	 * Corpo legado, gravado antes da chave, continua sendo lido.
	 *
	 * <p>É o que permite ligar a cifra numa instalação que já tem réplicas sem reprovar todo nó de uma
	 * vez — o arquivo antigo não tem o cabeçalho e é servido como está.</p>
	 */
	@Test
	void corpo_em_claro_anterior_a_chave_continua_legivel() throws Exception {
		var semCifra = new NodeBodyStore(root.toString());
		semCifra.write("sob_antigo", new ByteArrayInputStream("gravado antes".getBytes(StandardCharsets.UTF_8)));
		assertThat(semCifra.encryptionAtRest()).isFalse();

		var comCifra = new NodeBodyStore(root.toString(), KEY);
		try (InputStream in = comCifra.read("sob_antigo")) {
			assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("gravado antes");
		}
	}

	/** Sem chave a instalação grava em claro e diz isso — não finge cifrar. */
	@Test
	void sem_chave_grava_em_claro_e_declara_isso() throws Exception {
		var store = new NodeBodyStore(root.toString());
		store.write("sob_4", new ByteArrayInputStream("em claro".getBytes(StandardCharsets.UTF_8)));

		assertThat(store.encryptionAtRest()).isFalse();
		assertThat(new String(Files.readAllBytes(store.pathOf("sob_4")), StandardCharsets.UTF_8))
			.isEqualTo("em claro");
	}

	private static byte[] chave(int seed) {
		byte[] bytes = new byte[32];
		new Random(seed).nextBytes(bytes);
		return bytes;
	}
}