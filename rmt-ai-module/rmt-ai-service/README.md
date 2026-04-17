# RMT AI Service

Scaffold do Milestone 1 para o serviço Python da RMT.

## Escopo atual

- expõe `GET /health`;
- expõe `POST /api/v1/analyze`;
- usa backend `stub` substituível para validar contrato HTTP sem inferência real;
- reporta `model_loaded=false` enquanto não houver checkpoint carregado de verdade.

## Fora de escopo neste milestone

- `POST /api/v1/analyze/batch`;
- `GET /api/v1/model/info`;
- inferência real com GraphCodeBERT;
- model registry, checkpoint loading e thresholds versionados.

Esses pontos permanecem adiados de propósito para manter o Milestone 1 pequeno, coeso e seguro para evolução posterior.
