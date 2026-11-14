# ADR-009 — Modo operacional: acionamento humano com motivo, saída por número

**Status:** aceita (Fase 4)
**Data:** 2026-11-11

## Contexto

O [06 — Contingência](../rede-distribuida/06-contingencia.md) §1 promete uma tabela de cinco modos e
diz que "todo nó recebe o modo vigente no heartbeat" e que cada modo muda o que a rede faz. O
código, até a Fase 3, tinha os cinco modos **só como constantes do enum**:

- nenhuma rota acionava `DEGRADED`, `EMERGENCY` ou `RECOVERY` — só o cálculo automático escrevia o
  modo, e `evaluate()` recusava tocar em tudo que não fosse `NORMAL`/`HIGH_LOAD`;
- nenhum leitor: a resposta do heartbeat não levava o modo, o scheduler não consultava o modo, e
  `nextFor` entregava tarefa igual em qualquer estado;
- portanto "tarefas assíncronas adiadas" em `DEGRADED` era narrativa. Nenhum nó teria como saber, e
  nenhum despacho teria parado.

A Fase 4 exige "modos operacionais com critérios medidos" e "transição registrada em auditoria com o
número que a motivou". Faltava decidir duas coisas que a tabela do doc não resolve: **quem** pode
entrar em `DEGRADED`/`EMERGENCY` (o Control Plane não mede dependência externa) e **como** se sai de
`RECOVERY` (por número, ou esperando alguém lembrar de desligar o modo).

## Decisão

**Cinco modos, duas origens de transição, e um efeito declarado sobre o despacho e sobre o nó.**

1. **`NORMAL` e `HIGH_LOAD` são exclusivamente medidos.** Nenhum caminho manual leva a eles. Um
   admin "forçando `NORMAL`" com a fila acima da capacidade teria a decisão desfeita na varredura
   seguinte — o banco diria `NORMAL` por um ciclo e voltaria a `HIGH_LOAD` sem que ninguém tivesse
   agido. Botão que não sustenta o que promete é pior que botão ausente.
2. **`DEGRADED`, `EMERGENCY` e `RECOVERY` são declarados por admin, com motivo obrigatório.**
   `POST /admin/network/mode` recebe `{mode, reason}`, valida que o modo é acionável à mão e grava a
   transição com ator, modo anterior e motivo em `audit_logs` (`NETWORK_MODE_<NOVO>`,
   `target_id = 'global'`). Motivo em branco é 400: "ativei `EMERGENCY` para ver o que acontece" sem
   registro é exatamente o que a auditoria existe para impedir. O motivo é texto humano e entra em
   metadata JSON — é escapado, e há teste com aspas no meio (`S3 responde "503"`) provando que o
   metadata continua parseável.
3. **A automação nunca desfaz um modo de incidente.** `evaluate()` devolve o modo atual intacto
   quando ele é `DEGRADED` ou `EMERGENCY`. Automação encerrando um incidente que o humano abriu é como
   o incidente volta: a fila normaliza no meio da degradação e o sistema se declara saudável com a
   dependência crítica ainda fora.
4. **`RECOVERY` começa por decisão humana e termina por número.** `evaluate()` devolve `NORMAL`
   quando a razão fila/vagas cai a `<= exit-ratio` (0.5 por padrão). Recuperação que ninguém encerra
   vira modo esquecido, com o despacho pela metade para sempre — o mesmo tipo de defeito silencioso
   que o `HIGH_LOAD` piscando, só que permanente.
5. **O modo muda o despacho, não o trabalho em curso.** `DEGRADED` e `EMERGENCY` designam zero
   tarefas na rodada; `RECOVERY` designa metade do teto de varredura. O que já está `DISPATCHED`
   continua do nó: parar o despacho é adiar trabalho novo, não cancelar o que está na mão. E
   **reclamar tarefa órfã roda em qualquer modo** — devolver à fila a tarefa cujo claim venceu não é
   designar trabalho novo, e deixá-la parada em `DISPATCHED` faria a tarefa vencer o prazo esperando
   o modo voltar.
6. **O nó sabe o modo.** O heartbeat devolve `mode` e `acceptTasks` (`= dispatchAllowed()`). Sem
   isso a contingência existiria só no painel: o nó pediria tarefa no escuro e executaria durante um
   `EMERGENCY` que o Control Plane nem designou. O campo é aditivo — nó antigo que ignora o campo
   continua funcionando, e o contrato de `/network/nodes/heartbeat` já previa `mode`.
7. **`RECOVERY` não é acionável por nó nenhum na rota de tarefa.** O nó consulta o modo; ele não o
   declara. `GET`/`POST /admin/network/mode` ficam sob `/admin`, logo atrás de
   `AdminRouteInterceptor` **e** de `AdminGuard` no handler — defesa em profundidade, como as demais
   rotas administrativas da rede.

## Consequências

- Migração **não** foi necessária: `network_state` (V292) já guardava `mode`, `reason` e `since`, e a
  tabela `audit_logs` já existe. O que faltava era caminho de escrita e leitura, não coluna.
- O teto de despacho passou a ser variável por modo. `SCAN_LIMIT` virou `public` porque o teste que
  mede "metade da varredura" precisa do mesmo número — e ele é `50`; metade com piso 1 evita que um
  teto pequeno configurado em produção transforme `RECOVERY` em despacho zero.
- A razão de saída de `RECOVERY` usa a mesma capacidade que `HIGH_LOAD` já calculava (nós vivos, não
  saturados, fora de quarentena). Contar a vaga de quem está em quarentena faria a recuperação sair
  cedo, justamente quando a rede tem menos capacidade do que aparenta.
- Fica em aberto para a Fase 5/6: a config assinada (`GET /network/nodes/me/config`) ainda não
  existe, então o modo chega ao nó pelo heartbeat e não pela config. Quando a config assinada
  existir, o modo entra nela com a mesma semântica — o heartbeat continua sendo o caminho de curto
  prazo e a config o de longo prazo.
- Teste negativo indispensable: `POST /admin/network/mode` com `NORMAL` ou `HIGH_LOAD` recebe 400
  `NETWORK_MODE_NOT_MANUAL`. Se alguém "consertar" isso adicionando os dois à lista de acionáveis,
  este teste quebra primeiro — é o que impede a decisão do item 1 de ser desfeita por refactor.