# TCC — Experimental Hardening Plan

## Contexto

Este projeto integra um módulo de Inteligência Artificial à RMT 2.0 para apoiar a detecção de candidatos à refatoração para padrões de projeto em código Java.

O estado atual do experimento avalia o módulo em modo SHADOW. Nesse modo, a IA analisa entidades de código em paralelo à heurística da RMT, mas não interfere na decisão final nem na aplicação das refatorações.

A avaliação atual compara as previsões da IA com a saída heurística da RMT 2.0, utilizada como referência operacional. O resultado atual apresenta 46 observações válidas, 46 verdadeiros positivos, 0 falsos positivos, 0 falsos negativos, precisão 1,00, revocação 1,00, F1-score 1,00 e taxa de concordância 1,00 para os backends zeroshot e fine-tuned.

O objetivo desta etapa não é criar uma ground truth humana independente. O objetivo é fortalecer a avaliação experimental atual ampliando o universo analisado, adicionando exemplos negativos, calculando métricas mais robustas, avaliando sensibilidade a thresholds e repetindo automaticamente os benchmarks.

## Objetivo geral desta implementação

Transformar a avaliação atual de:

> “A IA concorda com os candidatos positivos detectados pela RMT?”

para:

> “A IA consegue separar, ranquear e reproduzir a decisão heurística da RMT sobre um universo maior de entidades candidatas e não candidatas, em múltiplas execuções e thresholds?”

## Não objetivos

Não implementar ground truth humana.

Não ativar modo GATED.

Não permitir que a IA altere a decisão final da RMT.

Não aplicar refatorações com base exclusiva na IA.

Não substituir a heurística da RMT.

Não reescrever a arquitetura existente.

Não remover os artefatos M4/M5 já existentes.

Não alterar resultados antigos sem preservar compatibilidade.

## Princípios obrigatórios

Toda nova exportação deve conter `run_id`.

Toda observação deve conter uma chave lógica estável para pareamento.

Todo arquivo experimental deve ser isolado por execução.

Nenhuma execução deve depender de arquivo residual de execução anterior.

O avaliador deve falhar explicitamente em caso de schema inválido, parse error ou chave ausente.

Não deve existir fallback silencioso.

Não inventar métricas nem resultados.

Não alterar o texto do TCC automaticamente.

Preservar compatibilidade com os CSVs/JSONLs antigos sempre que possível.

Adicionar testes automatizados para cada nova etapa.

---

# Fase 1 — Expandir o exportador: universo de entidades

## Objetivo

Criar uma exportação experimental ampliada contendo não apenas os candidatos positivos detectados pela RMT, mas também entidades analisáveis que não foram classificadas pela heurística como candidatas.

Essa fase deve gerar um arquivo JSONL contendo um universo de avaliação composto por:

1. Positivos heurísticos da RMT.
2. Negativos aleatórios.
3. Negativos difíceis, também chamados de hard negatives.

## Saída esperada

Criar um arquivo por execução:

```text
experiments/runs/<run-id>/candidate-universe.jsonl

{
  "schema_version": "candidate-universe-v1",
  "run_id": "m7-expanded-universe-001",
  "project_id": "p04",
  "project_name": "caliper",
  "project_commit": "UNKNOWN_OR_HASH",
  "entity_key": "p04|src/main/java/X.java|com.example.X|methodName(java.lang.String)|STRATEGY",
  "entity_id": "stable-entity-id",
  "entity_type": "METHOD",
  "file_path": "src/main/java/com/example/X.java",
  "class_name": "com.example.X",
  "method_name": "methodName",
  "method_signature": "methodName(java.lang.String)",
  "pattern": "STRATEGY",
  "source_code_hash": "sha256:...",
  "source_code": "...",
  "heuristic_label": 1,
  "heuristic_candidate_id": "optional-existing-rmt-candidate-id",
  "negative_type": null,
  "extractor_reason": "rmt_positive_candidate",
  "metadata": {
    "has_if": true,
    "has_switch": false,
    "extends_class": false,
    "overrides_method": false,
    "calls_super": false
  }
}