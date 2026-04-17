# PLANS.md

## Milestone 1 — Scaffold do serviço
Critérios:
- criar estrutura FastAPI
- endpoint `/health`
- endpoint `/api/v1/analyze` stubado
Validação:
- `pytest`
- subir API localmente

## Milestone 2 — Contrato de entrada/saída
Critérios:
- models Pydantic
- payload com trace_id, project_id, entity_id, entity_type, source_code, metadata
- response com pattern_scores, predicted_labels, confidence, explanation
Validação:
- testes de schema
- request de exemplo funcionando

## Milestone 3 — Pipeline de inferência
Critérios:
- carregar GraphCodeBERT
- tokenização
- classificador multilabel
- thresholds configuráveis
Validação:
- teste de inferência smoke
- logs de latência

## Milestone 4 — Extração Java na RMT
Critérios:
- Candidate Builder no lado Java
- serialização do payload
- chamada HTTP ao serviço
Validação:
- teste de integração com fixture Java pequeno

## Milestone 5 — Avaliação experimental
Critérios:
- exportar previsões
- comparar com baseline heurístico
- calcular precision, recall e F1
Validação:
- script reproduzível rodando num subconjunto do benchmark