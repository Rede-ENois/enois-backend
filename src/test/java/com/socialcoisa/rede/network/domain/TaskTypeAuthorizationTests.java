package com.socialcoisa.rede.network.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

/**
 * O mapa perfil → tipo de tarefa é a autorização real: capacidade computacional não dá permissão
 * (doc 04 §1). Estes testes fixam o mapa para que um tipo novo não seja autorizado "de carona" e
 * um perfil novo não receba tarefa sem alguém ter decidido isso.
 */
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
			List<TaskType> esperado = Arrays.stream(TaskType.values())
				.filter(type -> type.requiredProfile() == profile || profile == NodeProfile.NODE_LOCAL)
				.toList();
			assertEquals(esperado, daConfig, () -> "divergiu para " + profile);
		}
	}

	/**
	 * O Storage é a próxima capacidade (docs/rede-distribuida/10-storage-fase-0.md). Antes de a fase
	 * 1 acrescentar o perfil e o tipo, nenhum nó da rede recebe tarefa de armazenamento — nem um nó
	 * com todos os perfis de hoje. Este teste é o que falha se alguém autorizar storage por um
	 * perfil existente em vez de criar o perfil próprio do storage.
	 */
	@Test
	void nenhumPerfilDeHojeAutorizaArmazenamentoDeObjetos() {
		Set<String> tipos = Arrays.stream(TaskType.values()).map(Enum::name).collect(Collectors.toSet());
		assertFalse(tipos.contains("OBJECT_PUT"), "OBJECT_PUT não pode existir antes do perfil NODE_STORAGE");
		assertFalse(tipos.contains("OBJECT_GET"), "OBJECT_GET não pode existir antes do perfil NODE_STORAGE");
		assertFalse(Arrays.stream(NodeProfile.values()).map(Enum::name).toList().contains("NODE_STORAGE"),
			"NODE_STORAGE entra na fase 1, junto do tipo de tarefa — nunca sozinho");
	}
}