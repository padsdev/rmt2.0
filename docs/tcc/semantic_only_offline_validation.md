# Validação offline do fluxo semantic-only (stub)

## 1. Contexto

O **semantic-only offline** foi introduzido para **exercitar a cadeia experimental completa**—exportação do candidate universe, geração de predições em modo IA-only sem uso do rótulo heurístico na inferência, e avaliação com pareamento por `trace_id`—**antes** de acoplar um backend semântico real ou fine-tuned e antes de estender o avaliador com threshold sweep.

O runner offline atual usa um **backend stub** determinístico (scores derivados de `trace_id` + padrão, sem ler campos heurísticos proibidos). Por isso, **métricas de classificação obtidas com o stub não medem qualidade semântica** do modelo; servem apenas para verificar que o pipeline de dados, o contrato JSONL e o avaliador se comportam como esperado.

## 2. Fluxo validado

1. **RMT em modo heuristic-only** — detecção heurística, baseline e exportação do candidate universe com rótulos operacionais de referência.
2. **`candidate-universe.jsonl`** — uma linha JSON por observação, com `trace_id` e metadados necessários ao pareamento.
3. **`semantic-only-predictions-full.jsonl`** — uma predição por linha de entrada, sem `source_code` e sem `heuristic_label` (nem outros campos de referência proibidos na saída).
4. **Avaliador semantic-only** (`semantic_only_evaluator.py` via `run-semantic-only-eval.sh`) — leitura JSONL, checagens de integridade, matriz de confusão global e agregações por padrão e por projeto.
5. **Saídas** — `overall-metrics.csv`, `metrics-by-pattern.csv`, `metrics-by-project.csv`, `integrity-report.json`, `evaluation-summary.md`.

## 3. Artefatos usados (run de referência)

| Item | Valor |
|------|--------|
| **RUN_ID** | `cu-heuristic-val-20260503T212802Z` |
| **Candidate universe** | `experiments/runs/cu-heuristic-val-20260503T212802Z/candidate-universe/candidate-universe.jsonl` (**213** linhas) |
| **Predições semantic-only** | `experiments/runs/cu-heuristic-val-20260503T212802Z/semantic-only/semantic-only-predictions-full.jsonl` (**213** linhas) |
| **Diretório de avaliação** | `experiments/runs/cu-heuristic-val-20260503T212802Z/semantic-only-evaluation-full/` |

Os caminhos sob `experiments/runs/` são **locais** ao ambiente do experimentador; não fazem parte do versionamento do repositório (tipicamente ignorados pelo Git).

## 4. Resultado de integridade

Valores registrados na validação bem-sucedida:

| Indicador | Valor |
|-----------|--------|
| `valid_pairs` | **213** |
| `missing_predictions` | **0** |
| `orphan_predictions` | **0** |
| Duplicatas de `trace_id` (predições / universe) | **0** / **0** |
| Falhas de parse (CU / predições) | **0** / **0** |
| Mistura de `run_id` nos arquivos | **false** (sem mistura detectável) |
| `blocking_forbidden_prediction_fields` | **false** |

Restrições adicionais verificadas: **ausência de `source_code`** nas predições; **ausência de `heuristic_label`** nas predições; processo terminou com **código de saída 0**.

## 5. Métricas obtidas com o stub (referência)

Métricas **globais** reportadas pelo avaliador para este run com backend **stub**:

| Métrica | Valor |
|---------|--------|
| TP | 1 |
| FP | 100 |
| FN | 0 |
| TN | 112 |
| precision | 0.009900990099 |
| recall | 1 |
| F1 | 0.019607843137 |
| accuracy / agreement_rate | 0.530516431925 |

## 6. Interpretação

Os valores da secção 5 **não descrevem capacidade de um encoder semântico real**; refletem apenas o comportamento do **stub determinístico** frente aos rótulos heurísticos do candidate universe. Eles **validam o pipeline** (pareamento 1:1, ausência de vazamento de rótulo na predição, relatórios CSV/JSON/Markdown).

A **comparação metodologicamente relevante** para o TCC deverá ser obtida **depois**, com **backend semântico real** (por exemplo, zero-shot ou fine-tuned), mantendo o mesmo protocolo de integridade e avaliação.

## 7. Como reproduzir

A partir da raiz do repositório, **sem** `--limit` no runner (processa todas as linhas não vazias do JSONL):

```bash
./experiments/run-semantic-only.sh \
  --candidate-universe-input experiments/runs/cu-heuristic-val-20260503T212802Z/candidate-universe/candidate-universe.jsonl \
  --output experiments/runs/cu-heuristic-val-20260503T212802Z/semantic-only/semantic-only-predictions-full.jsonl
```

Avaliação:

```bash
./experiments/run-semantic-only-eval.sh \
  --candidate-universe-input experiments/runs/cu-heuristic-val-20260503T212802Z/candidate-universe/candidate-universe.jsonl \
  --semantic-only-predictions-input experiments/runs/cu-heuristic-val-20260503T212802Z/semantic-only/semantic-only-predictions-full.jsonl \
  --output-dir experiments/runs/cu-heuristic-val-20260503T212802Z/semantic-only-evaluation-full
```

**Nota:** substitua o `RUN_ID` e os subdiretórios se o seu run local usar outra pasta. Conteúdo de `experiments/runs/` é **local** (por convenção **gitignored**); este documento versiona apenas o **relato** da validação, não os binários/JSONL do run.

## 8. Implicação para o TCC

Esta etapa **sustenta rastreabilidade e reprodutibilidade** do modo semantic-only: cada observação permanece identificável por `trace_id`, os artefatos são isolados por `run_id`, e o avaliador expõe falhas de parse, duplicidade, órfãos e campos proibidos de forma explícita.

Do ponto de vista da contribuição técnica, demonstra que a **RMT continua a gerar evidências operacionais** (candidate universe com rótulos heurísticos) sobre as quais uma **camada semântica externa** pode ser avaliada **sem alterar** o **pipeline determinístico de detecção e refatoração** da ferramenta: a IA-only roda **fora** da decisão heurística em tempo de inferência, e a comparação ocorre **só na avaliação**.

---

_Validação documentada conforme execução de referência; métricas do stub não devem ser citadas como desempenho de modelo real._
