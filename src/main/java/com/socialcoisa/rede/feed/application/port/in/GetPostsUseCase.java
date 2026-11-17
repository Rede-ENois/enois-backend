package com.socialcoisa.rede.feed.application.port.in;

import java.util.List;

import com.socialcoisa.rede.feed.application.PostView;
import com.socialcoisa.rede.feed.domain.UserId;

public interface GetPostsUseCase {

	/**
	 * PostViews enriquecidos na ordem pedida. Post apagado, vencido, privado ou invisível para
	 * quem vê simplesmente sai da lista — coleções (murais, salvos) exibem só o que dá para ver.
	 */
	List<PostView> getPosts(List<String> postIds, UserId viewerId);
}
