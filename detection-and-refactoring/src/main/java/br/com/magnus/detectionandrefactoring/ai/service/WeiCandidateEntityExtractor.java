package br.com.magnus.detectionandrefactoring.ai.service;

import br.com.magnus.config.starter.file.JavaFile;
import br.com.magnus.config.starter.members.candidates.RefactoringCandidate;
import br.com.magnus.detectionandrefactoring.ai.domain.AiAnalysisEntityType;
import br.com.magnus.detectionandrefactoring.ai.domain.AiAnalysisRequest;
import br.com.magnus.detectionandrefactoring.ai.domain.CandidateEntity;
import br.com.magnus.detectionandrefactoring.refactor.methods.weiL.WeiEtAl2014Candidate;
import br.com.magnus.detectionandrefactoring.refactor.methods.weiL.WeiEtAl2014FactoryCandidate;
import br.com.magnus.detectionandrefactoring.refactor.methods.weiL.WeiEtAl2014StrategyCandidate;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
public class WeiCandidateEntityExtractor implements CandidateEntityExtractor {

    @Override
    public Optional<CandidateEntity> extract(RefactoringCandidate candidate) {
        if (!(candidate instanceof WeiEtAl2014Candidate weiCandidate)) {
            return Optional.empty();
        }

        var entityType = weiCandidate instanceof WeiEtAl2014FactoryCandidate
                ? AiAnalysisEntityType.CREATOR
                : AiAnalysisEntityType.METHOD;
        var classDeclaration = weiCandidate.getClassDeclaration();
        var methodDeclaration = weiCandidate.getMethodDcl();

        return Optional.of(new CandidateEntity(
                weiCandidate.getId(),
                entityId(weiCandidate.getFile(), classDeclaration.getNameAsString(), methodDeclaration.getNameAsString()),
                entityType,
                weiCandidate.getEligiblePattern(),
                methodDeclaration.toString(),
                new AiAnalysisRequest.Context(
                        weiCandidate.getFile().getFullName(),
                        weiCandidate.getPkg(),
                        classDeclaration.getNameAsString(),
                        methodDeclaration.getNameAsString(),
                        firstExtendedType(classDeclaration),
                        implementedTypes(classDeclaration),
                        imports(weiCandidate.getCompilationUnit()),
                        null,
                        new AiAnalysisRequest.StructuralHints(
                                hasSwitch(methodDeclaration),
                                hasFactoryCalls(methodDeclaration),
                                usesInheritance(classDeclaration),
                                usesComposition(weiCandidate)
                        )
                )
        ));
    }

    private String entityId(JavaFile file, String className, String methodName) {
        return file.getFullName() + "::" + className + "::" + methodName;
    }

    private List<String> imports(CompilationUnit compilationUnit) {
        return compilationUnit.getImports().stream()
                .map(importDeclaration -> importDeclaration.getNameAsString())
                .toList();
    }

    private List<String> implementedTypes(ClassOrInterfaceDeclaration classDeclaration) {
        return classDeclaration.getImplementedTypes().stream()
                .map(ClassOrInterfaceType::getNameAsString)
                .toList();
    }

    private String firstExtendedType(ClassOrInterfaceDeclaration classDeclaration) {
        return classDeclaration.getExtendedTypes().stream()
                .map(ClassOrInterfaceType::getNameAsString)
                .findFirst()
                .orElse(null);
    }

    private Boolean hasSwitch(MethodDeclaration methodDeclaration) {
        return !methodDeclaration.findAll(SwitchStmt.class).isEmpty();
    }

    private Boolean hasFactoryCalls(MethodDeclaration methodDeclaration) {
        return !methodDeclaration.findAll(ObjectCreationExpr.class).isEmpty();
    }

    private Boolean usesInheritance(ClassOrInterfaceDeclaration classDeclaration) {
        return !classDeclaration.getExtendedTypes().isEmpty();
    }

    private Boolean usesComposition(WeiEtAl2014Candidate candidate) {
        if (candidate instanceof WeiEtAl2014StrategyCandidate strategyCandidate) {
            return !strategyCandidate.getVariables().isEmpty();
        }
        return null;
    }
}
