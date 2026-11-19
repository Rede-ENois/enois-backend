# Sanções: decisão administrativa

Escopo da issue #729. As rotas abaixo são as do controller; o gateway público acrescenta `/api`.

## Decisão administrativa direta sobre denúncia

As rotas `POST /admin/reports/{reportId}/penalize`, `/warn` e `/ban` exigem sessão de
administrador validada no servidor. São o caminho direto para uma decisão administrativa,
inclusive emergencial, separado da votação dos moderadores. Não exigem votos e não alteram o
quórum configurado nem concedem esse acesso a moderadores sem privilégio administrativo.

O sucesso retorna `200 application/json` com o objeto `ReportView`, não texto de `record.toString()`:
`id`, `reporterId`, `targetType`, `targetId`, `reason`, `description`, `status`, `createdAt`,
`aiTriage` e `aiTriagedAt`. Datas são ISO-8601; os dois campos de triagem podem ser nulos.
O consumidor `reportsAdminApi` e o parser JSON estrito do cliente aceitam esse objeto diretamente;
não há fallback para texto nem mudança no corpo de entrada. O DTO administrativo não é a consulta
do titular e não deve ser exposto em telas públicas.

| Ação | Efeito existente preservado | Status da denúncia |
| --- | --- | --- |
| `penalize` | Remove o conteúdo denunciado quando suportado pelo módulo e aplica `RESTRICTION` ao autor identificado, se ainda não houver sanção ativa | `AUTO_PENALIZED` |
| `warn` | Avisa o autor, mantém o conteúdo e libera a quarentena de post, quando houver | `APPROVED` |
| `ban` | Remove o conteúdo denunciado, cria `BAN`, revoga sessões e executa a limpeza de conteúdo da conta | `APPROVED` |

`penalize` é restrição, não suspensão temporária nem banimento: impede publicar/comentar,
não revoga sessões por esta ação e não tem data de liberação automática. Recurso e sua revisão
seguem as regras existentes. Uma sanção já ativa é reutilizada sem mudança de tipo, prazo ou
referência de origem; o caso revisado e a notificação continuam registrados.

A penalização humana usa a mesma regra de restrição da triagem, mas grava `reviewedBy` e
`reviewedAt` na denúncia com a pessoa autenticada e o relógio da aplicação. A remoção registra
essa pessoa e o prefixo `HUMAN`, e a notificação descreve revisão humana, não análise automática.
O aprendizado continua marcado `HUMAN`. A votação que alcança quórum chama esse mesmo caso de uso,
com o revisor já fornecido pelo fluxo existente; os critérios de votação não mudam.
Por compatibilidade, o nome legado `AUTO_PENALIZED` permanece também na decisão humana:
**esse status sozinho não prova automação**. Os campos de revisão ficam persistidos, sem ampliação
do `ReportView`. Registros antigos não recebem autoria retroativa inventada.

### Guardas e limites desta integração

- Sem privilégio administrativo, as três rotas recusam a ação com `403`, sem resolver a denúncia
  nem aplicar efeitos. Denúncia inexistente retorna `404 REPORT_NOT_FOUND`.
- Todas exigem denúncia `PENDING`. Repetição sequencial retorna `409 ALREADY_REVIEWED`, sem
  repetir sanção, remoção ou notificação; não é replay de sucesso por `Idempotency-Key`.
- `penalize` e `ban` recusam `COMPANY` com `400 COMPANY_TARGET`: página empresarial tem ação própria,
  sem punição automática de seu dono. Penalização sem autor identificável retorna
  `409 CANNOT_PENALIZE`; banimento sem autor retorna `409 NO_AUTHOR`.
- A remoção compartilhada atua em `POST` e `COMMENT`. Outros alvos dependem dos leitores de autor
  e das capacidades já existentes dos módulos; esta integração não amplia tipos de alvo.
- **Concorrência: limitação preexistente, identificada por inspeção estática.** A proteção contra
  repetição sequencial não comprova execução única em paralelo. Esta integração não modifica a
  estratégia de concorrência existente e não inclui validação concorrente; essa garantia exige
  avaliação específica antes de ser assumida pelo consumidor.
