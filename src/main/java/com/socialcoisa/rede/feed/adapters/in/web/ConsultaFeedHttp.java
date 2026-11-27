package com.socialcoisa.rede.feed.adapters.in.web;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.socialcoisa.rede.feed.application.GetFeedQuery;
import com.socialcoisa.rede.feed.application.TrendingCursor;
import com.socialcoisa.rede.feed.application.port.in.GetFeedUseCase;
import com.socialcoisa.rede.feed.application.port.out.FeedSeenMarkerRepository;
import com.socialcoisa.rede.feed.domain.FeedTab;
import com.socialcoisa.rede.feed.domain.PostType;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;
import com.socialcoisa.rede.shared.web.CursorCodec;

@Component
public class ConsultaFeedHttp {

	private final GetFeedUseCase feed;
	private final CurrentUserResolver usuarios;
	private final CursorCodec cursores;
	private final FeedSeenMarkerRepository visitas;

	ConsultaFeedHttp(GetFeedUseCase feed, CurrentUserResolver usuarios, CursorCodec cursores,
			FeedSeenMarkerRepository visitas) {
		this.feed = feed;
		this.usuarios = usuarios;
		this.cursores = cursores;
		this.visitas = visitas;
	}

	public FeedResponse consultar(String sessao, String cursor, int limite, FeedTab aba, Set<PostType> tipos,
			Boolean resolvido) {
		var usuario = usuarios.resolve(sessao);
		Optional<Instant> anterior = aba == FeedTab.TRENDING ? Optional.empty() : cursores.decode(cursor);
		var pagina = feed.getFeed(new GetFeedQuery(usuario.id(), anterior, limite, aba,
			tipos == null ? Set.of() : tipos, resolvido, aba == FeedTab.TRENDING ? TrendingCursor.decode(cursor) : null));
		boolean incluiMarcador = anterior.isEmpty() && FeedResponse.carriesMarker(aba);
		return FeedResponse.of(pagina, incluiMarcador ? visitas.lastSeenAt(usuario.id()).orElse(null) : null);
	}
}
