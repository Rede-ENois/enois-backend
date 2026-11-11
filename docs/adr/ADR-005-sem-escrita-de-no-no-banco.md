# ADR-005 — Nenhum nó externo escreve no banco principal

**Status:** aceita (Fase 0)
**Data:** 2026-11-11

## Contexto

A rede permite que servidores de terceiros executem trabalho do Énóis. A forma "fácil" de fazer isso
é dar ao nó uma conexão de banco e deixá-lo rodar o mesmo código.

Isso é inaceitável: viola Zero Trust, viola Least Privilege, viola soberania de dados e cria caminho
direto do servidor de um participante para o dado pessoal de todos os usuários.

## Decisão

1. Nenhum nó externo recebe credencial do banco principal.
2. Nenhum nó externo recebe acesso administrativo ao banco.
3. A única exceção é `NODE_READ_REPLICA`, que recebe **projeções** com allowlist explícita, em
   usuário próprio, somente leitura.
4. Toda escrita passa pela infraestrutura principal, via API autorizada.
5. Tarefa entregue a um nó carrega `dataClass`; dado sensível não sai da infra autorizada.

## Consequências

**Positivas**
- O pior que um nó comprometido consegue é mexer no que ele recebeu, não no conjunto.
- A autoridade transacional continua num lugar só, com as transações e a outbox que já existem.
- A LGPD fica tratável: o dado pessoal não se espalha.

**Negativas**
- Algumas operações que seriam triviais com conexão direta exigem uma rota de API.
- `NODE_READ_REPLICA` exige manutenção de projeção e reconciliação.

## Alternativas descartadas

- **Réplica completa do banco:** distribui todo o dado pessoal para máquinas de terceiros.
- **Conexão de banco com usuário restrito:** "restrito" continua sendo acesso direto ao banco, sem
  auditoria de query e sem limite por tarefa.
- **Banco por região com replicação:** escrita distribuída sem estratégia formal de consenso —
  explicitamente fora de escopo.

## Relacionado

- [02 — Modelo de ameaças](../rede-distribuida/02-modelo-de-ameacas.md)
- [05 — Sincronização](../rede-distribuida/05-sincronizacao.md)
