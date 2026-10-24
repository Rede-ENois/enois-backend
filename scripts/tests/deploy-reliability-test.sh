#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
# Sourcing disponibiliza só as funções, sem chamar a API.
# shellcheck source=../deploy-reliability.sh
source "$ROOT/scripts/deploy-reliability.sh"

PASS=0
assert_eq() {
  local name="$1" expected="$2" actual="$3"
  if [[ "$expected" == "$actual" ]]; then
    PASS=$((PASS + 1)); printf '[PASS] %s\n' "$name"
  else
    printf '[FAIL] %s\n  esperado: <%s>\n  obtido: <%s>\n' "$name" "$expected" "$actual" >&2
    exit 1
  fi
}

classify() { classify_jobs <<<"$1"; }

# Em workflows normais, qa e prod coexistem: a branch não escolhida fica skipped.
normal='{"jobs":[{"name":"🐳 Build & Push → Harbor","conclusion":"success"},{"name":"🚢 Deploy → QA (docker-prod4)","conclusion":"success","started_at":"2026-10-23T20:00:00Z","completed_at":"2026-10-23T20:02:00Z","steps":[{"name":"🩺 Health check","conclusion":"success"}]},{"name":"🚢 Deploy → PROD (enois-node2)","conclusion":"skipped"}]}'
assert_eq 'deploy saudável, ignorando job skipped' $'post_deploy_health_passed\t120' "$(classify "$normal")"

failed_health='{"jobs":[{"name":"🐳 Build & Push → Harbor","conclusion":"success"},{"name":"🚢 Deploy → PROD","conclusion":"failure","started_at":"2026-10-23T20:00:00Z","completed_at":"2026-10-23T20:03:00Z","steps":[{"name":"🩺 Health check","conclusion":"failure"}]}]}'
assert_eq 'falha de health check nao vira MTTR' $'post_deploy_health_failed\t180' "$(classify "$failed_health")"

build_failed='{"jobs":[{"name":"🐳 Build & Push → Harbor","conclusion":"failure"},{"name":"🚢 Deploy → PROD","conclusion":"skipped"}]}'
assert_eq 'falha build' $'build_failed\t' "$(classify "$build_failed")"

old_gitops='{"jobs":[{"name":"🐳 Build & Push Docker Image","conclusion":"success"},{"name":"🔄 GitOps Update → EKS Floresta","conclusion":"success"}]}'
assert_eq 'gitops sem health nao validado' $'deploy_job_passed_unverified\t' "$(classify "$old_gitops")"

no_deploy='{"jobs":[{"name":"🧪 Validar build (PR)","conclusion":"skipped"},{"name":"🚢 Deploy → QA","conclusion":"skipped"}]}'
assert_eq 'job de deploy pulado' $'no_deploy_job\t' "$(classify "$no_deploy")"

ambiguous='{"jobs":[{"name":"Deploy → QA","conclusion":"success"},{"name":"GitOps Update","conclusion":"success"}]}'
assert_eq 'nao escolher deploy arbitrariamente' $'ambiguous_deploy_jobs\t' "$(classify "$ambiguous")"

failed_job='{"jobs":[{"name":"🚢 Deploy → PROD","conclusion":"failure","steps":[{"name":"Update Stack","conclusion":"failure"}]}]}'
assert_eq 'deploy falhou sem health' $'deploy_job_failed\t' "$(classify "$failed_job")"

incomplete='{"jobs":[{"name":"🚢 Deploy → PROD","conclusion":null}]}'
assert_eq 'deploy sem conclusao' $'deploy_incomplete\t' "$(classify "$incomplete")"

if classify '{"other":true}' >/dev/null 2>&1; then
  echo '[FAIL] rejeita JSON de jobs invalido' >&2; exit 1
fi
PASS=$((PASS + 1)); echo '[PASS] rejeita JSON de jobs invalido'

# Teste end-to-end OFFLINE com curl substituído: nenhum request à internet.
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/bin"
export MOCK_CREATED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
cat > "$TMP/bin/curl" <<'MOCK'
#!/usr/bin/env bash
set -euo pipefail
url="${@: -1}"
case "$url" in
  *'/runs?branch=qa&event=push&per_page=2')
    printf '{"workflow_runs":[{"id":101,"created_at":"%s","head_sha":"1234567890abcdef","conclusion":"success","status":"completed"}]}' "$MOCK_CREATED_AT" ;;
  *'/runs?branch=main&event=push&per_page=2')
    printf '{"workflow_runs":[{"id":202,"created_at":"%s","head_sha":"abcdef1234567890","conclusion":"failure","status":"completed"}]}' "$MOCK_CREATED_AT" ;;
  *'/runs/101/jobs?per_page=100')
    printf '%s\n' '{"jobs":[{"name":"Build & Push → Harbor","conclusion":"success"},{"name":"Deploy → QA","conclusion":"success","steps":[{"name":"Health check","conclusion":"success"}]}]}' ;;
  *'/runs/202/jobs?per_page=100')
    printf '%s\n' '{"jobs":[{"name":"Build & Push → Harbor","conclusion":"success"},{"name":"Deploy → PROD","conclusion":"failure","steps":[{"name":"Health check","conclusion":"failure"}]}]}' ;;
  *) printf 'URL inesperada: %s\n' "$url" >&2; exit 22 ;;
esac
MOCK
chmod +x "$TMP/bin/curl"
PATH="$TMP/bin:$PATH" bash "$ROOT/scripts/deploy-reliability.sh" \
  --days 7 --max-runs 2 --output "$TMP/relatorios/sre/report.md" > "$TMP/stdout"
grep -q 'post_deploy_health_passed' "$TMP/relatorios/sre/report.md"
grep -q 'post_deploy_health_failed' "$TMP/relatorios/sre/report.md"
grep -q 'Runs concluídos na amostra: \*\*1\*\*' "$TMP/relatorios/sre/report.md"
grep -Fq "github.com/Rede-ENois/enois-backend/actions/runs/101" "$TMP/relatorios/sre/report.md"
grep -Fq "100% dos observados" "$TMP/relatorios/sre/report.md"
PASS=$((PASS + 1)); echo '[PASS] coleta e relatório offline por branch'

if bash "$ROOT/scripts/deploy-reliability.sh" --days 0 >/dev/null 2>&1; then
  echo '[FAIL] validacao --days' >&2; exit 1
fi
PASS=$((PASS + 1)); echo '[PASS] validacao --days'

printf 'Total: %s testes OK\n' "$PASS"
