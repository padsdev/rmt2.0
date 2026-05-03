# AGENTS.md — Instruções para Agente Cursor/Coding Agent

## 1. Papel do agente

Você está trabalhando no projeto de TCC de Augusto Forte Padilla sobre a expansão da Refactoring and Measurement Tool (RMT) com um módulo de Inteligência Artificial.

Seu objetivo é implementar, de forma segura e rastreável, uma arquitetura experimental com os modos:

- `heuristic-only`
- `candidate-universe-export`
- `shadow-stub`
- `shadow-real-model`
- `semantic-only` ou `ai-only`

A implementação deve preservar a RMT original, evitar alterações destrutivas e produzir evidências experimentais para o Capítulo 4 do TCC.

---

## 2. Regra principal

Não faça “mágicas”.

Antes de alterar código:

1. leia este arquivo;
2. leia `SPEC.md`;
3. rode `git status`;
4. identifique a branch atual;
5. explique o plano;
6. faça mudanças pequenas;
7. rode testes;
8. reporte exatamente o que mudou.

---

## 3. Comandos iniciais obrigatórios

Execute antes de qualquer alteração:

```bash
git status
git branch --show-current
git log --oneline --decorate --graph --all -n 30
```

Se houver mudanças locais não commitadas, não apague nada. Primeiro reporte.

Se o usuário autorizar preservar as mudanças:

```bash
git stash push -u -m "work-in-progress-before-semantic-only"
```

Se o usuário pedir base limpa, use preferencialmente uma nova branch a partir do commit adequado.

Commit recomendado como base conceitual:

```text
feat: enhance candidate universe evaluation with threshold sweep metrics...
```

Criar branch:

```bash
git switch -c experiment/semantic-layer-from-rmt-evidence <HASH_DO_COMMIT>
```

---

## 4. Proibições

Não faça:

- `git reset --hard` sem autorização explícita;
- `git clean -fd` sem autorização explícita;
- force push;
- rebase destrutivo;
- apagar diretórios de experimento;
- apagar checkpoints de modelo;
- mudar arquivos LaTeX do TCC sem pedido direto;
- alterar comportamento determinístico da aplicação de refatorações;
- fazer fallback silencioso do modelo real para stub;
- treinar e avaliar no mesmo conjunto sem registrar isso;
- formatar o repositório inteiro;
- “melhorar” código fora do escopo.

---

## 5. Mapa provável do repositório

Procure por estes caminhos:

```text
detection-and-refactoring/
rmt-ai-module/rmt-ai-service/
experiments/
experiments/run-benchmark.sh
experiments/run-repeated-benchmark.sh
experiments/rmt-shadow-eval.sh
experiments/repeated_benchmark_aggregate.py
experiments/benchmark-projects.csv
docs/tcc/
docs/tcc/results/
```

Se algum caminho não existir, não invente. Procure com:

```bash
find . -maxdepth 4 -type f | sort | sed -n '1,200p'
```

---

## 6. Conceitos que devem guiar a implementação

### 6.1 `heuristic-only`

Modo da RMT 2.0 original.

Deve:

- rodar a heurística;
- gerar baseline;
- capturar tempo;
- exportar candidatos;
- fornecer rótulos operacionais.

Não deve:

- chamar modelo de IA;
- alterar fluxo por causa da IA.

---

### 6.2 `candidate-universe-export`

Etapa que exporta entidades avaliáveis.

Deve conter:

- positivos: entidades aprovadas pela heurística;
- negativos: entidades analisáveis rejeitadas pela heurística;
- `trace_id`;
- `run_id`;
- projeto;
- arquivo;
- classe;
- método;
- trecho de código;
- labels por padrão.

Formato esperado:

```text
JSONL: uma observação por linha
```

---

### 6.3 `shadow-stub`

Backend simulado.

Serve para:

- validar integração;
- validar contrato HTTP/JSON;
- validar JSONL;
- validar `trace_id`;
- validar avaliador;
- não medir qualidade da IA.

Não apresente resultados de stub como desempenho de modelo.

---

### 6.4 `shadow-real-model`

Modo shadow com modelo real.

Serve para:

- rodar GraphCodeBERT em paralelo à heurística;
- registrar previsões;
- comparar depois;
- não interferir na decisão final da RMT.

Backends:

```text
graphcodebert-zeroshot
graphcodebert-finetuned
```

---

### 6.5 `semantic-only` / `ai-only`

Modo em que a IA decide sobre o candidate universe.

Regras:

- ler `candidate-universe.jsonl`;
- enviar entidades para AI service;
- preservar `trace_id`;
- gerar `semantic-only-predictions.jsonl`;
- não usar a decisão heurística durante a inferência;
- comparar com a heurística apenas no avaliador.

Objetivo:

- avaliar se o modelo aprendeu a reproduzir a referência operacional da RMT.

---

## 7. Ordem recomendada de implementação

### Etapa 1 — Auditoria

Procure no código:

```bash
grep -R "candidate" -n experiments detection-and-refactoring rmt-ai-module | head -100
grep -R "shadow" -n experiments detection-and-refactoring rmt-ai-module | head -100
grep -R "trace_id\|traceId" -n experiments detection-and-refactoring rmt-ai-module | head -100
grep -R "threshold" -n experiments rmt-ai-module | head -100
```

Entregável:

- pequeno relatório no chat;
- lista de arquivos relevantes;
- proposta de patch mínimo.

---

### Etapa 2 — Candidate universe

Verifique se o exporter já existe.

Se existir:

- não reescreva do zero;
- adicione campos faltantes;
- adicione testes.

Se não existir:

- implemente exporter mínimo;
- mantenha isolado por variável de ambiente;
- escreva em JSONL.

Campos obrigatórios:

```text
schema_version
run_id
project_id
entity_id
trace_id
file_path
class_name
method_name
method_signature
slice_type
extractor_type
source_code
heuristic_labels
label_source
is_positive
```

---

### Etapa 3 — Semantic-only runner

Crie preferencialmente:

```text
experiments/run-semantic-only.sh
```

Ele deve:

- validar argumentos;
- recusar arquivo inexistente;
- recusar arquivo vazio;
- criar output dir;
- chamar script Python se necessário;
- preservar logs;
- retornar exit code diferente de zero em erro real.

Sugestão de argumentos:

```bash
--candidate-universe-input <path>
--backend <stub|graphcodebert-zeroshot|graphcodebert-finetuned>
--ai-service-url <url>
--output <path>
--limit <n opcional>
```

---

### Etapa 4 — Avaliador

Estenda o avaliador existente em vez de criar outro, se possível.

Adicionar suporte a:

```bash
--semantic-only-input <path>
```

O avaliador deve produzir:

```text
overall-metrics.csv
metrics-by-pattern.csv
metrics-by-project.csv
threshold-sweep.csv
integrity-report.json
evaluation-summary.md
warnings.md
```

Métricas mínimas:

```text
TP
FP
FN
TN
precision
recall
F1
accuracy
agreement_rate
support
parse_failures
schema_issues
duplicates
purity_failures
```

---

### Etapa 5 — Testes

Adicione testes pequenos e objetivos.

Preferências:

- Python: `unittest` ou `pytest`, conforme padrão existente;
- Java: padrão já usado pelo projeto;
- Bash: smoke test com arquivos temporários.

Testes mínimos:

- JSONL válido;
- linha JSON inválida gera parse failure;
- campo obrigatório ausente gera schema issue;
- `trace_id` duplicado gera duplicata;
- run misturado gera purity failure;
- semantic-only perfeito gera precision/recall/F1 = 1;
- semantic-only com FP/FN calcula corretamente;
- threshold sweep gera linhas esperadas.

---

## 8. Comandos de teste sugeridos

Use os comandos que existirem no projeto. Comece por estes:

```bash
python -m unittest discover experiments
```

```bash
cd rmt-ai-module/rmt-ai-service
python -m pytest
```

```bash
cd detection-and-refactoring
./mvnw test
```

Se algum comando falhar por ambiente, reporte:

- comando executado;
- erro resumido;
- se é falha de ambiente ou de código;
- próximo passo recomendado.

Não esconda falhas.

---

## 9. Estilo de código

### Python

- usar tipos quando possível;
- funções pequenas;
- erros explícitos;
- sem prints soltos quando houver logger;
- preservar compatibilidade com scripts existentes;
- não adicionar dependências sem necessidade.

### Bash

- usar `set -euo pipefail`;
- validar argumentos;
- citar variáveis com aspas;
- criar diretórios com `mkdir -p`;
- logs claros;
- mensagens de erro acionáveis.

### Java

- seguir estilo existente;
- não reestruturar pacotes sem necessidade;
- preferir DTOs claros;
- não acoplar lógica de modelo Python no domínio da RMT;
- preservar pipeline determinístico.

---

## 10. Regras de dados experimentais

- Cada execução deve ter `run_id`.
- Cada observação deve ter `trace_id`.
- Arquivos JSONL devem ser truncados ou criados por run, nunca reaproveitados silenciosamente.
- O avaliador deve detectar mistura de runs.
- O semantic-only não deve copiar o rótulo heurístico para a saída de predição.
- O rótulo heurístico só deve ser usado na avaliação posterior.
- Positivos e negativos devem estar identificados no candidate universe.
- Factory Method ausente deve ser reportado como ausência de suporte experimental, não como resultado perfeito.

---

## 11. Como reportar resultados ao usuário

Sempre responda com este formato:

```md
## Resumo
- O que foi implementado.

## Arquivos alterados
- caminho/arquivo: motivo.

## Comandos executados
- comando: resultado.

## Resultados
- métricas ou evidências.

## Pendências
- o que ainda falta.

## Riscos
- qualquer limitação encontrada.
```

Não diga “funciona” sem teste.

---

## 12. Plano de commits sugerido

Commit 1:

```text
feat: export candidate universe for semantic-only evaluation
```

Commit 2:

```text
feat: add semantic-only AI evaluation runner
```

Commit 3:

```text
feat: evaluate semantic-only predictions against RMT labels
```

Commit 4:

```text
test: cover semantic-only evaluation and JSONL integrity
```

Commit 5:

```text
docs: document semantic-only experiment architecture
```

---

## 13. Texto acadêmico que deve orientar decisões

A implementação deve sustentar esta afirmação:

> A RMT 2.0 foi utilizada como referência operacional para gerar rótulos programáticos sobre um universo de entidades candidatas. A partir desses dados, o módulo de IA foi avaliado não apenas em modo shadow, mas também em modo semantic-only, no qual o modelo produz decisões próprias e estas são comparadas posteriormente com a heurística da ferramenta.

Evite implementar qualquer coisa que contradiga essa frase.

---

## 14. Checklist final antes de entregar

- [ ] Branch correta criada.
- [ ] `git status` revisado.
- [ ] Candidate universe exporta positivos e negativos.
- [ ] `trace_id` preservado.
- [ ] JSONL válido.
- [ ] Shadow-stub continua funcionando.
- [ ] Shadow-real-model continua funcionando.
- [ ] Semantic-only implementado.
- [ ] Avaliador compara semantic-only com heurística.
- [ ] Threshold sweep disponível.
- [ ] Testes executados.
- [ ] Resultados documentados.
- [ ] Nenhuma alteração destrutiva feita.
