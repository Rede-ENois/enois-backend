package com.socialcoisa.rede.network.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * O mapa perfil → tipo de tarefa é a autorização real: capacidade computacional não dá permissão
 * (doc 04 §1). Estes testes fixam o mapa para que um tipo novo não seja autorizado "de carona" e
 * um perfil novo não receba tarefa sem alguém ter decidido isso.
 */
@Tag("critico")
class TaskTypeAuthorizationTests {

	@Test
	void cadaTipoExigeExatamenteUmPerfilENodeLocalCobreTodos() {
		for (TaskType type : TaskType.values()) {
			assertTrue(type.allowedFor(Set.of(type.requiredProfile())),
				() -> type + " deve ser liberado pelo próprio perfil");
			assertTrue(type.allowedFor(Set.of(NodeProfile.NODE_LOCAL)),
				() -> type + " deve ser liberado por NODE_LOCAL (prioridade de infraestrutura)");
		}
	}

	@Test
	void perfilSemRelacaoNaoLiberaTipoNenhum() {
		// Um nó só de borda não recebe transcode; um só de mídia não recebe cache nem requisição de API.
		assertFalse(TaskType.MEDIA_TRANSCODE.allowedFor(Set.of(NodeProfile.NODE_EDGE)));
		assertFalse(TaskType.EDGE_CACHE.allowedFor(Set.of(NodeProfile.NODE_MEDIA)));
		assertFalse(TaskType.READ_QUERY.allowedFor(Set.of(NodeProfile.NODE_WORKER)));
		assertFalse(TaskType.API_REQUEST.allowedFor(Set.of(NodeProfile.NODE_READ_REPLICA)));
	}

	@Test
	void perfilVazioOuNuloNaoAutorizaNada() {
		for (TaskType type : TaskType.values()) {
			assertFalse(type.allowedFor(Set.of()), () -> type + " autorizou com perfil vazio");
			assertFalse(type.allowedFor(null), () -> type + " autorizou com perfil nulo");
		}
	}

	/**
	 * A lista que vai na config assinada tem de ser a MESMA do predicado de despacho — senão a
	 * config diz que o nó pode uma coisa e o scheduler decide outra. A regra esperada é reescrita
	 * aqui a partir de `requiredProfile()`: comparar `allowedTypesFor` com `allowedFor` seria
	 * tautologia (as duas implementações do mesmo arquivo concordariam mesmo erradas).
	 */
	@Test
	void listaDaConfigAssinadaConcordaComOPredicadoDeDespacho() {
		for (NodeProfile profile : NodeProfile.values()) {
			Set<NodeProfile> profiles = Set.of(profile);
			List<TaskType> daConfig = TaskType.allowedTypesFor(profiles);
			// A regra esperada, reescrita do contrato: o perfil cobre o tipo quando é o exigido;
			// NODE_LOCAL cobre todos; e os tipos de armazenamento são cobertos por QUALQUER perfil
			// de storage (brief §6 — ver o teste abaixo).
			List<TaskType> esperado = Arrays.stream(TaskType.values())
				.filter(type -> type.requiredProfile() == profile || profile == NodeProfile.NODE_LOCAL
					|| (type.isStorage() && profile.storageKind() != null))
				.toList();
			assertEquals(esperado, daConfig, () -> "divergiu para " + profile);
		}
	}

	/**
	 * O Storage entrou na fase 1 (docs/rede-distribuida/12-storage-fase-1.md) com perfil e tipos
	 * PRÓPRIOS. A invariante da fase 0 continua valendo para todo perfil que <b>não</b> é de
	 * armazenamento: {@code OBJECT_PUT}/{@code OBJECT_GET} nunca são autorizados por ele. Se
	 * alguém acrescentar {@code OBJECT_PUT} ao {@code NODE_MEDIA}"porque é conveniente", o nó de
	 * mídia passaria a guardar bytes de terceiros sem o participante ter consentido com disco.
	 *
	 * <p>A partir do brief §6 o inverso mudou de forma, não de fundo: os seis tipos de storage
	 * ({@code NODE_STORAGE} e os especializados) autorizam armazenamento — a especialização é
	 * aditiva, e é a colocação que prefere um tipo a outro. O que este teste guarda agora é que
	 * todo perfil com {@code storageKind()} autoriza exatamente os tipos de armazenamento e nada
	 * mais, e que quem não tem {@code storageKind()} não autoriza nenhum dos dois.</p>
	 */
	@Test
	void armazenamentoSoEhAutorizadoPorPerfilDeStorage() {
		Set<String> armazenamento = Set.of("OBJECT_PUT", "OBJECT_GET");

		for (NodeProfile profile : NodeProfile.values()) {
			if (profile == NodeProfile.NODE_LOCAL) {
				// Infraestrutura do Énóis executa tudo: é prioridade de escalonamento, não permissão
				// nova — a distinção que o teste protege não se aplica a ela.
				continue;
			}
			Set<String> autoriza = TaskType.allowedTypesFor(Set.of(profile)).stream()
				.map(Enum::name)
				.filter(armazenamento::contains)
				.collect(Collectors.toSet());
			if (profile.storageKind() != null) {
				assertEquals(armazenamento, autoriza,
					profile + " é do Storage e tem de autorizar armazenamento (brief §6)");
			}
			else {
				assertTrue(autoriza.isEmpty(), () -> profile + " não é do Storage e autorizou " + autoriza);
			}
		}

		// E o Storage não é curinga: autoriza armazenamento e nada mais, para que um nó
		// contribuído com disco não comece a executar transcode ou leitura de dados.
		for (NodeProfile profile : NodeProfile.values()) {
			if (profile.storageKind() == null || profile == NodeProfile.NODE_LOCAL) {
				continue;
			}
			Set<String> todos = TaskType.allowedTypesFor(Set.of(profile)).stream().map(Enum::name)
				.collect(Collectors.toSet());
			assertEquals(armazenamento, todos, "o perfil " + profile + " autoriza exatamente armazenamento");
		}
	}
}
