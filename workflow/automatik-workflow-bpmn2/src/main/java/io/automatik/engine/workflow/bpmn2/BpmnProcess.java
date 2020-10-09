
package io.automatik.engine.workflow.bpmn2;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;

import io.automatik.engine.api.Model;
import io.automatik.engine.api.definition.process.Node;
import io.automatik.engine.api.definition.process.NodeContainer;
import io.automatik.engine.api.definition.process.Process;
import io.automatik.engine.api.io.Resource;
import io.automatik.engine.api.runtime.process.WorkflowProcessInstance;
import io.automatik.engine.api.workflow.ProcessConfig;
import io.automatik.engine.api.workflow.ProcessInstance;
import io.automatik.engine.workflow.AbstractProcess;
import io.automatik.engine.workflow.base.core.context.variable.VariableScope;
import io.automatik.engine.workflow.process.core.WorkflowProcess;
import io.automatik.engine.workflow.process.core.node.SubProcessNode;

public class BpmnProcess extends AbstractProcess<BpmnVariables> {

    private static BpmnProcessCompiler COMPILER = new BpmnProcessCompiler();

    private final Process process;

    public BpmnProcess(Process p) {
        process = p;
    }

    public BpmnProcess(Process p, ProcessConfig config) {
        super(config);
        process = p;
    }

    @Override
    public ProcessInstance<BpmnVariables> createInstance(Model m) {
        BpmnVariables variables = createModel();
        variables.fromMap(m.toMap());
        return new BpmnProcessInstance(this, variables, this.createProcessRuntime());
    }

    public ProcessInstance<BpmnVariables> createInstance() {
        return new BpmnProcessInstance(this, createModel(), this.createProcessRuntime());
    }

    @Override
    public ProcessInstance<BpmnVariables> createInstance(String businessKey, BpmnVariables variables) {
        BpmnVariables variablesModel = createModel();
        variablesModel.fromMap(variables.toMap());
        return new BpmnProcessInstance(this, variablesModel, businessKey, this.createProcessRuntime());
    }

    @Override
    public ProcessInstance<BpmnVariables> createInstance(BpmnVariables variables) {
        BpmnVariables variablesModel = createModel();
        variablesModel.fromMap(variables.toMap());
        return new BpmnProcessInstance(this, variablesModel, this.createProcessRuntime());
    }

    @Override
    public ProcessInstance<BpmnVariables> createInstance(WorkflowProcessInstance wpi, BpmnVariables model) {

        return new BpmnProcessInstance(this, model, this.createProcessRuntime(), wpi);
    }

    @Override
    public ProcessInstance<BpmnVariables> createReadOnlyInstance(WorkflowProcessInstance wpi, BpmnVariables model) {

        return new BpmnProcessInstance(this, model, wpi);
    }

    @Override
    public Process process() {
        return process;
    }

    @Override
    public BpmnVariables createModel() {
        VariableScope variableScope = (VariableScope) ((WorkflowProcess) process())
                .getDefaultContext(VariableScope.VARIABLE_SCOPE);
        return new BpmnVariables(variableScope.getVariables(), new HashMap<>());
    }

    @Override
    protected void registerListeners() {
        super.registerListeners();
        List<SubProcessNode> subprocessNodes = new ArrayList<SubProcessNode>();
        collectSubprocessNodes(subprocessNodes, (NodeContainer) process());

        for (SubProcessNode sp : subprocessNodes) {
            services.getSignalManager().addEventListener(sp.getProcessId(), completionEventListener);
        }
    }

    protected void collectSubprocessNodes(Collection<SubProcessNode> items, NodeContainer container) {

        for (Node node : container.getNodes()) {
            if (node instanceof SubProcessNode) {
                items.add((SubProcessNode) node);
            } else if (node instanceof NodeContainer) {
                collectSubprocessNodes(items, (NodeContainer) node);
            }
        }
    }

    /**
     *
     */
    public static void overrideCompiler(BpmnProcessCompiler compiler) {
        COMPILER = Objects.requireNonNull(compiler);
    }

    public static List<BpmnProcess> from(Resource... resource) {
        return from(null, resource);
    }

    public static List<BpmnProcess> from(ProcessConfig config, Resource... resources) {
        return COMPILER.from(config, resources);
    }

}
