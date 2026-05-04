package br.com.magnus.detectionandrefactoring.ai.service;

import br.com.magnus.config.starter.file.JavaFile;
import br.com.magnus.config.starter.members.candidates.RefactoringCandidate;
import br.com.magnus.detectionandrefactoring.ai.domain.AiAnalysisEntityType;
import br.com.magnus.detectionandrefactoring.ai.domain.AiAnalysisRequest;
import br.com.magnus.detectionandrefactoring.ai.domain.CandidateEntity;
import br.com.magnus.detectionandrefactoring.refactor.methods.zaiferisVE.ZafeirisEtAl2016Candidate;
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
public class ZafeirisCandidateEntityExtractor implements CandidateEntityExtractor {

    @Override
    public Optional<CandidateEntity> extract(RefactoringCandidate candidate) {
        if (!(candidate instanceof ZafeirisEtAl2016Candidate zafeirisCandidate)) {
            return Optional.empty();
        }

        var classDeclaration = zafeirisCandidate.getClassDeclaration();
        var methodDeclaration = zafeirisCandidate.getOverridingMethod();

        return Optional.of(new CandidateEntity(
                zafeirisCandidate.getId(),
                entityId(zafeirisCandidate.getFile(), classDeclaration.getNameAsString(), methodDeclaration.getNameAsString()),
                AiAnalysisEntityType.HIERARCHY,
                zafeirisCandidate.getEligiblePattern(),
                zafeirisCandidate.getCompilationUnit().toString(),
                new AiAnalysisRequest.Context(
                        zafeirisCandidate.getFile().getFullName(),
                        zafeirisCandidate.getPkg(),
                        classDeclaration.getNameAsString(),
                        methodDeclaration.getNameAsString(),
                        zafeirisCandidate.getParentType(),
                        implementedTypes(classDeclaration),
                        imports(zafeirisCandidate.getCompilationUnit()),
                        null,
                        new AiAnalysisRequest.StructuralHints(
                                hasSwitch(methodDeclaration),
                                hasFactoryCalls(methodDeclaration),
                                true,
                                null
                        )
                ),
                "compilation_unit",
                "zafeiris"
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

    private Boolean hasSwitch(MethodDeclaration methodDeclaration) {
        return !methodDeclaration.findAll(SwitchStmt.class).isEmpty();
    }

    private Boolean hasFactoryCalls(MethodDeclaration methodDeclaration) {
        return !methodDeclaration.findAll(ObjectCreationExpr.class).isEmpty();
    }
}
