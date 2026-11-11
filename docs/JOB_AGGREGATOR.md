# Agregador de vagas — levantamento técnico e proposta de arquitetura

Levantamento feito em 2026-11-10 (APIs testadas ao vivo na data). Complementa
`docs/JOB_SOURCES.md` (classificação das fontes existentes) e `docs/JOB_IMPORT_JOBRADAR.md`.

## Estado atual (o que já existe e NÃO será reconstruído)

O módulo `jobs` já tem a espinha dorsal do agregador (arquitetura hexagonal, porta única
`JobImportSource`):

| Peça | Onde | Estado |
|---|---|---|
| Porta de provedor (`JobImportSource`) | `jobs/application/port/out` | ✅ pronta; `discover(limit)` normaliza para o modelo interno |
| Importação em lotes + transação curta | `ExternalJobImportService` | ✅ pronta; falha de uma fonte não derruba as demais |
| Upsert por `(platform, external_id)` + **dedupe global por URL canônica** | `ExternalJobImportService.saveChunk` | ✅ pronta; `UrlCanonicalizer` remove `utm_*`/tracking; submissão da comunidade nunca é sobrescrita |
| Extração de e-mail de candidatura da descrição | `ExternalJob.extractApplyEmail` | ✅ pronta (pula `noreply@`, assets, `@example.`) |
| Agendamento diário + seed no boot | `ExternalJobRefreshScheduler` (cron `0 0 0 * * *`, toggle `EXTERNAL_JOBS_REFRESH_*`) | ✅ pronta |
| Fontes ativas | JobRadar (sitemap), Remotive, Jobicy, RemoteOK, Himalayas (`OpenJobApisConfig`) | ✅ 5 fontes |
| Listagem com selo de origem + busca | `JobContractController` (`GET /jobs`, `source=EXTERNAL`, `platform=`, `/platforms`) | ✅ pronta (externas sem paginação: teto `external-listing-limit`) |
| Salvos, candidatura interna, submissão manual da comunidade | `JobService`, `ExternalJobSubmissionService` | ✅ prontas |

**O que falta** (o escopo desta evolução):

1. **Metadados de provedor** — atribuição obrigatória + URL da fonte + política de atualização não
   estão estruturados; ficam soltos em comentários e no selo da UI.
2. **Busca/ordenação das externas** — sem cursor, sem ordenação por relevância/data, sem filtros
   (nível/local/tipo não existem nas externas hoje).
3. **Expiração** — `external_status` existe (`FOUND`/`POSSIBLY_CLOSED`) mas nada marca expirada;
   vagas de fontes removidas ficam para sempre.
4. **Fontes com chave** — Adzuna e Jooble (API oficial, credencial) não têm adaptador.
5. **Fonte brasileira de qualidade** — os repositórios comunitários do GitHub (mit) não têm adaptador.
6. **Candidatura por e-mail real** — hoje só `mailto:` no front (sem anexo de fato).

## Levantamento das fontes (testado ao vivo em 2026-11-10)

### Abertas (sem chave — já suportadas pelo `OpenJobApiSource` genérico)

| Fonte | Endpoint | Conteúdo | Custo | Obrigações | Observações |
|---|---|---|---|---|---|
| **Remotive** | `GET https://remotive.com/api/remote-jobs` (limit/category/search/company_name) | 13 vagas ativas; descrição HTML completa; salário anual USD opcional | grátis | **crédito + link de volta obrigatórios**; **proibido republicar em agregadores terceiros** (Jooble/Neuvoo/Google Jobs/LinkedIn são citados por nome); polling máx. ~4×/dia (bloqueiam acima de 2×/min) | `publication_date` atrasada 24h por política deles. **Atenção:** a proibição de republicar em *agregadores* fala de empurrar dados para OUTRO agregador; exibir com crédito/link é o uso pretendido da API. |
| **Jobicy** | `GET https://jobicy.com/api/v2/remote-jobs` (count/category/tag/geo; cursor `nextCursor`) | ~1 vaga nova/amostra; descrição completa; faixa anual + moeda | grátis | **crédito + link de origem obrigatórios**; botões de candidatura devem apontar para a URL original do feed (nunca para o Jobicy) | cursor de paginação disponível |
| **RemoteOK** | `GET https://remoteok.com/api` | ~600 vagas remotas; salário min/max; `apply_url` própria | grátis | **link de volta com follow (sem nofollow) + menção "Remote OK" obrigatórios**; logo é marca registrada (usar o NOME, não o logo) | primeiro item do array é um aviso legal (já ignorado pelo adaptador) |
| **Himalayas** | `GET https://himalayas.app/jobs/api` (limit/cursor) + `/jobs/api/search` + `/jobs/api/companies/:slug/jobs/:job` | vagas remotas com senioridade/employmentType/`applicationLink`; **chave gratuita opcional** (`Authorization: Bearer` → 60 req/min, 10k/dia) | grátis (chave grátis para mais cota) | `applicationLink` **inalterado** no botão; **link visível para himalayas.app como fonte**; **remover vagas expiradas**; proibido reexportar para outros agregadores/Google Jobs/LinkedIn (inclusive via `JobPosting` structured data) | único que exige explicitamente a remoção de expiradas — nossa expiração (§ abaixo) atende |
| **Arbeitnow** | `GET https://www.arbeitnow.com/api/job-board-api` (page, search, tags, location, visa_sponsorship, remote) | ~200 vagas/página focadas em Alemanha/UE; descrição HTML; tags; `remote` boolean | grátis | documentação/ToS detalhados não exibem restrições de reuso; os dados voltam a linkar o Arbeitnow | foco Europa — fonte secundária para vagas em inglês/exteriores |

### Com chave (API oficial — adaptador novo, fonte desligada sem credencial)

| Fonte | Autenticação | Custo | Cobertura | Obrigações e limites |
|---|---|---|---|---|
| **Adzuna** | `app_id` + `app_key` na query (cadastro gratuito em developer.adzuna.com; ou e-mail info@adzuna.com) | **Free tier é apenas para AVALIAÇÃO**; uso comercial/geração de leads **exige licença paga** (trial de 14 dias para validar) | **~20 países** (verificação do suporte a `br` precisa de conta; o país vai no path `/v1/api/jobs/{country}/search/{page}`) | resultados só como **snippet** de descrição; **atribuição obrigatória**: logo/nome "Adzuna" ≥116×23px com link (listagens) e crédito "The Adzuna API" (pesquisa); proibido sem contrato: análise comercial, revenda de dados, prospecção/lead-gen; limites variam por conta (`AUTH_FAIL` confirmado sem credencial) |
| **Jooble** | API key no path `POST https://jooble.org/api/<key>` | negócio próprio deles (sem preço público no site; a documentação respondeu 403 sem JS — registro gratuito pelo console) | agregador internacional com forte presença BR | termos detalhados só após cadastro; tratar como **condicional** até validar contrato (a navegação direta deles tem Cloudflare — nunca contornar) |

### Fontes públicas brasileiras (novo, sem custo e sem chave)

| Fonte | Mecanismo | Conteúdo | Licença | Observações |
|---|---|---|---|---|
| **frontendbr/vagas** | API oficial do GitHub (`GET /repos/frontendbr/vagas/issues?state=open&per_page=100`) | vagas BR de front-end; corpo em markdown estruturado ("### Sobre a empresa", e-mail de candidatura, salário) | **MIT** (repositório) | labels estruturadas: contrato (CLT/PJ), nível (Júnior/Pleno/Sênior), alocação (Remoto/Presencial/Híbrido) |
| **backend-br/vagas** | idem | ~41 vagas BR back-end ativas; labels idênticas | **MIT** | label `verificada` existe — **não** devemos espelhar isso como "vaga verificada" nossa; usamos só como dado de origem |
| **react-brasil/vagas** | idem | vagas BR React | sem licença declarada | verificação jurídica: conteúdo enviado por terceiros sem licença explícita — incluir só com a mesma base legal de citação (fonte + link) e volume baixo; alternativa é deixar desligado por padrão |

Estas são as melhores fontes para o requisito "mercado brasileiro": conteúdo em pt-BR, e-mail de
candidatura no corpo (habilita a candidatura por e-mail do Énóis), nível/contrato/alocação
estruturados em labels, e API oficial com rate limit público (5000 req/h autenticado com token
anônimo; sem token 60 req/h por IP — suficiente para 1×/dia).

### ATS (páginas de carreira) — FASE 2

Boards públicos com API aberta (testados): **Greenhouse** (`boards-api.greenhouse.io/v1/boards/{org}/jobs?content=true` — 200 OK, sem chave), **Ashby** (`api.ashbyhq.com/posting-api/job-board/{org}` — 200 OK, GET), **Lever** e **Workable** (rejeitaram orgs de teste em 404 — endpoints existem mas só para contas reais). SmartRecruiters tem API pública por empresa.

Estratégia: um adaptador `AtsJobSource` configurável por lista de organizações
(`rede.jobs.import.ats.greenhouse=stripe,linear,...`). Serve para vagas de empresas parceiras da
plataforma (o requisito "páginas de carreira de empresas") — nunca uma varredura indiscriminada.

### Descartadas (com motivo registrado — não fingir)

- **Indeed** — Publisher API exige aprovação de parceiro; robots.txt proíbe rastreamento. Só por
  parceria oficial.
- **LinkedIn Jobs** — sem API de busca pública; termos proíbem.
- **Gupy** (maior ATS do Brasil) — `portal.api.gupy.io/api/job` responde 404 (API pública retirada);
  só via parceria/contato com a empresa.
- **Trovit** — incorporada pela Adzuna no Brasil (`emprego.trovit.com.br` redireciona).
- **Google Jobs** — indexação via `JobPosting` structured data é caminho para RECEBER tráfego, não
  para importar; e Remotive/Himalayas proíbem reexportar para ele.
- **Scraping de SPA** — proibido pela política do módulo (`docs/JOB_IMPORT_SCRAPING.md`) e pelos
  termos das fontes (Cloudflare/anti-bot do Jooble, por exemplo).

## Viabilidade jurídica — regras que a implementação precisa cumprir

1. **Atribuição visível por fonte** — cada vaga exibe o selo `Vaga externa · {fonte}` com link para
   a fonte (atende Remotive, Jobicy, RemoteOK, Himalayas e Adzuna). O nome da fonte vem do novo
   metadado de provedor (§ arquitetura), não de string solta na UI.
2. **Link de candidatura original inalterado** — `applyUrl`/`url` da fonte é o destino do botão
   (Himalayas e Jobicy exigem explicitamente).
3. **Nunca reexportar para terceiros** — o Énóis exibe e credita; não há feed de saída, sitemap de
   vagas externas nem structured data `JobPosting` para as externas.
4. **Remoção de expiradas** — Himalayas exige; a expiração (§ arquitetura) remove da listagem ativa.
5. **Adzuna**: só entrar no ar após cadastro + aceitação dos termos (uso de avaliação; licença
   comercial antes de produção). Enquanto isso, o adaptador fica desligado sem credencial
   (fail-safe, igual JobRadar/Jooble hoje).
6. **GitHub comunitárias**: conteúdo MIT (frontendbr/backend-br) — incluir **link do issue original**
   como fonte; `react-brasil` sem licença só entra com revisão (desligado por padrão).
7. **Não republicar conteúdo protegido sem autorização** — descrição completa só de fontes cujos
   termos permitem (Remotive/Jobicy/RemoteOK/Arbeitnow/GitHub-MIT); Adzuna/Jonble: snippet + link.

## Proposta de arquitetura (evolução integrada, sem segundo sistema)

```
                    ┌──────────── JobImportSource (porta existente) ────────────┐
                    │  JobRadar  Remotive  Jobicy  RemoteOK  Himalayas          │
                    │  Arbeitnow  GitHubCommunities  Adzuna*  Jooble*  Ats**    │
                    └──────────────────────────┬────────────────────────────────┘
                                               │ discover(limit) → ExternalJob normalizado
                                               ▼
                    ExternalJobImportService (upsert + dedupe global por URL canônica)
                                               │
                                               ▼
  external_jobs (tabela existente) + colunas novas:
    published_at · employment_type · employment_type_normalized · level · location_city/uf
    remote (bool) · salary_currency/salary_min/salary_max numéricos · attribution_url
    expire_at · removed_at
                                               │
                    JobContractController (GET /jobs) — busca unificada com filtros
                    novos (remote/city/uf/publishedAfter/minSalary/sort), cursor também
                    para externas; JobProviderCatalog alimenta selo/atribuição da UI.
                                               │
                    ExternalJobRefreshScheduler — refresh diário + dedupe de vencidas
                    (fontes sem a vaga no refresh recente → POSSIBLY_CLOSED → REMOVED após
                    a janela configurável; Himalayas exige remoção).
                                               │
                    (Fase candidatura) ExternalApplicationService: candidatura por e-mail
                    REAL pelo backend (SMTP do Énóis), assunto com título+id da vaga,
                    corpo com apresentação + link do perfil + currículo anexado de fato,
                    histórico em external_applications (nunca automático sem confirmação
                    explícita; rate limit; log sem PII sensível).
                                               │
            (* ) fonte desligada sem credencial (fail-safe, igual JobRadar hoje)
            (**) Fase 2: lista de empresas parceiras por configuração
```

Decisões-chave:

- **Um formato interno só** — a porta `JobImportSource` já normaliza; as colunas novas são o
  detalhamento do que a fonte traz de estruturado (nada inventado: ausente fica nulo).
- **Dedupe**: camadas existentes (id externo + URL canônica) + camada nova de **similaridade
  empresa+título normalizado** para o caso de agregadores publicarem a mesma vaga com URL de
  redirecionamento diferente — só cria alias quando a confiança é alta, e o registro mantém UMA
  procedência original.
- **Procedência**: `platform` (descoberta) + `attribution_url` (link obrigatório da fonte) +
  `original_company_url` quando a fonte separa a empresa do agregador.
- **Fonte nova = um adaptador** + entrada no catálogo de provedores; nenhuma lógica de
  persistência/busca duplicada.
- **Internas ≠ externas sempre visíveis** na UI (selo de origem já existe) — externa nunca entra no
  fluxo de candidatura interna nem de ranking da plataforma.

## Fases

1. **Fase 1 — base de dados e busca** (backend): colunas normalizadas (`V269`),
   `ExternalJobDetails`, taxonomia (`JobTaxonomy`), expiração por ciclo de fonte
   (`ExternalJobImportService.expireStale`, janela de 3 dias), busca com cursor/ordenação/filtros
   para externas (`GET /jobs?source=EXTERNAL&workModel=REMOTE&sort=salary…`), contrato `JobView`
   com `externalDetails`. **Implementada.**
2. **Fase 2 — fontes novas**: adaptadores Arbeitnow, GitHub comunitárias (MIT), Adzuna (com
   credencial de avaliação), Jooble (condicional), ATS por configuração. Cada uma com teste de
   contrato offline (JSON fixo) + toggle de configuração. **Implementadas: Arbeitnow, GitHub
   comunitárias (backend-br/frontendbr) e Adzuna** — Jooble segue condicional (só com chave) e os
   ATS (Greenhouse/Ashby) ficam para uma fase própria, por orgão de parceiro.
3. **Fase 3 — candidatura externa por e-mail real** (backend): endpoint autenticado que envia o
   e-mail pelo SMTP da plataforma com o currículo anexado (o `mailto:` continua existindo como
   alternativa, sem fingir anexo), histórico e rate limit.
4. **Fase 4 — experiência de busca no front**: filtros completos, ordenação, paginação (o
   InfiniteScroll já existe e está quebrado na listagem), distintivo de origem, candidatura pelo
   Énóis, perfil como complemento do currículo (a candidatura interna já monta snapshot — o externo
   passa a reaproveitar).
5. **Fase 5 — recomendações e alertas** (matchScore por compatibilidade, já há campo no view; alertas
   de novas vagas compatíveis nas notificações existentes).

## Custos

| Cenário | Fontes ativas | Custo mensal |
|---|---|---|
| Hoje (Fase 0) | 5 abertas + JobRadar | R$ 0 |
| + Brasil e Europa | + GitHub comunitárias, Arbeitnow | R$ 0 |
| + Adzuna | licença comercial (obrigatória em produção) | sob consulta (histórico de mercado: ~centenas de USD/mês por volume; confirmar no cadastro) |
| + Jooble | negócio deles | sob consulta |
| ATS próprio | Greenhouse/Ashby públicos | R$ 0 |

Regra adotada: **nenhuma fonte paga entra em produção sem cadastro + aceite dos termos + limite
configurado** (o mesmo padrão fail-safe do restante do módulo).
