package com.socialcoisa.rede.network.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.network.application.testsupport.CatalogoEmMemoria;
import com.socialcoisa.rede.network.domain.StorageObject;
import com.socialcoisa.rede.network.domain.StorageObjectState;
import com.socialcoisa.rede.network.domain.StoragePlacement;
import com.socialcoisa.rede.shared.audit.AuditTrail;

/**
 * A soltura da designação pendente pelo operador (doc 13 §5.11).
 *
 * <p>O antes: a regra existia, mas só dentro de {@code StorageReplicationWorker}, que nasce desligado.
 * Com o auto-heal desligado — o padrão de fábrica —, uma {@code PENDING_REMOTE} cujo nó sumiu depois do
 * despacho ficava parada para sempre: o healer respondia {@code ALREADY_DESIGNATED} e o operador não
 * tinha rota nenhuma para soltar. O teste que prova isto é o que troca o estado da linha e verifica que
 * a lacuna volta a ser designável <b>sem</b> worker.</p>
 *
 * <p>O que estes testes seguram: o prazo (recente não é solta), a semente (linha sem
 * {@code verified_at} conta como vencida), o seco (não grava), o estado de destino ({@code GONE}, não
 * apagada — o histórico da tentativa continua) e a auditoria de cada linha.</p>
 */
class StoragePendingReleaseTests {

	private static final Instant AGORA = Instant.parse("2026-11-19T12:00:00Z");

	private Catalogo catalogo;
	private AuditTrail audit;
	private StoragePendingRelease servico;

	@BeforeEach
	void setUp() {
		catalogo = new Catalogo();
		audit = mock(AuditTrail.class);
		servico = new StoragePendingRelease(catalogo, audit, Clock.fixed(AGORA, ZoneOffset.UTC), 60);
	}

	private StorageObject objeto(String id) {
		StorageObject criado = catalogo.createPending(id, "fotos", id + ".jpg", "image/jpeg", "user_1",
			AGORA.minusSeconds(86400));
		catalogo.markStored(criado.id(), 1000L, "sha-" + id, AGORA.minusSeconds(86000));
		return catalogo.findById(id).orElseThrow();
	}

	private void pendente(String objectId, int replica, String nodeId, Instant designada) {
		catalogo.savePlacement(new StoragePlacement(objectId, replica, nodeId, 1000L, "sha-" + objectId,
			StoragePlacement.State.PENDING_REMOTE, designada));
	}

	private StoragePlacement copia(String objectId, int replica) {
		return catalogo.copies(objectId).stream().filter(p -> p.replica() == replica).findFirst().orElseThrow();
	}

	@Test
	void a_designacao_vencida_vira_gone_e_a_lacuna_volta_a_ser_curável() {
		objeto("sob_a");
		catalogo.savePlacement(new StoragePlacement("sob_a", 0, "control-plane", 1000L, "sha-sob_a",
			StoragePlacement.State.OK, AGORA.minusSeconds(86400)));
		// Designada há três horas, nunca confirmada: o nó sumiu depois do despacho.
		pendente("sob_a", 1, "nd_perdido", AGORA.minusSeconds(10800));

		var relatorio = servico.releaseExpired("admin_1", null, 0, false);

		assertThat(relatorio.dryRun()).isFalse();
		assertThat(relatorio.pending()).singleElement().satisfies(p -> {
			assertThat(p.replica()).isEqualTo(1);
			assertThat(p.nodeId()).isEqualTo("nd_perdido");
			assertThat(p.key()).as("o operador reconhece o objeto pela chave").isEqualTo("sob_a.jpg");
			assertThat(p.ageMinutes()).isEqualTo(180);
			assertThat(p.expired()).isTrue();
		});
		// GONE, não apagada: a tentativa continua contando na história, e deixa de contar como dono.
		assertThat(copia("sob_a", 1).state()).isEqualTo(StoragePlacement.State.GONE);
		assertThat(copia("sob_a", 1).nodeId()).isEqualTo("nd_perdido");
		// A lacuna está livre de novo: nenhuma PENDING_REMOTE no objeto.
		assertThat(catalogo.copies("sob_a"))
			.noneMatch(p -> p.state() == StoragePlacement.State.PENDING_REMOTE);
		verify(audit).record(eq("admin_1"), eq("STORAGE_PENDING_RELEASED"), eq("sob_a"), anyString());
	}

	@Test
	void a_designacao_recente_nao_e_solta() {
		objeto("sob_b");
		pendente("sob_b", 1, "nd_vivo", AGORA.minusSeconds(300));

		// O nó ainda pode confirmar: soltar agora é tirar a cópia de quem ia entregá-la.
		var relatorio = servico.releaseExpired("admin_1", null, 0, false);

		assertThat(relatorio.pending()).isEmpty();
		assertThat(copia("sob_b", 1).state()).isEqualTo(StoragePlacement.State.PENDING_REMOTE);
	}

	@Test
	void a_leitura_mostra_a_pendencia_sem_gravar() {
		objeto("sob_c");
		pendente("sob_c", 1, "nd_antigo", AGORA.minusSeconds(10800));
		pendente("sob_c", 2, "nd_parado", AGORA.minusSeconds(30));

		var relatorio = servico.pending(0);

		// Mais antiga primeiro. A de 30 min ainda não venceu: o prazo é "sem confirmação há MAIS que
		// 60 minutos", e a linha exatamente no limite ainda conta como a caminho (por isso 30, e não
		// 60: truncado para minuto, 60 seria "há mais de 60").
		assertThat(relatorio.pending()).extracting(StoragePendingRelease.Pending::replica).containsExactly(1, 2);
		// Leitura e escrita usam a mesma régua: com o prazo de 1 min a de 30 min já é vencida.
		assertThat(relatorio.pending()).extracting(StoragePendingRelease.Pending::expired).containsExactly(true, false);
		assertThat(servico.releaseExpired("admin_1", 1, 0, true).pending())
			.extracting(StoragePendingRelease.Pending::replica)
			.containsExactly(1);
		var semPrazo = servico.releaseExpired("admin_1", 0, 0, true);
		assertThat(semPrazo.maxAgeMinutes()).as("zero vence a designação no nascedouro: o piso é 1")
			.isEqualTo(1);
		assertThat(relatorio.maxAgeMinutes()).isEqualTo(60);
		// Ler não muda estado nenhum.
		assertThat(catalogo.copies("sob_c")).allMatch(p -> p.state() == StoragePlacement.State.PENDING_REMOTE);
	}

	@Test
	void o_seco_mostra_sem_gravar() {
		objeto("sob_d");
		pendente("sob_d", 1, "nd_perdido", AGORA.minusSeconds(10800));

		var relatorio = servico.releaseExpired("admin_1", null, 0, true);

		assertThat(relatorio.dryRun()).isTrue();
		assertThat(relatorio.pending()).hasSize(1);
		assertThat(copia("sob_d", 1).state()).isEqualTo(StoragePlacement.State.PENDING_REMOTE);
	}

	@Test
	void a_designacao_sem_instante_conta_como_vencida() {
		objeto("sob_e");
		// Linha antiga sem `verified_at`: não dá para provar que é nova, e assumir "nova" deixaria a
		// pendência eterna exatamente no caso em que o dado está incompleto.
		pendente("sob_e", 1, "nd_x", null);

		var relatorio = servico.releaseExpired("admin_1", null, 0, false);

		assertThat(relatorio.pending()).singleElement().satisfies(p -> {
			assertThat(p.ageMinutes()).isNull();
			assertThat(p.expired()).isTrue();
		});
		assertThat(copia("sob_e", 1).state()).isEqualTo(StoragePlacement.State.GONE);
	}

	@Test
	void o_prazo_da_chamada_vence_o_padrao() {
		objeto("sob_f");
		pendente("sob_f", 1, "nd_lento", AGORA.minusSeconds(300));

		// Cinco minutos pendente: com o padrão de 60 o nó ainda pode confirmar; com o prazo pedido de
		// 1 minuto o operador decide que já passou tempo demais.
		assertThat(servico.releaseExpired("admin_1", 1, 0, false).pending()).hasSize(1);
		assertThat(copia("sob_f", 1).state()).isEqualTo(StoragePlacement.State.GONE);
	}

	@Test
	void o_teto_limita_o_lote() {
		for (String id : List.of("sob_g", "sob_h", "sob_i")) {
			objeto(id);
			pendente(id, 1, "nd_perdido", AGORA.minusSeconds(10800));
		}

		var relatorio = servico.releaseExpired("admin_1", null, 2, false);

		assertThat(relatorio.pending()).hasSize(2);
		assertThat(catalogo.placementsInState(StoragePlacement.State.PENDING_REMOTE))
			.as("uma linha ficou para a próxima chamada").hasSize(1);
	}

	@Test
	void a_obrigacao_solta_devolve_a_folga_ao_no() {
		// A reserva de folga (doc 17 §2.2) conta a pendente como espaço prometido. Soltar a obrigação
		// perdida devolve a folga; sem isto, um nó que sumiu segurava a reserva para sempre.
		objeto("sob_j");
		pendente("sob_j", 1, "nd_perdido", AGORA.minusSeconds(10800));

		servico.releaseExpired("admin_1", null, 0, false);

		assertThat(catalogo.copiesOnNode("nd_perdido"))
			.noneMatch(p -> p.state() == StoragePlacement.State.PENDING_REMOTE);
	}

	/** O catálogo em memória, sem nada a mais: o serviço não decide com regra de catálogo. */
	private static final class Catalogo extends CatalogoEmMemoria {
	}
}