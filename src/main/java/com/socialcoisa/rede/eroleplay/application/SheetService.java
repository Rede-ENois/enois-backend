package com.socialcoisa.rede.eroleplay.application;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.eroleplay.application.port.out.EroleplayRepository;
import com.socialcoisa.rede.eroleplay.domain.Campaign;
import com.socialcoisa.rede.eroleplay.domain.CampaignCapability;
import com.socialcoisa.rede.eroleplay.domain.CampaignRole;
import com.socialcoisa.rede.eroleplay.domain.CharacterSheet;
import com.socialcoisa.rede.eroleplay.domain.DerivedAttribute;
import com.socialcoisa.rede.eroleplay.domain.SheetAudience;
import com.socialcoisa.rede.eroleplay.domain.SheetEntry;
import com.socialcoisa.rede.eroleplay.domain.SheetTemplate;
import com.socialcoisa.rede.eroleplay.domain.SheetVisibility;
import com.socialcoisa.rede.eroleplay.domain.SystemAdapter;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * A ficha: o modelo que o mestre define e o preenchimento que o jogador faz.
 *
 * <p>Três regras moram aqui e nenhuma delas é da tela:</p>
 *
 * <ul>
 * <li><b>O modelo vem do sistema.</b> O mestre não inventa campo: ele ajusta a abertura dos campos
 * que o adapter do sistema declara. Sem adapter, sem ficha.</li>
 * <li><b>A validação é do servidor.</b> Quem grava valor é {@link CharacterSheet#fill}, que
 * confronta cada valor com o campo correspondente. O cliente não decide tipo, limite nem
 * obrigatoriedade.</li>
 * <li><b>A leitura é filtrada por público.</b> Quem lê a ficha alheia recebe só os campos que a
 * visibilidade daquele campo deixa aparecer — e o público é calculado aqui, de quem é a ficha e
 * qual é o papel de quem pede, nunca aceito do corpo da requisição.</li>
 * </ul>
 *
 * <p>A ficha de outra pessoa não é um recurso que o jogador alcança, nem para ler um campo só: ler
 * a ficha alheia é exceção do mestre. O que o grupo enxerga de cada personagem é o QUADRO da
 * campanha ({@link #board}), montado exclusivamente com os campos que cada jogador abriu à
 * campanha — e um quadro não revela quem tem ficha fechada.</p>
 *
 * <p>Trocar o sistema de regras com ficha preenchida é recusado: ficha de D20 não vira ficha de
 * D100 sozinha, e regravar os valores noutro sistema seria inventar dado que o jogador não
 * escreveu.</p>
 */
@Service
public class SheetService {

	private final EroleplayRepository repository;
	private final CampaignService campaigns;
	private final GameSystemRegistry systems;
	private final Clock clock;

	public SheetService(EroleplayRepository repository, CampaignService campaigns, GameSystemRegistry systems,
			Clock clock) {
		this.repository = repository;
		this.campaigns = campaigns;
		this.systems = systems;
		this.clock = clock;
	}

	/** O modelo da campanha. Quem participa pode ler: o jogador precisa saber o que vai preencher. */
	@Transactional(readOnly = true)
	public SheetTemplate template(String campaignId, String actorId) {
		campaigns.requirePermission(campaignId, actorId, CampaignCapability.VIEW_CAMPAIGN);
		return requireTemplate(campaignId);
	}

	/**
	 * Define o modelo a partir dos atributos do sistema da campanha.
	 *
	 * <p>Só o mestre define, e só uma vez: mudar o conjunto de campos depois que alguém preencheu
	 * apagaria valor sem avisar. A partir daí, o que o mestre ajusta é a ABERTURA de cada campo
	 * ({@link #updateTemplate}), que não perde dado nenhum.</p>
	 */
	@Transactional
	public SheetTemplate defineTemplate(String campaignId, String actorId,
			Map<String, SheetVisibility> visibilities) {
		campaigns.requirePermission(campaignId, actorId, CampaignCapability.MANAGE_SHEET_TEMPLATE);
		Campaign campaign = requireCampaign(campaignId);
		if (repository.existsSheetTemplate(campaignId)) {
			throw new ApiException(409, "SHEET_TEMPLATE_EXISTS",
				"Esta campanha ja tem modelo de ficha. Ajuste a visibilidade dos campos.");
		}
		SystemAdapter system = systems.require(campaign.gameSystem());
		SheetTemplate template = SheetTemplate.from(newId("roleplay_sheet_template"), campaignId, system,
			visibilities, clock.instant());
		repository.saveSheetTemplate(template);
		return template;
	}

	/** Ajusta a abertura máxima de campos do modelo. Não apaga valor: o que já foi preenchido fica. */
	@Transactional
	public SheetTemplate updateTemplate(String campaignId, String actorId,
			Map<String, SheetVisibility> visibilities) {
		campaigns.requirePermission(campaignId, actorId, CampaignCapability.MANAGE_SHEET_TEMPLATE);
		SheetTemplate template = requireTemplate(campaignId);
		if (visibilities == null || visibilities.isEmpty()) {
			throw new ApiException(400, "SHEET_FIELDS_REQUIRED", "Informe a visibilidade de pelo menos um campo.");
		}
		SheetTemplate updated = template.withVisibilities(visibilities, clock.instant());
		repository.saveSheetTemplate(updated);
		// O máximo pode ter fechado: o que já estava preenchido acima dele encolhe junto, senão a
		// regra nova valeria só a partir da próxima gravação.
		tightenSheets(campaignId, updated);
		return updated;
	}

	/** Remove o modelo. Só quando ninguém preencheu — apagar o modelo deixaria ficha órfã. */
	@Transactional
	public void deleteTemplate(String campaignId, String actorId) {
		campaigns.requirePermission(campaignId, actorId, CampaignCapability.MANAGE_SHEET_TEMPLATE);
		requireTemplate(campaignId);
		if (repository.countSheets(campaignId) > 0) {
			throw new ApiException(409, "SHEET_TEMPLATE_IN_USE",
				"Ha fichas preenchidas nesta campanha; o modelo nao pode ser removido.");
		}
		repository.deleteSheetTemplate(campaignId);
	}

	/**
	 * Preenche a própria ficha. Substitui a anterior por inteiro; o corpo traz todos os campos.
	 *
	 * <p>Devolve a leitura do DONO, e não a ficha crua: o caminho de resposta é o mesmo da leitura,
	 * então não há como a gravação devolver um campo que a leitura esconderia.</p>
	 */
	@Transactional
	public SheetReading fill(String campaignId, String userId, String name, List<SheetEntry> entries) {
		campaigns.requirePermission(campaignId, userId, CampaignCapability.POST_IN_CAMPAIGN);
		SheetTemplate template = requireTemplate(campaignId);
		CharacterSheet filled = CharacterSheet.fill(newId("roleplay_sheet"), campaignId, userId, name, entries,
			template, clock.instant());
		repository.saveSheet(filled);
		return new SheetReading(filled, SheetAudience.OWNER);
	}

	/**
	 * A ficha de outra pessoa, no recorte do mestre.
	 *
	 * <p>Este é o ponto em que IDOR e enumeração seriam possíveis: a rota recebe um {@code userId}
	 * e devolve dado de terceiro. O que impede o vazamento não é a tela esconder o botão — é o
	 * papel ser conferido aqui e cada campo passar pela régua de {@link SheetAudience}. Jogador não
	 * alcança ficha de jogador: recebe 403, e não 404, porque o que ele pediu é proibido, não
	 * inexistente.</p>
	 */
	@Transactional(readOnly = true)
	public SheetReading read(String campaignId, String targetUserId, String actorId) {
		CampaignRole role = campaigns.requirePermission(campaignId, actorId, CampaignCapability.VIEW_CAMPAIGN);
		if (role != CampaignRole.GAME_MASTER && !actorId.equals(targetUserId)) {
			throw new ApiException(403, "FORBIDDEN", "Voce nao le a ficha de outra pessoa. O grupo ve o quadro.");
		}
		SheetAudience audience = audienceFor(role, targetUserId, actorId);
		CharacterSheet sheet = repository.findSheet(campaignId, targetUserId)
			.orElseThrow(() -> new ApiException(404, "SHEET_NOT_FOUND", "Ficha nao encontrada."));
		return new SheetReading(sheet, audience);
	}

	/**
	 * As fichas que a leitura do mestre alcança, cada uma no recorte dele.
	 *
	 * <p>Jogador não lista ficha alheia: listar já revelaria quem preencheu, e isso é informação de
	 * jogo. O que ele vê do grupo é o quadro.</p>
	 */
	@Transactional(readOnly = true)
	public List<SheetReading> listForMaster(String campaignId, String actorId) {
		CampaignRole role = campaigns.requirePermission(campaignId, actorId, CampaignCapability.VIEW_CAMPAIGN);
		if (role != CampaignRole.GAME_MASTER) {
			throw new ApiException(403, "FORBIDDEN", "So o mestre le as fichas do grupo.");
		}
		return repository.listSheets(campaignId).stream()
			.map(sheet -> new SheetReading(sheet, SheetAudience.GAME_MASTER))
			.toList();
	}

	/**
	 * O quadro da campanha: o que TODO participante enxerga de cada personagem.
	 *
	 * <p>Só entram os campos que o dono abriu à campanha. Uma ficha sem nenhum campo aberto ainda
	 * aparece com o nome do personagem, porque o nome é o que a mesa usa para se referir a ele — e
	 * uma ficha que sumisse da lista diria que ela existe e tem segredo.</p>
	 */
	@Transactional(readOnly = true)
	public List<SheetReading> board(String campaignId, String actorId) {
		campaigns.requirePermission(campaignId, actorId, CampaignCapability.VIEW_CAMPAIGN);
		return repository.listSheets(campaignId).stream()
			.map(sheet -> new SheetReading(sheet, SheetAudience.MEMBER))
			.toList();
	}

	/** O público de uma leitura: dono, mestre ou outro participante. Derivado do papel, nunca do corpo. */
	public SheetAudience audienceFor(CampaignRole role, String targetUserId, String actorId) {
		if (targetUserId != null && targetUserId.equals(actorId)) {
			return SheetAudience.OWNER;
		}
		return role == CampaignRole.GAME_MASTER ? SheetAudience.GAME_MASTER : SheetAudience.MEMBER;
	}

	/** Os derivados que o sistema da campanha sabe calcular (vazio quando ele não declara nenhum). */
	@Transactional(readOnly = true)
	public List<DerivedAttribute> derivedAttributes(String campaignId) {
		Campaign campaign = requireCampaign(campaignId);
		Optional<SystemAdapter> system = systems.find(campaign.gameSystem());
		return system.map(SystemAdapter::derivedAttributes).orElse(List.of());
	}

	/**
	 * O que uma leitura devolve: a ficha e o recorte de quem pediu.
	 *
	 * <p>Andam juntos de propósito — não existe caminho que devolva a ficha sem saber para quem,
	 * então não existe leitura que esqueça de filtrar.</p>
	 */
	public record SheetReading(CharacterSheet sheet, SheetAudience audience) {

		public List<SheetEntry> entries() {
			return sheet.visibleTo(audience);
		}

		/** Os derivados visíveis: só os cuja origem o público também enxerga. */
		public Map<String, String> derived(List<DerivedAttribute> derived) {
			Map<String, String> computed = sheet.derivedValues(derived);
			Map<String, String> visible = new LinkedHashMap<>();
			computed.forEach((key, value) -> {
				String source = derived.stream()
					.filter(attribute -> attribute.key().equals(key))
					.map(DerivedAttribute::sourceKey)
					.findFirst()
					.orElse(null);
				if (source != null && audience.sees(sheet.visibilityOf(source))) {
					visible.put(key, value);
				}
			});
			return visible;
		}
	}

	/**
	 * Fecha as visibilidades que passaram do novo máximo do modelo.
	 *
	 * <p>A regra nova vale para o que já existe, não só para a próxima gravação: se o mestre fecha
	 * um campo, ele fecha agora. Sem isso, a ficha continuaria aberta até alguém reeditar.</p>
	 */
	private void tightenSheets(String campaignId, SheetTemplate template) {
		for (CharacterSheet sheet : repository.listSheets(campaignId)) {
			List<SheetEntry> adjusted = new ArrayList<>(sheet.all().size());
			boolean changed = false;
			for (SheetEntry entry : sheet.all()) {
				var field = template.byKey().get(entry.key());
				if (field == null || field.maxVisibility().reaches(entry.visibility())) {
					adjusted.add(entry);
					continue;
				}
				adjusted.add(entry.withVisibility(field.maxVisibility()));
				changed = true;
			}
			if (changed) {
				repository.saveSheet(CharacterSheet.restore(sheet.id(), sheet.campaignId(), sheet.userId(),
					sheet.name(), adjusted, sheet.createdAt(), clock.instant()));
			}
		}
	}

	private Campaign requireCampaign(String campaignId) {
		return repository.findCampaignById(campaignId)
			.orElseThrow(() -> new ApiException(404, "CAMPAIGN_NOT_FOUND", "Campanha nao encontrada."));
	}

	private SheetTemplate requireTemplate(String campaignId) {
		return repository.findSheetTemplate(campaignId)
			.orElseThrow(() -> new ApiException(409, "SHEET_TEMPLATE_MISSING",
				"Esta campanha ainda nao tem modelo de ficha."));
	}

	private static String newId(String prefix) {
		return prefix + "_" + UUID.randomUUID().toString().replace("-", "");
	}
}