package com.socialcoisa.rede.network.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.socialcoisa.rede.network.application.testsupport.CatalogoEmMemoria;

/**
 * A coleta dos corpos do Control Plane sem linha no catálogo (brief do Storage, "os dados têm de
 * sobreviver ao próprio Control Plane").
 *
 * <p>O órfão nasce da ordem que a gravação <b>tem</b> de ter: arquivo primeiro, linha depois. O que
 * estes testes fixam é a fronteira entre apagar resíduo e apagar dado de alguém: id de nó nunca sai,
 * arquivo novo (upload de agora) nunca sai, e a leitura não apaga nada.</p>
 */
class StorageOrphanCollectorTests {

	private static final Instant AGORA = Instant.parse("2026-11-26T12:00:00Z");

	@TempDir
	Path raiz;

	private final CatalogoQueSabeDeNos catalogo = new CatalogoQueSabeDeNos();

	private StorageOrphanCollector coletor() {
		return new StorageOrphanCollector(catalogo, raiz.toString(), Clock.fixed(AGORA, ZoneOffset.UTC));
	}

	private Path corpo(String id, Duration idade) throws IOException {
		Path arquivo = raiz.resolve(id + ".bin");
		Files.writeString(arquivo, "bytes");
		Files.setLastModifiedTime(arquivo, FileTime.from(AGORA.minus(idade)));
		return arquivo;
	}

	@Test
	void residuoDeGravacaoInterrompidaSaiEOSinalEAPresencaDaColocacaoNoCatalogo() throws IOException {
		// A colocação existe (é o caso normal: a linha veio antes do arquivo). Nada acontece com ele.
		Path colocado = corpo("so_com_colocacao", Duration.ofDays(3));
		catalogo.comColocacao("so_com_colocacao");
		// Gravação interrompida entre o arquivo e a linha: sem linha, id do Control Plane, velho.
		Path residuo = corpo("so_sem_colocacao", Duration.ofDays(3));

		StorageOrphanCollector.Report relatorio = coletor().collect(false);

		assertThat(Files.exists(colocado)).as("corpo com colocação não é órfão").isTrue();
		assertThat(Files.exists(residuo)).as("resíduo de gravação interrompida sai").isFalse();
		assertThat(relatorio.removed()).containsExactly("so_sem_colocacao");
		assertThat(relatorio.orphansToRemove()).containsExactly("so_sem_colocacao");
	}

	@Test
	void corpoComIdDeNoNaoEAPagadoMercadoOOperadorDecide() throws IOException {
		// Id de nó: o arquivo pode ser a cópia de uma colocação que o banco perdeu. O nó é a fonte
		// da verdade do que guarda — apagar aqui seria decidir no lugar do dono.
		Path deNo = corpo("nd_abc123", Duration.ofDays(3));
		catalogo.comNo("nd_abc123");

		StorageOrphanCollector.Report relatorio = coletor().collect(false);

		assertThat(Files.exists(deNo)).as("id de nó nunca é apagado").isTrue();
		assertThat(relatorio.keptNodeBodies()).containsExactly("nd_abc123");
		assertThat(relatorio.removed()).isEmpty();
	}

	@Test
	void arquivoNovoPodeSerOUploadDeAgoraENaoSai() throws IOException {
		// Entre criar o arquivo e gravar a linha existe uma janela; apagar dentro dela seria matar o
		// upload que o usuário está enviando neste instante.
		Path novo = corpo("so_novo", Duration.ofMinutes(5));

		StorageOrphanCollector.Report relatorio = coletor().collect(false);

		assertThat(Files.exists(novo)).as("arquivo dentro da janela de seguro fica").isTrue();
		assertThat(relatorio.skippedRecent()).containsExactly("so_novo");
		assertThat(relatorio.removed()).isEmpty();
	}

	@Test
	void aLeituraListaENaoApaga() throws IOException {
		Path residuo = corpo("so_velho", Duration.ofDays(3));

		StorageOrphanCollector.Report relatorio = coletor().collect(true);

		assertThat(Files.exists(residuo)).as("dryRun não apaga").isTrue();
		assertThat(relatorio.orphansToRemove()).containsExactly("so_velho");
		assertThat(relatorio.removed()).isEmpty();
		assertThat(relatorio.dryRun()).isTrue();
	}

	@Test
	void temporarioDeEscritaNaoECorpo() throws IOException {
		// O temporário do upload tem id único da JVM e pode conter ponto (`.upload-123.part` e
		// `.upload-8f3a1b.9f2e.part` são os nomes que o `createTempFile` gera). Nenhum dos dois pode
		// entrar como órfão: apagá-los esconderia o upload que está rodando agora.
		Path temp = raiz.resolve(".upload-8f3a1b.9f2e.part");
		Files.writeString(temp, "parcial");
		Files.setLastModifiedTime(temp, FileTime.from(AGORA.minus(Duration.ofDays(3))));

		StorageOrphanCollector.Report relatorio = coletor().collect(false);

		assertThat(Files.exists(temp)).as("temporário de upload fica").isTrue();
		assertThat(relatorio.orphansToRemove()).isEmpty();
	}

	@Test
	void arquivoDeNomeSobrandoNaoVirapedido() throws IOException {
		// A substração do sufixo de um arquivo chamado só `.bin` deixaria um id vazio; apagá-lo seria
		// apagar com base num nome que não é id de nada.
		Path soSufixo = raiz.resolve(".bin");
		Files.writeString(soSufixo, "x");
		Files.setLastModifiedTime(soSufixo, FileTime.from(AGORA.minus(Duration.ofDays(3))));

		StorageOrphanCollector.Report relatorio = coletor().collect(false);

		assertThat(Files.exists(soSufixo)).isTrue();
		assertThat(relatorio.orphansToRemove()).isEmpty();
	}

	@Test
	void semRaizConfiguradaORelatorioDizIssoENao_nenhum_orfao() {
		StorageOrphanCollector semRaiz = new StorageOrphanCollector(catalogo,
			raiz.resolve("nao-existe").toString(), Clock.fixed(AGORA, ZoneOffset.UTC));

		StorageOrphanCollector.Report relatorio = semRaiz.collect(false);

		assertThat(relatorio.installed()).isFalse();
	}

	/** Catálogo em memória que sabe (por teste) quais ids têm colocação e quais são de nó. */
	static final class CatalogoQueSabeDeNos extends CatalogoEmMemoria {

		private final java.util.Set<String> colocacoes = new java.util.HashSet<>();

		private final java.util.Set<String> nos = new java.util.HashSet<>();

		void comColocacao(String id) {
			colocacoes.add(id);
		}

		void comNo(String id) {
			nos.add(id);
		}

		@Override
		public boolean storageNodeExists(String nodeId) {
			return nos.contains(nodeId);
		}

		@Override
		public Optional<com.socialcoisa.rede.network.domain.StorageObject> findById(String id) {
			// A linha como o catálogo a devolveria; o arquivo não é o que se prova aqui.
			return colocacoes.contains(id)
				? Optional.of(com.socialcoisa.rede.network.domain.StorageObject.current(id, "bkt", "k",
					"application/octet-stream", 5L, "0".repeat(64),
					com.socialcoisa.rede.network.domain.StorageObjectState.STORED, "user", AGORA, AGORA, null))
				: Optional.empty();
		}
	}

}