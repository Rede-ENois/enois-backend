#!/usr/bin/env bash
# Retenção do registro: mantém só o que está em uso e apaga o resto.
#
# O projeto no Harbor tem cota fixa (4 GiB) e cada build deixava mais uma imagem parada até o
# teto estourar e TODOS os pushes passarem a ser recusados. O que fica:
#
#   • as KEEP_PROD imagens de produção mais recentes (a atual + uma de backup);
#   • a tag flutuante `main` (para quem referencia por ela);
#   • a imagem mais recente da QA (a que está rodando lá — sem ela, a QA não reinicia);
#   • a imagem deste build (CURRENT_TAG), para nunca apagar o que acabou de ser publicado.
#
# Todo o resto é apagado por digest: as tags antigas de produção, as tags de QA antigas e as
# imagens sem tag. O delete é idempotente: o que já sumiu conta como apagado.
#
# Entradas (ambiente): HARBOR_REGISTRY, HARBOR_PROJECT, IMAGE_NAME, HARBOR_USER,
# HARBOR_PASSWORD. Opcionais: CURRENT_TAG, KEEP_PROD (padrão 2), DRY_RUN (1 = só relata).
set -euo pipefail

: "${HARBOR_REGISTRY:?HARBOR_REGISTRY não definido}"
: "${HARBOR_PROJECT:?HARBOR_PROJECT não definido}"
: "${IMAGE_NAME:?IMAGE_NAME não definido}"
: "${HARBOR_USER:?HARBOR_USER não definido}"
: "${HARBOR_PASSWORD:?HARBOR_PASSWORD não definido}"

# `HARBOR_SCHEME` só existe para o teste falar com o servidor falso, que não tem TLS.
export HARBOR_API="${HARBOR_SCHEME:-https}://${HARBOR_REGISTRY}/api/v2.0"
export HARBOR_REPO="${HARBOR_PROJECT}/${IMAGE_NAME}"
export CURRENT_TAG="${CURRENT_TAG:-}"
export KEEP_PROD="${KEEP_PROD:-2}"
export DRY_RUN="${DRY_RUN:-0}"

python3 - <<'PY'
import base64
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request

API = os.environ["HARBOR_API"]
REPO = os.environ["HARBOR_REPO"]
CURRENT_TAG = os.environ.get("CURRENT_TAG", "").strip()
KEEP_PROD = int(os.environ.get("KEEP_PROD", "2"))
DRY_RUN = os.environ.get("DRY_RUN", "0") == "1"

auth = base64.b64encode(
    f"{os.environ['HARBOR_USER']}:{os.environ['HARBOR_PASSWORD']}".encode()
).decode()


def call(method, url):
    """Chama a API do Harbor. Erro de HTTP vira exceção — nunca é engolido."""
    req = urllib.request.Request(url, method=method)
    req.add_header("Authorization", f"Basic {auth}")
    req.add_header("Accept", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            corpo = resp.read()
            return resp.status, (json.loads(corpo) if corpo.strip() else None)
    except urllib.error.HTTPError as erro:
        return erro.code, erro.read().decode(errors="replace")


project_q = urllib.parse.quote(REPO.split("/", 1)[0], safe="")
image_q = urllib.parse.quote(REPO.split("/", 1)[1], safe="")
base = f"{API}/projects/{project_q}/repositories/{image_q}"


def listar_artefatos():
    artefatos, pagina = [], 1
    while True:
        query = urllib.parse.urlencode(
            {"page": pagina, "page_size": 100, "with_tag": "true", "with_label": "false"}
        )
        status, dados = call("GET", f"{base}/artifacts?{query}")
        if status != 200:
            print(f"ERRO: não consegui listar as imagens ({status}): {dados}", file=sys.stderr)
            sys.exit(1)
        if not dados:
            return artefatos
        artefatos.extend(dados)
        if len(dados) < 100:
            return artefatos
        pagina += 1


def tags_de(artefato):
    return [t.get("name", "") for t in (artefato.get("tags") or []) if t.get("name")]


artefatos = listar_artefatos()
print(f"{REPO}: {len(artefatos)} artefato(s) no registro")

if not artefatos:
    print("Nada para avaliar.")
    sys.exit(0)

# Produção = tags `main-<sha>`. A ordem vem do push_time, que é o que decide "atual" e "backup".
prod = sorted(
    (a for a in artefatos if any(t.startswith("main-") for t in tags_de(a))),
    key=lambda a: a.get("push_time", ""),
    reverse=True,
)
qa = sorted(
    (a for a in artefatos if any(t.startswith("qa-") for t in tags_de(a))),
    key=lambda a: a.get("push_time", ""),
    reverse=True,
)

manter = {a["digest"] for a in prod[:KEEP_PROD]}
manter |= {a["digest"] for a in qa[:1]}
manter |= {a["digest"] for a in artefatos if "main" in tags_de(a)}
manter |= {a["digest"] for a in artefatos if CURRENT_TAG and CURRENT_TAG in tags_de(a)}

for artefato in artefatos:
    digest = artefato["digest"]
    tags = tags_de(artefato) or ["(sem tag)"]
    rotulo = ", ".join(tags)
    if digest in manter:
        print(f"  mantém  {rotulo}")
        continue
    if DRY_RUN:
        print(f"  apagaria {rotulo}")
        continue
    caminho = urllib.parse.quote(digest, safe="")
    status, corpo = call("DELETE", f"{base}/artifacts/{caminho}")
    if status in (200, 202, 204):
        print(f"  apagado {rotulo}")
    elif status == 404:
        print(f"  apagado {rotulo} (já não estava lá)")
    else:
        print(f"ERRO ao apagar {rotulo} ({status}): {corpo}", file=sys.stderr)
        sys.exit(1)
PY