package com.socialcoisa.rede.feed.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.socialcoisa.rede.feed.domain.Post;
import com.socialcoisa.rede.feed.domain.PostId;

/** Compara o destino e o conteudo efetivamente validado, nunca metadados ignorados na escrita. */
final class ContextoDuplicacaoFeed {

	private ContextoDuplicacaoFeed() {
	}

	static String[] publicacao(Post post, String artigo, List<String> alternativas, Set<String> midiasVisiveis) {
		var campos = new ArrayList<>(List.of("publish", post.type().name(), post.authorIdentity().name(),
			Objects.toString(post.communityId(), ""), Objects.toString(post.channelId(), ""),
			Objects.toString(post.pageHtml(), ""), Objects.toString(artigo, ""),
			Integer.toString(alternativas.size())));
		campos.addAll(alternativas);
		campos.addAll(midiasRelevantes(post.mediaIds(), midiasVisiveis));
		return campos.toArray(String[]::new);
	}

	static String[] comentario(PostId postId, String respostaA, List<String> midias, Set<String> midiasVisiveis) {
		var campos = new ArrayList<>(List.of("comment", postId.value(), Objects.toString(respostaA, "")));
		campos.addAll(midiasRelevantes(midias, midiasVisiveis));
		return campos.toArray(String[]::new);
	}

	private static List<String> midiasRelevantes(List<String> midias, Set<String> visiveis) {
		// As colecoes persistidas nao guardam posicao; anexos removidos nao mudam o conteudo visivel.
		return midias.stream().filter(visiveis::contains).sorted().toList();
	}
}
