#!/usr/bin/env python3
"""Executor mínimo de um nó de Storage, para o harness de aceitação (§25).

Por que existe: o §25 fala de sistema operando — "um participante instala e vincula o servidor",
"a indisponibilidade de um nó não compromete arquivos com redundância saudável". O Control Plane
enfileira a cópia como tarefa `OBJECT_PUT` e espera que o nó a execute. Sem um executor do outro
lado, a colocação fica `PENDING_REMOTE` para sempre e o cenário não prova nada.

Este executor percorre o mesmo caminho que o `deploy/node-runtime/node-runtime.sh` real:
  1. GET  /network/nodes/me/config         — a config assinada (o nó recusa a que não confere com
                                              o pino, como o runtime real);
  2. GET  /network/tasks/next              — a próxima tarefa;
  3. OBJECT_PUT: baixa o corpo, mede sha-256 e bytes, confere com o payload e confirma em
     POST /network/storage/objects/{id}/replica;
  4. POST /network/tasks/{id}/result       — DONE ou FAILED com motivo.

O que NÃO é: o runtime de produção (que também faz transcode, cache de borda e auto-update). O
harness precisa do caminho de storage; reimplementar o resto só daria um segundo sistema para a
suíte manter. O pino (chave pública da config) é conferido de verdade — o harness gera o par.

Uso:
  node-executor.py --base URL --node NODE_ID --key API_KEY --pino PUBKEY_B64 --home DIR [--rodadas N]
Sai com 0 mesmo quando só não havia tarefa; 1 quando o nó foi recusado (chave revogada, etc.).
"""
import argparse
import base64
import hashlib
import json
import os
import sys
import urllib.error
import urllib.request


def http(method, url, headers=None, body=None):
    req = urllib.request.Request(url, data=body, method=method)
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    # urllib manda `application/x-www-form-urlencoded` quando o corpo existe; o Control Plane só
    # aceita JSON nas rotas de tarefa.
    if body is not None:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return r.status, r.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", required=True)
    ap.add_argument("--node", required=True)
    ap.add_argument("--key", required=True)
    ap.add_argument("--pino", required=True)
    ap.add_argument("--home", required=True)
    ap.add_argument("--rodadas", type=int, default=1)
    args = ap.parse_args()

    auth = {"X-Node-Id": args.node, "X-Node-Key": args.key}
    corpos = os.path.join(args.home, "objects")
    os.makedirs(corpos, exist_ok=True)

    for _ in range(args.rodadas):
        status, corpo = http("GET", f"{args.base}/network/nodes/me/config", auth)
        if status != 200:
            sys.stderr.write(f"[executor] config recusada ({status})\n")
            return 1
        config = json.loads(corpo)
        # A config sem assinatura é recusada, como no runtime real: sem ela o nó não tem
        # permissão provada para executar nada.
        if not config.get("signed") or config.get("publicKey") != args.pino:
            sys.stderr.write("[executor] config sem assinatura do pino — recusada\n")
            return 1
        permitidas = set(config.get("allowedTasks") or [])

        status, corpo = http("GET", f"{args.base}/network/tasks/next", auth)
        if status != 200:
            sys.stderr.write(f"[executor] poll falhou ({status})\n")
            return 1
        tarefas = json.loads(corpo).get("tasks") or []
        if not tarefas:
            continue

        tarefa = tarefas[0]
        task_id, tentativa, tipo = tarefa["taskId"], tarefa["attempt"], tarefa["type"]
        payload = json.loads(tarefa["payload"] or "{}")
        resultado = {"taskId": task_id, "attempt": tentativa, "status": "DONE", "output": None}
        if tipo not in permitidas:
            resultado |= {"status": "FAILED", "error": {"code": "TASK_NOT_ALLOWED",
                         "message": "a config assinada nao autoriza " + tipo, "retryable": False}}
        elif tipo == "OBJECT_PUT":
            object_id = payload["objectId"]
            status, corpo = http("GET", f"{args.base}/network/storage/objects/{object_id}", auth)
            if status != 200:
                sys.stderr.write(f"[executor] download {status}: {corpo[:200]}\n")
                resultado |= {"status": "FAILED", "error": {"code": "STORAGE_FETCH_FAILED",
                             "message": f"download {status}", "retryable": True}}
            else:
                # A medida é do nó, do que veio pela rede — não do que o payload promete.
                sha, tamanho = hashlib.sha256(corpo).hexdigest(), len(corpo)
                if sha != payload.get("sha256") or tamanho != payload.get("bytes"):
                    resultado |= {"status": "FAILED", "error": {"code": "STORAGE_REPLICA_MISMATCH",
                                 "message": f"sha={sha} bytes={tamanho}", "retryable": False}}
                else:
                    status, resposta = http(
                        "POST", f"{args.base}/network/storage/objects/{object_id}/replica", auth,
                        json.dumps({"bytes": tamanho, "sha256": sha}).encode())
                    if status != 200:
                        sys.stderr.write(f"[executor] confirmacao {status}: {resposta[:200]}\n")
                        resultado |= {"status": "FAILED", "error": {"code": "STORAGE_REPLICA_REFUSED",
                                     "message": f"confirmacao {status}", "retryable": True}}
                    else:
                        caminho = os.path.join(corpos, object_id + ".bin")
                        with open(caminho, "wb") as saida:
                            saida.write(corpo)
                        resultado["output"] = json.dumps({"objectId": object_id, "sha256": sha,
                                                          "bytes": tamanho})
        status, _ = http("POST", f"{args.base}/network/tasks/{task_id}/result", auth,
                         json.dumps(resultado).encode())
        if status != 200:
            sys.stderr.write(f"[executor] resultado recusado ({status}) {resultado}\n")
            return 1

    return 0


if __name__ == "__main__":
    sys.exit(main())