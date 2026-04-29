from __future__ import annotations

from typing import Any


class GraphCodeBertForMultilabelClassification:
    def __init__(self, torch_module: Any, encoder: Any, *, num_labels: int, dropout_probability: float = 0.1) -> None:
        self._torch = torch_module
        self.encoder = encoder
        self.dropout = torch_module.nn.Dropout(dropout_probability)
        hidden_size = int(encoder.config.hidden_size)
        self.classifier = torch_module.nn.Linear(hidden_size, num_labels)

    def __call__(self, input_ids: Any, attention_mask: Any) -> Any:
        return self.forward(input_ids=input_ids, attention_mask=attention_mask)

    def forward(self, *, input_ids: Any, attention_mask: Any) -> Any:
        outputs = self.encoder(input_ids=input_ids, attention_mask=attention_mask)
        last_hidden_state = outputs.last_hidden_state
        mask = attention_mask.unsqueeze(-1).to(last_hidden_state.dtype)
        masked_embeddings = last_hidden_state * mask
        pooled_embeddings = masked_embeddings.sum(dim=1) / mask.sum(dim=1).clamp(min=1.0)
        pooled_embeddings = self.dropout(pooled_embeddings)
        return self.classifier(pooled_embeddings)

    def to(self, device: str) -> "GraphCodeBertForMultilabelClassification":
        self.encoder.to(device)
        self.classifier.to(device)
        self.dropout.to(device)
        return self

    def train(self) -> "GraphCodeBertForMultilabelClassification":
        self.encoder.train()
        self.classifier.train()
        self.dropout.train()
        return self

    def eval(self) -> "GraphCodeBertForMultilabelClassification":
        self.encoder.eval()
        self.classifier.eval()
        self.dropout.eval()
        return self

    def state_dict(self) -> dict[str, Any]:
        return {
            "encoder": self.encoder.state_dict(),
            "classifier": self.classifier.state_dict(),
        }

    def load_state_dict(self, state_dict: dict[str, Any]) -> None:
        self.encoder.load_state_dict(state_dict["encoder"])
        self.classifier.load_state_dict(state_dict["classifier"])


def create_classifier_from_encoder(
    *,
    torch_module: Any,
    encoder: Any,
    num_labels: int,
    dropout_probability: float = 0.1,
) -> GraphCodeBertForMultilabelClassification:
    return GraphCodeBertForMultilabelClassification(
        torch_module=torch_module,
        encoder=encoder,
        num_labels=num_labels,
        dropout_probability=dropout_probability,
    )

