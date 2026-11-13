# Énóis Distributed Network — plano e documentação

Rede cooperativa de infraestrutura: pessoas e organizações **expressamente autorizadas** disponibilizam
servidores, VPS ou máquinas físicas para executar instâncias auxiliares do Énóis (processamento,
atendimento, distribuição de conteúdo, resiliência). Não é replicar o backend em várias VPS — é uma
arquitetura distribuída com Control Plane, Data Plane e Node Runtime.

## Estado

**Fase 0 — em andamento.** Nada vai para `qa` até o plano estar completo (fases 0–6 entregues e
testadas). O trabalho vive na branch de longa duração `feat/rede-distribuida`, criada de `origin/qa`.

## Documentos

| # | Documento | O que responde |
|---|---|---|
| 00 | [Diagnóstico](00-diagnostico.md) | O que o Énóis é hoje, com evidência de código: serviços, banco, auth, filas, deploy, observabilidade, dependências. |
| 01 | [Arquitetura](01-arquitetura.md) | Control Plane, Data Plane, Node Runtime, tipos de nó, diagramas. |
| 02 | [Modelo de ameaças](02-modelo-de-ameacas.md) | Em quem não confiamos, o que cada ataque consegue e qual controle responde. |
| 03 | [Contratos entre nós](03-contratos.md) | Endpoints, envelopes, versionamento, erros, idempotência. |
| 04 | [Identidade e autorização](04-identidade-e-autorizacao.md) | Identidade criptográfica do nó, **API key por nó gerada na tela de admin**, permissões por perfil. |
| 05 | [Sincronização e consistência](05-sincronizacao.md) | O que replica, o que não replica, e por quê. |
| 06 | [Contingência](06-contingencia.md) | Modos operacionais, contingência local e distribuída, anti split-brain. |
| 07 | [Plano incremental](07-plano-incremental.md) | Fases 1–6, cada uma com entrega funcional e critério de aceite. |
| 08 | [Impacto na infraestrutura atual](08-impacto.md) | O que muda no que já está no ar, e o que não muda. |
| 09 | [Instalação do Node Runtime](09-instalacao.md) | Como um participante instala, registra e diagnostica um nó com `deploy/node-runtime/`. |

Decisões arquiteturais ficam em [`docs/adr/`](../adr/).

## Regras que valem para toda a implementação

1. **Nada de arquitetura paralela.** Tudo se apoia no que já existe: outbox transacional, idempotência,
   auditoria, `AdminGuard`, cache Caffeine, S3, actuator/Prometheus.
2. **A infraestrutura principal continua sendo a autoridade transacional.** Nó auxiliar não escreve no
   banco principal, nunca.
3. **Nenhum nó entra sem autorização humana expressa.** A API key de cada nó é gerada por um admin, na
   tela de administração, e exibida uma única vez.
4. **Conteúdo de usuário (HTML, CSS, KofScript) nunca roda no ambiente de um nó.** Continua nos
   sandboxes próprios, como hoje.
5. **Sem dependência pesada sem justificativa.** Sem consenso distribuído, criptografia própria ou
   protocolo proprietário sem necessidade comprovada.
