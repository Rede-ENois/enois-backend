package com.socialcoisa.rede.network.adapters.in.web;

import java.io.StringWriter;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

/**
 * Escritor de XML do gateway S3 sobre o {@link XMLStreamWriter} do JDK.
 *
 * <p><b>Por que não concatenar strings:</b> o gateway monta documentos com valores que vêm do cliente
 * (nome de bucket, prefixo, chave de objeto). Concatenar isso na resposta é a definição de XSS/XML
 * injection — e foi o que o CodeQL apontou no PR #914 ({@code java/xss}). A versão anterior tentava
 * escapar num helper próprio; o analisador não reconhece sanitizadores caseiros, e com razão: um
 * escape pode ser esquecido na próxima chamada.</p>
 *
 * <p>Aqui quem escapa é a <b>API do JDK</b>: {@code writeCharacters} e {@code writeAttribute} são
 * obrigados a produzir caracteres válidos, por contrato. Não existe caminho que escreva dado cru —
 * os métodos públicos abaixo são os únicos que aceitam dado, e todos delegam ao writer.</p>
 *
 * <p>Cada instância escreve mais de um documento (por exemplo, um {@code ListBucketResult} e, se
 * preciso, o conteúdo de cada objeto), então a classe é usada também fora do MVC.</p>
 */
final class S3Xml {

	/** O namespace do S3: os SDKs o usam para desserializar; sem ele, o documento "não é S3". */
	private static final String S3_NAMESPACE = "http://s3.amazonaws.com/doc/2006-03-01/";

	private final StringWriter out = new StringWriter(512);
	private final XMLStreamWriter writer;
	private boolean started;

	S3Xml() {
		try {
			this.writer = XMLOutputFactory.newFactory().createXMLStreamWriter(out);
			this.writer.writeStartDocument("UTF-8", "1.0");
		}
		catch (XMLStreamException falha) {
			throw new IllegalStateException("XMLStreamWriter indisponivel", falha);
		}
	}

	/** Abre o elemento raiz com o namespace do S3 (literal, não dado). */
	S3Xml root(String name) {
		try {
			writer.writeStartElement(name);
			writer.writeDefaultNamespace(S3_NAMESPACE);
		}
		catch (XMLStreamException falha) {
			throw broken(falha);
		}
		return this;
	}

	/** Abre um elemento. {@code name} é literal do gateway, jamais dado do cliente. */
	S3Xml open(String name) {
		try {
			writer.writeStartElement(name);
		}
		catch (XMLStreamException falha) {
			throw broken(falha);
		}
		return this;
	}

	S3Xml close(String name) {
		try {
			writer.writeEndElement();
		}
		catch (XMLStreamException falha) {
			throw broken(falha);
		}
		return this;
	}

	/**
	 * Atributo com prefixo e namespace: {@code prefix:name="value"}. O valor é dado e a API o escapa;
	 * o prefixo é declarado no próprio elemento, como o S3 faz no {@code <Grantee xsi:type=...>}.
	 */
	S3Xml attribute(String prefix, String namespace, String name, String value) {
		try {
			writer.writeNamespace(prefix, namespace);
			writer.writeAttribute(prefix, namespace, name, value);
		}
		catch (XMLStreamException falha) {
			throw broken(falha);
		}
		return this;
	}

	/** Elemento com texto: o valor é escrito como caracteres, e a API o escapa. */
	S3Xml element(String name, String value) {
		open(name);
		return text(value).close(name);
	}

	S3Xml element(String name, long value) {
		return element(name, Long.toString(value));
	}

	S3Xml element(String name, boolean value) {
		return element(name, value ? "true" : "false");
	}

	/** Texto de dado. {@code null} vira elemento vazio, nunca a string "null". */
	S3Xml text(String value) {
		if (value == null) {
			return this;
		}
		try {
			writer.writeCharacters(value);
		}
		catch (XMLStreamException falha) {
			throw broken(falha);
		}
		return this;
	}

	@Override
	public String toString() {
		if (!started) {
			started = true;
			try {
				writer.writeEndDocument();
				writer.flush();
				writer.close();
			}
			catch (XMLStreamException falha) {
				throw broken(falha);
			}
		}
		return out.toString();
	}

	private static IllegalStateException broken(XMLStreamException falha) {
		// Não é erro do cliente: a montagem do documento é do gateway. 500 explícito e logado é
		// melhor que um corpo truncado que o SDK leria como "lista vazia".
		return new IllegalStateException("falha ao montar XML do gateway S3", falha);
	}
}