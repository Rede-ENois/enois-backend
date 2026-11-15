package com.socialcoisa.rede.importresume;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import com.socialcoisa.rede.importlinkedin.LinkedinImportService;
import com.socialcoisa.rede.jobs.application.port.out.JobRepository;
import com.socialcoisa.rede.jobs.domain.Job;
import com.socialcoisa.rede.jobs.domain.JobApplicationField;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * O PDF gerado de verdade (via PDFBox) e o prefill lendo a vaga pelo repositorio: prova o
 * caminho ponta a ponta do modulo sem subir servidor nem tocar nos fluxos do LinkedIn.
 */
@ExtendWith(MockitoExtension.class)
class ResumePrefillServiceTests {

	@Mock
	private JobRepository jobs;

	private ResumeImportService resumes;
	private ResumePrefillService prefill;

	@org.junit.jupiter.api.BeforeEach
	void setup() {
		resumes = new ResumeImportService(new LinkedinImportService());
		prefill = new ResumePrefillService(jobs, resumes);
	}

	private MockMultipartFile pdf(String... lines) throws Exception {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (PDDocument document = new PDDocument()) {
			PDPage page = new PDPage();
			document.addPage(page);
			try (PDPageContentStream content = new PDPageContentStream(document, page)) {
				content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
				content.beginText();
				content.newLineAtOffset(50, 750);
				for (String line : lines) {
					content.showText(line);
					content.newLineAtOffset(0, -15);
				}
				content.endText();
			}
			document.save(bytes);
		}
		return new MockMultipartFile("file", "curriculo.pdf", "application/pdf", bytes.toByteArray());
	}

	@Test
	void pdfViraRetrato() throws Exception {
		var file = pdf("Ana Souza", "Engenheira de Software", "", "Sobre",
			"Engenheira com 5 anos de experiência em Java.", "", "Habilidades", "Java, Spring");
		var preview = resumes.parse(file);
		assertThat(preview.profile().name()).isEqualTo("Ana Souza");
		assertThat(preview.skills()).contains("Java");
	}

	@Test
	void pdfEscaneadoSemTextoRecusado() throws Exception {
		// PDF valido mas sem nenhuma linha de texto: parece o escaneado (imagem).
		var file = pdf();
		assertThatThrownBy(() -> resumes.parse(file))
			.isInstanceOf(ApiException.class)
			.hasMessageContaining("texto legivel");
	}

	@Test
	void prefillLeVagaEPreeencheCampos() {
		Job job = Job.create("job-1", "Dev Java", "Enois", null, "Remoto", null, null, null, null, null, null,
			null, "Vaga backend Java.", List.of("Java"),
			List.of(JobApplicationField.create("f1", "job-1", "LinkedIn", JobApplicationField.FieldType.LINK,
				false, 0)),
			"autor-1", null, Instant.now(), null);
		when(jobs.findById("job-1")).thenReturn(Optional.of(job));
		String resume = "Ana Souza\nana@mail.com\nhttps://www.linkedin.com/in/ana\n"
			+ "texto corrido suficiente para passar do minimo de trinta caracteres aqui.";
		var view = prefill.prefill("candidata-1", "job-1", resume);
		assertThat(view.jobId()).isEqualTo("job-1");
		assertThat(view.jobTitle()).isEqualTo("Dev Java");
		assertThat(view.fields()).hasSize(1);
		assertThat(view.fields().get(0).suggestedValue()).contains("linkedin.com");
		assertThat(view.suggestedMessage()).contains("ana@mail.com");
	}

	@Test
	void prefillRecusaVagaInexistente() {
		when(jobs.findById("nope")).thenReturn(Optional.empty());
		var thrown = org.assertj.core.api.Assertions.catchThrowable(
			() -> prefill.prefill("candidata-1", "nope", "texto ".repeat(10)));
		assertThat(thrown).isInstanceOf(ApiException.class);
		assertThat(((ApiException) thrown).code()).isEqualTo("JOB_NOT_FOUND");
	}
}
