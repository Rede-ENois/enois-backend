# Rede Énois — inscrição para servir um nó

Documento do que **existe** no código. Complementa
[`04-identidade-e-autorizacao.md`](04-identidade-e-autorizacao.md) (o protocolo de identidade do nó,
que não muda) e acrescenta o caminho para quem quer oferecer uma máquina **sem já ter conta**.

## 1. O fluxo, em cinco passos

1. **Formulário público** — `POST /network/join/applications`, sem sessão, limitado por IP
   (`rede.security.rate-limit.node-join`). Campos: nome completo, e-mail, RG, CPF, motivo (até
   2.000 caracteres), aceite da **versão vigente** do contrato e perfis desejados. O CPF é
   conferido pelos dígitos verificadores (`Cpf`); pedido duplicado em análise (mesmo e-mail ou
   mesmo CPF) responde 409.
2. **Fila administrativa** — `GET /admin/network/join-requests`. A listagem **mascara** RG e CPF;
   o detalhe (`GET /admin/network/join-requests/{id}`) mostra os dois inteiros, porque a decisão de
   identificar a pessoa depende deles.
3. **Decisão** — `POST /admin/network/join-requests/{id}/decision` com `APPROVE` ou `REJECT`, o
   motivo e os perfis que a máquina pode ter. Aprovar **não** cria nó e **não** devolve chave:
   gera o link de exibição e manda por e-mail. A decisão é condicional por estado — dois admins
   na fila ao mesmo tempo, só o primeiro decide; o outro recebe 409.
4. **Link de uso único** — `POST /network/join/reveal` com o token do e-mail. Consome o uso único e
   emite, **neste momento**, a chave de provisionamento (`npt_…`). Segunda abertura, mesmo em outra
   aba: 409 `NODE_REVEAL_USED`. Link vencido (7 dias): 410 `NODE_REVEAL_EXPIRED`.
5. **Protocolo de sempre** — a chave é consumida pelo enroll existente
   (`POST /network/nodes/enroll`), que cria o nó em `PENDING_APPROVAL`; o servidor decide se aprova
   (`POST /admin/network/nodes/{id}/approve`) e só então emite a API key de operação.

## 2. Decisões que valem por si

**A chave não existe antes de o link ser aberto.** Aprovar registra intenção; o link é o que emite.
Duas consequências: renovar o link (perdeu o e-mail **antes** de abrir) é seguro — não há
credencial antiga para vazar; e depois de exibida, a chave não tem reemissão. É o que o contrato
(cláusula 8) promete por escrito.

**Uso único é condicional no banco.** `consumeReveal` faz `select … for update` e depois um
`update … where reveal_consumed_at is null`. Duas abas concorrentes: uma exibe, a outra vê "usado".
Renovação só vale enquanto não houve uso.

**O contrato mora no código.** `NetworkJoinTerm` guarda o texto e a **versão**; a inscrição grava a
versão aceita. Mudar o texto sem subir a versão apagaria a distinção do que cada pessoa aceitou.

**RG e CPF só para a decisão.** Não aparecem em listagem, não vão para nó nenhum, não são
pesquisáveis por rota pública. A auditoria registra a inscrição e a decisão sem os números.

**Quem responde pelo nó aparece no painel.** `NodeView.responsible` traz `{name, email, source}`:
`source = JOIN_REQUEST` vem da inscrição (nome e e-mail exatamente como escritos no formulário);
`source = ACCOUNT` é a conta que ativou a possibilidade de servir antes desta fase — o caminho
original, que não tem registro próprio de ativação e por isso mostra o texto do cadastro. Nó
provisionado por token administrativo tem conta dona e aparece por ela. Sem nenhum dos dois, o
campo é `null` e o painel mostra o id da conta dona — nunca uma pessoa inventada.

**Conta existente é reconhecida.** Se o e-mail do formulário corresponde a uma conta (`lower(email)`,
sem diferenciar caixa), o enroll faz o nó nascer com dono. O retrato da inscrição fica em
`network_node_responsibles` de qualquer forma.

## 3. O que NÃO está entregue (e por quê)

| item | por quê |
|---|---|
| **verificação de CPF na Receita** | exigiria serviço externo (e custo por consulta). A validação é aritmética: elimina erro de digitação e número inventado, não prova que o CPF existe nem de quem é. A decisão continua humana |
| **verificação de RG** | não existe base nacional consultável pelo app; o campo é declarado e conferido só por formato |
| **tela de front** | o backend entrega as rotas; a página do formulário e a do link (`/rede/entrar/confirmar?token=…`, que o e-mail aponta) são do front |
| **revogação automática por fraude** | a revogação de nó já existe (`/admin/network/nodes/{id}/status`), e é decisão humana, com auditoria |
| **reaproveitar uma inscrição recusada** | recusada, o caminho é um novo pedido; o histórico da decisão permanece |

## 4. Migração

`V324__network_join_requests.sql` — cria `network_join_requests` e `network_node_responsibles`,
acrescenta `owner_name`/`owner_email` em `network_provision_tokens` (nulos nos tokens anteriores, que
continuam funcionando pelo caminho administrativo). Aditiva e idempotente.

## 5. Verificação

| verificação | resultado |
|---|---|
| `CpfTests` | 6 — verificadores, repetição, tamanho, máscara |
| `NetworkJoinServiceTests` | 10 — aprovar não gera chave, uso único, vencimento, renovação, duplicado, decisão dupla |
| `NetworkJoinTests` | 4 — formulário → decisão → link → chave → enroll → responsável no painel; máscara x detalhe; rota pública x admin; termo |
| `node-join-migration-test` | **22** asserções em MariaDB 11.4 real, migrações 2× com dados |

Mutações conferidas: emitir a chave na aprovação derruba `abrirOLinkEmiteAChaveUmaVezESoUmaVez`;
aceitar o segundo uso do link derruba o mesmo; abrir link vencido derruba
`linkVencidoNaoAbreENaoEmiteChave`; trocar `saveAndFlush` por `save` no `create` do nó derruba
`formularioDecisaoELinkEmitemAChaveUmaVezEMostramOResponsavel` (FK do responsável falha).

## 6. Defeitos encontrados no caminho

- **O link não era enviado.** A decisão devolvia "link enviado" e o serviço só gravava o hash. O
  teste de integração pegou (o dublê do e-mail nunca era chamado). Agora `deliverReveal` envia e
  carimba `email_sent_at` só no sucesso; falha de envio não desfaz a decisão, mas fica auditada
  (`NODE_JOIN_LINK_FAILED`) e o painel oferece renovar.
- **`Integer` nulo no reveal virava 500.** Abrir o link sem corpo de declaração derrubava a
  requisição em vez de responder o 409 de link usado.
- **FK do responsável falhava no enroll.** `repository.save` deixava o insert do nó pendente no
  contexto de persistência e o insert do responsável chegava antes; `saveAndFlush` fixa a ordem.
- **Cota do limiter compartilhada.** O link dividia a cota do formulário (3/hora) e barrava quem
  acabou de ser aprovado; agora tem ação própria (`node-reveal`, 10/15min).