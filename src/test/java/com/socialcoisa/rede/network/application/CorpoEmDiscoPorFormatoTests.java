package com.socialcoisa.rede.network.application;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * O que vale para <b>todos</b> os formatos de corpo em disco (envelope da raiz, {@code ENOISENC1} da
 * réplica local, corpo legado em claro).
 *
 * <p>Cada formato tem o seu teste de formato; este arquivo testa a <b>regra comum</b>, que não pertence
 * a nenhum deles: o tamanho que o catálogo promete é sempre o do <b>texto claro</b>, e o número medido
 * no disco tem de devolver exatamente isso. Um desconto de cabeçalho errado passa despercebido no
 * round-trip de cada formato — porque cada round-trip lê o seu próprio arquivo — e só aparece quando
 * alguém compara dois formatos lado a lado, que é o que o catálogo faz ao escolher a fonte.</p>
 */
class CorpoEmDiscoPorFormatoTests {

	@TempDir
	Path dir;

	private static final byte[] CHAVE = chave();

	/**
	 * Os três formatos guardam o mesmo objeto (1000 bytes) e todos têm de reportar 1000 em claro.
	 *
	 * <p>Mutuação discriminante: descontar o cabeçalho sem conferir o formato dá número negativo no
	 * corpo legado; não descontar nada dá +30 no {@code ENOISENC1} e +99 no envelope.</p>
	 */
	@Test
	void os_tres_formatos_reportam_o_mesmo_tamanho_em_claro() throws Exception {
		byte[] conteudo = new byte[1000];
		new Random(7).nextBytes(conteudo);

		// 1. réplica local cifrada (ENOISENC1)
		NodeBodyStore localCifrado = new NodeBodyStore(dir.resolve("no-cifrado").toString(), CHAVE);
		localCifrado.write("sob_1", new java.io.ByteArrayInputStream(conteudo));

		// 2. réplica local em claro (legado: instalação sem chave)
		NodeBodyStore localClaro = new NodeBodyStore(dir.resolve("no-claro").toString());
		localClaro.write("sob_1", new java.io.ByteArrayInputStream(conteudo));

		// 3. envelope da raiz do Control Plane
		Path origem = Files.createTempFile(dir, "origem-", ".bin");
		Files.write(origem, conteudo);
		Path envelope = dir.resolve("sob_1.env");
		new EnvelopeBodyCrypto(CHAVE).encryptTo(origem, envelope, dir);

		assertThat(localCifrado.plainBytesOf("sob_1")).as("ENOISENC1").isEqualTo(1000);
		assertThat(localClaro.plainBytesOf("sob_1")).as("legado em claro").isEqualTo(1000);
		assertThat(Files.size(envelope) - EnvelopeBodyCrypto.headerBytes() - 16)
			.as("envelope (soma do caminho openVerified)").isEqualTo(1000);

		// Os arquivos no disco NÃO têm 1000 bytes: se tivessem, o desconto não estaria sendo testado.
		assertThat(Files.size(localCifrado.pathOf("sob_1"))).isEqualTo(1000 + EncryptedBodyFile.headerBytes() + 16);
		assertThat(Files.size(localClaro.pathOf("sob_1"))).isEqualTo(1000);
		assertThat(Files.size(envelope)).isEqualTo(1000 + EnvelopeBodyCrypto.headerBytes() + 16);
	}

	/**
	 * Um arquivo adulterado tem o tamanho certo e o corpo errado: o tamanho em claro não pode “corrigir”
	 * isso, e a leitura tem de falhar.
	 */
	@Test
	void tamanho_certo_com_conteudo_adulterado_ainda_falha_na_leitura() throws Exception {
		byte[] conteudo = "objeto de tamanho fixo".getBytes(StandardCharsets.UTF_8);
		NodeBodyStore local = new NodeBodyStore(dir.resolve("no").toString(), CHAVE);
		local.write("sob_1", new java.io.ByteArrayInputStream(conteudo));
		Path arquivo = local.pathOf("sob_1");
		byte[] bytes = Files.readAllBytes(arquivo);
		bytes[bytes.length - 2] ^= 0x01;
		Files.write(arquivo, bytes);

		assertThat(local.plainBytesOf("sob_1")).as("o tamanho continua o mesmo").isEqualTo(conteudo.length);
		assertThatThrownBy(() -> {
			try (var fluxo = local.read("sob_1")) {
				fluxo.readAllBytes();
			}
		}).isInstanceOf(java.io.IOException.class);
	}

	private static byte[] chave() {
		byte[] bytes = new byte[32];
		new Random(13).nextBytes(bytes);
		return bytes;
	}
}