#!/usr/bin/env bash
# Prova a regra de retenção sem falar com o Harbor: sobe um servidor falso que responde o
# formato da API e confere quais digests o script teria apagado em cada cenário.
set -euo pipefail

AQUI="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPT="${AQUI}/harbor-retention.sh"
FALHAS=0

falha() { echo "FALHOU: $*" >&2; FALHAS=$((FALHAS + 1)); }
ok() { echo "ok: $*"; }

# Servidor falso: recebe a lista de artefatos e devolve o que o script pediu para apagar.
# A lista chega em ARTEFATOS (JSON) e as exclusões vão para um arquivo temporário.
rodar_cenario() {
  local nome="$1" artefatos="$2" esperado="$3" current="${4:-}" keep="${5:-2}"
  local saida apagados
  saida="$(mktemp)"
  apagados="$(mktemp)"

  ARTEFATOS="$artefatos" APAGADOS="$apagados" python3 - "$saida" <<'PY' &
import base64, http.server, json, os, re, sys, threading, urllib.parse

artefatos = json.loads(os.environ["ARTEFATOS"])
apagados = os.environ["APAGADOS"]
saida = sys.argv[1]


class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def _json(self, codigo, corpo):
        dados = json.dumps(corpo).encode() if corpo is not None else b""
        self.send_response(codigo)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(dados)))
        self.end_headers()
        self.wfile.write(dados)

    def do_GET(self):
        # Só a primeira página tem conteúdo; a segunda devolve lista vazia.
        if "page=1" in self.path:
            self._json(200, artefatos)
        else:
            self._json(200, [])

    def do_DELETE(self):
        digest = urllib.parse.unquote(self.path.rsplit("/", 1)[-1])
        with open(apagados, "a") as arq:
            arq.write(digest + "\n")
        self._json(200, None)


servidor = http.server.HTTPServer(("127.0.0.1", 0), Handler)
porta = servidor.server_address[1]
threading.Thread(target=servidor.serve_forever, daemon=True).start()
with open(saida, "w") as arq:
    arq.write(str(porta))
try:
    threading.Event().wait(120)
except KeyboardInterrupt:
    pass
PY
  local pid=$! rc=0 porta=""

  for _ in $(seq 1 50); do
    [ -s "$saida" ] && break
    sleep 0.1
  done
  porta="$(cat "$saida")"
  [ -n "$porta" ] || { falha "$nome: servidor falso não subiu"; kill "$pid" 2>/dev/null || true; return; }

  HARBOR_SCHEME="http" \
  HARBOR_REGISTRY="127.0.0.1:${porta}" \
  HARBOR_PROJECT="enois" \
  IMAGE_NAME="frontend" \
  HARBOR_USER="teste" \
  HARBOR_PASSWORD="teste" \
  CURRENT_TAG="$current" \
  KEEP_PROD="$keep" \
  bash "$SCRIPT" >/dev/null 2>&1 || rc=$?
  kill "$pid" 2>/dev/null || true
  wait "$pid" 2>/dev/null || true

  if [ "$rc" -ne 0 ]; then
    falha "$nome: script saiu com código $rc"
    rm -f "$saida" "$apagados"
    return
  fi

  local obtido
  obtido="$(sort "$apagados" | tr '\n' ' ' | sed 's/ *$//')"
  local normalizado
  # `|| true`: sem nada para apagar, o `grep -v` sai 1 e o `pipefail` derrubaria a suíte.
  normalizado="$(printf '%s' "$esperado" | tr ' ' '\n' | { grep -v '^$' || true; } | sort | tr '\n' ' ' | sed 's/ *$//')"

  if [ "$obtido" = "$normalizado" ]; then
    ok "$nome"
  else
    falha "$nome: esperava apagar [${normalizado}] e apagou [${obtido}]"
  fi
  rm -f "$saida" "$apagados"
  # Devolve sucesso sempre: o veredito do cenário foi dado acima, e a contagem é que decide o fim.
  return 0
}

# --- cenários ------------------------------------------------------------------------------

# 1. Três builds de produção: mantém os dois mais recentes (por push_time) e apaga o mais antigo.
rodar_cenario "mantém a atual + 1 backup de produção" '[
  {"digest":"sha256:aatual","push_time":"2026-11-04T10:00:00Z","tags":[{"name":"main-aaa1111"}]},
  {"digest":"sha256:anterior","push_time":"2026-11-03T10:00:00Z","tags":[{"name":"main-bbb2222"}]},
  {"digest":"sha256:velha","push_time":"2026-11-01T10:00:00Z","tags":[{"name":"main-ccc3333"}]}
]' "sha256:velha"

# 2. A tag flutuante `main` sobrevive mesmo apontando para um digest antigo.
rodar_cenario "a tag flutuante main sobrevive" '[
  {"digest":"sha256:aatual","push_time":"2026-11-04T10:00:00Z","tags":[{"name":"main-aaa1111"}]},
  {"digest":"sha256:anterior","push_time":"2026-11-03T10:00:00Z","tags":[{"name":"main-bbb2222"}]},
  {"digest":"sha256:flutuante","push_time":"2026-10-01T10:00:00Z","tags":[{"name":"main"}]}
]' ""

# 3. QA antiga sai; a mais nova da QA fica (é a que roda na QA).
rodar_cenario "guarda só a QA mais recente" '[
  {"digest":"sha256:qa1","push_time":"2026-11-04T10:00:00Z","tags":[{"name":"qa-aaa1111"}]},
  {"digest":"sha256:qa2","push_time":"2026-11-03T10:00:00Z","tags":[{"name":"qa-bbb2222"}]},
  {"digest":"sha256:qa3","push_time":"2026-11-02T10:00:00Z","tags":[{"name":"qa-ccc3333"}]}
]' "sha256:qa2 sha256:qa3"

# 4. O build que acabou de sair nunca é apagado, mesmo sendo o mais antigo por push_time.
rodar_cenario "preserva o build atual" '[
  {"digest":"sha256:atual","push_time":"2026-11-01T00:00:00Z","tags":[{"name":"qa-aaa1111"}]},
  {"digest":"sha256:qa2","push_time":"2026-11-04T10:00:00Z","tags":[{"name":"qa-bbb2222"}]},
  {"digest":"sha256:qa3","push_time":"2026-11-03T10:00:00Z","tags":[{"name":"qa-ccc3333"}]}
]' "sha256:qa3" "qa-aaa1111"

# 5. Imagem sem tag (sobra de push interrompido) é apagada.
rodar_cenario "imagem sem tag é apagada" '[
  {"digest":"sha256:atual","push_time":"2026-11-04T10:00:00Z","tags":[{"name":"main-aaa1111"}]},
  {"digest":"sha256:orfa","push_time":"2026-11-04T11:00:00Z","tags":[]}
]' "sha256:orfa"

# 6. Registro vazio não explode.
rodar_cenario "registro vazio não falha" '[]' ""

echo
if [ "$FALHAS" -ne 0 ]; then
  echo "$FALHAS cenário(s) falharam."
  exit 1
fi
echo "Todos os cenários passaram."