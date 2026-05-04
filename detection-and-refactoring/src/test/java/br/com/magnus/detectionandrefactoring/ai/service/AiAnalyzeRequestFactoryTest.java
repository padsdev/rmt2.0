package br.com.magnus.detectionandrefactoring.ai.service;

import br.com.magnus.config.starter.file.JavaFile;
import br.com.magnus.config.starter.patterns.DesignPattern;
import br.com.magnus.config.starter.projects.BaseProject;
import br.com.magnus.config.starter.projects.Project;
import br.com.magnus.detectionandrefactoring.ai.domain.AiAnalysisEntityType;
import br.com.magnus.detectionandrefactoring.ai.domain.PreparedAiAnalysisRequest;
import br.com.magnus.detectionandrefactoring.refactor.methods.weiL.WeiEtAl2014StrategyCandidate;
import br.com.magnus.detectionandrefactoring.refactor.methods.zaiferisVE.ZafeirisEtAl2016Candidate;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.SuperExpr;
import com.github.javaparser.ast.stmt.IfStmt;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiAnalyzeRequestFactoryTest {

    private final AiAnalyzeRequestFactory factory = new AiAnalyzeRequestFactory(
            List.of(new WeiCandidateEntityExtractor(), new ZafeirisCandidateEntityExtractor())
    );
    private final JavaParser javaParser = new JavaParser();

    @Test
    void shouldCreateStrategyRequestFromWeiCandidate() {
        var source = """
                package foo;
                import java.util.List;

                class OrderService {
                    private Object strategy;

                    void execute(String kind) {
                        if ("credit".equals(kind)) {
                            var created = new Object();
                            System.out.println(created);
                        }
                    }
                }
                """;
        var compilationUnit = parse(source);
        var classDeclaration = compilationUnit.findFirst(ClassOrInterfaceDeclaration.class).orElseThrow();
        var methodDeclaration = compilationUnit.findFirst(MethodDeclaration.class).orElseThrow();
        var packageDeclaration = compilationUnit.getPackageDeclaration().orElseThrow();
        var file = javaFile("OrderService.java", "src/main/java/foo/", source, compilationUnit);
        var candidate = new WeiEtAl2014StrategyCandidate(
                file,
                compilationUnit,
                packageDeclaration,
                classDeclaration,
                methodDeclaration,
                methodDeclaration.findAll(IfStmt.class),
                classDeclaration.findAll(com.github.javaparser.ast.body.VariableDeclarator.class)
        );

        var preparedRequest = factory.create(project(), candidate);

        assertTrue(preparedRequest.isPresent());
        assertEquals("method", preparedRequest.get().sliceType());
        assertEquals("wei", preparedRequest.get().extractorType());
        assertStrategyRequest(preparedRequest.get(), candidate.getId());
    }

    @Test
    void shouldCreateHierarchyRequestFromTemplateMethodCandidate() {
        var source = """
                package foo;

                abstract class ParentTemplate {
                    void process() {
                    }
                }

                class ChildTemplate extends ParentTemplate {
                    @Override
                    void process() {
                        before();
                        super.process();
                        after();
                    }

                    void before() {
                    }

                    void after() {
                    }
                }
                """;
        var compilationUnit = parse(source);
        var classes = compilationUnit.findAll(ClassOrInterfaceDeclaration.class);
        var parentClass = classes.getFirst();
        var childClass = classes.getLast();
        var methods = compilationUnit.findAll(MethodDeclaration.class);
        var parentMethod = methods.stream()
                .filter(method -> method.getParentNode().filter(parentClass::equals).isPresent())
                .findFirst()
                .orElseThrow();
        var childMethod = methods.stream()
                .filter(method -> method.getParentNode().filter(childClass::equals).isPresent())
                .filter(method -> method.getNameAsString().equals("process"))
                .findFirst()
                .orElseThrow();
        var file = javaFile("ChildTemplate.java", "src/main/java/foo/", source, compilationUnit);
        var candidate = ZafeirisEtAl2016Candidate.builder()
                .file(file)
                .compilationUnit(compilationUnit)
                .packageDcl(compilationUnit.getPackageDeclaration().orElseThrow())
                .classDcl(childClass)
                .overriddenMethod(parentMethod)
                .overridingMethod(childMethod)
                .superCall(compilationUnit.findFirst(SuperExpr.class).orElseThrow())
                .build();

        var preparedRequest = factory.create(project(), candidate);

        assertTrue(preparedRequest.isPresent());
        assertEquals("compilation_unit", preparedRequest.get().sliceType());
        assertEquals("zafeiris", preparedRequest.get().extractorType());
        assertEquals(AiAnalysisEntityType.HIERARCHY, preparedRequest.get().request().entityType());
        assertEquals(List.of(DesignPattern.TEMPLATE_METHOD), preparedRequest.get().request().patternScope());
        assertEquals("ParentTemplate", preparedRequest.get().request().context().superClass());
        assertEquals("process", preparedRequest.get().request().context().methodName());
        assertTrue(preparedRequest.get().request().sourceCode().contains("class ChildTemplate extends ParentTemplate"));
    }

    private void assertStrategyRequest(PreparedAiAnalysisRequest preparedRequest, String candidateId) {
        var request = preparedRequest.request();
        assertEquals(AiAnalysisEntityType.METHOD, request.entityType());
        assertEquals(List.of(DesignPattern.STRATEGY), request.patternScope());
        assertEquals("project-17", request.projectId());
        assertEquals(candidateId, request.candidateId());
        assertEquals("foo", request.context().packageName());
        assertEquals("OrderService", request.context().className());
        assertEquals("execute", request.context().methodName());
        assertEquals(List.of("java.util.List"), request.context().imports());
        assertEquals(Boolean.FALSE, request.context().structuralHints().hasSwitch());
        assertEquals(Boolean.TRUE, request.context().structuralHints().hasFactoryCalls());
        assertEquals(Boolean.TRUE, request.context().structuralHints().usesComposition());
    }

    private Project project() {
        return Project.builder()
                .baseProject(BaseProject.builder().id("project-17").build())
                .build();
    }

    private CompilationUnit parse(String source) {
        return javaParser.parse(source)
                .getResult()
                .orElseThrow();
    }

    private JavaFile javaFile(String name, String path, String source, CompilationUnit compilationUnit) {
        return JavaFile.builder()
                .name(name)
                .path(path)
                .originalClass(source)
                .parsed(compilationUnit)
                .build();
    }
}
