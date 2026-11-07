package com.socialcoisa.rede.groups.application;

import org.springframework.stereotype.Component;

import com.socialcoisa.rede.calls.application.TopicLookup;
import com.socialcoisa.rede.groups.application.port.out.GroupContentRepository;
import com.socialcoisa.rede.groups.domain.GroupTopic;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * O tópico do grupo, para quem está fora dos grupos (as chamadas). A regra é a mesma da
 * publicação em tópico: o id precisa existir E ser daquele grupo.
 */
@Component
public class GroupTopicLookup implements TopicLookup {

	private final GroupContentRepository content;

	public GroupTopicLookup(GroupContentRepository content) {
		this.content = content;
	}

	@Override
	public GroupTopic requireIn(String groupId, String topicId) {
		return content.findTopic(topicId)
			.filter(topic -> topic.groupId().equals(groupId))
			.orElseThrow(() -> new ApiException(404, "TOPIC_NOT_FOUND", "Topico nao encontrado neste grupo."));
	}
}
