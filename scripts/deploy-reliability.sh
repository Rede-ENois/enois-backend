#!/usr/bin/env bash
# Énóis SRE — coleta read-only de fatos de deploy no GitHub Actions.
# Dependências: bash, curl, jq e GNU date (ambiente Linux).
set -euo pipefail

REPO='Rede-ENois/enois-backend'
WORKFLOW='build-and-push.yml'
API="https://api.github.com/repos/${REPO}"
DAYS=7
MAX_RUNS=10
OUTPUT=''

usage() {
  cat <<'HELP'
Uso: bash scripts/deploy-reliability.sh [--days N] [--max-runs N] [--output ARQUIVO]

  --days N       Janela retroativa em dias (1..90; padrão 7)
  --max-runs N   Máximo de runs recentes por branch (1..50; padrão 10)
  --output PATH  Salva relatório Markdown no arquivo em vez de stdout
  -h, --help     Mostra esta ajuda

Requer bash, curl, jq e GNU date. Somente leitura: não faz deploy/rollback.
GITHUB_TOKEN é opcional para ampliar a cota da API; não o coloque em comandos/logs.
HELP
}

die() { printf 'Erro: %s\n' "$*" >&2; exit 1; }

while (($#)); do
  case "$1" in
    --days|--max-runs|--output)
      (($# >= 2)) || die "Falta valor para $1"
      case "$1" in
        --days) DAYS="$2" ;;
        --max-runs) MAX_RUNS="$2" ;;
        --output) OUTPUT="$2" ;;
      esac
      shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) die "Opção desconhecida: $1" ;;
  esac
done

[[ "$DAYS" =~ ^[0-9]+$ ]] && ((10#$DAYS >= 1 && 10#$DAYS <= 90)) || die '--days precisa ser 1..90'
[[ "$MAX_RUNS" =~ ^[0-9]+$ ]] && ((10#$MAX_RUNS >= 1 && 10#$MAX_RUNS <= 50)) || die '--max-runs precisa ser 1..50'
DAYS=$((10#$DAYS))
MAX_RUNS=$((10#$MAX_RUNS))
for tool in curl jq date mktemp; do command -v "$tool" >/dev/null 2>&1 || die "Comando ausente: $tool"; done
date -u -d '1 day ago' +%s >/dev/null 2>&1 || die 'GNU date é necessário (Linux)'

# Classificação estritamente operacional, não disponibilidade percebida pelo usuário.
# IMPORTANTE: jobs das duas branches aparecem no workflow; ignorar jobs SKIPPED.
classify_jobs() {
  jq -er '
    if (.jobs | type) != "array" then error("jobs não é array") else . end
    | def active: select(.conclusion != "skipped");
      def hasname($regex): (.name // "" | ascii_downcase | test($regex));
      ([.jobs[] | active | select(hasname("build.*push"))]) as $builds
      | ([.jobs[] | active | select(hasname("deploy|gitops"))]) as $deploys
      | if any($builds[]; .conclusion == "failure") then ["build_failed", ""]
        elif ($deploys | length) == 0 then ["no_deploy_job", ""]
        elif ($deploys | length) > 1 then ["ambiguous_deploy_jobs", ""]
        else
          ($deploys[0]) as $deploy
          | ([($deploy.steps // [])[] | select(.name // "" | ascii_downcase | contains("health"))]) as $health
          | (try (
               if ($deploy.started_at and $deploy.completed_at)
               then (($deploy.completed_at | fromdateiso8601) - ($deploy.started_at | fromdateiso8601))
               else null end
             ) catch null) as $seconds
          | (if ($seconds != null and $seconds >= 0) then ($seconds | tostring) else "" end) as $duration
          | if any($health[]; .conclusion == "failure") then ["post_deploy_health_failed", $duration]
            elif $deploy.conclusion == "failure" then ["deploy_job_failed", $duration]
            elif $deploy.conclusion == "success" then
              if any($health[]; .conclusion == "success") then ["post_deploy_health_passed", $duration]
              else ["deploy_job_passed_unverified", $duration] end
            else ["deploy_incomplete", $duration]
            end
        end | @tsv
  '
}

api_get() {
  local path="$1" url="${API}${1}"
  local -a headers=(-H 'Accept: application/vnd.github+json'
                    -H 'X-GitHub-Api-Version: 2022-11-28'
                    -H 'User-Agent: enois-deploy-reliability/1')
  if [[ -n "${GITHUB_TOKEN:-}" ]]; then
    headers+=(-H "Authorization: Bearer ${GITHUB_TOKEN}")
  fi
  curl --fail --silent --show-error --connect-timeout 5 --max-time 25 \
    --retry 1 "${headers[@]}" "$url" || die "Falha ao consultar GitHub API: ${path} (limite ou acesso?)."
}

main() {
  local now cutoff branch payload run_id created sha workflow_conclusion
  local jobs classification duration state url totals counted health passed pct
  local -a entry=()
  now="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  cutoff="$(date -u -d "${DAYS} days ago" +%s)"

  TMP_DIR="$(mktemp -d)"
  trap 'rm -rf "$TMP_DIR"' EXIT
  : > "$TMP_DIR/results.tsv"

  for branch in qa main; do
    payload="$(api_get "/actions/workflows/${WORKFLOW}/runs?branch=${branch}&event=push&per_page=${MAX_RUNS}")"
    # Falhar fechado se a API mudar. Usar created_at para cutoff e id numérico.
    jq -er '.workflow_runs | type == "array"' <<<"$payload" >/dev/null || die 'Resposta sem workflow_runs válido'
    while IFS=$'\t' read -r run_id created sha workflow_conclusion state; do
      [[ "$run_id" =~ ^[0-9]+$ ]] || die 'Run ID inválido na API'
      [[ "$state" == completed ]] || continue
      # API retorna ISO UTC e jq aceita o formato do GitHub.
      local started_epoch
      started_epoch="$(jq -nr --arg v "$created" 'try ($v | fromdateiso8601) catch empty')"
      [[ "$started_epoch" =~ ^[0-9]+$ ]] || die 'Timestamp inválido na API'
      ((started_epoch >= cutoff)) || continue
      jobs="$(api_get "/actions/runs/${run_id}/jobs?per_page=100")"
      classification="$(classify_jobs <<<"$jobs")" || die "Jobs inválidos para run ${run_id}"
      IFS=$'\t' read -r label duration <<<"$classification"
      # URLs do relatório são construídas localmente, sem aceitar URL remota arbitrária.
      url="https://github.com/${REPO}/actions/runs/${run_id}"
      printf '%s|%s|%s|%s|%s|%s|%s|%s\n' \
        "$branch" "$run_id" "$created" "$sha" "$workflow_conclusion" "$label" "${duration:-}" "$url" \
        >> "$TMP_DIR/results.tsv"
    done < <(jq -r '.workflow_runs[] | [.id, .created_at, (.head_sha // "" | .[0:12]), (.conclusion // "unknown"), .status] | @tsv' <<<"$payload")
  done

  {
    printf '# Énóis — Deployment Reliability Baseline\n\n'
    printf 'Coletado (UTC): `%s`. Fonte: GitHub Actions `%s`, workflow `%s`.\n\n' "$now" "$REPO" "$WORKFLOW"
    printf 'Janela: últimos **%s dias**. **Amostra limitada às %s execuções `push` mais recentes por branch**; resultados podem não cobrir toda a janela.\n\n' "$DAYS" "$MAX_RUNS"
    printf '> Este relatório mede eventos de pipeline, **não uptime, SLO, MTTR ou incidentes confirmados**. Health check é uma sonda pontual.\n\n'
    for branch in qa main; do
      printf '## `%s`\n\n' "$branch"
      # O arquivo contém apenas dados normalizados de saída da API; jq trata os agregados.
      local stats
      stats="$(jq -Rnr --arg branch "$branch" '
        [inputs | split("|") | select(.[0] == $branch) | {label: .[5], duration: (try (.[6] | tonumber) catch null)}] as $rows
        | {
            runs: ($rows | length),
            checked: ([$rows[] | select(.label == "post_deploy_health_passed" or .label == "post_deploy_health_failed")] | length),
            passed: ([$rows[] | select(.label == "post_deploy_health_passed")] | length),
            mean_seconds: ([$rows[].duration | select(. != null)] | if length == 0 then null else (add / length) end),
            failures: ([$rows[] | select(.label == "post_deploy_health_failed" or .label == "deploy_job_failed" or .label == "build_failed")] | length)
          }
        | [(.runs|tostring),(.checked|tostring),(.passed|tostring), (if .mean_seconds == null then "n/d" else ((.mean_seconds / 60 * 100 | round) / 100 | tostring) + " min" end), (.failures|tostring)] | @tsv
      ' < "$TMP_DIR/results.tsv")"
      local avg failures
      IFS=$'\t' read -r totals counted passed avg failures <<<"$stats"
      if ((counted > 0)); then pct="$(jq -nr --argjson p "$passed" --argjson t "$counted" '((1000 * $p / $t | round) / 10 | tostring) + "%"')"; else pct='n/d'; fi
      printf -- '- Runs concluídos na amostra: **%s**\n' "$totals"
      printf -- '- Health checks observados: **%s**, aprovados: **%s** (%s dos observados)\n' "$counted" "$passed" "$pct"
      printf -- '- Runs com falha em build/deploy/health identificada: **%s**\n' "$failures"
      printf -- '- Duração média de job de deploy (quando disponível): **%s**\n\n' "$avg"
      printf '| Run | UTC | Commit | Workflow | Classificação | Deploy (seg) |\n'
      printf '| --- | --- | --- | --- | --- | ---: |\n'
      local found=0
      while IFS='|' read -r b id created sha conclusion label duration link; do
        [[ "$b" == "$branch" ]] || continue
        found=1
        [[ "$sha" =~ ^[a-fA-F0-9]*$ ]] || sha='?'
        [[ "$conclusion" =~ ^[a-z_]+$ ]] || conclusion='unknown'
        [[ "$duration" =~ ^[0-9]+$ ]] || duration='n/d'
        printf '| [#%s](%s) | %s | `%s` | %s | `%s` | %s |\n' \
          "$id" "$link" "$created" "$sha" "$conclusion" "$label" "$duration"
      done < "$TMP_DIR/results.tsv"
      if ((found == 0)); then printf '| — | — | — | — | — | — |\n'; fi
      printf '\n'
    done
    printf '### Interpretação\n\n'
    printf -- '- `post_deploy_health_failed`: health check do deploy reprovado; **não prova indisponibilidade**.\n'
    printf -- '- `deploy_job_passed_unverified`: job concluído, sem health check identificável (ex.: workflow GitOps antigo).\n'
    printf -- '- Dados sem jobs adequados aparecem como `no_deploy_job` ou `ambiguous_deploy_jobs` e não contam como saúde confirmada.\n'
    printf -- '- Jobs consultados por execução: apenas primeira página (até 100). Não há paginação; para janelas maiores, evoluir o coletor antes de tirar conclusões gerais.\n'
  } > "$TMP_DIR/report.md"

  if [[ -n "$OUTPUT" ]]; then
    [[ "$OUTPUT" != -* ]] || die '--output deve ser um caminho, não uma opção'
    # Não sobrepor arquivos de código por padrão; responsabilidade do chamador.
    mkdir -p -- "$(dirname -- "$OUTPUT")" || die "Falha ao criar diretório de saída"
    cp -- "$TMP_DIR/report.md" "$OUTPUT" || die 'Falha ao escrever saída'
    printf 'Relatório salvo em: %s\n' "$OUTPUT"
  else
    cat "$TMP_DIR/report.md"
  fi
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then main; fi
