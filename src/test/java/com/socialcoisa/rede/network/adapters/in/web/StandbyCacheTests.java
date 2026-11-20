package com.socialcoisa.rede.network.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.network.application.StandbyDumpSnapshotProvider;
import com.socialcoisa.rede.network.application.StandbyDumpSource;
import com.socialcoisa.rede.network.application.StandbyFailureRecorder;
import com.socialcoisa.rede.network.application.StandbySyncService;
import com.socialcoisa.rede.network.application.port.out.NetworkAuthorityRepository;
import com.socialcoisa.rede.network.application.port.out.NetworkNodeRepository;
import com.socialcoisa.rede.network.application.port.out.StandbySnapshotRepository;
import com.socialcoisa.rede.network.domain.NetworkNode;
import com.socialcoisa.rede.network.domain.NodeProfile;
import com.socialcoisa.rede.network.domain.NodeStatus;
import com.socialcoisa.rede.shared.audit.AuditTrail;
import com.socialcoisa.rede.shared.web.ApiException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * #953 item 4: o dump é gerado UMA vez por janela, e a retomada sai do mesmo arquivo.
 *
 * <p>A fonte deste teste muda a cada chamada — é o caso real: entre duas chamadas o banco de produção
 * mudou, e regenerar daria um dump com outro digest. A retomada do nó juntaria metades de dois dumps
 * e a confirmação seria recusada por digest (ou, pior, aceita com dado inconsistente).</p>
 *
 * <p>O teste da rota ({@code NetworkStandbyTests}) usa uma fonte <b>estável</b>, e por isso não
 * discrimina: regenerar o mesmo byte dá o mesmo byte. É este que prova o cache.</p>
 */
class StandbyCacheTests {

	/** Cada chamada devolve um dump diferente, como o banco de produção entre duas leituras. */
	static final class MudaACadaChamada implements StandbyDumpSource {

		final AtomicInteger chamadas = new AtomicInteger();
		volatile boolean verificado = true;
		volatile boolean verificarParaVer = true;

		@Override
		public byte[] dump(long offset) {
			int n = chamadas.incrementAndGet();
			String sql = "-- dump #" + n + "\n" + "CREATE TABLE t (id int);\n" + "INSERT INTO t VALUES (" + n + ");\n".repeat(4);
			byte[] completo = sql.getBytes(StandardCharsets.UTF_8);
			return offset <= 0 ? completo : java.util.Arrays.copyOfRange(completo, (int) offset, completo.length);
		}

		@Override
		public boolean verify() {
			return verificarParaVer;
		}

		@Override
		public String lastFailure() {
			return "dump recusado pela verificacao";
		}
	}

	private final Instant agora = Instant.parse("2026-11-19T12:00:00Z");
	private final MudaACadaChamada fonte = new MudaACadaChamada();

	private StandbySyncService servico(long cacheSeconds) {
		StandbySnapshotRepository snapshots = mock(StandbySnapshotRepository.class);
		StandbySnapshotRepository.Snapshot linha = new StandbySnapshotRepository.Snapshot("nd_1", "original",
			"SYNCING", 0, agora, null, null, 0L, null, null, "admin");
		when(snapshots.find(anyString(), anyString())).thenReturn(Optional.of(linha));
		NetworkAuthorityRepository authorities = mock(NetworkAuthorityRepository.class);
		when(authorities.current()).thenReturn(Optional.empty());
		NetworkNodeRepository nodes = mock(NetworkNodeRepository.class);
		NetworkNode node = org.mockito.Mockito.mock(NetworkNode.class);
		when(node.id()).thenReturn("nd_1");
		when(nodes.findById(anyString())).thenReturn(Optional.of(node));
		Clock relogio = Clock.fixed(agora, ZoneOffset.UTC);
		return new StandbySyncService(snapshots, authorities, nodes,
			new StandbyDumpSnapshotProvider(fonte, relogio, cacheSeconds), mock(StandbyFailureRecorder.class),
			mock(AuditTrail.class), relogio, 6, 4);
	}

	@Test
	void a_retomada_nao_regenera_o_dump_entre_duas_leituras() {
		StandbySyncService servico = servico(600);

		StandbySyncService.Copy inteira = servico.download("admin", "nd_1", 0);
		StandbySyncService.Copy retomada = servico.download("admin", "nd_1", 30);

		// Uma geração só para as duas leituras: o banco não é lido duas vezes por uma retomada.
		assertThat(fonte.chamadas.get()).isEqualTo(1);
		assertThat(retomada.sha256()).isEqualTo(inteira.sha256());
		assertThat(retomada.totalBytes()).isEqualTo(inteira.totalBytes());
		assertThat(retomada.body()).isEqualTo(java.util.Arrays.copyOfRange(inteira.body(), 30, inteira.body().length));
	}

	@Test
	void janela_vencida_le_o_banco_de_novo() {
		// cache-seconds=0: cada leitura é uma geração nova (é o que o operador escolhe ao desligar o cache).
		StandbySyncService servico = servico(0);
		StandbySyncService.Copy primeira = servico.download("admin", "nd_1", 0);
		StandbySyncService.Copy segunda = servico.download("admin", "nd_1", 0);

		assertThat(fonte.chamadas.get()).isEqualTo(2);
		assertThat(segunda.sha256()).isNotEqualTo(primeira.sha256());
	}

	@Test
	void dump_que_nao_passa_na_verificacao_nao_e_entregue() {
		StandbySyncService servico = servico(600);
		fonte.verificarParaVer = false;

		// Sem entrega: a cópia falha com o motivo da verificação, nunca com um arquivo que o nó baixa.
		org.assertj.core.api.Assertions.assertThatThrownBy(() -> servico.download("admin", "nd_1", 0))
			.isInstanceOf(ApiException.class)
			.hasMessageContaining("dump recusado pela verificacao");
		assertThat(fonte.chamadas.get()).isEqualTo(1);
	}

	@Test
	void a_referencia_do_digest_e_do_dump_inteiro_e_nao_do_trecho() {
		StandbySyncService servico = servico(600);
		StandbySyncService.Copy inteira = servico.download("admin", "nd_1", 0);
		StandbySyncService.Copy retomada = servico.download("admin", "nd_1", inteira.body().length / 2);

		assertThat(retomada.sha256()).isEqualTo(StandbySyncService.sha256(inteira.body()));
		assertThat(retomada.byteStart()).isEqualTo(inteira.body().length / 2);
	}
}