package br.com.magnus.detectionandrefactoring.ai.experimental.universe;

import br.com.magnus.config.starter.patterns.DesignPattern;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.SuperExpr;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.SwitchStmt;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;

@Component
public class HardNegativeSignalDetector {

    List<String> signalsForPattern(DesignPattern pattern, MethodDeclaration method, ClassOrInterfaceDeclaration owningClass) {
        var signals = switch (pattern) {
            case STRATEGY -> strategySignals(method);
            case TEMPLATE_METHOD -> templateSignals(method, owningClass);
            case FACTORY_METHOD -> factorySignals(method);
            default -> List.<String>of();
        };
        signals.sort(Comparator.naturalOrder());
        return signals;
    }

    private List<String> strategySignals(MethodDeclaration method) {
        var ordered = new LinkedHashSet<String>();
        var body = method.getBody();
        if (body.isEmpty()) {
            return List.of();
        }
        var b = body.get();
        if (!b.findAll(SwitchStmt.class).isEmpty()) {
            ordered.add("CONTAINS_SWITCH");
        }
        var ifStmts = b.findAll(IfStmt.class);
        if (ifStmts.size() >= 2) {
            ordered.add("MULTIPLE_CONDITIONALS");
        }
        var nested = false;
        for (var ifStmt : ifStmts) {
            var parentOpt = ifStmt.getParentNode();
            while (parentOpt.isPresent()) {
                var parent = parentOpt.get();
                if (parent instanceof IfStmt) {
                    nested = true;
                    break;
                }
                parentOpt = parent.getParentNode();
            }
            if (nested) {
                break;
            }
        }
        if (nested) {
            ordered.add("NESTED_CONDITIONALS");
        }
        return new ArrayList<>(ordered);
    }

    private List<String> templateSignals(MethodDeclaration method, ClassOrInterfaceDeclaration owningClass) {
        var ordered = new LinkedHashSet<String>();
        if (!owningClass.getExtendedTypes().isEmpty()) {
            ordered.add("CLASS_HAS_EXTENDS");
        }
        for (var ann : method.getAnnotations()) {
            if ("Override".equals(ann.getNameAsString())) {
                ordered.add("HAS_OVERRIDE_ANNOTATION");
                break;
            }
        }
        var body = method.getBody();
        if (!body.isEmpty() && !body.get().findAll(SuperExpr.class).isEmpty()) {
            ordered.add("USES_SUPER");
        }
        return new ArrayList<>(ordered);
    }

    private List<String> factorySignals(MethodDeclaration method) {
        var ordered = new LinkedHashSet<String>();
        var body = method.getBody();
        if (!body.isEmpty() && !body.get().findAll(ObjectCreationExpr.class).isEmpty()) {
            ordered.add("HAS_OBJECT_CREATION");
        }
        var name = method.getNameAsString();
        if (name.startsWith("create") || name.startsWith("build")) {
            ordered.add("FACTORY_LIKE_METHOD_NAME");
        }
        return new ArrayList<>(ordered);
    }
}
