#!/usr/bin/env python3
"""Cliente S3 mínimo para o harness de aceitação — SigV4 assinado, como o aws-cli assina.

Por que existe: o §25.3 pede que o backend "armazene e recupere objetos através da API S3". Uma
chamada sem assinatura não prova isso: ela prova só que a rota existe. Este cliente assina com o
segredo da credencial que a rota real emitiu, e a assinatura é conferida pelo gateway como qualquer
cliente faria.

Uso:
  s3client.py PUT  /bucket/chave BASE accessKey secretKey  < corpo
  s3client.py GET  /bucket/chave BASE accessKey secretKey
  s3client.py HEAD /bucket/chave BASE accessKey secretKey
  s3client.py DELETE /bucket/chave BASE accessKey secretKey

Sai com 0 quando o status é o esperado; imprime `STATUS <código>` e o corpo em stdout. O esperado
vem em `--status` (padrão 200; 204 para DELETE).
"""
import hashlib
import hmac
import sys
import urllib.error
import urllib.request
from datetime import datetime, timezone


def h(key, msg):
    return hmac.new(key, msg.encode(), hashlib.sha256).digest()


def assinar(method, url_path, body, access_key, secret_key, host):
    amz_date = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    dia = amz_date[:8]
    corpo_hash = hashlib.sha256(body).hexdigest()
    # O caminho canônico é o DEPOIS do endpoint: o `/s3` é o prefixo da instalação, não parte da URL
    # do bucket. Mesma régua do `s3Path` do gateway.
    if url_path.startswith("/s3/"):
        url_path_sig = url_path[3:]
    else:
        url_path_sig = url_path
    canonico = "\n".join([method, url_path_sig, "", f"host:{host}",
                          f"x-amz-content-sha256:{corpo_hash}", f"x-amz-date:{amz_date}",
                          "", "host;x-amz-content-sha256;x-amz-date", corpo_hash])
    escopo = f"{dia}/us-east-1/s3/aws4_request"
    a_assinar = "\n".join(["AWS4-HMAC-SHA256", amz_date, escopo,
                           hashlib.sha256(canonico.encode()).hexdigest()])
    k = h(("AWS4" + secret_key).encode(), dia)
    k = h(k, "us-east-1")
    k = h(k, "s3")
    k = h(k, "aws4_request")
    assinatura = hmac.new(k, a_assinar.encode(), hashlib.sha256).hexdigest()
    return amz_date, corpo_hash, (
        f"AWS4-HMAC-SHA256 Credential={access_key}/{escopo}, "
        f"SignedHeaders=host;x-amz-content-sha256;x-amz-date, Signature={assinatura}")


def main():
    argv = sys.argv[1:]
    esperado = 200
    if "--status" in argv:
        i = argv.index("--status")
        esperado = int(argv[i + 1])
        del argv[i:i + 2]
    # `--saida` guarda o corpo da leitura num arquivo; o resto dos argumentos é posicional.
    saida = None
    if "--saida" in argv:
        i = argv.index("--saida")
        saida = argv[i + 1]
        del argv[i:i + 2]
    # O harness chama s3client.py MÉTODO /caminho BASE ak sk (o shell monta na ordem dele).
    method, path, base, ak, sk = argv[:5]
    body = sys.stdin.buffer.read() if sys.stdin.isatty() is False else b""
    host = base.split("://", 1)[1].rstrip("/")
    amz_date, corpo_hash, auth = assinar(method, path, body, ak, sk, host)
    req = urllib.request.Request(base.rstrip("/") + path, data=body if method in ("PUT", "POST") else None,
                                 method=method)
    # urllib manda o `Host` sozinho; cabecalho "Host" aqui virava DOIS valores e o `getHeader` do
    # servlet devolvia o primeiro — o assinado era outro, e a assinatura nunca conferia.
    req.add_header("host", host)
    req.add_header("x-amz-date", amz_date)
    req.add_header("x-amz-content-sha256", corpo_hash)
    req.add_header("Authorization", auth)
    if method in ("PUT", "POST"):
        req.add_header("Content-Type", "application/octet-stream")
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            status, dados = r.status, r.read()
    except urllib.error.HTTPError as e:
        status, dados = e.code, e.read()
    if status != esperado:
        sys.stderr.write(f"resposta inesperada: {dados[:400].decode(errors='replace')}\n")
    sys.stdout.write(f"STATUS {status}\n")
    sys.stdout.flush()
    if method == "GET" and status == 200:
        # O objeto guardado pelo gateway mora em disco com outro nome; para o harness o que interessa
        # é o corpo, e é ele que fica em `--saida`.
        if saida:
            with open(saida, "wb") as arquivo:
                arquivo.write(dados)
            return 0 if status == esperado else 1
    sys.stdout.buffer.write(dados)
    return 0 if status == esperado else 1


if __name__ == "__main__":
    sys.exit(main())