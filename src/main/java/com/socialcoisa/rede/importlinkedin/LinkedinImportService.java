package com.socialcoisa.rede.importlinkedin;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Importador do ARQUIVO DE DADOS do LinkedIn (Configurações → "Obter uma cópia dos seus
 * dados" → ZIP). O LinkedIn exige aprovação de parceiro para entregar histórico via API,
 * mas a exportação por arquivo é um direito do próprio membro — então o usuário sobe o ZIP,
 * o énois parseia e DEVOLVE o retrato para revisão; nada é salvo automaticamente.
 *
 * Formatos encontrados no arquivo: CSVs com BOM, colunas em inglês (Profile.csv,
 * Positions.csv, Education.csv, Certifications.csv, Projects.csv) e datas em "YYYY-MM"
 * ou "MMM YYYY" (pt/en) — o parser aceita as duas.
 */
@Service
public class LinkedinImportService {

	private static final int MAX_ZIP_BYTES = 5 * 1024 * 1024;
	/**
	 * Decompression budget: a small ZIP can expand to hundreds of MB. Only the CSVs the importer
	 * uses are ever inflated; each is streamed against these caps (bytes actually read, never the
	 * sizes the archive declares) and the read stops as soon as one is crossed.
	 */
	private static final int MAX_ENTRIES = 50;
	private static final long MAX_ENTRY_BYTES = 2L * 1024 * 1024;
	/** Expansion ratio cap, only checked past {@link #RATIO_FLOOR_BYTES} so tiny exports never trip it. */
	private static final long MAX_EXPANSION_RATIO = 100;
	private static final long RATIO_FLOOR_BYTES = 1024L * 1024;
	private static final long DEFAULT_MAX_TOTAL_BYTES = 8L * 1024 * 1024;
	/** The CSVs read below; every other entry (photos, messages, other data) is never inflated. */
	private static final Set<String> USED_CSVS = Set.of("Positions.csv", "Profile.csv", "Education.csv",
		"Skills.csv", "Certifications.csv", "Courses.csv", "Languages.csv", "Projects.csv", "Publications.csv",
		"Volunteering.csv", "Honors & Awards.csv", "Connections.csv");
	/** Teto de contatos por importação (o arquivo real costuma ter centenas, não milhares). */
	private static final int MAX_CONTACTS = 5000;
	/** Teto da coluna do nome (linkedin_contact_invites.contact_name) e do e-mail consultado. */
	private static final int MAX_NAME_LENGTH = 200;
	private static final int MAX_EMAIL_LENGTH = 254;

	private final long maxTotalBytes;

	public LinkedinImportService() {
		this(DEFAULT_MAX_TOTAL_BYTES);
	}

	@Autowired
	public LinkedinImportService(
			@Value("${rede.import.linkedin.max-uncompressed-bytes:8388608}") long maxTotalBytes) {
		this.maxTotalBytes = maxTotalBytes;
	}

	// ===== contatos (Connections.csv) =====

	/**
	 * Lê os contatos do arquivo de dados (Connections.csv dentro do ZIP) ou de um CSV avulso.
	 * A API de conexões do LinkedIn exige o escopo r_1st_connections, restrito a apps aprovados;
	 * o arquivo exportado é o caminho sancionado e o membro é dono dos próprios dados.
	 */
	public List<ParsedContact> parseContacts(MultipartFile file) {
		if (file == null || file.isEmpty()) {
			throw new ApiException(400, "FILE_REQUIRED", "Envie o arquivo .zip ou .csv do LinkedIn.");
		}
		try {
			// O multipart aceita centenas de MB; materializar em heap antes de conferir o limite
			// transformava alguns POSTs em esgotamento de memoria. O tamanho vem do multipart.
			if (file.getSize() > MAX_ZIP_BYTES) {
				throw new ApiException(400, "FILE_TOO_LARGE",
					"O arquivo passa do limite de " + (MAX_ZIP_BYTES / 1024 / 1024) + " MB.");
			}
			byte[] bytes = file.getBytes();
			if (bytes.length > MAX_ZIP_BYTES) {
				throw new ApiException(400, "FILE_TOO_LARGE",
					"O arquivo passa do limite de " + (MAX_ZIP_BYTES / 1024 / 1024) + " MB.");
			}
			String csv;
			if (bytes.length >= 2 && bytes[0] == 'P' && bytes[1] == 'K') {
				csv = readUsedCsvs(file).get("Connections.csv");
			}
			else {
				csv = new String(bytes, StandardCharsets.UTF_8);
			}
			return contactsFromCsv(csv);
		}
		catch (ApiException failure) {
			throw failure;
		}
		catch (Exception failure) {
			throw new ApiException(400, "INVALID_LINKEDIN_FILE",
				"Não foi possível ler o arquivo do LinkedIn. Use o ZIP de \"Obter uma cópia dos seus dados\".");
		}
	}

	private List<ParsedContact> contactsFromCsv(String csv) {
		if (!StringUtils.hasText(csv)) {
			throw new ApiException(400, "CONNECTIONS_NOT_FOUND",
				"Não encontrei o Connections.csv. Exporte em Configurações → \"Obter uma cópia dos seus dados\".");
		}
		// O export do LinkedIn põe algumas linhas de nota antes do cabeçalho.
		String[] lines = csv.split("R?\\n", -1);
		int header = -1;
		for (int i = 0; i < lines.length; i++) {
			if (lines[i].trim().toLowerCase(java.util.Locale.ROOT).startsWith("first name")) {
				header = i;
				break;
			}
		}
		String body = header < 0 ? csv : String.join("\n", java.util.Arrays.copyOfRange(lines, header, lines.length));
		List<ParsedContact> contacts = new ArrayList<>();
		java.util.Set<String> seen = new java.util.HashSet<>();
		for (Map<String, String> row : rows(body)) {
			String name = (row.getOrDefault("first name", "") + " " + row.getOrDefault("last name", "")).trim();
			if (name.isBlank()) {
				continue;
			}
			// A coluna guarda 200 caracteres; coluna do arquivo é texto livre e não pode derrubar o INSERT.
			if (name.length() > MAX_NAME_LENGTH) {
				name = name.substring(0, MAX_NAME_LENGTH).trim();
			}
			String key = name.toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", " ");
			if (!seen.add(key)) {
				continue;
			}
			String email = row.getOrDefault("email address", "").trim();
			if (email.length() > MAX_EMAIL_LENGTH) {
				email = "";
			}
			contacts.add(new ParsedContact(name, email.isBlank() ? null : email.toLowerCase(java.util.Locale.ROOT)));
			if (contacts.size() >= MAX_CONTACTS) {
				break;
			}
		}
		return contacts;
	}

	public record ParsedContact(String name, String email) {
	}

	// ===== retrato devolvido para revisão no front =====

	public record ExperiencePreview(String title, String company, String location, String description,
			String startDate, String endDate, boolean currentlyHere) {
	}

	public record EducationPreview(String school, String degree, String field, String description,
			String startDate, String endDate, boolean currentlyHere) {
	}

	public record CertificationPreview(String name, String issuer, String description, String issueDate,
			String expirationDate, String credentialUrl) {
	}

	public record ProjectPreview(String title, String description, String url, String startDate,
			String endDate) {
	}

	/** Campos do Profile.csv que o perfil do Énóis tem onde guardar. */
	public record ProfilePreview(String name, String headline, String summary, String location, String industry) {
	}

	public record LinkedinImportPreview(List<ExperiencePreview> experiences,
			List<EducationPreview> education, List<CertificationPreview> certifications,
			List<ProjectPreview> projects,
			/** Headline/sobre/localização/indústria do Profile.csv (aplicados no perfil). */
			ProfilePreview profile,
			/** Skills.csv — entram na lista de habilidades do perfil. */
			List<String> skills,
			/** O que veio no arquivo mas NÃO tem estrutura no Énóis (idiomas, prêmios, cursos…). */
			Map<String, Integer> semEstrutura,
			int found) {
	}

	public LinkedinImportPreview parse(MultipartFile file) {
		if (file == null || file.isEmpty()) {
			throw new ApiException(400, "FILE_REQUIRED", "Envie o arquivo .zip exportado do LinkedIn.");
		}
		if (file.getSize() > MAX_ZIP_BYTES) {
			throw new ApiException(400, "FILE_TOO_LARGE", "O arquivo do LinkedIn deve ter no maximo 5 MB.");
		}
		Map<String, String> csvs = readUsedCsvs(file);
		// O export RÁPIDO ("fast file") só traz Profile.csv + Registration.csv — aceitamos ele com
		// o que tem (perfil), em vez de dar beco sem saída. Recusamos só quando NENHUM CSV é do
		// LinkedIn (arquivo que não é export, imagem etc.). O export completo vem com senha: o
		// ZipInputStream do Java não abre, e a saída é a aba de COLAR TEXTO (mesma análise).
		if (!StringUtils.hasText(csvs.get("Positions.csv")) && !StringUtils.hasText(csvs.get("Profile.csv"))) {
			throw new ApiException(400, "NOT_A_LINKEDIN_EXPORT",
				"Não encontrei os CSVs do LinkedIn dentro do arquivo. Exporte em Configurações → "
					+ "\"Obter uma cópia dos seus dados\" → arquivamento completo.");
		}

		List<ExperiencePreview> experiences = new ArrayList<>();
		for (Map<String, String> row : rows(csvs.getOrDefault("Positions.csv", ""))) {
			// A coluna mudou de nome no export: "Position Title" (antigo) → "Title" (atual). Sem o
			// fallback, TODA experiência importada vinha sem cargo.
			String title = firstFilled(row, "position title", "title");
			String company = row.get("company name");
			if (!StringUtils.hasText(title) && !StringUtils.hasText(company)) {
				continue;
			}
			String start = parseDate(row.get("started on"));
			String end = parseDate(row.get("finished on"));
			experiences.add(new ExperiencePreview(orNull(title), orNull(company), orNull(row.get("location")),
				orNull(row.get("description")), start, end, end == null));
		}

		List<EducationPreview> education = new ArrayList<>();
		for (Map<String, String> row : rows(csvs.getOrDefault("Education.csv", ""))) {
			String school = row.get("school name");
			if (!StringUtils.hasText(school)) {
				continue;
			}
			String start = parseDate(row.get("start date"));
			String end = parseDate(row.get("end date"));
			// Mesmo caso do cargo: "Degree" (antigo) → "Degree Name" (atual).
			education.add(new EducationPreview(school, orNull(firstFilled(row, "degree", "degree name")),
				orNull(firstFilled(row, "field of study", "field name")), orNull(row.get("notes")), start, end,
				end == null));
		}

		List<CertificationPreview> certifications = new ArrayList<>();
		for (Map<String, String> row : rows(csvs.getOrDefault("Certifications.csv", ""))) {
			String name = row.get("name");
			if (!StringUtils.hasText(name)) {
				continue;
			}
			certifications.add(new CertificationPreview(name, orNull(row.get("authority")), null,
				parseDate(firstFilled(row, "time period", "started on")), null, orNull(row.get("url"))));
		}

		List<ProjectPreview> projects = new ArrayList<>();
		for (Map<String, String> row : rows(csvs.getOrDefault("Projects.csv", ""))) {
			String title = row.get("title");
			if (!StringUtils.hasText(title)) {
				continue;
			}
			projects.add(new ProjectPreview(title, orNull(row.get("description")), orNull(row.get("url")),
				parseDate(row.get("started on")), parseDate(row.get("finished on"))));
		}

		// Publicações do LinkedIn têm casa nos PROJETOS do perfil (título, descrição, link, data).
		for (Map<String, String> row : rows(csvs.getOrDefault("Publications.csv", ""))) {
			String title = row.get("title");
			if (!StringUtils.hasText(title)) {
				continue;
			}
			projects.add(new ProjectPreview(title, orNull(row.get("description")), orNull(row.get("url")),
				parseDate(row.get("published on")), null));
		}

		// Profile.csv: só os campos que o perfil do Énóis tem onde guardar.
		ProfilePreview profile = null;
		for (Map<String, String> row : rows(csvs.getOrDefault("Profile.csv", ""))) {
			String headline = orNull(row.get("headline"));
			String summary = orNull(row.get("summary"));
			String location = orNull(row.get("geo location")) != null ? orNull(row.get("geo location"))
				: orNull(row.get("location"));
			String industry = orNull(row.get("industry"));
			String first = orNull(row.get("first name"));
			String last = orNull(row.get("last name"));
			String name = (first == null && last == null) ? null
				: ((first == null ? "" : first) + " " + (last == null ? "" : last)).trim();
			if (headline != null || summary != null || location != null || industry != null || name != null) {
				profile = new ProfilePreview(name, headline, summary, location, industry);
			}
			break;
		}

		// Skills.csv: a coluna é "name" no export atual (o antigo usava "skill name").
		List<String> skills = new ArrayList<>();
		for (Map<String, String> row : rows(csvs.getOrDefault("Skills.csv", ""))) {
			String skill = orNull(row.get("name")) != null ? orNull(row.get("name")) : orNull(row.get("skill name"));
			if (skill != null && !skills.contains(skill)) {
				skills.add(skill);
			}
		}

		// O que veio mas não tem estrutura no Énóis: contamos para a pessoa saber (nada é perdido
		// em silêncio, e conexões/mensagens NÃO entram — são dados de terceiros, LGPD).
		Map<String, Integer> semEstrutura = new LinkedHashMap<>();
		registrarSemEstrutura(semEstrutura, "Idiomas", rows(csvs.getOrDefault("Languages.csv", "")));
		registrarSemEstrutura(semEstrutura, "Prêmios", rows(csvs.getOrDefault("Honors & Awards.csv", "")));
		registrarSemEstrutura(semEstrutura, "Voluntariado", rows(csvs.getOrDefault("Volunteering.csv", "")));
		registrarSemEstrutura(semEstrutura, "Cursos", rows(csvs.getOrDefault("Courses.csv", "")));

		int found = experiences.size() + education.size() + certifications.size() + projects.size()
			+ skills.size() + (profile != null ? 1 : 0);
		return new LinkedinImportPreview(experiences, education, certifications, projects, profile, skills,
			semEstrutura, found);
	}

	private void registrarSemEstrutura(Map<String, Integer> alvo, String rotulo, List<Map<String, String>> linhas) {
		int comConteudo = 0;
		for (Map<String, String> linha : linhas) {
			if (linha.values().stream().anyMatch(StringUtils::hasText)) {
				comConteudo++;
			}
		}
		if (comConteudo > 0) {
			alvo.put(rotulo, comConteudo);
		}
	}

	// ===== CSV (com aspas e vírgula escapada) =====

	private List<Map<String, String>> rows(String csv) {
		List<Map<String, String>> rows = new ArrayList<>();
		if (!StringUtils.hasText(csv)) {
			return rows;
		}
		List<List<String>> records = new ArrayList<>();
		for (String line : csv.split("R?\\n", -1)) {
			if (line.isBlank()) {
				continue;
			}
			records.add(parseCsvLine(line));
		}
		if (records.isEmpty()) {
			return rows;
		}
		List<String> header = records.get(0);
		for (int i = 1; i < records.size(); i++) {
			Map<String, String> row = new LinkedHashMap<>();
			List<String> record = records.get(i);
			for (int c = 0; c < header.size(); c++) {
				row.put(header.get(c).trim().toLowerCase(), c < record.size() ? record.get(c).trim() : "");
			}
			rows.add(row);
		}
		return rows;
	}

	/** Uma linha de CSV com aspas ("" = aspas literal) e vírgulas dentro de campos. */
	private List<String> parseCsvLine(String line) {
		List<String> fields = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		boolean inQuotes = false;
		for (int i = 0; i < line.length(); i++) {
			char c = line.charAt(i);
			if (inQuotes) {
				if (c == '"') {
					if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
						current.append('"');
						i++;
					} else {
						inQuotes = false;
					}
				} else {
					current.append(c);
				}
			} else if (c == '"') {
				inQuotes = true;
			} else if (c == ',') {
				fields.add(current.toString());
				current.setLength(0);
			} else {
				current.append(c);
			}
		}
		fields.add(current.toString());
		return fields;
	}

	/**
	 * Data ISO completa ("YYYY-MM-DD") — que é o que a API do perfil recebe (`LocalDate`).
	 *
	 * O LinkedIn só informa mês/ano nas experiências, então o dia é o 1º: é a data que a pessoa vê
	 * e edita depois. Devolver "2020-01" (como antes) fazia o SAVE da importação falhar com
	 * "requisição inválida" — o JSON não vira `LocalDate`.
	 */
	private String parseDate(String raw) {
		if (!StringUtils.hasText(raw)) {
			return null;
		}
		String value = raw.trim();
		// Data já completa (o export tem alguns "YYYY-MM-DD").
		if (value.matches("\\d{4}-\\d{2}-\\d{2}")) {
			return value;
		}
		// "2020-01" (formato de export antigo): 1º dia do mês.
		if (value.matches("\\d{4}-\\d{2}")) {
			return value + "-01";
		}
		// "Jan 2020" / "jan de 2020" / "janeiro de 2020"
		var matcher = java.util.regex.Pattern
			.compile("(?i)^(\\w{3,12})\\.?\\s*(?:de\\s*)?(\\d{4})$").matcher(value);
		if (matcher.matches()) {
			int month = monthNumber(matcher.group(1));
			if (month > 0) {
				return "%s-%02d-01".formatted(matcher.group(2), month);
			}
		}
		return null;
	}

	/** O primeiro valor preenchido entre as colunas (o LinkedIn renomeia cabeçalhos entre exports). */
	private String firstFilled(Map<String, String> row, String... columns) {
		for (String column : columns) {
			String value = row.get(column);
			if (StringUtils.hasText(value)) {
				return value;
			}
		}
		return null;
	}

	private int monthNumber(String name) {
		String key = name.toLowerCase().replaceAll("[^a-zà-ú]", "");
		Map<String, Integer> months = Map.ofEntries(
			Map.entry("jan", 1), Map.entry("janeiro", 1), Map.entry("january", 1),
			Map.entry("fev", 2), Map.entry("fevereiro", 2), Map.entry("february", 2),
			Map.entry("mar", 3), Map.entry("marco", 3), Map.entry("março", 3), Map.entry("march", 3),
			Map.entry("abr", 4), Map.entry("abril", 4), Map.entry("april", 4),
			Map.entry("mai", 5), Map.entry("maio", 5), Map.entry("may", 5),
			Map.entry("jun", 6), Map.entry("junho", 6), Map.entry("june", 6),
			Map.entry("jul", 7), Map.entry("julho", 7), Map.entry("july", 7),
			Map.entry("ago", 8), Map.entry("agosto", 8), Map.entry("august", 8), Map.entry("aug", 8),
			Map.entry("set", 9), Map.entry("setembro", 9), Map.entry("september", 9), Map.entry("sep", 9),
			Map.entry("out", 10), Map.entry("outubro", 10), Map.entry("october", 10), Map.entry("oct", 10),
			Map.entry("nov", 11), Map.entry("novembro", 11), Map.entry("november", 11),
			Map.entry("dez", 12), Map.entry("dezembro", 12), Map.entry("december", 12), Map.entry("dec", 12));
		return months.getOrDefault(key, 0);
	}

	private String stripBom(String content) {
		return content.startsWith("﻿") ? content.substring(1) : content;
	}

	private String orNull(String value) {
		return StringUtils.hasText(value) ? value.trim() : null;
	}

	// ===== TEXTO COLADO (o perfil copiado do LinkedIn, sem arquivo nenhum) =====
	//
	// A API do LinkedIn só entrega nome/e-mail/foto (histórico é programa de parceiros), e o
	// ZIP da exportação é atrito demais. O caminho sem arquivo: a pessoa abre o perfil dela,
	// copia e cola — o LinkedIn renderiza as seções num formato repetível (título, org, datas,
	// descrição) e este parser extrai pelo MESMO retrato do import de arquivo. Heurístico de
	// propósito: nada é salvo sem a pessoa conferir a prévia no front.

	private static final int MAX_TEXTO_CHARS = 120_000;

	private static final java.util.regex.Pattern FAIXA_MESES = java.util.regex.Pattern
		.compile("(?i)^(\\p{L}{3,12}\\.?\\s*(?:de\\s*)?\\d{4})\\s*[-–—]\\s*(.+)$");
	private static final java.util.regex.Pattern FAIXA_ANOS = java.util.regex.Pattern
		.compile("^(\\d{4})\\s*[-–—]\\s*(\\d{4}|.+)$");
	private static final java.util.regex.Pattern EMITIDO = java.util.regex.Pattern
		.compile("(?i)^(?:emitido em|issued)\\s+(.+)$");

	public LinkedinImportPreview parseTexto(String texto) {
		if (!StringUtils.hasText(texto)) {
			throw new ApiException(400, "TEXT_REQUIRED", "Cole o texto do seu perfil do LinkedIn.");
		}
		if (texto.length() > MAX_TEXTO_CHARS) {
			throw new ApiException(400, "TEXT_TOO_LARGE", "O texto colado é grande demais (limite de 120 mil caracteres).");
		}
		List<String> linhas = java.util.Arrays.stream(texto.replace("\r\n", "\n").split("\n", -1))
			.map(String::trim)
			.toList();

		// Fronteiras das seções: uma linha que (sem acento, minúscula) É um cabeçalho conhecido.
		Map<String, List<Integer>> secoes = new LinkedHashMap<>();
		String atual = "cabecalho";
		for (int i = 0; i < linhas.size(); i++) {
			String nome = secao(linhas.get(i));
			if (nome != null) {
				atual = nome;
				continue;
			}
			secoes.computeIfAbsent(atual, k -> new ArrayList<>()).add(i);
		}

		List<ExperiencePreview> experiences = entradasComDatas(secoes.getOrDefault("experiencia", List.of()), linhas,
			true).stream().map(e -> new ExperiencePreview(e.titulo(), e.org(), e.local(), e.descricao(),
				e.inicio(), e.fim(), e.atual())).toList();
		List<EducationPreview> education = entradasComDatas(secoes.getOrDefault("educacao", List.of()), linhas,
			true).stream().map(e -> new EducationPreview(e.titulo(), e.org(), null, e.descricao(),
				e.inicio(), e.fim(), e.atual())).toList();
		List<ProjectPreview> projects = entradasComDatas(secoes.getOrDefault("projetos", List.of()), linhas,
			false).stream()
			.map(e -> new ProjectPreview(e.titulo(), e.descricao(), null, e.inicio(), e.fim()))
			.toList();
		List<CertificationPreview> certifications = certificacoes(secoes.getOrDefault("certificacoes", List.of()),
			linhas);
		List<String> skills = habilidades(secoes.getOrDefault("habilidades", List.of()), linhas);

		// Perfil: nome/headline são as primeiras linhas do paste; o resumo vem da seção "Sobre".
		String nome = null;
		String headline = null;
		for (int i : secoes.getOrDefault("cabecalho", List.of())) {
			if (linhas.get(i).isBlank()) {
				continue;
			}
			if (nome == null && linhas.get(i).length() <= 60) {
				nome = linhas.get(i);
			} else if (headline == null && linhas.get(i).length() <= 120) {
				headline = linhas.get(i);
			} else {
				break;
			}
		}
		String sobre = textoSecao(secoes.getOrDefault("sobre", List.of()), linhas);
		ProfilePreview profile = nome != null || headline != null || sobre != null
			? new ProfilePreview(nome, headline, sobre, null, null)
			: null;

		Map<String, Integer> semEstrutura = new LinkedHashMap<>();
		contarSecao(semEstrutura, "Idiomas", secoes.getOrDefault("idiomas", List.of()), linhas);
		contarSecao(semEstrutura, "Prêmios", secoes.getOrDefault("premios", List.of()), linhas);
		contarSecao(semEstrutura, "Voluntariado", secoes.getOrDefault("voluntariado", List.of()), linhas);
		contarSecao(semEstrutura, "Cursos", secoes.getOrDefault("cursos", List.of()), linhas);

		int found = experiences.size() + education.size() + certifications.size() + projects.size()
			+ skills.size() + (profile != null ? 1 : 0);
		return new LinkedinImportPreview(experiences, education, certifications, projects, profile, skills,
			semEstrutura, found);
	}

	/** Cabeçalhos de seção do perfil público do LinkedIn (pt e en), sem acento e minúsculo. */
	private String secao(String linha) {
		String s = java.text.Normalizer.normalize(linha.toLowerCase(), java.text.Normalizer.Form.NFD)
			.replaceAll("\\p{M}", "")
			.replaceAll("\\s+", " ")
			.replaceAll(":$", "")
			.trim();
		return switch (s) {
			case "experiencia", "experience", "experiencia profissional" -> "experiencia";
			case "formacao academica", "formacao", "educacao", "education" -> "educacao";
			case "licencas e certificacoes", "certificacoes", "licenses & certifications",
					"licences & certifications", "certifications" -> "certificacoes";
			case "projetos", "projects" -> "projetos";
			case "sobre", "about" -> "sobre";
			case "habilidades", "competencias", "skills" -> "habilidades";
			case "idiomas", "languages" -> "idiomas";
			case "premios", "premios e honras", "honors & awards", "honors and awards", "premios e premiacoes" ->
				"premios";
			case "voluntariado", "volunteering" -> "voluntariado";
			case "cursos", "courses" -> "cursos";
			default -> null;
		};
	}

	private record EntradaDatas(String titulo, String org, String local, String descricao, String inicio,
			String fim, boolean atual) {
	}

	/**
	 * Entradas de uma seção cujo formato é "título, org, datas, descrição…" (experiência e
	 * formação; para projetos `org=false` — lá vai direto "título, datas, descrição"). A linha
	 * de datas é a âncora: o que vem 1–2 linhas antes é título/org, o que vem depois é o resto
	 * até o começo da próxima entrada.
	 */
	private List<EntradaDatas> entradasComDatas(List<Integer> indices, List<String> linhas, boolean comOrg) {
		List<EntradaDatas> entradas = new ArrayList<>();
		if (indices.isEmpty()) {
			return entradas;
		}
		// Âncoras: posição da linha de datas dentro da lista de ÍNDICES da seção.
		record Ancora(int pos, String inicio, String fim, boolean atual) {
		}
		List<Ancora> ancoras = new ArrayList<>();
		for (int p = 0; p < indices.size(); p++) {
			var faixa = faixa(linhas.get(indices.get(p)));
			if (faixa != null) {
				ancoras.add(new Ancora(p, faixa[0], faixa[1], Boolean.parseBoolean(faixa[2])));
			}
		}
		for (int a = 0; a < ancoras.size(); a++) {
			int p = ancoras.get(a).pos();
			// A entrada acaba no TÍTULO da próxima (não na linha de datas dela — senão as duas
			// linhas de título/org do próximo item entrariam como descrição deste).
			int fimProxima = a + 1 < ancoras.size()
				? indices.get(Math.max(0, ancoras.get(a + 1).pos() - (comOrg ? 2 : 1)))
				: Integer.MAX_VALUE;
			String titulo = null;
			String org = null;
			if (p >= 1 && (p >= 2 || !comOrg)) {
				// LinkedIn manda título e org nas linhas ANTES da data (2 para experiência e
				// formação; 1 para projetos). Se falta uma, o que sobrou é só o título.
				int idxTitulo = indices.get(p - (comOrg ? 2 : 1));
				if (p - (comOrg ? 2 : 1) >= 0) {
					titulo = linhaUtil(linhas.get(idxTitulo));
				}
				if (comOrg && p >= 1) {
					org = linhaUtil(linhas.get(indices.get(p - 1)));
				}
			} else if (p >= 1) {
				titulo = linhaUtil(linhas.get(indices.get(p - 1)));
			}
			if (!StringUtils.hasText(titulo)) {
				titulo = p >= 1 ? linhaUtil(linhas.get(indices.get(p - 1))) : null;
				org = null;
			}
			if (!StringUtils.hasText(titulo)) {
				continue; // sem título não há entrada — o texto solto fica de fora (nada é perdido: está no perfil colado)
			}
			String local = null;
			StringBuilder descricao = new StringBuilder();
			for (int i = p + 1; i < indices.size() && indices.get(i) < fimProxima; i++) {
				String linha = linhaUtil(linhas.get(indices.get(i)));
				if (linha == null) {
					continue;
				}
				if (local == null && pareceLocal(linha)) {
					local = linha;
					continue;
				}
				if (descricao.length() > 0) {
					descricao.append("\n");
				}
				descricao.append(linha);
				if (descricao.length() > 3000) {
					break;
				}
			}
			entradas.add(new EntradaDatas(titulo, org, local, StringUtils.hasText(descricao) ? descricao.toString() : null,
				ancoras.get(a).inicio(), ancoras.get(a).fim(), ancoras.get(a).atual()));
		}
		return entradas;
	}

	/** Reconhece "mar de 2021 - o momento · 4 anos", "Jan 2020 - Jun 2022" e "2015 - 2019". */
	private String[] faixa(String linha) {
		if (!StringUtils.hasText(linha)) {
			return null;
		}
		var m = FAIXA_MESES.matcher(linha);
		if (!m.matches() && (m = FAIXA_ANOS.matcher(linha)) != null && !m.matches()) {
			return null;
		}
		String inicio = parseDate(m.group(1).trim());
		if (inicio == null) {
			// Ano solto ("2015 - 2019", comum em formação): janeiro na entrada e dezembro na saída,
			// para a faixa não encolher — em ISO completo, que é o que a API do perfil aceita.
			inicio = m.group(1).trim().matches("\\d{4}") ? m.group(1).trim() + "-01-01" : null;
		}
		if (inicio == null) {
			return null;
		}
		// O fim vem antes do "· duração" ("mar de 2021 - o momento · 4 anos 2 meses").
		String ladoFim = m.group(2).split("·")[0].trim();
		boolean atual = ladoFim.matches("(?i)(o momento|presente|atualmente|present|now|current.*)");
		String fim = atual ? null : parseDate(ladoFim);
		if (!atual && fim == null && ladoFim.matches("\\d{4}")) {
			fim = ladoFim + "-12-01";
		}
		if (!atual && fim == null && ladoFim.matches("(?i)(o momento|presente|present)")) {
			atual = true;
		}
		return new String[] { inicio, fim, String.valueOf(atual) };
	}

	/** "Emitido em mar de 2022 · Sem validade": nome/emissor 1–2 linhas antes, link depois. */
	private List<CertificationPreview> certificacoes(List<Integer> indices, List<String> linhas) {
		List<CertificationPreview> certificados = new ArrayList<>();
		for (int p = 0; p < indices.size(); p++) {
			var m = EMITIDO.matcher(linhas.get(indices.get(p)));
			if (!m.matches()) {
				continue;
			}
			String quando = m.group(1).split("·")[0].trim();
			String nome = p >= 2 ? linhaUtil(linhas.get(indices.get(p - 2))) : null;
			String emissor = p >= 1 ? linhaUtil(linhas.get(indices.get(p - 1))) : null;
			if (!StringUtils.hasText(nome)) {
				nome = emissor;
				emissor = null;
			}
			if (!StringUtils.hasText(nome)) {
				continue;
			}
			String url = null;
			for (int q = p + 1; q < indices.size(); q++) {
				var link = java.util.regex.Pattern.compile("https?://\\S+")
					.matcher(linhas.get(indices.get(q)));
				if (link.find()) {
					url = link.group();
					break;
				}
				if (faixa(linhas.get(indices.get(q))) != null || EMITIDO.matcher(linhas.get(indices.get(q))).matches()) {
					break;
				}
			}
			certificados.add(new CertificationPreview(nome, emissor, null, parseDate(quando), null, url));
		}
		return certificados;
	}

	private List<String> habilidades(List<Integer> indices, List<String> linhas) {
		List<String> skills = new ArrayList<>();
		for (int i : indices) {
			for (String pedaco : linhas.get(i).split("[,;·|]")) {
				String skill = pedaco.trim();
				if (skill.length() >= 2 && skill.length() <= 60 && !skills.contains(skill)) {
					skills.add(skill);
				}
				if (skills.size() >= 50) {
					return skills;
				}
			}
		}
		return skills;
	}

	private String textoSecao(List<Integer> indices, List<String> linhas) {
		StringBuilder texto = new StringBuilder();
		for (int i : indices) {
			String linha = linhaUtil(linhas.get(i));
			if (linha == null) {
				continue;
			}
			if (texto.length() > 0) {
				texto.append("\n");
			}
			texto.append(linha);
			if (texto.length() > 2000) {
				break;
			}
		}
		return texto.length() > 0 ? texto.toString() : null;
	}

	private void contarSecao(Map<String, Integer> alvo, String rotulo, List<Integer> indices, List<String> linhas) {
		int n = 0;
		for (int i : indices) {
			if (StringUtils.hasText(linhas.get(i))) {
				n++;
			}
		}
		if (n > 0) {
			alvo.put(rotulo, n);
		}
	}

	/** Descrições coladas terminam em "…ver mais"/"…more" e as linhas podem ser vazias. */
	private String linhaUtil(String linha) {
		if (!StringUtils.hasText(linha)) {
			return null;
		}
		String s = linha.trim();
		if (s.matches("(?i)(ver mais|ver menos|…more|…see more|see more|mostrar mais)")) {
			return null;
		}
		return s;
	}

	/** "São Paulo, Brasil" e afins: poucas palavras, sem dígitos, com vírgula — nunca descrição. */
	private boolean pareceLocal(String linha) {
		return linha.matches("[\\p{L}\\p{M}' .,-]{2,60}") && !linha.matches(".*\\d.*") && linha.contains(", ");
	}

	/**
	 * Spools the upload (already capped at {@link #MAX_ZIP_BYTES}) to a temp file and opens it by its
	 * central directory, so entries the importer does not use are skipped without being inflated.
	 */
	private Map<String, String> readUsedCsvs(MultipartFile file) {
		Map<String, String> csvs = new LinkedHashMap<>();
		Path spooled = null;
		try {
			spooled = Files.createTempFile("linkedin-import-", ".zip");
			file.transferTo(spooled);
			try (ZipFile zip = new ZipFile(spooled.toFile())) {
				if (zip.size() > MAX_ENTRIES) {
					throw tooLarge();
				}
				long totalCap = Math.min(maxTotalBytes, Math.max(RATIO_FLOOR_BYTES, file.getSize() * MAX_EXPANSION_RATIO));
				long totalRead = 0;
				for (ZipEntry entry : java.util.Collections.list(zip.entries())) {
					String name = entry.getName();
					// O ZIP do LinkedIn guarda os CSVs numa pasta (".../Positions.csv") — casa pelo nome.
					String simple = name.substring(name.lastIndexOf('/') + 1);
					if (entry.isDirectory() || !USED_CSVS.contains(simple) || csvs.containsKey(simple)) {
						continue;
					}
					ByteArrayOutputStream out = new ByteArrayOutputStream();
					try (InputStream in = zip.getInputStream(entry)) {
						totalRead = readBounded(in, out, totalRead, totalCap);
					}
					csvs.put(simple, stripBom(out.toString(StandardCharsets.UTF_8)));
				}
			}
		} catch (IOException | IllegalArgumentException error) {
			throw new ApiException(400, "INVALID_FILE", "Não foi possível ler o arquivo. É um .zip do LinkedIn?");
		} finally {
			if (spooled != null) {
				try {
					Files.deleteIfExists(spooled);
				} catch (IOException ignored) {
					// The OS temp cleaner removes it; the content is the user's own export.
				}
			}
		}
		return csvs;
	}

	/**
	 * Streams one entry into {@code out}, counting the bytes actually inflated, and aborts as soon
	 * as the entry or the running total crosses its cap. Returns the new running total.
	 */
	private static long readBounded(InputStream in, ByteArrayOutputStream out, long totalSoFar, long totalCap)
			throws IOException {
		byte[] buffer = new byte[8192];
		long entryBytes = 0;
		long total = totalSoFar;
		int read;
		while ((read = in.read(buffer)) != -1) {
			entryBytes += read;
			total += read;
			if (entryBytes > MAX_ENTRY_BYTES || total > totalCap) {
				throw tooLarge();
			}
			out.write(buffer, 0, read);
		}
		return total;
	}

	private static ApiException tooLarge() {
		return new ApiException(413, "IMPORT_TOO_LARGE",
			"O conteudo descompactado do ZIP e grande demais para um export do LinkedIn.");
	}
}
