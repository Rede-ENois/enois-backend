package com.socialcoisa.rede.linkedin.application;

import org.springframework.stereotype.Component;

import com.socialcoisa.rede.feed.application.port.out.PostRepository;
import com.socialcoisa.rede.feed.domain.AuthorIdentity;
import com.socialcoisa.rede.feed.domain.Post;
import com.socialcoisa.rede.feed.domain.PostId;
import com.socialcoisa.rede.feed.domain.PostStatus;
import com.socialcoisa.rede.feed.domain.PostType;
import com.socialcoisa.rede.shared.outbox.OutboxEventHandler;

/**
 * PostPublished -> LinkedIn, quando a pessoa ligou a sincronizacao.
 *
 * So publicacoes da IDENTIDADE PROFISSIONAL vao para o LinkedIn: o LinkedIn e vitrine de trabalho,
 * e a vida social no Enois nao vaza para la (regra de produto da separacao das interfaces).
 *
 * Roda no worker do outbox (depois do commit da publicacao): o post ja existe, e o envio ao
 * LinkedIn nao segura a requisicao de quem publicou. MOMENT fica de fora de proposito — ele some
 * em 24h no Enois e viraria um post permanente no LinkedIn.
 */
@Component
class LinkedInShareOutboxEventHandler implements OutboxEventHandler {

	private final PostRepository posts;
	private final LinkedInIntegrationService linkedin;

	LinkedInShareOutboxEventHandler(PostRepository posts, LinkedInIntegrationService linkedin) {
		this.posts = posts;
		this.linkedin = linkedin;
	}

	@Override
	public void handle(String eventType, String aggregateId, String payload) {
		if (!"PostPublished".equals(eventType)) {
			return;
		}
		Post post = posts.findById(new PostId(aggregateId)).orElse(null);
		if (post == null || post.status() != PostStatus.ACTIVE || post.type() == PostType.MOMENT
				|| post.authorIdentity() != AuthorIdentity.CORPORATE) {
			return;
		}
		linkedin.sharePublishedPost(post.authorId().value(), post.id().value(), post.content(),
			"Publicação no Énóis");
	}
}
