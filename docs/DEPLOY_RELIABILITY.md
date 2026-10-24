# Énóis — baseline de confiabilidade de deploy

Coletor read-only de eventos públicos do GitHub Actions para o backend. **Não mede uptime, MTTR, incidentes confirmados nem disponibilidade de funcionalidades para usuários.** Não altera GitHub, Harbor, Portainer, containers ou infraestrutura.

## Rodar localmente (Linux)

Requisitos: **Bash, `curl`, `jq` e GNU `date`**. Não requer Maven nem acesso à infraestrutura de produção. Recomenda-se executar a partir da raiz do repositório:

```bash
bash scripts/deploy-reliability.sh --days 7 --max-runs 10 \
  --output /tmp/enois-deploy-report.md
bash scripts/tests/deploy-reliability-test.sh
```

Sem `--output`, imprime Markdown em stdout. `GITHUB_TOKEN` é **opcional** para ampliar a cota da API pública; jamais registre valores em arquivos, comandos compartilhados ou logs. Sem token, a cota da API GitHub pode acabar rapidamente (cada run consultado pede os jobs separadamente).

## O que realmente mede

- Quantidade de execuções `push` concluídas do workflow `build-and-push.yml`, separadas por `qa` e `main`.
- Classificação operacional por **job ativo**, ignorando jobs `skipped` do outro ambiente.
- Taxa de aprovação entre **health checks pós-deploy observados**; não é taxa de sucesso de deploys totais, uptime ou SLO.
- Duração média do **job de deploy** quando o GitHub fornece início e fim; não confundir com tempo de indisponibilidade.
- Links para runs e SHAs de commit para investigação manual.

| Classe | Evidência GitHub Actions |
| --- | --- |
| `build_failed` | Falha no job Build & Push |
| `post_deploy_health_passed` | Etapa Health do deploy concluída com sucesso |
| `post_deploy_health_failed` | Etapa Health falhou (não comprova incidente) |
| `deploy_job_failed` | Job de deploy falhou sem evidência explícita de health reprovado |
| `deploy_job_passed_unverified` | Job de deploy passou, mas sem etapa Health identificável |
| `no_deploy_job` | Não foi observado job ativo de deploy |
| `ambiguous_deploy_jobs` | Vários jobs ativos de deploy; o coletor não escolhe arbitrariamente |
| `deploy_incomplete` | Job ativo sem conclusão identificável |

## Limites de interpretação

- O script consulta **uma única página de até `--max-runs` execuções por branch**, filtrando depois pela janela `--days`. Não é uma série exaustiva: com mais runs no período, as proporções representam **a amostra** e não todo o período.
- Apenas **primeira página de jobs (até 100)** por run. Em históricos complexos, pode faltar informação.
- Fluxos antigos usavam GitOps/EKS; fluxos novos usam Harbor/Portainer. Um GitOps que terminou sem health check explícito aparece como **não verificado**, e não como saudável.
- Um health check vermelho após atualizar a stack justifica investigar; **não prova queda para usuários nem que houve rollback**.
- Uptime exige probes/SLIs contínuos; MTTR exige incidentes com timestamps confiáveis de início e recuperação. Não derivar nenhuma dessas métricas do pipeline.

## Próximos passos fora de escopo

Investigar falhas de health com logs e sinais de infraestrutura, definir SLI das jornadas principais, criar registro de incidentes e preparar runbook de rollback **manual** com avaliação de compatibilidade das migrations Flyway. Qualquer alteração de pipeline, rollback automático ou intervenção no ambiente precisa de revisão dos mantenedores.
