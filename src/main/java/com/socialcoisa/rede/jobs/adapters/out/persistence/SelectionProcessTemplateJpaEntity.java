package com.socialcoisa.rede.jobs.adapters.out.persistence;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.socialcoisa.rede.jobs.domain.SelectionProcessTemplate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Modelo de processo seletivo. As etapas moram em JSON (não em tabela filha): o modelo se aplica
 * por CÓPIA para selection_stages da vaga, então não há relação a manter.
 */
@Entity
@Table(name = "selection_process_templates")
class SelectionProcessTemplateJpaEntity {

	@Id
	private String id;

	@Column(name = "owner_id", nullable = false)
	private String ownerId;

	@Column(nullable = false, length = 120)
	private String name;

	@Column(name = "stages_json", nullable = false, columnDefinition = "mediumtext")
	private String stagesJson;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected SelectionProcessTemplateJpaEntity() {
	}

	static SelectionProcessTemplateJpaEntity fromDomain(SelectionProcessTemplate template) {
		SelectionProcessTemplateJpaEntity e = new SelectionProcessTemplateJpaEntity();
		e.id = template.id();
		e.ownerId = template.ownerId();
		e.name = template.name();
		e.stagesJson = SelectionProcessTemplateJson.write(template.stageNames());
		e.createdAt = template.createdAt();
		e.updatedAt = template.updatedAt();
		return e;
	}

	SelectionProcessTemplate toDomain() {
		return SelectionProcessTemplate.restore(id, ownerId, name, SelectionProcessTemplateJson.read(stagesJson),
			createdAt, updatedAt);
	}

	/** Lista de nomes em JSON como texto curto — leitura e escrita triviais, sem tipagem frágil. */
	private static final class SelectionProcessTemplateJson {

		private SelectionProcessTemplateJson() {
		}

		static String write(List<String> stageNames) {
			StringBuilder json = new StringBuilder("[");
			for (int i = 0; i < stageNames.size(); i++) {
				if (i > 0) {
					json.append(',');
				}
				json.append('"').append(stageNames.get(i).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
			}
			return json.append(']').toString();
		}

		static List<String> read(String json) {
			// Formato escrito pelo próprio write acima: ["a","b",...]. Nomes escapados só de
			// barra e aspas, que são os que o domínio aceita; qualquer outra coisa é corrupta.
			List<String> names = new ArrayList<>();
			String body = json.trim();
			if (!body.startsWith("[") || !body.endsWith("]")) {
				throw new IllegalStateException("stages_json corrupto");
			}
			body = body.substring(1, body.length() - 1);
			int i = 0;
			while (i < body.length()) {
				if (body.charAt(i) != '"') {
					i++;
					continue;
				}
				StringBuilder value = new StringBuilder();
				i++;
				while (body.charAt(i) != '"') {
					if (body.charAt(i) == '\\') {
						i++;
					}
					value.append(body.charAt(i));
					i++;
				}
				i++;
				names.add(value.toString());
			}
			return names;
		}
	}
}
