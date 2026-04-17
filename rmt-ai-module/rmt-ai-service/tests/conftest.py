from pathlib import Path
import sys

PROJECT_ROOT = Path(__file__).resolve().parents[1]
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))


def build_analyze_payload() -> dict:
    return {
        "trace_id": "9ce0db76-b5ea-4722-8c1c-4d8a8a8250e4",
        "project_id": "project-17",
        "entity_id": "src/main/java/foo/Bar.java::Bar::calculate",
        "language": "java",
        "entity_type": "method",
        "pattern_scope": ["TEMPLATE_METHOD", "STRATEGY", "FACTORY_METHOD"],
        "source_code": "public class Bar { void calculate() { if (flag) run(); } }",
        "context": {
            "file_path": "src/main/java/foo/Bar.java",
            "package_name": "foo",
            "class_name": "Bar",
            "method_name": "calculate",
            "super_class": "BaseBar",
            "interfaces": ["Rule"],
            "imports": ["java.util.*"],
            "metrics": {"loc": 87, "cc": 12, "dit": 2},
            "structural_hints": {
                "has_switch": True,
                "has_factory_calls": False,
                "uses_inheritance": True,
                "uses_composition": True,
            },
        },
    }
