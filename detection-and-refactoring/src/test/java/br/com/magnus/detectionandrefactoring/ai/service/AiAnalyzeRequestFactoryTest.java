package br.com.magnus.detectionandrefactoring.ai.service;

import br.com.magnus.config.starter.file.JavaFile;
import br.com.magnus.config.starter.patterns.DesignPattern;
import br.com.magnus.config.starter.projects.BaseProject;
import br.com.magnus.config.starter.projects.Project;
import br.com.magnus.detectionandrefactoring.ai.domain.AiAnalysisEntityType;
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

        var request = factory.create(project(), candidate);

        assertTrue(request.isPresent());
        assertEquals(AiAnalysisEntityType.METHOD, request.get().entityType());
        assertEquals(List.of(DesignPattern.STRATEGY), request.get().patternScope());
        assertEquals("project-17", request.get().projectId());
        assertEquals(candidate.getId(), request.get().candidateId());
        assertEquals("foo", request.get().context().packageName());
        assertEquals("OrderService", request.get().context().className());
        assertEquals("execute", request.get().context().methodName());
        assertEquals(List.of("java.util.List"), request.get().context().imports());
        assertEquals(Boolean.FALSE, request.get().context().structuralHints().hasSwitch());
        assertEquals(Boolean.TRUE, request.get().context().structuralHints().hasFactoryCalls());
        assertEquals(Boolean.TRUE, request.get().context().structuralHints().usesComposition());
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

        var request = factory.create(project(), candidate);

        assertTrue(request.isPresent());
        assertEquals(AiAnalysisEntityType.HIERARCHY, request.get().entityType());
        assertEquals(List.of(DesignPattern.TEMPLATE_METHOD), request.get().patternScope());
        assertEquals("ParentTemplate", request.get().context().superClass());
        assertEquals("process", request.get().context().methodName());
        assertTrue(request.get().sourceCode().contains("class ChildTemplate extends ParentTemplate"));
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
