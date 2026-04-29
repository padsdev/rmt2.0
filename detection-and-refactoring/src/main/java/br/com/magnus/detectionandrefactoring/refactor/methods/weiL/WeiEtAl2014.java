package br.com.magnus.detectionandrefactoring.refactor.methods.weiL;

import br.com.magnus.config.starter.file.JavaFile;
import br.com.magnus.config.starter.members.RefactorFiles;
import br.com.magnus.config.starter.members.candidates.RefactoringCandidate;
import br.com.magnus.config.starter.patterns.DesignPattern;
import br.com.magnus.detectionandrefactoring.refactor.dataExtractions.ExtractionMethodFactory;
import br.com.magnus.detectionandrefactoring.refactor.dataExtractions.ast.AbstractSyntaxTreeExtraction;
import br.com.magnus.detectionandrefactoring.refactor.methods.weiL.executors.WeiEtAl2014Executor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

@Slf4j
@Component
@RequiredArgsConstructor
public class WeiEtAl2014 implements AbstractSyntaxTreeExtraction {

    private final List<RefactoringCandidatesVerifier> refactoringCandidatesVerifier;
    private final ExtractionMethodFactory extractionMethodFactory;
    @Getter
    private final Set<DesignPattern> designPatterns = Set.of(DesignPattern.STRATEGY, DesignPattern.FACTORY_METHOD);
    private final List<WeiEtAl2014Executor> executors;

    public List<RefactoringCandidate> extractCandidates(List<JavaFile> javaFiles) {
        this.extractionMethodFactory.build(this).parseAll(javaFiles);
        var validJavaFiles = javaFiles.stream()
                .filter(this::hasParsedCompilationUnit)
                .toList();
        return this.refactoringCandidatesVerifier.stream()
                .map(f -> f.retrieveCandidatesFrom(validJavaFiles))
                .flatMap(List::stream)
                .toList();
    }

    private boolean hasParsedCompilationUnit(JavaFile file) {
        if (file.getCompilationUnit() != null) {
            return true;
        }
        log.warn("Skipping Java file without parsed compilation unit path={} skip_reason=NO_COMPILATION_UNIT", resolvePath(file));
        return false;
    }

    private String resolvePath(JavaFile file) {
        if (file == null) {
            return "<unknown>";
        }
        if (file.getPath() == null) {
            return file.getName();
        }
        return file.getPath() + file.getName();
    }

    public void refactor(RefactorFiles refactorFiles) {
        this.getExecutors()
                .filter(e -> e.isApplicable(refactorFiles.candidate()))
                .findFirst()
                .orElseThrow(IllegalArgumentException::new)
                .refactor(refactorFiles);
    }

    private Stream<WeiEtAl2014Executor> getExecutors() {
        return executors.stream();
    }
}
