-- Énóis Distributed Network (fase 5): a versão da permissão que o nó lê e assina.
--
-- O contrato `GET /network/nodes/me/config` sempre prometeu `configVersion` e `issuedAt`, e até aqui
-- a rota não existia. Ao implementá-la apareceu o problema que estas duas colunas resolvem: sem
-- estado, `issuedAt` teria de ser o relógio da requisição, e duas leituras seguidas do MESMO nó
-- produziriam documentos diferentes. O runtime trata config nova como evento — ele revalida,
-- reaplica e registra —, então config "nova" a cada segundo viraria ruído e mascararia a mudança
-- que importa.
--
-- A versão nasce do ciclo de vida, não de um contador que alguém precisa lembrar de incrementar:
--   - o enroll grava 1, com o instante do enroll;
--   - toda transição administrativa (aprovar, suspender, quarentenar, revogar) soma 1 e carimba o
--     instante da transição.
-- É a mesma informação que já está em `status`, vista de um ângulo que o nó consegue comparar sem
-- entender nossos estados: "o número mudou, a permissão mudou".
--
-- `config_issued_at` é nullable e fica null só em linha anterior a esta migração; nesse caso a
-- leitura cai no instante em que o nó passou a existir de fato (`approved_at`, senão `created_at`).
-- Não preenchemos aqui porque `add column ... default` com `now()` não é portátil entre o MariaDB e
-- o H2 da suíte, e as duas rodam esta migração.
--
-- Aditiva e idempotente, como todas as deste módulo: `if not exists` em cada instrução, uma por
-- comando (a forma composta do MySQL não roda no H2).

alter table network_nodes add column if not exists config_version int not null default 1;

alter table network_nodes add column if not exists config_issued_at timestamp null;

-- A leitura da config é por id (chave primária), então não há índice novo a criar.