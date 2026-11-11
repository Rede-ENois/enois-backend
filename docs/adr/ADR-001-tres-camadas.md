# ADR-001 — Control Plane, Data Plane e Node Runtime como três camadas

**Status:** aceita (Fase 0)
**Data:** 2026-11-11

## Contexto

O Énóis é um monólito Spring Boot implantado como um container em um host. Crescer significa hoje
comprar mais uma máquina no mesmo provedor, na mesma região. A visão é uma rede cooperativa em que
pessoas e organizações autorizadas contribuem com capacidade.

A tentação óbvia é "subir o mesmo jar em várias VPS". Isso não resolve nada: multiplica os pontos de
falha, replica credencial de banco e não cria autoridade sobre quem pode fazer o quê.

## Decisão

Separar a rede em três camadas com responsabilidades distintas:

1. **Control Plane** — autoridade sobre identidade, permissão, configuração, saúde e escalonamento.
2. **Data Plane** — execução, por perfil de nó (`NODE_EDGE`, `NODE_WORKER`, `NODE_MEDIA`,
   `NODE_READ_REPLICA`, `NODE_API`, `NODE_LOCAL`, `NODE_EMERGENCY`).
3. **Node Runtime** — pacote padronizado que transforma uma máquina comum em participante.

A infraestrutura principal continua sendo a **autoridade transacional**. Nenhum nó externo escreve
no banco principal.

## Consequências

**Positivas**
- Permissão é dado do Control Plane, não propriedade do nó.
- Um nó comprometido não alcança o banco nem a sessão de ninguém.
- Cada perfil tem contrato próprio; capacidade não vira permissão.
- A plataforma continua funcionando sem nenhum nó externo.

**Negativas**
- Mais superfície de API (rotas de nó) e mais um artefato para manter (runtime).
- O Control Plane vira componente crítico e precisa de estratégia de recuperação (Fase 6).
- Exige disciplina de contrato versionado entre as partes.

## Alternativas descartadas

- **Replicar o backend em várias VPS:** multiplica pontos de falha, distribui credencial de banco,
  não cria autoridade. É a "falsa descentralização" que o pedido explicitamente rejeita.
- **Kubernetes multi-cluster:** resolve orquestração, não resolve governança de participante externo,
  e adiciona dependência pesada sem necessidade comprovada.
- **Serverless por região:** não permite que um participante disponibilize a própria máquina.
