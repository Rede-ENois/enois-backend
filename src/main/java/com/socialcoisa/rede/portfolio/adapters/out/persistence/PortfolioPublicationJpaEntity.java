package com.socialcoisa.rede.portfolio.adapters.out.persistence;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.socialcoisa.rede.portfolio.domain.PortfolioBlock;
import com.socialcoisa.rede.portfolio.domain.PortfolioBlockType;
import com.socialcoisa.rede.portfolio.domain.PortfolioPublication;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Entity
@Table(name = "portfolio_publications")
class PortfolioPublicationJpaEntity {

	/** As peças da versão vão como JSON: a linha é um retrato imutável, não uma coleção editável. */
	static ObjectMapper blocksMapper = new ObjectMapper();

	@Id
	@Column(name = "id", nullable = false, length = 40)
	private String id;

	@Column(name = "user_id", nullable = false)
	private String userId;

	@Column(name = "version", nullable = false)
	private int version;

	@Column(columnDefinition = "longtext")
	private String html;

	@Column(columnDefinition = "longtext")
	private String blocks;

	@Column(name = "published_at", nullable = false)
	private Instant publishedAt;

	protected PortfolioPublicationJpaEntity() {
	}

	static PortfolioPublicationJpaEntity fromDomain(PortfolioPublication publication) {
		PortfolioPublicationJpaEntity entity = new PortfolioPublicationJpaEntity();
		entity.id = publication.id();
		entity.userId = publication.userId();
		entity.version = publication.version();
		entity.html = publication.html();
		entity.blocks = serialize(publication.blocks());
		entity.publishedAt = publication.publishedAt();
		return entity;
	}

	PortfolioPublication toDomain() {
		return new PortfolioPublication(id, userId, version, html, parse(blocks), publishedAt);
	}

	private static String serialize(List<PortfolioBlock> blocks) {
		ArrayNode array = blocksMapper.createArrayNode();
		for (PortfolioBlock block : blocks) {
			ObjectNode node = blocksMapper.createObjectNode()
				.put("position", block.position())
				.put("type", block.type().name());
			if (block.title() != null) {
				node.put("title", block.title());
			}
			if (block.text() != null) {
				node.put("text", block.text());
			}
			if (block.projectId() != null) {
				node.put("projectId", block.projectId());
			}
			if (block.mediaId() != null) {
				node.put("mediaId", block.mediaId());
			}
			if (block.linkUrl() != null) {
				node.put("linkUrl", block.linkUrl());
			}
			array.add(node);
		}
		return blocksMapper.writeValueAsString(array);
	}

	private static List<PortfolioBlock> parse(String json) {
		if (json == null || json.isBlank()) {
			return List.of();
		}
		List<PortfolioBlock> blocks = new ArrayList<>();
		for (var node : blocksMapper.readTree(json)) {
			PortfolioBlockType type;
			try {
				type = PortfolioBlockType.valueOf(node.path("type").asText(""));
			}
			catch (IllegalArgumentException unknown) {
				// Retrato antigo com um tipo que esta versão não conhece: fica de fora da leitura
				// pública em vez de derrubar o portfólio inteiro.
				continue;
			}
			blocks.add(new PortfolioBlock(null, null, node.path("position").asInt(),
				type, text(node, "title"), text(node, "text"), text(node, "projectId"), text(node, "mediaId"),
				text(node, "linkUrl"), null, null, null));
		}
		return blocks;
	}

	private static String text(tools.jackson.databind.JsonNode node, String field) {
		return node.hasNonNull(field) ? node.get(field).asText() : null;
	}
}
