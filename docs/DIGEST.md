# Resumo semanal e preferências de notificação

O que chega à pessoa **fora do app**: o push no celular e o resumo semanal por e-mail. A caixa de
notificações in-app nunca é filtrada; as preferências só decidem o que sai dela.

## Preferências (`notification_preferences`, V211)

Uma linha por pessoa, criada na primeira mudança. Sem linha = padrão: tudo ligado.

| Campo | O que controla |
|---|---|
| `weeklyDigest` | Resumo semanal por e-mail |
| `pushSocial` | Reações, comentários, respostas, menções, conexões |
| `pushMessages` | Mensagens diretas |
| `pushGroups` | Grupos (inclusive comentários em grupo), blogs, jogos (lançamentos e jogo diário) |
| `pushJobs` | Vagas (mudança de estado da candidatura) |
| `pushSystem` | Suporte, microprojetos, avisos da plataforma |

Segurança, moderação, sanção e recurso (`SEGURANCA`, `MODERACAO`, `SANCAO`, `RECURSO`) **sempre**
viram push e não têm campo.

O mapa `kind → categoria` vive num lugar só: `notifications/domain/NotificationCategory`. Kind novo
entra ali no mesmo commit; o `NotificationCategoryTests` varre o código e falha se algum kind usado
em `NotificationService.create(...)` ficar sem categoria. Kind sem mapa cai em `SYSTEM` (continua
obedecendo a um botão da pessoa).

## Resumo semanal

- **Quando**: `rede.digest.cron` (padrão segunda 09:00) em `rede.digest.zone` (padrão
  `America/Sao_Paulo`), ligado por `rede.digest.enabled`. O agendador só entrega a rodada para uma
  thread própria; a rodada percorre as pessoas em páginas de `rede.digest.batch-size` (padrão 200).
  Uma pessoa com falha não para a rodada. O log só traz contagens (nada de e-mail ou id).
- **Quem**: e-mail verificado, resumo ligado, sem suspensão/banimento ativo, e ainda sem resumo
  nesta semana ISO (`digest_deliveries`, chave `2026-W44`).
- **Conteúdo** (últimos 7 dias):
  - quantas notificações estão sem ler;
  - até 5 destaques de comunidades e grupos de que a pessoa participa, de outras pessoas, ordenados
    por reações + comentários (o número nunca aparece no e-mail). Comunidade: trecho de post público
    (fora de canal, momento e repost). Grupo: só o nome do grupo, nunca o texto (mesma regra de
    privacidade das notificações de grupo);
  - até 3 vagas abertas publicadas na semana com alguma habilidade em comum, em que a pessoa ainda
    não se candidatou;
  - até 3 eventos com presença confirmada nos próximos 7 dias.
  - Bloqueios valem nos dois sentidos, quem a pessoa silenciou (mute) não aparece, e módulos desligados em "O que não quero ver"
    (`COMMUNITIES`, `GROUPS`, `JOBS`, `EVENTS`) ficam de fora.
- **Só envia com conteúdo real**: pelo menos 2 itens além da contagem de não lidas. Semana vazia não
  envia e não grava nada (a próxima semana tenta de novo).
- **No máximo um por semana**: a linha de `digest_deliveries` é reservada antes do envio. Se o SMTP
  falhar, a semana não é reenviada (preferimos perder um resumo a mandar dois).

## Descadastro

Todo resumo traz o link "Não quero mais receber" e o cabeçalho `List-Unsubscribe` apontando para
`GET /api/notifications/digest/unsubscribe?token=...`. O token é o mesmo esquema da newsletter do
blog (AES-GCM com a chave da instalação, base64 url-safe) com prefixo próprio: token alterado ou de
outro uso é recusado (400). O clique desliga `weeklyDigest`, é idempotente e responde uma página
HTML simples.

Descadastro em um clique do cliente de e-mail (RFC 8058): o e-mail também leva
`List-Unsubscribe-Post: List-Unsubscribe=One-Click`, e a mesma URL aceita `POST` (corpo
`List-Unsubscribe=One-Click`, token na query) sem sessão e sem `Origin`, respondendo 200 sem corpo
(400 para token inválido). O cabeçalho sai do `EmailService.sendHtml` sempre que há URL de
descadastro, então a newsletter do blog ganhou o mesmo `POST /blogs/descadastrar`. As duas rotas
estão no catálogo de escritas públicas do `WriteRouteInterceptor` (travado pelo
`AutomatedWritePathsTests`): o token cifrado é a autorização e o único efeito é parar e-mails. Sem `REDE_SECURITY_ENCRYPTION_KEY` nenhum resumo é enviado (não haveria link de saída).

## Configuração

| Propriedade | Variável | Padrão |
|---|---|---|
| `rede.digest.enabled` | `DIGEST_ENABLED` | `true` |
| `rede.digest.cron` | `DIGEST_CRON` | `0 0 9 * * MON` |
| `rede.digest.zone` | `DIGEST_ZONE` | `America/Sao_Paulo` |
| `rede.digest.batch-size` | `DIGEST_BATCH_SIZE` | `200` |
