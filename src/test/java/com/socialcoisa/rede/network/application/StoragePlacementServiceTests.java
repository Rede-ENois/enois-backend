package com.socialcoisa.rede.network.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.network.application.port.out.NetworkNodeRepository;
import com.socialcoisa.rede.network.application.port.out.StorageCatalogRepository;
import com.socialcoisa.rede.network.domain.NetworkNode;
import com.socialcoisa.rede.network.domain.NodeProfile;
import com.socialcoisa.rede.network.domain.NodeStatus;
import com.socialcoisa.rede.network.domain.StoragePlacement;

/**
 * A escolha de réplica é o que decide se a fase 3 replica <b>de verdade</b> ou só aumenta um
 * contador.
 *
 * <p>O que estes testes fixam, e por quê:</p>
 * <ul>
 * <li><b>nunca duas cópias no mesmo nó</b> — dois arquivos no mesmo disco não sobrevivem à perda do
 * disco, e a contagem diria 2. É a parte de "domínio de falha" que dá para afirmar sem inventar
 * topologia;</li>
 * <li><b>não designa para nó que não está pronto</b> — nó sem heartbeat recente pode ter o processo
 * morto; designar para ele é a cópia que nunca chega;</li>
 * <li><b>não designa para nó sem folga cedida</b> — o §8/§9 decidem pela parte que o participante
 * cedeu (declarado menos ocupado), não pelo disco livre da máquina. Sem declarado não há teto, e
 * sem teto não há como afirmar que cabe: a colocação não designa (o {@code store} também recusa);</li>
 * <li><b>escolhe pela folga cedida</b> e de forma estável — ordem decrescente e desempate por id,
 * para o mesmo objeto não espalhar cópias diferentes a cada reenvio.</li>
 * </ul>
 */
class StoragePlacementServiceTests {

	private static final Instant NOW = Instant.parse("2026-11-15T12:00:00Z");
	private static final long MB = 1024 * 1024;

	private NetworkNodeRepository nodes;
	private StorageCatalogRepository catalog;
	private StoragePlacementService service;

	@BeforeEach
	void setUp() {
		nodes = mock(NetworkNodeRepository.class);
		catalog = mock(StorageCatalogRepository.class);
		when(catalog.placements("sob_1")).thenReturn(List.of());
		service = new StoragePlacementService(nodes, catalog, 3, 90);
	}

	@Test
	void distribuiPelosNosProntosComMaisFolga() {
		candidates(nodeWithDisk("nd_a", 40960L, 40960L, null), nodeWithDisk("nd_b", 81920L, 81920L, null),
			nodeWithDisk("nd_c", 20480L, 20480L, null));

		List<NetworkNode> chosen = service.chooseReplicas("sob_1", 0, NOW);

		// Dois porque o alvo é 3 e a réplica 0 é do Control Plane. Mais folga primeiro.
		assertThat(chosen).extracting(NetworkNode::id).containsExactly("nd_b", "nd_a");
	}

	@Test
	void nuncaDesignaDuasCopiasNoMesmoNo() {
		when(catalog.placements("sob_1"))
			.thenReturn(List.of(new StoragePlacement("sob_1", 0, "nd_a", 10, "abc", StoragePlacement.State.OK, NOW)));
		candidates(storageNode("nd_a", 40960L), storageNode("nd_b", 81920L), storageNode("nd_c", 20480L));

		List<NetworkNode> chosen = service.chooseReplicas("sob_1", 0, NOW);

		assertThat(chosen).extracting(NetworkNode::id).containsExactly("nd_b", "nd_c");
	}

	@Test
	void replicaQuePerdeuACopiaVoltaASerCandidata() {
		// A linha existe em STALE: o nó foi suspenso ou não confirmou. Ele pode receber de novo.
		when(catalog.placements("sob_1")).thenReturn(
			List.of(new StoragePlacement("sob_1", 1, "nd_a", 10, "abc", StoragePlacement.State.STALE, NOW)));
		candidates(nodeWithDisk("nd_a", 40960L, 40960L, null), nodeWithDisk("nd_b", 81920L, 81920L, null));

		List<NetworkNode> chosen = service.chooseReplicas("sob_1", 0, NOW);

		assertThat(chosen).extracting(NetworkNode::id).containsExactly("nd_b", "nd_a");
	}

	@Test
	void noQueNaoEstaProntoNaoRecebeCopia() {
		NetworkNode morto = nodeWith("nd_morto", NodeStatus.SUSPENDED, Set.of(NodeProfile.NODE_STORAGE), 40960L, NOW);
		NetworkNode semPerfil = nodeWith("nd_sem", NodeStatus.ACTIVE, Set.of(NodeProfile.NODE_WORKER), 40960L, NOW);
		NetworkNode semHeartbeat = nodeWith("nd_nunca", NodeStatus.ACTIVE, Set.of(NodeProfile.NODE_STORAGE), 40960L,
			NOW.minusSeconds(600));
		NetworkNode precário = storageNode("nd_a", 40960L);
		candidates(morto, semPerfil, semHeartbeat, precário);

		assertThat(service.chooseReplicas("sob_1", 0, NOW)).extracting(NetworkNode::id).containsExactly("nd_a");
	}

	@Test
	void noComDiscoMedidoAbaixoDoPisoNaoRecebeCopia() {
		// 500 MB livres: não cabe o objeto mais a folga mínima — e o disco continua sendo régua,
		// além da cedida. O nó cedeu 50 GB, mas o disco da máquina não tem.
		candidates(nodeWithDisk("nd_cheio", 500L, 51200L, null), nodeWithDisk("nd_a", 40960L, 409600L, null));

		assertThat(service.chooseReplicas("sob_1", 0, NOW)).extracting(NetworkNode::id).containsExactly("nd_a");
	}

	/**
	 * Sem declarado não há teto cedido, e sem teto a colocação não afirma que cabe. Antes ela
	 * designava — "não medir não é saber que não cabe" —, mas o que faltava não era a medida do
	 * disco: era o número que só o participante pode dar. Nó sem cadastro receberia cópia por tempo
	 * indeterminado, sem limite nenhum, contra o §7 ("não permitir que ... consuma além do limite")
	 * e o §8 ("capacidade real dos participantes").
	 */
	@Test
	void noSemTetoCedidoNaoRecebeCopia() {
		candidates(nodeWithDisk("nd_sem_teto", 40960L, 0L, null));

		assertThat(service.chooseReplicas("sob_1", 0, NOW)).isEmpty();
	}

	@Test
	void umaCopiaNaoDesignaNada() {
		StoragePlacementService soControlPlane = new StoragePlacementService(nodes, catalog, 1, 90);
		candidates(storageNode("nd_a", 40960L));

		assertThat(soControlPlane.chooseReplicas("sob_1", 0, NOW)).isEmpty();
	}

	@Test
	void objetoGrandeDemaisParaAFolgaNaoEntra() {
		// 2 GiB de objeto contra 2,5 GiB cedidos: sobra 0,5 GiB, abaixo do piso de 1 GiB.
		candidates(nodeWithDisk("nd_a", 102400L, 2560L, null), nodeWithDisk("nd_b", 102400L, 102400L, null));

		assertThat(service.chooseReplicas("sob_1", 2L * 1024 * MB, NOW)).extracting(NetworkNode::id)
			.containsExactly("nd_b");
	}

	// ------------------------------------------------- fase 4: domínios de falha

	/**
	 * Duas réplicas na mesma região não sobrevivem à queda da região — é o mesmo erro que duas
	 * cópias no mesmo nó, um nível acima. A fase 3 só sabia não repetir o NÓ; esta sabe não repetir
	 * a REGIÃO declarada.
	 */
	@Test
	void naoDesignaDuasReplicasNaMesmaRegiao() {
		candidates(regionNode("nd_a", "sudeste", 81920L), regionNode("nd_b", "sudeste", 40960L),
			regionNode("nd_c", "sul", 20480L));

		List<NetworkNode> chosen = service.chooseReplicas("sob_1", 0, NOW);

		// A primeira escolha é a de mais folga; a segunda tem de buscar OUTRA região, mesmo com
		// disco menor — só nd_c é de região diferente.
		assertThat(chosen).extracting(NetworkNode::id).containsExactly("nd_a", "nd_c");
	}

	/**
	 * Quando não há região distinta suficiente, a cópia continua: uma segunda cópia na mesma região
	 * ainda é melhor que nenhuma, e o painel mostra a distribuição real. Recusar aqui seria
	 * declarar o objeto sem redundância por causa de uma restrição de topologia.
	 */
	@Test
	void usaAMesmaRegiaoQuandoNaoHaOutraDisponivel() {
		candidates(regionNode("nd_a", "sudeste", 81920L), regionNode("nd_b", "sudeste", 40960L),
			regionNode("nd_c", "sudeste", 20480L));

		List<NetworkNode> chosen = service.chooseReplicas("sob_1", 0, NOW);

		assertThat(chosen).extracting(NetworkNode::id).containsExactly("nd_a", "nd_b");
	}

	/**
	 * Região não declarada é um domínio de falha <b>desconhecido</b>. Ela não é igual à de um nó
	 * que declarou região (não sabemos que é a mesma), e dois nós sem região não são a mesma
	 * região entre si. Nunca igualar desconhecido a declarado é a regra de {@code NetworkRegions}.
	 */
	@Test
	void regiaoNaoDeclaradaNaoEhADaRegiaoDeOutro() {
		candidates(regionNode("nd_a", null, 81920L), regionNode("nd_b", "sudeste", 40960L),
			regionNode("nd_c", null, 20480L));

		List<NetworkNode> chosen = service.chooseReplicas("sob_1", 0, NOW);

		// Três domínios distintos: vazio, sudeste, vazio. A ordem por folga vale e nenhum é
		// tratado como cópia da região de outro.
		assertThat(chosen).extracting(NetworkNode::id).containsExactly("nd_a", "nd_b");
	}

	/** A região que já recebeu cópia também conta: a segunda tem de ir para outra. */
	@Test
	void naoRepeteARegiaoQueJaTemCopia() {
		when(catalog.placements("sob_1")).thenReturn(List.of(
			new StoragePlacement("sob_1", 1, "nd_ok", 10, "abc", StoragePlacement.State.OK, NOW)));
		candidates(regionNode("nd_ok", "sul", 102400L), regionNode("nd_a", "sul", 81920L),
			regionNode("nd_b", "norte", 40960L));

		List<NetworkNode> chosen = service.chooseReplicas("sob_1", 0, NOW);

		// Faltam duas: a primeira fora da região ocupada (norte) e, esgotadas as regiões distintas,
		// a segunda completa na repetida — menos redundância seria pior que a disponível.
		assertThat(chosen).extracting(NetworkNode::id).containsExactly("nd_b", "nd_a");
	}

	/** A grafia não cria domínio: "Sudeste" e "sudeste" são a mesma região. */
	@Test
	void grafiaDaRegiaoNaoCriaDominioDiferente() {
		candidates(regionNode("nd_a", "Sudeste", 81920L), regionNode("nd_b", "sudeste", 40960L),
			regionNode("nd_c", "SUDESTE", 20480L));

		List<NetworkNode> chosen = service.chooseReplicas("sob_1", 0, NOW);

		// Todas a mesma região: usa a mesma região quando não há outra disponível.
		assertThat(chosen).extracting(NetworkNode::id).containsExactly("nd_a", "nd_b");
	}

	private static NetworkNode regionNode(String id, String region, long diskFreeMb) {
		return new NetworkNode(id, id, "owner", NodeStatus.ACTIVE, Set.of(NodeProfile.NODE_STORAGE), region, "BR",
			"0.1.0", NOW, 4, 8192, 102400, 100, null, null, null, null, diskFreeMb, null, null, 0, null, null);
	}

	// ------------------------------------------------- §8: a folga que decide é a cedida

	/**
	 * O caso do §7 literalmente: 2 TB de volume, 500 GB reservados, 1,5 TB cedidos — e o disco está
	 * com 1,6 TB já ocupados por OUTRA coisa (essa é a razão de não usar o livre).
	 *
	 * <p>nd_a tem 2 TB livres e cedeu 1 MiB: o disco nunca vai barrar, mas ele não tem mais nada
	 * para ceder. nd_b cedeu 100 GB e tem quase tudo livre. Só olhando o livre (o comportamento
	 * anterior), nd_a venceria — e o objeto iria para além do que a pessoa autorizou. Com a folga
	 * cedida, quem tem espaço é nd_b.</p>
	 *
	 * <p>Os lados são desiguais de propósito: se a folga fosse o livre, a ordem seria a inversa e o
	 * teste falharia; se fosse o menor dos dois zeros, nd_a continuaria vencendo.</p>
	 */
	@Test
	void noQueEsgotouOQueCedeuNaoRecebeCopiaMesmoComDiscoLivre() {
		NetworkNode esgotado = nodeWithDisk("nd_a", 2L * 1024 * 1024, 1L, null);
		NetworkNode comFolga = nodeWithDisk("nd_b", 102400L, 102400L - 1024L, null);
		candidates(esgotado, comFolga);

		List<NetworkNode> chosen = service.chooseReplicas("sob_1", 0, NOW);

		assertThat(chosen).extracting(NetworkNode::id).containsExactly("nd_b");
	}

	/**
	 * O confirmado no catálogo é o ocupado, não o número de cópias: 3 cópias de 400 MiB cada (mais o
	 * arredondamento de 1 MiB para cima, porque os bytes vão por MB inteiro) somam mais que a folga
	 * de 1 GiB, e o nó sai mesmo com o disco quase vazio. Se a conta ignorasse os bytes confirmados,
	 * ele venceria — a asserção cai.
	 */
	@Test
	void ocupadoPelasCopiasConfirmadasContaContraOTeto() {
		when(catalog.placementsOnNode("nd_a")).thenReturn(List.of(
			copia("nd_a", 400L), copia("nd_a", 400L), copia("nd_a", 400L)));
		candidates(nodeWithDisk("nd_a", 512000L, 1024L, null), nodeWithDisk("nd_b", 40960L, 40960L, null));

		assertThat(service.chooseReplicas("sob_1", 0, NOW)).extracting(NetworkNode::id).containsExactly("nd_b");
	}

	/**
	 * O nó medindo o uso em si (V344) pode ver arquivo que o catálogo não confirma — cópia
	 * interrompida, sobra de troca. O maior dos dois é o ocupado: aqui o confirmado dá 0 e o medido
	 * enche o teto, e o nó sai. Se a conta usasse o menor, ele ficaria.
	 */
	@Test
	void usoMedidoPeloNoValeMesmoSemCopiaConfirmada() {
		// Lados escolhidos para o MAX ser o único que recusa: o catálogo diz 0 (nada confirmado) e o
		// nó diz 4095. Cedeu 4096. Só o medido (4095 de 4096) tira o nd_a; com o menor (0) ele teria
		// 4096 MB de folga e receberia. O disco livre é 2 TB para a folga física não decidir por
		// acaso o que é a conta do uso medido.
		NetworkNode medidoCheio = nodeWithDisk("nd_a", 2L * 1024 * 1024, 4096L, 4095L);
		candidates(medidoCheio);

		assertThat(service.chooseReplicas("sob_1", 0, NOW)).isEmpty();
	}

	/**
	 * Sem declarado não há teto a aplicar — é o cadastro do enroll, que o enrollment deixa em zero —
	 * e sem teto a colocação não afirma que cabe. Antes deste contrato o nó era designado ("não
	 * medir não é saber que não cabe"); a mudança de régua é explícita: a medida que faltava não era
	 * o disco, era o número que só o participante pode dar, e receber cópia sem limite nenhum
	 * contraria o §7. O nó com folga ao lado recebe, para o caso não passar por "rede vazia".
	 */
	@Test
	void nóSemTetoCedidoPerdeParaQuemTemTeto() {
		candidates(nodeWithDisk("nd_sem_teto", 409600L, 0L, null), nodeWithDisk("nd_com", 40960L, 40960L, null));

		assertThat(service.chooseReplicas("sob_1", 0, NOW)).extracting(NetworkNode::id).containsExactly("nd_com");
	}

	private static StoragePlacement copia(String nodeId, long bytes) {
		return new StoragePlacement("sob_" + nodeId + bytes, 1, nodeId, bytes * MB, "abc",
			StoragePlacement.State.OK, NOW);
	}

	/**
	 * Nó com os três números do disco separados: livre (da máquina), declarado (cedido) e o uso que
	 * ele mediu em si.
	 */
	private static NetworkNode nodeWithDisk(String id, long diskFreeMb, long declaredMb, Long usedMb) {
		return new NetworkNode(id, id, "owner", NodeStatus.ACTIVE, Set.of(NodeProfile.NODE_STORAGE), "sa-east-1",
			"BR", "0.1.0", NOW, 4, 8192, (int) declaredMb, 100, null, null, null, null, diskFreeMb, null, usedMb, 0,
			null, null);
	}

	private void candidates(NetworkNode... found) {
		when(nodes.findCandidates(any(), anyInt())).thenReturn(List.of(found));
	}

	/**
	 * Nó pronto com o disco livre dado e um teto cedido generoso: estes casos exercitam prontidão,
	 * região e ordem, não a folga — a folga tem os seus testes próprios, com lados desiguais.
	 */
	private static NetworkNode storageNode(String id, long diskFreeMb) {
		return nodeWithDisk(id, diskFreeMb, 102400L, null);
	}

	private static NetworkNode nodeWith(String id, NodeStatus status, Set<NodeProfile> profiles, Long diskFreeMb,
			Instant lastSeen) {
		return new NetworkNode(id, id, "owner", status, profiles, "sa-east-1", "BR", "0.1.0", lastSeen, 4, 8192,
			102400, 100, null, null, null, null, diskFreeMb, null, null, 0, null, null);
	}

	// ------------------------------------------------- §6: o tipo do nó prefere, nunca filtra

	/**
	 * Objeto de mídia vai primeiro para nó de mídia — mesmo que outro tenha mais folga.
	 *
	 * <p>Sem isto, a especialização do §6 não existiria: declarar seis tipos e continuar ordenando
	 * só por folga daria o mesmo comportamento de antes com nomes novos.</p>
	 */
	@Test
	void objetoDeMidiaPrefereNoDeMidia() {
		when(catalog.findById("sob_1")).thenReturn(java.util.Optional.of(mediaObject(NOW.minusSeconds(60))));
		candidates(kindNode("nd_convencional", NodeProfile.NODE_STORAGE, 81920L),
			kindNode("nd_midia", NodeProfile.NODE_STORAGE_MEDIA, 20480L));

		assertThat(service.chooseReplicas("sob_1", 0, NOW)).extracting(NetworkNode::id).containsExactly("nd_midia",
			"nd_convencional");
	}

	/**
	 * Objeto parado há mais que a janela vai para o arquivo. O nó MEDIA está lá e é mais folgado: não
	 * importa — o objeto não é de mídia, é velho.
	 */
	@Test
	void objetoParadoPrefereNoDeArquivo() {
		when(catalog.findById("sob_1")).thenReturn(java.util.Optional.of(textObject(NOW.minusSeconds(40L * 86400))));
		candidates(kindNode("nd_midia", NodeProfile.NODE_STORAGE_MEDIA, 81920L),
			kindNode("nd_arquivo", NodeProfile.NODE_STORAGE_ARCHIVE, 20480L));

		assertThat(service.chooseReplicas("sob_1", 0, NOW)).extracting(NetworkNode::id)
			.containsExactly("nd_arquivo", "nd_midia");
	}

	/**
	 * O ponto do "preferência, nunca filtro": sem nó do tipo preferido, o convencional recebe. Se o
	 * tipo fosse exigência, este objeto ficaria sem réplica por falta de um perfil declarativo — e a
	 * durabilidade medida no §9 cairia por topologia, não por falta de espaço.
	 */
	@Test
	void semNoDoTipoPreferidoQualquerStorageRecebe() {
		when(catalog.findById("sob_1")).thenReturn(java.util.Optional.of(mediaObject(NOW.minusSeconds(60))));
		candidates(kindNode("nd_convencional", NodeProfile.NODE_STORAGE, 20480L),
			kindNode("nd_arquivo", NodeProfile.NODE_STORAGE_ARCHIVE, 81920L));

		assertThat(service.chooseReplicas("sob_1", 0, NOW)).extracting(NetworkNode::id)
			.containsExactly("nd_arquivo", "nd_convencional");
	}

	/**
	 * Nó com vários tipos casa com qualquer um deles (o brief §6 diz que um servidor pode ter
	 * múltiplos perfis). Ordenar por UM perfil escolhido ao acaso faria o mesmo servidor perder a
	 * preferência só por ter declarado mais.
	 */
	@Test
	void noComVariosTiposCasaComQualquerUmDeles() {
		when(catalog.findById("sob_1")).thenReturn(java.util.Optional.of(mediaObject(NOW.minusSeconds(60))));
		NetworkNode multi = nodeWith("nd_multi", NodeStatus.ACTIVE,
			Set.of(NodeProfile.NODE_STORAGE_ARCHIVE, NodeProfile.NODE_STORAGE_MEDIA), 20480L, NOW);
		candidates(multi, kindNode("nd_convencional", NodeProfile.NODE_STORAGE, 81920L));

		assertThat(service.chooseReplicas("sob_1", 0, NOW)).extracting(NetworkNode::id)
			.containsExactly("nd_multi", "nd_convencional");
	}

	/**
	 * Objeto sem entrada no catálogo: classe DEFAULT, preferência pelo convencional. A colocação não
	 * pode quebrar por objeto desconhecido — ela é chamada com o id antes de qualquer leitura.
	 */
	@Test
	void objetoForaDoCatalogoNaoQuebraAColocacao() {
		when(catalog.findById("sob_1")).thenReturn(java.util.Optional.empty());
		candidates(kindNode("nd_midia", NodeProfile.NODE_STORAGE_MEDIA, 81920L),
			kindNode("nd_convencional", NodeProfile.NODE_STORAGE, 20480L));

		assertThat(service.chooseReplicas("sob_1", 0, NOW)).extracting(NetworkNode::id)
			.containsExactly("nd_convencional", "nd_midia");
	}

	private static NetworkNode kindNode(String id, NodeProfile profile, long declaredMb) {
		return nodeWith(id, NodeStatus.ACTIVE, Set.of(profile), 409600L, NOW);
	}

	private static com.socialcoisa.rede.network.domain.StorageObject mediaObject(java.time.Instant createdAt) {
		return new com.socialcoisa.rede.network.domain.StorageObject("sob_1", "fotos", "a.jpg", "image/jpeg", 10L,
			"abc", com.socialcoisa.rede.network.domain.StorageObjectState.STORED, "owner", createdAt, createdAt, null);
	}

	private static com.socialcoisa.rede.network.domain.StorageObject textObject(java.time.Instant createdAt) {
		return new com.socialcoisa.rede.network.domain.StorageObject("sob_1", "docs", "a.txt", "text/plain", 10L,
			"abc", com.socialcoisa.rede.network.domain.StorageObjectState.STORED, "owner", createdAt, createdAt, null);
	}
}
