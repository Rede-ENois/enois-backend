package com.socialcoisa.rede.eroleplay.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.socialcoisa.rede.shared.web.ApiException;

/**
 * O modelo de ficha da campanha: quais campos existem, como cada um é validado e até onde ele
 * pode ser aberto.
 *
 * <p>O modelo nasce dos atributos que o adapter do sistema declara — o Éroleplay não inventa campo
 * de sistema. A partir daí é do MESTRE: ele escolhe o que é obrigatório, o que dá para tirar do
 * modelo do sistema e qual a abertura máxima de cada campo. Por isso o modelo é uma campanha, não
 * um sistema: duas mesas do mesmo sistema podem querer fichas diferentes.</p>
 *
 * <p>O modelo é o contrato da validação. Uma ficha só é gravada se cada valor dela couber no campo
 * correspondente — a validação é do SERVIDOR, sempre, porque a ficha é o dado que o jogador mais
 * tem interesse em forjar.</p>
 */
public class SheetTemplate {

	private final String id;
	private final String campaignId;
	private final String gameSystem;
	private final List<SheetField> fields;
	private final Instant createdAt;
	private final Instant updatedAt;

	private SheetTemplate(String id, String campaignId, String gameSystem, List<SheetField> fields, Instant createdAt,
			Instant updatedAt) {
		this.id = id;
		this.campaignId = campaignId;
		this.gameSystem = gameSystem;
		this.fields = List.copyOf(fields);
		this.createdAt = createdAt;
		this.updatedAt = updatedAt;
	}

	/**
	 * Monta o modelo a partir dos atributos do sistema.
	 *
	 * <p>Cada campo nasce com a visibilidade padrão da mesa ({@link SheetVisibility#GAME_MASTER}) e
	 * o mestre pode ajustar antes de salvar. Chave repetida é erro do adapter, não do mestre: um
	 * sistema que declara dois campos com a mesma chave não descreve uma ficha possível.</p>
	 */
	public static SheetTemplate from(String id, String campaignId, SystemAdapter system,
			Map<String, SheetVisibility> visibilities, Instant now) {
		if (system == null) {
			throw new ApiException(400, "GAME_SYSTEM_REQUIRED", "O sistema de regras e obrigatorio.");
		}
		List<SheetAttribute> attributes = system.attributes();
		if (attributes == null || attributes.isEmpty()) {
			throw new ApiException(400, "GAME_SYSTEM_WITHOUT_ATTRIBUTES",
				"Este sistema nao declara atributos de ficha.");
		}
		Set<String> seen = new LinkedHashSet<>();
		List<SheetField> fields = new ArrayList<>(attributes.size());
		for (SheetAttribute attribute : attributes) {
			if (attribute == null || attribute.key() == null || attribute.key().isBlank()) {
				throw new ApiException(400, "GAME_SYSTEM_WITHOUT_ATTRIBUTES",
					"Este sistema declara um atributo sem identificador.");
			}
			if (!seen.add(attribute.key())) {
				throw new ApiException(409, "GAME_SYSTEM_ATTRIBUTE_DUPLICATED",
					"Este sistema declara o atributo " + attribute.key() + " mais de uma vez.");
			}
			SheetField field = SheetField.from(attribute);
			SheetVisibility requested = visibilities == null ? null : visibilities.get(field.key());
			fields.add(requested == null ? field : field.withVisibility(requested));
		}
		return new SheetTemplate(id, campaignId, system.id(), fields, now, now);
	}

	public static SheetTemplate restore(String id, String campaignId, String gameSystem, List<SheetField> fields,
			Instant createdAt, Instant updatedAt) {
		return new SheetTemplate(id, campaignId, gameSystem, fields, createdAt, updatedAt);
	}

	/** O modelo com novas visibilidades. Campo desconhecido é erro: o cliente não inventa campo. */
	public SheetTemplate withVisibilities(Map<String, SheetVisibility> visibilities, Instant now) {
		if (visibilities == null || visibilities.isEmpty()) {
			return new SheetTemplate(id, campaignId, gameSystem, fields, createdAt, now);
		}
		Map<String, SheetField> byKey = byKey();
		List<SheetField> updated = new ArrayList<>(fields.size());
		for (SheetField field : fields) {
			SheetVisibility requested = visibilities.get(field.key());
			if (requested == null) {
				updated.add(field);
				continue;
			}
			updated.add(field.withVisibility(requested));
		}
		for (String key : visibilities.keySet()) {
			if (!byKey.containsKey(key)) {
				throw new ApiException(400, "SHEET_FIELD_UNKNOWN",
					"O modelo desta campanha nao tem o campo " + key + ".");
			}
		}
		return new SheetTemplate(id, campaignId, gameSystem, updated, createdAt, now);
	}

	public String id() {
		return id;
	}

	public String campaignId() {
		return campaignId;
	}

	public String gameSystem() {
		return gameSystem;
	}

	public List<SheetField> fields() {
		return fields;
	}

	public Instant createdAt() {
		return createdAt;
	}

	public Instant updatedAt() {
		return updatedAt;
	}

	public Map<String, SheetField> byKey() {
		Map<String, SheetField> map = new LinkedHashMap<>();
		fields.forEach(field -> map.put(field.key(), field));
		return map;
	}
}