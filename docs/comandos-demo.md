# Comandos da apresentacao

Entrar na pasta do projeto:

```bash
cd minidynamo
```

Subir os tres servidores:

```bash
docker compose up -d --build
```

Conferir se estao saudaveis:

```bash
docker compose ps
```

Demo 1 - cabo entre SP e o resto:

```bash
powershell -ExecutionPolicy Bypass -File .\scripts\demo-particao.ps1
```

Demo 2 - servidor RJ cai, com recado:

```bash
powershell -ExecutionPolicy Bypass -File .\scripts\demo-queda-de-no.ps1
```

Demo 3 - convergencia sem leitura:

```bash
powershell -ExecutionPolicy Bypass -File .\scripts\demo-anti-entropia.ps1
```

Demo 4 - forte, eventual e causal com o mesmo carrinho:

```bash
powershell -ExecutionPolicy Bypass -File .\scripts\demo-modos.ps1
```

Demo 5 - falha no eventual:

```bash
powershell -ExecutionPolicy Bypass -File .\scripts\demo-falha-eventual.ps1
```

Experimentos com numeros:

```bash
powershell -ExecutionPolicy Bypass -File .\scripts\experimentos.ps1 -Repeticoes 3 -Ops 300 -Chaves 30 -LatenciaMs 120 -JitterMs 40
```

Testes unitarios:

```bash
mvn -B test
```

Plano B, sem Docker (depois de compilar):

```bash
mvn -B -DskipTests package
```

```bash
powershell -ExecutionPolicy Bypass -File .\scripts\local-3servidores.ps1
```

Encerrar o plano B:

```bash
powershell -ExecutionPolicy Bypass -File .\scripts\local-3servidores.ps1 -Parar
```

Reiniciar o cluster do zero:

```bash
docker compose down
```

```bash
docker compose up -d
```
