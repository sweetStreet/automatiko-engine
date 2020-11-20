
package io.automatik.engine.codegen.process;

import static io.automatik.engine.api.io.ResourceType.determineResourceType;
import static io.automatik.engine.codegen.ApplicationGenerator.log;
import static io.automatik.engine.services.utils.IoUtils.readBytesFromInputStream;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import io.automatik.engine.workflow.serverless.parser.ServerlessWorkflowParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.SAXException;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;

import io.automatik.engine.api.definition.process.Process;
import io.automatik.engine.api.definition.process.WorkflowProcess;
import io.automatik.engine.api.io.Resource;
import io.automatik.engine.api.io.ResourceType;
import io.automatik.engine.codegen.AbstractGenerator;
import io.automatik.engine.codegen.ApplicationGenerator;
import io.automatik.engine.codegen.ApplicationSection;
import io.automatik.engine.codegen.ConfigGenerator;
import io.automatik.engine.codegen.DefaultResourceGeneratorFactory;
import io.automatik.engine.codegen.GeneratedFile;
import io.automatik.engine.codegen.GeneratedFile.Type;
import io.automatik.engine.codegen.ResourceGeneratorFactory;
import io.automatik.engine.codegen.di.DependencyInjectionAnnotator;
import io.automatik.engine.codegen.process.config.ProcessConfigGenerator;
import io.automatik.engine.services.io.ByteArrayResource;
import io.automatik.engine.services.io.FileSystemResource;
import io.automatik.engine.services.io.InternalResource;
import io.automatik.engine.services.utils.StringUtils;
import io.automatik.engine.workflow.bpmn2.xml.BPMNDISemanticModule;
import io.automatik.engine.workflow.bpmn2.xml.BPMNExtensionsSemanticModule;
import io.automatik.engine.workflow.bpmn2.xml.BPMNSemanticModule;
import io.automatik.engine.workflow.compiler.canonical.ModelMetaData;
import io.automatik.engine.workflow.compiler.canonical.OpenAPIMetaData;
import io.automatik.engine.workflow.compiler.canonical.ProcessMetaData;
import io.automatik.engine.workflow.compiler.canonical.ProcessToExecModelGenerator;
import io.automatik.engine.workflow.compiler.canonical.TriggerMetaData;
import io.automatik.engine.workflow.compiler.canonical.UserTaskModelMetaData;
import io.automatik.engine.workflow.compiler.xml.SemanticModules;
import io.automatik.engine.workflow.compiler.xml.XmlProcessReader;

/**
 * Entry point to process code generation
 */
public class ProcessCodegen extends AbstractGenerator {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProcessCodegen.class);

    private static final SemanticModules BPMN_SEMANTIC_MODULES = new SemanticModules();
    public static final Set<String> SUPPORTED_BPMN_EXTENSIONS = Collections
            .unmodifiableSet(new HashSet<>(Arrays.asList(".bpmn", ".bpmn2")));
    private static final String YAML_PARSER = "yml";
    private static final String JSON_PARSER = "json";
    public static final Map<String, String> SUPPORTED_SW_EXTENSIONS;

    static {
        BPMN_SEMANTIC_MODULES.addSemanticModule(new BPMNSemanticModule());
        BPMN_SEMANTIC_MODULES.addSemanticModule(new BPMNExtensionsSemanticModule());
        BPMN_SEMANTIC_MODULES.addSemanticModule(new BPMNDISemanticModule());

        Map<String, String> extMap = new HashMap<>();
        extMap.put(".sw.yml", YAML_PARSER);
        extMap.put(".sw.yaml", YAML_PARSER);
        extMap.put(".sw.json", JSON_PARSER);
        SUPPORTED_SW_EXTENSIONS = Collections.unmodifiableMap(extMap);
    }

    private ClassLoader contextClassLoader;
    private ResourceGeneratorFactory resourceGeneratorFactory;

    public static ProcessCodegen ofJar(Path... jarPaths) {
        List<Process> processes = new ArrayList<>();

        for (Path jarPath : jarPaths) {
            try (ZipFile zipFile = new ZipFile(jarPath.toFile())) {
                Enumeration<? extends ZipEntry> entries = zipFile.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    ResourceType resourceType = determineResourceType(entry.getName());
                    if (SUPPORTED_BPMN_EXTENSIONS.stream().anyMatch(entry.getName()::endsWith)) {
                        InternalResource resource = new ByteArrayResource(
                                readBytesFromInputStream(zipFile.getInputStream(entry)));
                        resource.setResourceType(resourceType);
                        resource.setSourcePath(entry.getName());
                        processes.addAll(parseProcessFile(resource));
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        return ofProcesses(processes);
    }

    public static ProcessCodegen ofPath(Path... paths) throws IOException {

        List<Process> allProcesses = new ArrayList<>();
        for (Path path : paths) {
            Path srcPath = Paths.get(path.toString());
            try (Stream<Path> filesStream = Files.walk(srcPath)) {
                List<File> files = filesStream
                        .filter(p -> SUPPORTED_BPMN_EXTENSIONS.stream().anyMatch(p.toString()::endsWith)
                                || SUPPORTED_SW_EXTENSIONS.keySet().stream().anyMatch(p.toString()::endsWith))
                        .map(Path::toFile).collect(Collectors.toList());
                allProcesses.addAll(parseProcesses(files));
            }
        }
        return ofProcesses(allProcesses);
    }

    public static ProcessCodegen ofFiles(Collection<File> processFiles) {
        List<Process> allProcesses = parseProcesses(processFiles);
        return ofProcesses(allProcesses);
    }

    private static ProcessCodegen ofProcesses(List<Process> processes) {
        return new ProcessCodegen(processes);
    }

    static List<Process> parseProcesses(Collection<File> processFiles) {
        List<Process> processes = new ArrayList<>();
        for (File processSourceFile : processFiles) {
            if (processSourceFile.getAbsolutePath().contains("target" + File.separator + "classes")) {
                // exclude any resources files that come from target folder especially in dev mode which can cause overrides
                continue;
            }
            try {
                FileSystemResource r = new FileSystemResource(processSourceFile);
                if (SUPPORTED_BPMN_EXTENSIONS.stream().anyMatch(processSourceFile.getPath()::endsWith)) {
                    processes.addAll(parseProcessFile(r));
                } else {
                    SUPPORTED_SW_EXTENSIONS.entrySet().stream()
                            .filter(e -> processSourceFile.getPath().endsWith(e.getKey()))
                            .forEach(e -> processes.add(parseWorkflowFile(r, e.getValue())));
                }
                if (processes.isEmpty()) {
                    throw new IllegalArgumentException(
                            "Unable to process file with unsupported extension: " + processSourceFile);
                }
            } catch (RuntimeException e) {
                throw new ProcessCodegenException(processSourceFile.getAbsolutePath(), e);
            }
        }
        return processes;
    }

    private static Process parseWorkflowFile(Resource r, String parser) {
        try {
            ServerlessWorkflowParser workflowParser = new ServerlessWorkflowParser(parser);
            Process p = workflowParser.parseWorkFlow(r.getReader());
            p.setResource(r);
            return p;
        } catch (IOException e) {
            throw new ProcessParsingException("Could not parse file " + r.getSourcePath(), e);
        }
    }

    private static Collection<? extends Process> parseProcessFile(Resource r) {
        try {
            XmlProcessReader xmlReader = new XmlProcessReader(BPMN_SEMANTIC_MODULES,
                    Thread.currentThread().getContextClassLoader());
            Collection<? extends Process> parsed = xmlReader.read(r.getReader());
            parsed.forEach(p -> p.setResource(r));
            return parsed;
        } catch (SAXException | IOException e) {
            throw new ProcessParsingException("Could not parse file " + r.getSourcePath(), e);
        }
    }

    private String applicationCanonicalName;
    private DependencyInjectionAnnotator annotator;

    private ProcessesContainerGenerator moduleGenerator;

    private final Map<String, WorkflowProcess> processes;
    private final List<GeneratedFile> generatedFiles = new ArrayList<>();

    private boolean persistence;

    public ProcessCodegen(Collection<? extends Process> processes) {
        this.processes = new HashMap<>();
        for (Process process : processes) {
            String version = "";
            if (process.getVersion() != null) {
                version = "_" + process.getVersion();
            }
            this.processes.put(process.getId() + version, (WorkflowProcess) process);
        }

        // set default package name
        setPackageName(ApplicationGenerator.DEFAULT_PACKAGE_NAME);
        contextClassLoader = Thread.currentThread().getContextClassLoader();

        // FIXME: once all endpoint generators are implemented it should be changed to
        // ResourceGeneratorFactory, to
        // consider Spring generators.
        resourceGeneratorFactory = new DefaultResourceGeneratorFactory();
    }

    public static String defaultWorkItemHandlerConfigClass(String packageName) {
        return packageName + ".WorkItemHandlerConfig";
    }

    public static String defaultProcessListenerConfigClass(String packageName) {
        return packageName + ".ProcessEventListenerConfig";
    }

    public void setPackageName(String packageName) {
        this.moduleGenerator = new ProcessesContainerGenerator(packageName);
        this.applicationCanonicalName = packageName + ".Application";
    }

    public void setDependencyInjection(DependencyInjectionAnnotator annotator) {
        this.annotator = annotator;
        this.moduleGenerator.withDependencyInjection(annotator);
    }

    public ProcessesContainerGenerator moduleGenerator() {
        return moduleGenerator;
    }

    public ProcessCodegen withPersistence(boolean persistence) {
        this.persistence = persistence;
        return this;
    }

    public ProcessCodegen withClassLoader(ClassLoader projectClassLoader) {
        this.contextClassLoader = projectClassLoader;
        return this;
    }

    public List<GeneratedFile> generate() {
        if (processes.isEmpty()) {
            return Collections.emptyList();
        }

        List<ProcessGenerator> ps = new ArrayList<>();
        List<ProcessInstanceGenerator> pis = new ArrayList<>();
        List<ProcessExecutableModelGenerator> processExecutableModelGenerators = new ArrayList<>();
        List<AbstractResourceGenerator> rgs = new ArrayList<>(); // REST resources
        List<MessageDataEventGenerator> mdegs = new ArrayList<>(); // message data events
        List<MessageConsumerGenerator> megs = new ArrayList<>(); // message endpoints/consumers
        List<MessageProducerGenerator> mpgs = new ArrayList<>(); // message producers
        Set<OpenAPIClientGenerator> opgs = new LinkedHashSet<>(); // OpenAPI clients

        List<String> publicProcesses = new ArrayList<>();

        Map<String, ModelMetaData> processIdToModel = new HashMap<>();

        Map<String, ModelClassGenerator> processIdToModelGenerator = new HashMap<>();
        Map<String, InputModelClassGenerator> processIdToInputModelGenerator = new HashMap<>();
        Map<String, OutputModelClassGenerator> processIdToOutputModelGenerator = new HashMap<>();

        Map<String, List<UserTaskModelMetaData>> processIdToUserTaskModel = new HashMap<>();
        Map<String, ProcessMetaData> processIdToMetadata = new HashMap<>();

        // first we generate all the data classes from variable declarations
        for (Entry<String, WorkflowProcess> entry : processes.entrySet()) {
            ModelClassGenerator mcg = new ModelClassGenerator(context(), entry.getValue());
            processIdToModelGenerator.put(entry.getKey(), mcg);
            processIdToModel.put(entry.getKey(), mcg.generate());

            InputModelClassGenerator imcg = new InputModelClassGenerator(context(), entry.getValue());
            processIdToInputModelGenerator.put(entry.getKey(), imcg);

            OutputModelClassGenerator omcg = new OutputModelClassGenerator(context(), entry.getValue());
            processIdToOutputModelGenerator.put(entry.getKey(), omcg);
        }

        // then we generate user task inputs and outputs if any
        for (Entry<String, WorkflowProcess> entry : processes.entrySet()) {
            UserTasksModelClassGenerator utcg = new UserTasksModelClassGenerator(entry.getValue());
            processIdToUserTaskModel.put(entry.getKey(), utcg.generate());
        }

        // then we can instantiate the exec model generator
        // with the data classes that we have already resolved
        ProcessToExecModelGenerator execModelGenerator = new ProcessToExecModelGenerator(contextClassLoader);

        // collect all process descriptors (exec model)
        for (Entry<String, WorkflowProcess> entry : processes.entrySet()) {
            ProcessExecutableModelGenerator execModelGen = new ProcessExecutableModelGenerator(entry.getValue(),
                    execModelGenerator);
            String packageName = entry.getValue().getPackageName();
            String id = entry.getKey();
            try {
                ProcessMetaData generate = execModelGen.generate();
                processIdToMetadata.put(id, generate);
                processExecutableModelGenerators.add(execModelGen);

                context.addProcess(id, generate);
            } catch (RuntimeException e) {
                LOGGER.error(e.getMessage());
                throw new ProcessCodegenException(id, packageName, e);
            }
        }

        // generate Process, ProcessInstance classes and the REST resource
        for (ProcessExecutableModelGenerator execModelGen : processExecutableModelGenerators) {
            String classPrefix = StringUtils.capitalize(execModelGen.extractedProcessId());
            WorkflowProcess workFlowProcess = execModelGen.process();
            ModelClassGenerator modelClassGenerator = processIdToModelGenerator.get(execModelGen.getProcessId());

            ProcessGenerator p = new ProcessGenerator(workFlowProcess, execModelGen, classPrefix,
                    modelClassGenerator.className(), applicationCanonicalName).withDependencyInjection(annotator)
                            .withPersistence(persistence);

            ProcessInstanceGenerator pi = new ProcessInstanceGenerator(execModelGen, workFlowProcess.getPackageName(),
                    classPrefix, modelClassGenerator.generate());

            ProcessMetaData metaData = processIdToMetadata.get(execModelGen.getProcessId());

            if (isPublic(workFlowProcess)) {

                // Creating and adding the ResourceGenerator
                resourceGeneratorFactory
                        .create(context(), workFlowProcess, modelClassGenerator.className(), execModelGen.className(),
                                applicationCanonicalName)
                        .map(r -> r.withDependencyInjection(annotator).withParentProcess(null)
                                .withUserTasks(processIdToUserTaskModel.get(execModelGen.getProcessId()))
                                .withPathPrefix("{id}").withSignals(metaData.getSignals())
                                .withTriggers(metaData.isStartable(), metaData.isDynamic())
                                .withSubProcesses(populateSubprocesses(workFlowProcess,
                                        processIdToMetadata.get(execModelGen.getProcessId()), processIdToMetadata,
                                        processIdToModelGenerator, processExecutableModelGenerators,
                                        processIdToUserTaskModel)))
                        .ifPresent(rgs::add);
            }
            if (metaData.getTriggers() != null) {

                for (TriggerMetaData trigger : metaData.getTriggers()) {

                    // generate message consumers for processes with message start events
                    if (isPublic(workFlowProcess)
                            && trigger.getType().equals(TriggerMetaData.TriggerType.ConsumeMessage)) {

                        MessageDataEventGenerator msgDataEventGenerator = new MessageDataEventGenerator(workFlowProcess,
                                trigger).withDependencyInjection(annotator);
                        mdegs.add(msgDataEventGenerator);

                        megs.add(new MessageConsumerGenerator(context(), workFlowProcess,
                                modelClassGenerator.className(), execModelGen.className(), applicationCanonicalName,
                                msgDataEventGenerator.className(), trigger).withDependencyInjection(annotator));
                    } else if (trigger.getType().equals(TriggerMetaData.TriggerType.ProduceMessage)) {

                        MessageDataEventGenerator msgDataEventGenerator = new MessageDataEventGenerator(workFlowProcess,
                                trigger).withDependencyInjection(annotator);
                        mdegs.add(msgDataEventGenerator);

                        mpgs.add(new MessageProducerGenerator(context(), workFlowProcess,
                                modelClassGenerator.className(), execModelGen.className(),
                                msgDataEventGenerator.className(), trigger).withDependencyInjection(annotator));
                    }
                }
            }

            if (metaData.getOpenAPIs() != null) {

                for (OpenAPIMetaData api : metaData.getOpenAPIs()) {
                    OpenAPIClientGenerator oagenerator = new OpenAPIClientGenerator(context, workFlowProcess, api)
                            .withDependencyInjection(annotator);

                    opgs.add(oagenerator);
                }
            }
            moduleGenerator.addProcess(p);

            ps.add(p);
            pis.add(pi);
        }

        for (

        ModelClassGenerator modelClassGenerator : processIdToModelGenerator.values()) {
            ModelMetaData mmd = modelClassGenerator.generate();
            storeFile(Type.MODEL, modelClassGenerator.generatedFilePath(), mmd.generate());
        }

        for (InputModelClassGenerator modelClassGenerator : processIdToInputModelGenerator.values()) {
            ModelMetaData mmd = modelClassGenerator.generate();
            storeFile(Type.MODEL, modelClassGenerator.generatedFilePath(), mmd.generate());
        }

        for (OutputModelClassGenerator modelClassGenerator : processIdToOutputModelGenerator.values()) {
            ModelMetaData mmd = modelClassGenerator.generate();
            storeFile(Type.MODEL, modelClassGenerator.generatedFilePath(), mmd.generate());
        }

        for (List<UserTaskModelMetaData> utmd : processIdToUserTaskModel.values()) {

            for (UserTaskModelMetaData ut : utmd) {
                storeFile(Type.MODEL, UserTasksModelClassGenerator.generatedFilePath(ut.getInputModelClassName()),
                        ut.generateInput());

                storeFile(Type.MODEL, UserTasksModelClassGenerator.generatedFilePath(ut.getOutputModelClassName()),
                        ut.generateOutput());
            }
        }

        for (AbstractResourceGenerator resourceGenerator : rgs) {
            storeFile(Type.REST, resourceGenerator.generatedFilePath(), resourceGenerator.generate());
        }

        for (MessageDataEventGenerator messageDataEventGenerator : mdegs) {
            storeFile(Type.CLASS, messageDataEventGenerator.generatedFilePath(), messageDataEventGenerator.generate());
        }

        for (MessageConsumerGenerator messageConsumerGenerator : megs) {
            storeFile(Type.MESSAGE_CONSUMER, messageConsumerGenerator.generatedFilePath(),
                    messageConsumerGenerator.generate());
        }

        for (MessageProducerGenerator messageProducerGenerator : mpgs) {
            storeFile(Type.MESSAGE_PRODUCER, messageProducerGenerator.generatedFilePath(),
                    messageProducerGenerator.generate());
        }

        for (OpenAPIClientGenerator openApiClientGenerator : opgs) {
            openApiClientGenerator.generate();

            Map<String, String> contents = openApiClientGenerator.generatedClasses();

            for (Entry<String, String> entry : contents.entrySet()) {

                storeFile(Type.CLASS, entry.getKey().replace('.', '/') + ".java", entry.getValue());
            }
        }

        for (ProcessGenerator p : ps) {
            storeFile(Type.PROCESS, p.generatedFilePath(), p.generate());

            p.getAdditionalClasses().forEach(cp -> {
                String packageName = cp.getPackageDeclaration().map(pd -> pd.getName().toString()).orElse("");
                String clazzName = cp.findFirst(ClassOrInterfaceDeclaration.class).map(cls -> cls.getName().toString())
                        .get();
                String path = (packageName + "." + clazzName).replace('.', '/') + ".java";
                storeFile(Type.CLASS, path, cp.toString());
            });
        }

        for (ProcessInstanceGenerator pi : pis) {
            storeFile(Type.PROCESS_INSTANCE, pi.generatedFilePath(), pi.generate());
        }

        for (ProcessExecutableModelGenerator processGenerator : processExecutableModelGenerators) {
            if (processGenerator.isPublic()) {
                publicProcesses.add(processGenerator.extractedProcessId());
                this.addLabel(processGenerator.label(), "process"); // add the label id of the process with value set to
                                                                    // process as resource type
            }
        }

        return generatedFiles;
    }

    @Override
    public void updateConfig(ConfigGenerator cfg) {
        if (!processes.isEmpty()) {
            cfg.withProcessConfig(new ProcessConfigGenerator());
        }
    }

    private void storeFile(Type type, String path, String source) {
        generatedFiles.add(new GeneratedFile(type, path, log(source).getBytes(StandardCharsets.UTF_8)));
    }

    public List<GeneratedFile> getGeneratedFiles() {
        return generatedFiles;
    }

    @Override
    public ApplicationSection section() {
        return moduleGenerator;
    }

    protected boolean isPublic(WorkflowProcess process) {
        return WorkflowProcess.PUBLIC_VISIBILITY.equalsIgnoreCase(process.getVisibility());
    }

    protected List<AbstractResourceGenerator> populateSubprocesses(WorkflowProcess parentProcess,
            ProcessMetaData metaData, Map<String, ProcessMetaData> processIdToMetadata,
            Map<String, ModelClassGenerator> processIdToModelGenerator,
            List<ProcessExecutableModelGenerator> processExecutableModelGenerators,
            Map<String, List<UserTaskModelMetaData>> processIdToUserTaskModel) {
        List<AbstractResourceGenerator> subprocesses = new ArrayList<AbstractResourceGenerator>();

        for (Entry<String, String> entry : metaData.getSubProcesses().entrySet()) {

            ProcessExecutableModelGenerator execModelGen = processExecutableModelGenerators.stream()
                    .filter(p -> p.getProcessId().equals(entry.getValue())).findFirst().orElse(null);

            if (execModelGen != null) {
                WorkflowProcess workFlowProcess = execModelGen.process();
                ModelClassGenerator modelClassGenerator = processIdToModelGenerator.get(entry.getValue());

                Optional.of(new SubprocessResourceGenerator(context(), workFlowProcess, modelClassGenerator.className(),
                        execModelGen.className(), applicationCanonicalName))
                        .map(r -> r.withDependencyInjection(annotator).withParentProcess(parentProcess)
                                .withUserTasks(processIdToUserTaskModel.get(execModelGen.getProcessId()))
                                .withSignals(metaData.getSignals())
                                .withTriggers(metaData.isStartable(), metaData.isDynamic())
                                .withSubProcesses(populateSubprocesses(workFlowProcess,
                                        processIdToMetadata.get(execModelGen.getProcessId()), processIdToMetadata,
                                        processIdToModelGenerator, processExecutableModelGenerators,
                                        processIdToUserTaskModel)))
                        .ifPresent(subprocesses::add);
            }
        }

        return subprocesses;
    }
}
