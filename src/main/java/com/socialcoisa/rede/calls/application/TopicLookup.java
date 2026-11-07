package com.socialcoisa.rede.calls.application;

import com.socialcoisa.rede.groups.domain.GroupTopic;

/** O tópico de um grupo, lido de fora dos grupos sem arrastar o serviço inteiro para as chamadas. */
public interface TopicLookup {

	/**
	 * O tópico, somente quando ele pertence a este grupo. O par é conferido no servidor: o id
	 * sozinho não basta, porque um tópico de outro grupo daria acesso a uma sala que não é dele.
	 *
	 * @throws com.socialcoisa.rede.shared.web.ApiException 404 quando o tópico não existe neste grupo
	 */
	GroupTopic requireIn(String groupId, String topicId);
}
