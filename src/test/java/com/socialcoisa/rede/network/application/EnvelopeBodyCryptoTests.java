package com.socialcoisa.rede.network.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Criptografia envelope do corpo de objeto (brief §10).
 *
 * <p>O que o envelope promete, e que a cifra em repouso da instalação não prometia: cada objeto tem a
 * <b>sua</b> chave de dados, e o arquivo guarda essa chave <b>embrulhada</b> pela mestra. O teste que
 * prova o benefício não é o round-trip — é <b>pegar a chave de um objeto e não abrir outro</b>, e
 * <b>trocar a mestra e não abrir nenhum</b>.</p>
 */
class EnvelopeBodyCryptoTests {

	private static final byte[] MESTRA = chave(1);
	private static final byte[] OUTRA_MESTRA = chave(2);

	@TempDir
	Path dir;

	@Test
	void round_trip_e_texto_ausente_do_disco() throws Exception {
		var crypto = new EnvelopeBodyCrypto(MESTRA);
		Path origem = arquivo("dentro do envelope: dado do participante");
		Path destino = dir.resolve("corpo.enc");

		crypto.encryptTo(origem, destino, dir);

		assertThat(crypto.isEnvelope(destino)).isTrue();
		assertThat(Files.readAllBytes(destino)).startsWith("ENOISENV1".getBytes(StandardCharsets.US_ASCII));
		assertThat(new String(Files.readAllBytes(destino), StandardCharsets.ISO_8859_1))
			.doesNotContain("dentro do envelope");
		try (InputStream in = crypto.decrypt(destino)) {
			assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8))
				.isEqualTo("dentro do envelope: dado do participante");
		}
	}

	/**
	 * Cada objeto tem a DEK dele: os cabeçalhos de dois objetos do mesmo conteúdo são diferentes.
	 *
	 * <p>É a diferença prática entre envelope e cifra com chave única — com a mesma DEK e o mesmo IV
	 * dois arquivos iguais seriam idênticos, e o disco revelaria que dois objetos têm o mesmo conteúdo
	 * sem precisar decifrar.</p>
	 */
	@Test
	void dois_objetos_do_mesmo_conteudo_tem_chaves_diferentes() throws Exception {
		var crypto = new EnvelopeBodyCrypto(MESTRA);
		Path origem = arquivo("mesmo conteudo");
		Path a = dir.resolve("a.enc");
		Path b = dir.resolve("b.enc");
		crypto.encryptTo(origem, a, dir);
		crypto.encryptTo(origem, b, dir);

		assertThat(Files.readAllBytes(a)).isNotEqualTo(Files.readAllBytes(b));
		// A DEK embrulhada difere: sem isso seria a mesma chave para os dois.
		assertThat(crypto.wrappedDekOf(a)).isNotEqualTo(crypto.wrappedDekOf(b));
	}

	/**
	 * A chave embrulhada de um objeto NÃO abre outro.
	 *
	 * <p>É o teste do "envelope" propriamente: quem tem a DEK do objeto A (destravada com a mestra)
	 * não abre o objeto B. Mutação discriminante: se a DEK fosse da instalação — a cifra em repouso
	 * antiga —, o corpo B se abriria com a mesma chave.</p>
	 */
	@Test
	void chave_embrulhada_de_um_objeto_nao_abre_outro() throws Exception {
		var crypto = new EnvelopeBodyCrypto(MESTRA);
		Path a = dir.resolve("a.enc");
		Path b = dir.resolve("b.enc");
		crypto.encryptTo(arquivo("objeto A"), a, dir);
		crypto.encryptTo(arquivo("objeto B"), b, dir);

		// Troca a DEK embrulhada de B pela de A, mantendo o resto do arquivo.
		byte[] bytesB = Files.readAllBytes(b);
		byte[] dekA = crypto.wrappedDekOf(a);
		int headerEnd = EnvelopeBodyCrypto.headerBytes();
		int dekStart = headerEnd - dekA.length;
		System.arraycopy(dekA, 0, bytesB, dekStart, dekA.length);
		Files.write(b, bytesB);

		assertThatThrownBy(() -> {
			try (InputStream in = crypto.decrypt(b)) {
				in.readAllBytes();
			}
		}).isInstanceOf(IOException.class);
	}

	/** Mestra diferente não abre nada: o disco de um Control Plane não vaza em outro. */
	@Test
	void outra_mestra_nao_decifra() throws Exception {
		Path destino = dir.resolve("corpo.enc");
		new EnvelopeBodyCrypto(MESTRA).encryptTo(arquivo("da instalação A"), destino, dir);

		var outra = new EnvelopeBodyCrypto(OUTRA_MESTRA);
		assertThatThrownBy(() -> {
			try (InputStream in = outra.decrypt(destino)) {
				in.readAllBytes();
			}
		}).isInstanceOf(IOException.class)
			.hasMessageContaining("nao desembrulha");
	}

	/** Corpo binário grande: fluxo, não memória. */
	@Test
	void corpo_binario_grande_faz_round_trip_exato() throws Exception {
		var crypto = new EnvelopeBodyCrypto(MESTRA);
		byte[] corpo = new byte[2 * 1024 * 1024];
		new Random(9).nextBytes(corpo);
		Path origem = dir.resolve("grande.bin");
		Files.write(origem, corpo);
		Path destino = dir.resolve("grande.enc");

		crypto.encryptTo(origem, destino, dir);
		try (InputStream in = crypto.decrypt(destino)) {
			assertThat(in.readAllBytes()).isEqualTo(corpo);
		}
	}

	/** Arquivo adulterado: a tag do GCM não fecha. */
	@Test
	void corpo_adulterado_falha() throws Exception {
		var crypto = new EnvelopeBodyCrypto(MESTRA);
		Path destino = dir.resolve("c.enc");
		crypto.encryptTo(arquivo("integridade"), destino, dir);
		byte[] bytes = Files.readAllBytes(destino);
		bytes[bytes.length - 1] ^= 0x01;
		Files.write(destino, bytes);

		assertThatThrownBy(() -> {
			try (InputStream in = crypto.decrypt(destino)) {
				in.readAllBytes();
			}
		}).isInstanceOf(IOException.class);
	}

	/** Arquivo que não é envelope é recusado — nunca lido como se fosse. */
	@Test
	void formato_desconhecido_e_recusado() throws Exception {
		var crypto = new EnvelopeBodyCrypto(MESTRA);
		Path claro = arquivo("corpo em claro");
		assertThat(crypto.isEnvelope(claro)).isFalse();
		assertThatThrownBy(() -> crypto.decrypt(claro))
			.isInstanceOf(IOException.class)
			.hasMessageContaining("envelope");
	}

	private Path arquivo(String conteudo) throws IOException {
		Path path = Files.createTempFile(dir, "origem-", ".bin");
		Files.writeString(path, conteudo);
		return path;
	}

	private static byte[] chave(int seed) {
		byte[] bytes = new byte[32];
		new Random(seed).nextBytes(bytes);
		return bytes;
	}
}