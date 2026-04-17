# AGENTS.md

## Objetivo do repositório
Implementar o módulo de IA da RMT para detecção de candidatos à refatoração em Java,
usando GraphCodeBERT como classificador e preservando a aplicação determinística
da refatoração no núcleo da RMT.

## Fonte de verdade
- Leia primeiro `docs/architecture/SPEC_RMT_GraphCodeBERT_Modulo_IA.md`.
- Se houver conflito entre código existente e spec, prefira preservar compatibilidade com a RMT e registre a divergência.

## Restrições
- Não substituir o pipeline de refatoração da RMT.
- Não usar LLM generativa como arquitetura principal de detecção.
- Não expandir escopo para geração automática de código refatorado.
- Não introduzir novas dependências sem justificar no diff.

## Arquitetura esperada
- Serviço de IA em Python com FastAPI.
- Núcleo RMT continua em Java.
- Integração via HTTP/JSON.
- GraphCodeBERT como modelo principal de classificação.
- A unidade de análise é entidade candidata, não projeto inteiro.

## Regras de implementação
- Trabalhar em milestones pequenas.
- Após cada milestone, rodar validação local.
- Se teste falhar, corrigir antes de seguir.
- Manter logs, trace_id e payloads reproduzíveis.
- Escrever código e testes minimamente suficientes, sem overengineering.

## Done when
- Endpoint sobe localmente.
- Payload e response seguem contrato do spec.
- Testes essenciais passam.
- Integração mínima com a RMT está demonstrável.
- Documentação da mudança foi atualizada.
