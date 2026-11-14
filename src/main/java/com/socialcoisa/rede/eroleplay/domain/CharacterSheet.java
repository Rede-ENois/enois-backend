package com.socialcoisa.rede.eroleplay.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.socialcoisa.rede.shared.web.ApiException;

/**
 * A ficha de um personagem numa campanha: o que o jogador escreveu, validado pelo servidor.
 *
 * <p>Duas coisas fazem desta classe o lugar da validação. Primeiro, a ficha é o dado que o jogador
 * mais tem motivo para forjar — um atributo a mais, uma visibilidade a menos. Segundo, a ficha é o
 * dado que o jogador mais tem motivo para esconder. Então as duas regras moram juntas e no
 * servidor: o valor só entra se couber no modelo, e a visibilidade só abre dentro do que o mestre
 * autorizou.</p>
 *
 * <p>Derivado não é gravado. Um número que o sistema calcula é recalculado na leitura a partir dos
 * valores validados — guardar o derivado permitiria gravá-lo errado e a ficha passaria a mentir
 * sobre a própria regra.</p>
 */
public class CharacterSheet {

	private static final int NAME_MAX = 120;

	private final String id;
	private final String campaignId;
	private final String userId;
	private final String name;
	private final Map<String, SheetEntry> entries;
	private final Instant createdAt;
	private final Instant updatedAt;

	private CharacterSheet(String id, String campaignId, String userId, String name, Map<String, SheetEntry> entries,
			Instant createdAt, Instant updatedAt) {
		this.id = id;
		this.campaignId = campaignId;
		this.userId = userId;
		this.name = name;
		this.entries = new LinkedHashMap<>(entries);
		this.createdAt = createdAt;
		this.updatedAt = updatedAt;
	}

	/**
	 * Preenche (ou reescreve) a ficha contra o modelo da campanha.
	 *
	 * <p>O preenchimento é SUBSTITUTIVO: o corpo traz a ficha inteira. Isso evita o problema de
	 * mesclar campo ausente com campo apagado — num formulário de ficha, ausente e apagado querem
	 * dizer a mesma coisa, e dizer que não é ambiguidade é o que faz um campo secreto reaparecer
	 * por acidente.</p>
	 */
	public static CharacterSheet fill(String id, String campaignId, String userId, String name,
			Collection<SheetEntry> rawEntries, SheetTemplate template, Instant now) {
		if (name == null || name.isBlank()) {
			throw new ApiException(400, "SHEET_NAME_REQUIRED", "O nome do personagem e obrigatorio.");
		}
		String finalName = name.trim();
		if (finalName.length() > NAME_MAX) {
			throw new ApiException(400, "SHEET_NAME_REQUIRED", "O nome do personagem deve ter ate 120 caracteres.");
		}
		Map<String, SheetField> fields = template.byKey();
		Map<String, SheetEntry> normalized = new LinkedHashMap<>();
		if (rawEntries != null) {
			for (SheetEntry entry : rawEntries) {
				if (entry == null) {
					continue;
				}
				SheetField field = fields.get(entry.key());
				if (field == null) {
					throw new ApiException(400, "SHEET_FIELD_UNKNOWN",
						"O modelo desta campanha nao tem o campo " + entry.key() + ".");
				}
				if (normalized.containsKey(field.key())) {
					throw new ApiException(400, "SHEET_FIELD_DUPLICATED",
						"O campo " + field.label() + " veio mais de uma vez.");
				}
				SheetVisibility visibility = entry.visibility() == null ? SheetVisibility.PRIVATE
					: entry.visibility();
				if (!field.maxVisibility().reaches(visibility)) {
					throw new ApiException(400, "SHEET_VISIBILITY_TOO_OPEN",
						"O campo " + field.label() + " nao pode ser mais aberto que "
							+ field.maxVisibility().name() + ".");
				}
				String value = normalize(field, entry.value());
				if (value == null) {
					continue;
				}
				normalized.put(field.key(), new SheetEntry(field.key(), value, visibility));
			}
		}
		for (SheetField field : template.fields()) {
			if (field.required() && !normalized.containsKey(field.key())) {
				throw new ApiException(400, "SHEET_FIELD_REQUIRED",
					"O campo " + field.label() + " e obrigatorio nesta campanha.");
			}
		}
		return new CharacterSheet(id, campaignId, userId, finalName, normalized, now, now);
	}

	public static CharacterSheet restore(String id, String campaignId, String userId, String name,
			Collection<SheetEntry> entries, Instant createdAt, Instant updatedAt) {
		Map<String, SheetEntry> byKey = new LinkedHashMap<>();
		if (entries != null) {
			entries.forEach(entry -> byKey.put(entry.key(), entry));
		}
		return new CharacterSheet(id, campaignId, userId, name, byKey, createdAt, updatedAt);
	}

	/** Os valores como o sistema os lê (chave → texto), para o cálculo dos derivados. */
	public Map<String, String> values() {
		Map<String, String> values = new LinkedHashMap<>();
		entries.forEach((key, entry) -> values.put(key, entry.value()));
		return values;
	}

	/**
	 * O que ESTE público pode ler da ficha, campo a campo.
	 *
	 * <p>A regra de leitura é decidida no domínio, não na tela: quem preencheu lê tudo; o mestre lê
	 * o que foi aberto ao mestre ou à campanha; outro participante lê só o que foi aberto à
	 * campanha. Campo fechado não volta nem com a chave: devolvê-la com valor vazio contaria que o
	 * campo existe e que tem conteúdo.</p>
	 */
	public List<SheetEntry> visibleTo(SheetAudience audience) {
		if (audience == null) {
			return List.of();
		}
		return entries.values().stream().filter(entry -> audience.sees(entry.visibility())).toList();
	}

	/** Todos os campos derivados calculáveis a partir dos valores validados, com a origem deles. */
	public Map<String, String> derivedValues(List<DerivedAttribute> derived) {
		Map<String, String> values = values();
		Map<String, String> computed = new LinkedHashMap<>();
		for (DerivedAttribute attribute : derived) {
			String value = attribute.compute(values);
			if (value != null) {
				computed.put(attribute.key(), value);
			}
		}
		return computed;
	}

	/** A visibilidade da origem de um derivado — o derivado nunca aparece mais aberto que ela. */
	public SheetVisibility visibilityOf(String key) {
		SheetEntry entry = entries.get(key);
		return entry == null ? SheetVisibility.PRIVATE : entry.visibility();
	}

	/** O que a leitura devolve para quem preencheu: a ficha inteira. */
	public List<SheetEntry> all() {
		return List.copyOf(entries.values());
	}

	public Optional<SheetEntry> entry(String key) {
		return Optional.ofNullable(entries.get(key));
	}

	public String id() {
		return id;
	}

	public String campaignId() {
		return campaignId;
	}

	public String userId() {
		return userId;
	}

	/** O rótulo do personagem. Dentro da campanha ele é do grupo, como o nome de um participante. */
	public String name() {
		return name;
	}

	public Instant createdAt() {
		return createdAt;
	}

	public Instant updatedAt() {
		return updatedAt;
	}

	/**
	 * Valida e normaliza um valor contra o campo do modelo. {@code null} quando o campo ficou vazio
	 * e não é obrigatório — vazio é ausência, não string vazia, senão um campo em branco contaria
	 * como preenchido.
	 */
	private static String normalize(SheetField field, String raw) {
		String value = raw == null ? null : raw.trim();
		if (value == null || value.isEmpty()) {
			if (field.required()) {
				throw new ApiException(400, "SHEET_FIELD_REQUIRED",
					"O campo " + field.label() + " e obrigatorio nesta campanha.");
			}
			return null;
		}
		if (value.length() > field.lengthLimit()) {
			throw new ApiException(400, "SHEET_VALUE_TOO_LONG",
				"O campo " + field.label() + " deve ter ate " + field.lengthLimit() + " caracteres.");
		}
		return switch (field.type()) {
			case NUMBER -> normalizeNumber(field, value);
			case BOOLEAN -> normalizeBoolean(field, value);
			case CHOICE -> normalizeChoice(field, value);
			case TEXT, LONGTEXT -> normalizeText(field, value);
		};
	}

	private static String normalizeNumber(SheetField field, String value) {
		BigDecimal number;
		try {
			number = new BigDecimal(value.replace(',', '.'));
		}
		catch (NumberFormatException error) {
			throw invalidNumber(field);
		}
		if (number.abs().compareTo(BigDecimal.valueOf(999_999)) > 0 || number.scale() > 4) {
			throw invalidNumber(field);
		}
		return number.stripTrailingZeros().toPlainString();
	}

	private static ApiException invalidNumber(SheetField field) {
		return new ApiException(400, "SHEET_VALUE_INVALID",
			"O campo " + field.label() + " espera um numero.");
	}

	private static String normalizeBoolean(SheetField field, String value) {
		String lowered = value.toLowerCase(java.util.Locale.ROOT);
		if ("true".equals(lowered) || "1".equals(lowered) || "sim".equals(lowered)) {
			return "true";
		}
		if ("false".equals(lowered) || "0".equals(lowered) || "nao".equals(lowered) || "não".equals(lowered)) {
			return "false";
		}
		throw new ApiException(400, "SHEET_VALUE_INVALID",
			"O campo " + field.label() + " espera sim ou nao.");
	}

	private static String normalizeChoice(SheetField field, String value) {
		for (String option : field.options()) {
			if (option.equalsIgnoreCase(value)) {
				return option;
			}
		}
		throw new ApiException(400, "SHEET_VALUE_INVALID",
			"O campo " + field.label() + " nao aceita o valor informado.");
	}

	/** Texto não aceita caractere de controle: a ficha é exibida e vai a log de auditoria. */
	private static String normalizeText(SheetField field, String value) {
		boolean multiline = field.type() == AttributeType.LONGTEXT;
		for (int index = 0; index < value.length(); index++) {
			char character = value.charAt(index);
			boolean allowedLineBreak = multiline && (character == '\n' || character == '\r' || character == '\t');
			if (Character.isISOControl(character) && !allowedLineBreak) {
				throw new ApiException(400, "SHEET_VALUE_INVALID",
					"O campo " + field.label() + " contem caracteres nao permitidos.");
			}
		}
		return value;
	}
}