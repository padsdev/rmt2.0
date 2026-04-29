# RMT AI Service

Serviço Python da RMT com três backends:

- `stub`: caminho determinístico para testes rápidos, validação de contrato e baseline;
- `graphcodebert`: inferência zero-shot baseada em embeddings do `microsoft/graphcodebert-base`.
- `graphcodebert_finetuned`: inferência supervisionada a partir de checkpoint treinado offline com o dataset grounded.

## Endpoints

- `GET /health`
- `GET /api/v1/model/info`
- `POST /api/v1/analyze`

## Configuração relevante

- `RMT_AI_BACKEND_MODE=stub|graphcodebert|graphcodebert_finetuned`
- `RMT_AI_GRAPHCODEBERT_MODEL_NAME=microsoft/graphcodebert-base`
- `RMT_AI_FINETUNED_ARTIFACT_PATH=/abs/path/to/artifacts`
- `RMT_AI_MODEL_MAX_LENGTH=512`
- `RMT_AI_DEVICE_PREFERENCE=auto|cpu|cuda|mps`
- `RMT_AI_EXPERIMENT_PROFILE=default|low-template-threshold|per-pattern-threshold`

## Observações

- O backend `graphcodebert` carrega tokenizer e modelo no startup do FastAPI.
- O backend `graphcodebert_finetuned` carrega tokenizer, encoder, checkpoint e thresholds calibrados no startup.
- `model_loaded=true` só é exposto quando o carregamento do backend real conclui com sucesso.
- A estratégia atual de inferência real é zero-shot por similaridade entre embedding de código e embeddings de descrições textuais dos padrões suportados.
- O pipeline supervisionado usa `heuristic_pattern` como rótulo operacional. Isso não é ground truth absoluto.

## Treinamento offline

Execute a partir de `rmt-ai-module/rmt-ai-service`:

```bash
python -m training.train \
  --input /abs/path/to/grounded-jsonl \
  --output /abs/path/to/training-artifacts \
  --epochs 3 \
  --batch-size 4 \
  --seed 42
```

Artifacts gerados:

- `checkpoint.pt`
- `label_mapping.json`
- `thresholds.json`
- `training_config.json`
- `metrics.json`
- `split_manifest.json`
