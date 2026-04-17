from enum import Enum


class PatternLabel(str, Enum):
    TEMPLATE_METHOD = "TEMPLATE_METHOD"
    STRATEGY = "STRATEGY"
    FACTORY_METHOD = "FACTORY_METHOD"


SUPPORTED_PATTERN_LABELS: tuple[PatternLabel, ...] = tuple(PatternLabel)


class EntityType(str, Enum):
    CLASS = "class"
    METHOD = "method"
    HIERARCHY = "hierarchy"
    CREATOR = "creator"


class Language(str, Enum):
    JAVA = "java"
