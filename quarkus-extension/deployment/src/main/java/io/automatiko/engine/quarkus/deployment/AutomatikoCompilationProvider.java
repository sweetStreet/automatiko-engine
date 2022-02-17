package io.automatiko.engine.quarkus.deployment;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

import org.jboss.logging.Logger;
import org.objectweb.asm.ClassVisitor;

import io.automatiko.engine.codegen.ApplicationGenerator;
import io.automatiko.engine.codegen.GeneratedFile;
import io.automatiko.engine.codegen.Generator;
import io.automatiko.engine.codegen.di.CDIDependencyInjectionAnnotator;
import io.quarkus.bootstrap.model.PathsCollection;
import io.quarkus.deployment.dev.CompilationProvider;
import io.quarkus.deployment.dev.CompilerFlags;
import io.quarkus.gizmo.Gizmo;
import io.quarkus.paths.PathCollection;

public abstract class AutomatikoCompilationProvider implements CompilationProvider {

    private static final Logger log = Logger.getLogger(AutomatikoCompilationProvider.class);

    // -g is used to make the java compiler generate all debugging info
    // -parameters is used to generate metadata for reflection on method parameters
    // this is useful when people using debuggers against their hot-reloaded app
    private static final Set<String> COMPILER_OPTIONS = new HashSet<>(Arrays.asList("-g", "-parameters"));
    private static final Set<String> IGNORE_NAMESPACES = new HashSet<>(Collections.singletonList("org.osgi"));

    JavaCompiler compiler;
    StandardJavaFileManager fileManager;
    DiagnosticCollector<JavaFileObject> fileManagerDiagnostics;

    @Override
    public Set<String> handledSourcePaths() {
        return Collections.singleton("src" + File.separator + "main" + File.separator + "resources");
    }

    protected Set<File> filterFilesToCompile(Set<File> filesToCompile) {
        return filesToCompile;
    }

    @Override
    public final void compile(Set<File> filesToCompile, Context context) {

        Set<File> allFiles = AutomatikoBuildData.get().getGenerationContext()
                .collectConnectedFiles(filterFilesToCompile(filesToCompile));

        if (allFiles.isEmpty()) {
            return;
        }

        File outputDirectory = AutomatikoBuildData.get().getGenerationContext().getClassesPath();
        try {

            ApplicationGenerator appGen = new ApplicationGenerator(
                    AutomatikoBuildData.get().getConfig().packageName().orElse(AutomatikoQuarkusProcessor.DEFAULT_PACKAGE_NAME),
                    outputDirectory)
                            .withDependencyInjection(new CDIDependencyInjectionAnnotator())
                            .withGeneratorContext(AutomatikoBuildData.get().getGenerationContext());

            addGenerator(appGen, allFiles, context);

            Collection<GeneratedFile> generatedFiles = appGen.generate();

            Set<File> generatedSourceFiles = new HashSet<>();
            for (GeneratedFile file : generatedFiles) {
                Path path = pathOf(outputDirectory.getPath(), file.relativePath());
                if (file.getType() != GeneratedFile.Type.APPLICATION
                        && file.getType() != GeneratedFile.Type.APPLICATION_CONFIG) {

                    Files.write(path, file.contents());
                    generatedSourceFiles.add(path.toFile());
                }
            }
            javaCompile(generatedSourceFiles, context, outputDirectory);

        } catch (IOException e) {
            throw new AutomatikoCompilerException(e);
        }
    }

    @Override
    public Path getSourcePath(Path classFilePath, PathCollection sourcePaths, String classesPath) {
        try {
            return AutomatikoBuildData.get().getGenerationContext().getClassSource(classFilePath);
        } catch (IllegalStateException e) {
            return null;
        }

    }

    protected abstract Generator addGenerator(ApplicationGenerator appGen, Set<File> filesToCompile, Context context)
            throws IOException;

    static Path pathOf(String path, String relativePath) {
        Path p = Paths.get(path, relativePath);
        p.getParent().toFile().mkdirs();
        return p;
    }

    private void javaCompile(Set<File> filesToCompile, Context context, File outputDirectory) {
        JavaCompiler compiler = this.compiler;
        if (compiler == null) {
            compiler = this.compiler = ToolProvider.getSystemJavaCompiler();
        }
        if (compiler == null) {
            throw new RuntimeException("No system java compiler provided");
        }
        try {
            if (fileManager == null) {
                fileManager = compiler.getStandardFileManager(fileManagerDiagnostics = new DiagnosticCollector<>(), null,
                        context.getSourceEncoding());
            }

            DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
            fileManager.setLocation(StandardLocation.CLASS_PATH, context.getClasspath());
            fileManager.setLocation(StandardLocation.CLASS_OUTPUT, Collections.singleton(outputDirectory));

            CompilerFlags compilerFlags = new CompilerFlags(COMPILER_OPTIONS, context.getCompilerOptions(),
                    context.getReleaseJavaVersion(), context.getSourceJavaVersion(), context.getTargetJvmVersion());

            Iterable<? extends JavaFileObject> sources = fileManager.getJavaFileObjectsFromFiles(filesToCompile);
            JavaCompiler.CompilationTask task = compiler.getTask(null, fileManager, diagnostics,
                    compilerFlags.toList(), null, sources);

            if (!task.call()) {
                StringBuilder sb = new StringBuilder("\u001B[91mCompilation Failed:");
                for (Diagnostic<? extends JavaFileObject> i : diagnostics.getDiagnostics()) {
                    sb.append("\n");
                    sb.append(i.toString());
                }
                sb.append("\u001b[0m");
                throw new RuntimeException(sb.toString());
            }

            logDiagnostics(diagnostics);

            if (!fileManagerDiagnostics.getDiagnostics().isEmpty()) {
                logDiagnostics(fileManagerDiagnostics);
                fileManager.close();
                fileManagerDiagnostics = null;
                fileManager = null;
            }
        } catch (IOException e) {
            throw new RuntimeException("Cannot close file manager", e);
        }
    }

    @Override
    public void close() throws IOException {
        if (fileManager != null) {
            fileManager.close();
            fileManager = null;
            fileManagerDiagnostics = null;
        }
    }

    private void logDiagnostics(final DiagnosticCollector<JavaFileObject> diagnostics) {
        for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
            Logger.Level level = diagnostic.getKind() == Diagnostic.Kind.ERROR ? Logger.Level.ERROR : Logger.Level.WARN;
            String message = diagnostic.getMessage(null);
            if (level.equals(Logger.Level.WARN) && ignoreWarningForNamespace(message)) {
                continue;
            }

            log.logf(level, "%s, line %d in %s", message, diagnostic.getLineNumber(),
                    diagnostic.getSource() == null ? "[unknown source]" : diagnostic.getSource().getName());
        }
    }

    private static boolean ignoreWarningForNamespace(String message) {
        for (String ignoreNamespace : IGNORE_NAMESPACES) {
            if (message.contains(ignoreNamespace)) {
                return true;
            }
        }
        return false;
    }

    static class RuntimeUpdatesClassVisitor extends ClassVisitor {
        private final PathsCollection sourcePaths;
        private final String classesPath;
        private String sourceFile;

        public RuntimeUpdatesClassVisitor(PathsCollection sourcePaths, String classesPath) {
            super(Gizmo.ASM_API_VERSION);
            this.sourcePaths = sourcePaths;
            this.classesPath = classesPath;
        }

        @Override
        public void visitSource(String source, String debug) {
            this.sourceFile = source;
        }

        public Path getSourceFileForClass(final Path classFilePath) {
            for (Path sourcesDir : sourcePaths) {
                final Path classesDir = Paths.get(classesPath);
                final StringBuilder sourceRelativeDir = new StringBuilder();
                sourceRelativeDir.append(classesDir.relativize(classFilePath.getParent()));
                sourceRelativeDir.append(File.separator);
                sourceRelativeDir.append(sourceFile);
                final Path sourceFilePath = sourcesDir.resolve(Paths.get(sourceRelativeDir.toString()));
                if (Files.exists(sourceFilePath)) {
                    return sourceFilePath;
                }
            }

            return null;
        }
    }

}
