## Plano: IP real + stacktrace no painel (sem vazar dado sensível)

**Respostas do usuário:** B7 Cloud (Alloy → Tempo/Loki/Grafana, não CloudWatch); Matt já corrigiu o recebimento do IP; quer stacktrace no stdout (Loki) E no painel; painel exibe IP inteiro.

### Backend (branch `feat/monitor-ip-stacktrace` → PR para `qa`)

1. **Verificar o fix do Matt antes de tocar** (`git log` no `ClientIpResolver.java` + teste manual em QA: bater com XFF forjado e conferir o `clientIp` no painel). Se o IP real já chega certo, o `ForwardedHeaderFilter` pode ser redundante — não empilhar dois mecanismos. Só adicionar `server.forward-headers-strategy=framework` + bean `ForwardedHeaderFilter` se o teste mostrar que o Spring ainda vê o IP do Traefik.
2. **`GlobalExceptionHandler`: logar stacktrace nos 500 sem vazar PII** — nos handlers `handleApiException` (≥500) e `handleUnexpected`, trocar para `LOGGER.error("api_error ...", exception)` passando o throwable (o ECS/Alloy já carrega para o Loki; resposta da API continua genérica). Sanitizar antes: `safe()` já cobre e-mail/token/senha na mensagem; stacktrace de framework não contém corpo de requisição. Adicionar teste assertando que a resposta continua `INTERNAL_ERROR` sem detalhe.
3. **Persistir stacktrace sanitizado no `system_events.metadata`** — `recordException` passa a gravar ` Throwables.getStackTraceAsString` truncado (ex. 4000 chars) na coluna `metadata` (já existe, nunca lida). Reuso do `safe()` + corte de linhas de pacote do framework? Não — stacktrace cru é mais útil; o `safe()` já redacta e-mail/token. `EventView` ganha campo `metadata`, query do `events()` passa a selecionar, controller idem.
4. **Exibir no painel** — front `monitoring-section.tsx`: coluna/botão expandir mostra `clientIp` (inteiro, painel é só moderador) + stacktrace com `<pre>` + copiar. Tipo `MonitoringEvent` ganha `metadata: string | null`.
5. **Testes**: `ClientIpResolver`/`ForwardedHeader` (XFF forjado vs real), `GlobalExceptionHandler` (resposta genérica + evento com stacktrace), `MonitoringService.safe()` (e-mail/token no stacktrace saem redactados).

### Riscos/controles
- `metadata` com stacktrace aumenta a linha do banco: retenção 7d + teto 250k já existem; stacktrace truncado em 4000 chars.
- AGENTS.md: IP é dado pessoal — painel continua `requireModerator`; commit/PR neutros.
- Sem migração de banco (coluna `metadata` já existe).

**Validação:** testes do escopo + `tsc`; em QA, forçar um 500 e conferir stacktrace no painel e IP real na linha.