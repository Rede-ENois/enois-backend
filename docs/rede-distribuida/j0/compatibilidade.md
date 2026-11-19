# Contrato HTTP e compatibilidade

## Frontend na revisão de referência

| Fluxo | Contrato atual e evidência |
| --- | --- |
| URL e origem | O cliente usa `VITE_API_BACKEND`, com `/api` como padrão. A configuração atual de QA e produção usa um host de API com o prefixo `/api`. O Vite encaminha o caminho completo e indica a origem do cliente no desenvolvimento. [Cliente](https://github.com/Rede-ENois/enois-frontend/blob/3e69d045f8f0be4ce2d4aee71c9b23ebd33ef6af/src/lib/api-client.ts) · [Vite](https://github.com/Rede-ENois/enois-frontend/blob/3e69d045f8f0be4ce2d4aee71c9b23ebd33ef6af/vite.config.ts) · [Build](https://github.com/Rede-ENois/enois-frontend/blob/3e69d045f8f0be4ce2d4aee71c9b23ebd33ef6af/.github/workflows/build-and-push.yml) |
| Login e sessão | `POST /api/auth/login` recebe JSON e pode devolver `200` com sessão ou sinal de senha expirada, ou `202` com desafio. `POST /api/auth/2fa/verify` conclui o desafio. `GET /api/auth/me` valida a sessão. Manter a entrega dos cookies e a forma atual do JSON. [Cliente de autenticação](https://github.com/Rede-ENois/enois-frontend/blob/3e69d045f8f0be4ce2d4aee71c9b23ebd33ef6af/src/lib/api/auth.ts) |
| Renovação e logout | O cliente renova em `POST /api/auth/refresh` com cookie e aceita a troca única de token legado por cookie. O lock de abas tem janela de 15 s por chamada de sessão. Distingue recusa `400/401/403` de indisponibilidade e preserva sessão local durante falha transitória. Logout revoga a sessão e expira cookies. [Cliente](https://github.com/Rede-ENois/enois-frontend/blob/3e69d045f8f0be4ce2d4aee71c9b23ebd33ef6af/src/lib/api-client.ts) |
| Escritas | Fetch inclui credenciais, `X-CSRF-Protection: 1` e chave de idempotência nas chamadas marcadas para replay. Preservar a mesma chave nas retentativas feitas pelo cliente. O gateway deve entregar o resultado da tentativa sem repetir automaticamente uma escrita de resultado incerto. [Cliente](https://github.com/Rede-ENois/enois-frontend/blob/3e69d045f8f0be4ce2d4aee71c9b23ebd33ef6af/src/lib/api-client.ts) |
| Feed | `POST /api/feed/posts` responde com o post criado e `201`. `GET /api/feed/posts/{postId}` pode ser anônimo e aplica visibilidade atual. `GET /api/feed` exige sessão e recebe cursor, limite e filtros. Fica fora da primeira fatia. A tela completa de publicação também depende de rotas de apoio. [Cliente do feed](https://github.com/Rede-ENois/enois-frontend/blob/3e69d045f8f0be4ce2d4aee71c9b23ebd33ef6af/src/lib/api/feed.ts) |
| SSE | `GET /api/conversations/stream` usa `EventSource` com credenciais. Eventos de conversa, notificação e chamada disparam consultas autorizadas. O cliente reconecta e usa polling enquanto o stream está indisponível. [Cliente SSE](https://github.com/Rede-ENois/enois-frontend/blob/3e69d045f8f0be4ce2d4aee71c9b23ebd33ef6af/src/lib/message-stream.ts) |
| OAuth | A UI lê a configuração de autenticação e inicia o fluxo com rotas de provedor. O callback do backend redireciona a origem permitida e pode emitir cookies. Preservar query, método do callback e `Location`. J4 valida navegador e J7 amplia a superfície. [Cliente de autenticação](https://github.com/Rede-ENois/enois-frontend/blob/3e69d045f8f0be4ce2d4aee71c9b23ebd33ef6af/src/lib/api/auth.ts) |

## Regras comuns aos mappings

- **Prefixos.** A URL observada do produto começa em `/api`. Spring serve
  mappings relativos à aplicação e o alias `/v1/...` reescreve para o mesmo
  handler Java. `GET /api/v1` é o índice separado. O gateway da plataforma
  precisa preservar esses caminhos externos por configuração explícita. A
  coluna de caminho é o endereçamento atual, sujeito aos filtros, perfis e
  exposição de cada ingresso.
- **Autorização.** Manter a sessão explícita `X-Session-Token` e o transporte
  por cookies `enois_session`, `enois_refresh` e `enois_2fa_challenge`.
  `SessionCookieFilter` aplica a proteção de escrita por cookie.
  `SecurityWebMvcConfig` registra os guardas de escrita e administração.
  Resolução de sessão obrigatória e resolução tolerante de visualizador
  têm semânticas diferentes. Domínios autorizam dono, visibilidade e
  permissões atuais. S3 tem autenticação e ingresso próprios.
- **Cabeçalhos.** Preservar `Cookie` e ocorrências separadas de `Set-Cookie`,
  `Origin`, `X-Client-Origin`, `X-CSRF-Protection`, `X-Session-Token`,
  `Idempotency-Key`, cabeçalhos de preflight, `Location`, `Retry-After`,
  tipos, condicionais e cache. O acesso é HttpOnly, Secure por padrão e
  SameSite=Lax. Refresh é HttpOnly, Secure por padrão e SameSite=Strict.
  Identidade de proxy, endereços e métricas de risco dependem de cadeia
  confiável e devem conservar a política de aceitação atual.
- **Erros.** `GlobalExceptionHandler` retorna `code/message/timestamp` para
  erros de aplicação e `Retry-After` onde informado. O cliente trata
  `401` de sessão de forma diferente de `403`. S3 mantém XML. O filtro de
  superfície responde `200` vazio para caminhos desconhecidos. Probes
  devem validar o payload e a dependência que cada um afirma medir.
- **Infraestrutura.** A captura H2 inclui Actuator e o despacho de erro do
  Spring. A exposição varia por perfil. O padrão da aplicação seleciona
  health, desliga o índice do Actuator e configura sondas adicionais de
  liveness e readiness. Confirmar portas e rotas habilitadas em staging.
- **Limites.** HTTP comum segue limites e validações de DTO/domínio.
  A configuração multipart do Java é 520 MB por arquivo e 540 MB por
  requisição, com timeout MVC assíncrono de 300 s. No túnel atual, o limite
  é 1 MiB por corpo em cada direção, caminho de até 8.192 caracteres e
  prazo de 5 s. A allowlist atual de `HttpFrames` preserva um subconjunto
  de cabeçalhos. Os casos de sessão/CORS, SSE, OAuth e transferência de
  mídia exigem trabalho em J4/J7 antes de serem anunciados. SSE usa
  `text/event-stream` e `X-Accel-Buffering: no`. Mídia precisa de
  condicionais, cache, download e `Range` com `206/416`, conforme a rota.

## Primeira aplicação candidata e matriz

A proposta para aprovação em J0 usa `enois-backend` na versão de implantação
`0.1.0`, restrita a réplicas confiáveis. É uma identidade lógica para o
primeiro pacote integrado. O Maven atual produz `0.0.1-SNAPSHOT`, que pode
identificar mais de um build. J2 deve atestar a identidade de implantação e
o SHA-256 do jar de forma independente do digest do bundle do agente. J6
atesta o mesmo par nas duas réplicas e na reserva.

| Eixo | Baseline e condição de compatibilidade |
| --- | --- |
| Rotas J6 | Configuração pública de autenticação, login, verificação de segundo fator, refresh, logout, usuário da sessão, publicação de texto e leitura do post pelo ID. Roteamento e autorização dependem de J2/J3/J4/J5. Manter `/health` e `/version` como contratos próprios. Demais rotas aguardam a etapa indicada no manifesto. |
| Sondagem | No Java, `/health` emite JSON `status=UP` e `/version` emite JSON de build. O agente exige hoje `/healthz` e versão em texto bruto. J2 precisa de sonda Java explícita, prontidão dependente dos dados e versão/digest conferidos. A variante operacional de versão que consulta Flyway pertence ao dono de dados. |
| Agente, gateway e CP | Comparar com a revisão da plataforma no [índice J0](README.md). A combinação só é elegível após as adaptações de handoff, probes, atestação e HTTP. O fixture de app M3 continua com seu próprio contrato. |
| API de dados | Definir versão interna antes de implementar J3. Operações novas validam autoridade e o estado atual. Preservar formato, status, escopo e hash das chaves de replay. Sessão e outbox ficam na camada de dados. |
| MariaDB e Flyway | Baseline de fonte até V368. Confirmar a versão aplicada e o histórico em staging. Alterações aditivas e propriedade exclusiva na camada de dados. |
| Rolling upgrade | Primeira fatia usa um único par versão/digest. Liberar pares mistos somente após prova de compatibilidade de API de dados, schema, sessões, replay e intenções pendentes. |
| Reversão | Drenar o novo par antes de anunciar o anterior. Exigir capacidade de usar API e schema correntes e preservar escritas reconhecidas. Se o par anterior for incompatível, pausar escritas afetadas e seguir recuperação com um dono. |

J6 mantém o texto sem mídia como caso de aceitação. A rota de publicação
também aceita variantes de conteúdo e vínculos de comunidade e mídia. Antes
de habilitá-la, validar essas restrições no fluxo interno. J7 fecha a cobertura
do contrato completo. J8 mede latência e erros, saturação/idade das filas,
conexões e espera por pool, atraso dos sinais, integridade e recuperação.
