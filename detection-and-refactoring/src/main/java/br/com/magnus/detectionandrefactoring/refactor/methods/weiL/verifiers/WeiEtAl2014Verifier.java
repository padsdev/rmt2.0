package br.com.magnus.detectionandrefactoring.refactor.methods.weiL.verifiers;

import br.com.magnus.config.starter.file.JavaFile;
import br.com.magnus.config.starter.members.candidates.RefactoringCandidate;
import br.com.magnus.detectionandrefactoring.refactor.dataExtractions.ast.AstHandler;
import br.com.magnus.detectionandrefactoring.refactor.dataExtractions.ast.exceptions.AstHandlerException;
import br.com.magnus.detectionandrefactoring.refactor.methods.weiL.RefactoringCandidatesVerifier;
import br.com.magnus.detectionandrefactoring.refactor.methods.weiL.WeiEtAl2014Candidate;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.type.VoidType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.Assert;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Slf4j
public abstract class WeiEtAl2014Verifier implements RefactoringCandidatesVerifier {

    public List<RefactoringCandidate> retrieveCandidatesFrom(List<JavaFile> javaFiles) {
        Assert.notNull(javaFiles, "JavaFiles cannot be null");
        final var candidates = new ArrayList<RefactoringCandidate>();

        javaFiles.forEach(file -> collectCandidatesForFile(javaFiles, file, candidates));
        return candidates;
    }

    private void collectCandidatesForFile(List<JavaFile> javaFiles, JavaFile file, List<RefactoringCandidate> candidates) {
        try {
            if (file.getCompilationUnit() == null) {
                log.warn(
                        "Skipping Wei candidate extraction for file without parsed compilation unit path={} skip_reason=NO_COMPILATION_UNIT",
                        resolvePath(file)
                );
                return;
            }

            var classOrInterface = AstHandler.getClassOrInterfaceDeclaration(file.getCompilationUnit());

            classOrInterface.ifPresent(classOrInterfaceDeclaration -> {
                if (!classOrInterfaceDeclaration.isInterface()) {
                    for (var method : AstHandler.getMethods(classOrInterfaceDeclaration)) {
                        final var candidate = this.retrieveCandidate(javaFiles, file, method);
                        candidate.ifPresent(candidates::add);
                    }
                }
            });
        } catch (AstHandlerException | IllegalArgumentException exception) {
            log.warn(
                    "Skipping Wei candidate extraction for invalid Java file path={} skip_reason=INVALID_JAVA_FILE reason={}",
                    resolvePath(file),
                    exception.getMessage(),
                    exception
            );
        }
    }

    private boolean isMethodInvalid(MethodDeclaration method) {
        return method.getParameters() == null
                || method.getParameters().isEmpty()
                || (method.getType() instanceof VoidType);
    }

    private Optional<WeiEtAl2014Candidate> retrieveCandidate(List<JavaFile> javaFiles, JavaFile file, MethodDeclaration method) {

        if (this.isMethodInvalid(method)) {
            return Optional.empty();
        }

        final var ifStatements = AstHandler.getIfStatements(method);

        if (!this.areIfStmtsValid(javaFiles, file,method, ifStatements)) {
            return Optional.empty();
        }

        return Optional.of(this.createCandidate(file, method, ifStatements));
    }

    protected abstract WeiEtAl2014Candidate createCandidate(JavaFile file, MethodDeclaration method, Collection<IfStmt> ifStatements);

    protected abstract boolean areIfStmtsValid(List<JavaFile> javaFiles, JavaFile file, MethodDeclaration method, Collection<IfStmt> ifStatements);

    private String resolvePath(JavaFile file) {
        if (file == null) {
            return "<unknown>";
        }
        if (file.getPath() == null) {
            return file.getName();
        }
        return file.getPath() + file.getName();
    }

}
