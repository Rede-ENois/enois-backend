package com.socialcoisa.rede.network.adapters.in.web;

import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Erro do gateway S3 no formato que o SDK entende.
 *
 * <p>O corpo é {@code <Error><Code>...} com o <b>código da S3</b> ({@code AccessDenied},
 * {@code SignatureDoesNotMatch}, {@code NoSuchKey}), não o código interno do Énóis: o SDK lê o
 * {@code Code} para decidir se tenta de novo e para mostrar a mensagem ao usuário final.</p>
 *
 * <p>O documento é montado pelo {@link S3Xml}, onde todo valor passa pelo escape — o recurso vem do
 * cliente e o CodeQL cobriu este caminho no PR#914.</p>
 */
public class S3ApiException extends ApiException {

	private static final long serialVersionUID = 1L;

	public S3ApiException(int status, String code, String message) {
			super(status, code, message);
	}

	private S3ApiException(int status, String code, String message, String etag) {
			super(status, code, message,etag);	
	}

	@Override
	public S3ApiException withEtag(String value) {
		return new S3ApiException(status(), code(), getMessage(), value);
	}

	/**
	 * O documento XML de erro, do jeito que a S3 manda.
	 *
	 * <p>
	 * <b>Antes:</b> saía sem {@code <RequestId>} — o {@code errorXml} do
	 * controller, que o
	 * preenchia, não era o caminho usado, e nenhuma resposta tinha o cabeçalho. O
	 * SDK imprime o id
	 * na mensagem de erro e o suporte procura a requisição por ele.
	 * </p>
	 *
	 * <p>
	 * <b>Agora:</b> o {@code requestId} do cabeçalho (sorteado pelo
	 * {@link S3RequestIdFilter})
	 * entra no corpo, então as duas metades da mesma resposta dizem o mesmo id.
	 * </p>
	 *
	 * @param requestId o id da requisição; {@code null} sorteia um — o corpo nunca
	 *                  fica sem id.
	 */
	public String toXml(String resource, String requestId) {
		String id = requestId == null || requestId.isBlank()
				? java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase()
				: requestId;
		return new S3Xml().open("Error").element("Code", code()).element("Message", getMessage())
				.element("Resource", resource == null ? "" : resource).element("RequestId", id)
				.close("Error").toString();
	}
}