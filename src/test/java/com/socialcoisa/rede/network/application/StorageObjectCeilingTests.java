package com.socialcoisa.rede.network.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.socialcoisa.rede.network.application.port.out.NetworkNodeRepository;
import com.socialcoisa.rede.shared.audit.AuditTrail;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * O teto de objeto tem de parar a <b>leitura</b>, não só recusar depois de gravar.
 *
 * <p><b>Antes:</b> {@code finishUpload} chamava {@code measure(body)}, que copiava o corpo inteiro para
 * um temporário em disco e só então comparava com {@code maxObjectBytes}. Um cliente que mandasse 50 GiB
 * num limite de 5 GiB fazia o servidor <b>escrever 50 GiB em {@code /tmp}</b> para depois responder
 * {@code 413}. O pior caso não é o disco cheio: é o upload longo que ainda vai ser recusado, ocupando
 * espaço que pertence a quem está gravando de verdade.</p>
 *
 * <p><b>Agora:</b> {@code measure} conta enquanto lê e para no teto, apagando o temporário. O que o
 * teste discrimina é o <b>quanto foi lido</b>: o fluxo diz quantos bytes entregou, e a asserção exige
 * que a leitura pare logo depois do teto — não que ela termine.</p>
 */
class StorageObjectCeilingTests {

	private static final Instant NOW = Instant.parse("2026-11-19T12:00:00Z");

	/** Teto pequeno o bastante para o teste ler pouco e provar o corte. */
	private static final long TETO = 1024;

	@TempDir
	Path root;

	private CatalogoEmMemoria catalog;
	private StorageCatalogService service;

	/** O mesmo catálogo em memória dos testes de serviço — o teto não é assunto do SQL. */
	private static final class CatalogoEmMemoria
			extends com.socialcoisa.rede.network.application.testsupport.CatalogoEmMemoria {
	}

	/** Um fluxo que conta o que já entregou: é por ele que se prova onde a leitura parou. */
	private static final class CorpoContado extends InputStream {

		private final long total;
		private long entregues;

		CorpoContado(long total) {
			this.total = total;
		}

		long entregues() {
			return entregues;
		}

		@Override
		public int read() {
			if (entregues >= total) {
				return -1;
			}
			entregues++;
			return 'x';
		}

		@Override
		public int read(byte[] b, int off, int len) {
			if (entregues >= total) {
				return -1;
			}
			int n = (int) Math.min(len, total - entregues);
			java.util.Arrays.fill(b, off, off + n, (byte) 'x');
			entregues += n;
			return n;
		}
	}

	@BeforeEach
	void setUp() {
		catalog = new CatalogoEmMemoria();
		var nodes = mock(NetworkNodeRepository.class);
		when(nodes.findCandidates(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt()))
			.thenReturn(List.of());
		service = new StorageCatalogService(catalog, mock(AuditTrail.class), Clock.fixed(NOW, ZoneOffset.UTC),
			TETO, root.toString(), "", new StoragePlacementService(nodes, catalog, 0, 90), null, null, 0, null);
	}

	private static long temporariosNo(Path root) throws Exception {
		try (var lista = Files.list(root)) {
			return lista.filter(p -> p.getFileName().toString().startsWith(".upload-")).count();
		}
	}

	@Test
	void corpo_muito_acima_do_teto_para_de_ser_lido_no_teto() throws Exception {
		String id = service.beginUpload("user_1", "fotos", "grande.bin", null).id();
		// 200x o teto: se a leitura não parar, o teste vê os 200 KB entregues.
		var corpo = new CorpoContado(TETO * 200);

		assertThatThrownBy(() -> service.finishUpload(id, corpo, "user_1"))
			.isInstanceOf(ApiException.class)
			.hasMessageContaining("excede o teto");

		// Um byte além do teto basta para saber que ele passou: a leitura pede no máximo o que ainda
		// falta até esse byte, nunca um bloco inteiro de 64 KiB a mais.
		assertThat(corpo.entregues())
			.as("a leitura tem de parar no teto mais um byte, nao ler o corpo inteiro")
			.isEqualTo(TETO + 1);
		assertThat(temporariosNo(root)).as("o temporario da recusa nao fica no disco").isZero();
	}

	@Test
	void corpo_no_teto_passa_e_e_gravado() throws Exception {
		String id = service.beginUpload("user_1", "fotos", "cheio.bin", null).id();
		var corpo = new CorpoContado(TETO);

		com.socialcoisa.rede.network.domain.StorageObject gravado = service.finishUpload(id, corpo, "user_1");

		assertThat(gravado.bytes()).isEqualTo(TETO);
		assertThat(corpo.entregues()).isEqualTo(TETO);
		assertThat(temporariosNo(root)).as("o temporario sai depois de virar corpo").isZero();
	}

	@Test
	void corpo_vazio_e_recusado_sem_deixar_temporario() throws Exception {
		String id = service.beginUpload("user_1", "fotos", "vazio.bin", null).id();

		assertThatThrownBy(() -> service.finishUpload(id,
			new ByteArrayInputStream("".getBytes(StandardCharsets.UTF_8)), "user_1"))
			.isInstanceOf(ApiException.class)
			.hasMessageContaining("vazio");
		assertThat(temporariosNo(root)).isZero();
	}
}
