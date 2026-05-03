# Zeroshot vs Finetuned Summary

| Dimension | Zeroshot | Finetuned | Difference |
|---|---|---|---|
| M4 total records | 46 | 46 | 0 |
| M4 duplicates | 0 | 0 | 0 |
| M5 TP/FP/FN | 46 / 0 / 0 | 46 / 0 / 0 | identical |
| M5 valid observations | 46 | 46 | 0 |
| M5 agreement rate | 1.0 | 1.0 | identical |
| Pattern metrics | STRATEGY: 22/0/0; TEMPLATE_METHOD: 24/0/0 | STRATEGY: 22/0/0; TEMPLATE_METHOD: 24/0/0 | identical |
| Runtime overhead (%) | 43.571332 | 10.460256 | finetuned lower by 33.111076 p.p. |
| Total AI duration (ms) | 40799 | 28997 | finetuned lower by 11802 ms |

No M5 quality improvement is supported by the current metrics because classification outputs are identical across runs.
