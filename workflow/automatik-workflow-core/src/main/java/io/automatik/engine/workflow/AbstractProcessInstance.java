
package io.automatik.engine.workflow;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import io.automatik.engine.api.Model;
import io.automatik.engine.api.definition.process.Node;
import io.automatik.engine.api.runtime.process.EventListener;
import io.automatik.engine.api.runtime.process.ProcessRuntime;
import io.automatik.engine.api.runtime.process.WorkItemNotFoundException;
import io.automatik.engine.api.runtime.process.WorkflowProcessInstance;
import io.automatik.engine.api.workflow.EventDescription;
import io.automatik.engine.api.workflow.MutableProcessInstances;
import io.automatik.engine.api.workflow.NodeInstanceNotFoundException;
import io.automatik.engine.api.workflow.NodeNotFoundException;
import io.automatik.engine.api.workflow.Process;
import io.automatik.engine.api.workflow.ProcessError;
import io.automatik.engine.api.workflow.ProcessInstance;
import io.automatik.engine.api.workflow.ProcessInstanceDuplicatedException;
import io.automatik.engine.api.workflow.ProcessInstanceNotFoundException;
import io.automatik.engine.api.workflow.Signal;
import io.automatik.engine.api.workflow.Tag;
import io.automatik.engine.api.workflow.Tags;
import io.automatik.engine.api.workflow.WorkItem;
import io.automatik.engine.api.workflow.flexible.AdHocFragment;
import io.automatik.engine.api.workflow.flexible.Milestone;
import io.automatik.engine.api.workflow.workitem.Policy;
import io.automatik.engine.api.workflow.workitem.Transition;
import io.automatik.engine.services.correlation.CorrelationAwareProcessRuntime;
import io.automatik.engine.services.correlation.CorrelationKey;
import io.automatik.engine.services.correlation.StringCorrelationKey;
import io.automatik.engine.services.uow.ProcessInstanceWorkUnit;
import io.automatik.engine.workflow.base.instance.InternalProcessRuntime;
import io.automatik.engine.workflow.process.executable.core.ExecutableProcess;
import io.automatik.engine.workflow.process.instance.NodeInstance;
import io.automatik.engine.workflow.process.instance.NodeInstanceContainer;
import io.automatik.engine.workflow.process.instance.impl.NodeInstanceImpl;
import io.automatik.engine.workflow.process.instance.impl.WorkflowProcessInstanceImpl;
import io.automatik.engine.workflow.process.instance.node.WorkItemNodeInstance;

public abstract class AbstractProcessInstance<T extends Model> implements ProcessInstance<T> {

    protected final T variables;
    protected final AbstractProcess<T> process;
    protected final ProcessRuntime rt;
    protected io.automatik.engine.api.runtime.process.ProcessInstance processInstance;

    protected Integer status;
    protected String id;
    protected CorrelationKey correlationKey;
    protected String description;
    protected String parentProcessInstanceId;
    protected String rootProcessInstanceId;
    protected String rootProcessId;

    protected ProcessError processError;

    protected Tags tags;

    protected Supplier<io.automatik.engine.api.runtime.process.ProcessInstance> reloadSupplier;

    protected CompletionEventListener completionEventListener;

    public AbstractProcessInstance(AbstractProcess<T> process, T variables, ProcessRuntime rt) {
        this(process, variables, null, rt);
    }

    public AbstractProcessInstance(AbstractProcess<T> process, T variables, String businessKey, ProcessRuntime rt) {
        this.process = process;
        this.rt = rt;
        this.variables = variables;

        setCorrelationKey(businessKey);
        Map<String, Object> map = bind(variables);
        String processId = process.process().getId();
        syncProcessInstance((WorkflowProcessInstance) ((CorrelationAwareProcessRuntime) rt)
                .createProcessInstance(processId, correlationKey, map));
        // this applies to business keys only as non business keys process instances id
        // are always unique
        if (correlationKey != null && ((MutableProcessInstances<T>) process.instances()).exists(id)) {
            throw new ProcessInstanceDuplicatedException(correlationKey.getName());
        }
        // add to the instances upon creation so it can be immediately found even if not
        // started
        ((MutableProcessInstances<T>) process.instances()).create(id, this);
    }

    /**
     * Without providing a ProcessRuntime the ProcessInstance can only be used as
     * read-only
     * 
     * @param process
     * @param variables
     * @param wpi
     */
    public AbstractProcessInstance(AbstractProcess<T> process, T variables, WorkflowProcessInstance wpi) {
        this.process = process;
        this.variables = variables;
        this.rt = null;
        syncProcessInstance((WorkflowProcessInstance) wpi);
        unbind(variables, processInstance.getVariables());
    }

    public AbstractProcessInstance(AbstractProcess<T> process, T variables, ProcessRuntime rt,
            WorkflowProcessInstance wpi) {
        this.process = process;
        this.rt = rt;
        this.variables = variables;
        syncProcessInstance((WorkflowProcessInstance) wpi);
        reconnect();
    }

    protected void reconnect() {
        if (((WorkflowProcessInstanceImpl) processInstance).getProcessRuntime() == null) {
            ((WorkflowProcessInstanceImpl) processInstance)
                    .setProcessRuntime(((InternalProcessRuntime) getProcessRuntime()));
        }
        ((WorkflowProcessInstanceImpl) processInstance).reconnect();
        ((WorkflowProcessInstanceImpl) processInstance).setMetaData("AutomatikProcessInstance", this);
        addCompletionEventListener();

        for (io.automatik.engine.api.runtime.process.NodeInstance nodeInstance : ((WorkflowProcessInstance) processInstance)
                .getNodeInstances()) {
            if (nodeInstance instanceof WorkItemNodeInstance) {
                ((WorkItemNodeInstance) nodeInstance).internalRegisterWorkItem();
            }
        }

        unbind(variables, processInstance.getVariables());
    }

    private void addCompletionEventListener() {
        if (completionEventListener == null) {
            completionEventListener = new CompletionEventListener();
            ((WorkflowProcessInstanceImpl) processInstance).addEventListener("processInstanceCompleted:" + id,
                    completionEventListener, false);
        }
    }

    private void removeCompletionListener() {
        if (completionEventListener != null) {
            ((WorkflowProcessInstanceImpl) processInstance).removeEventListener("processInstanceCompleted:" + id,
                    completionEventListener, false);
            completionEventListener = null;
        }
    }

    public void disconnect() {
        if (processInstance == null) {
            return;
        }

        ((WorkflowProcessInstanceImpl) processInstance).disconnect();
        ((WorkflowProcessInstanceImpl) processInstance).setMetaData("AutomatikProcessInstance", null);
    }

    private void syncProcessInstance(WorkflowProcessInstance wpi) {
        processInstance = wpi;
        status = wpi.getState();
        id = wpi.getId();
        description = ((WorkflowProcessInstanceImpl) wpi).getDescription();
        parentProcessInstanceId = "".equals(wpi.getParentProcessInstanceId()) ? null : wpi.getParentProcessInstanceId();
        rootProcessInstanceId = "".equals(wpi.getRootProcessInstanceId()) ? null : wpi.getRootProcessInstanceId();
        rootProcessId = "".equals(wpi.getRootProcessId()) ? null : wpi.getRootProcessId();
        tags = buildTags();

        setCorrelationKey(wpi.getCorrelationKey());
    }

    private void setCorrelationKey(String businessKey) {
        if (businessKey != null && !businessKey.trim().isEmpty()) {
            correlationKey = new StringCorrelationKey(businessKey);
        }
    }

    public io.automatik.engine.api.runtime.process.ProcessInstance internalGetProcessInstance() {
        return processInstance;
    }

    public void internalRemoveProcessInstance(
            Supplier<io.automatik.engine.api.runtime.process.ProcessInstance> reloadSupplier) {
        this.reloadSupplier = reloadSupplier;
        this.status = processInstance.getState();
        if (this.status == STATE_ERROR) {
            this.processError = buildProcessError();
        }
        removeCompletionListener();
        if (((WorkflowProcessInstanceImpl) processInstance).getProcessRuntime() != null) {
            disconnect();
        }
        processInstance = null;
    }

    public void start() {
        start(null, null, null);
    }

    public void start(String trigger, String referenceId, Object data) {
        synchronized (this) {
            if (this.status != ProcessInstance.STATE_PENDING) {
                throw new IllegalStateException("Impossible to start process instance that already was started");
            }
            this.status = ProcessInstance.STATE_ACTIVE;

            if (referenceId != null) {
                ((WorkflowProcessInstanceImpl) processInstance).setReferenceId(referenceId);
            }

            ((InternalProcessRuntime) getProcessRuntime()).getProcessInstanceManager()
                    .addProcessInstance(this.processInstance, this.correlationKey);
            this.id = processInstance.getId();

            ((WorkflowProcessInstanceImpl) processInstance).setMetaData("AutomatikProcessInstance", this);
            addCompletionEventListener();
            io.automatik.engine.api.runtime.process.ProcessInstance processInstance = this.getProcessRuntime()
                    .startProcessInstance(this.id, trigger, data);
            syncProcessInstance((WorkflowProcessInstance) processInstance);
            addToUnitOfWork(pi -> ((MutableProcessInstances<T>) process.instances()).create(pi.id(), pi));
            unbind(variables, processInstance.getVariables());
            if (this.processInstance != null) {
                this.status = this.processInstance.getState();
            }
        }
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    protected void addToUnitOfWork(Consumer<ProcessInstance<T>> action) {
        ((InternalProcessRuntime) getProcessRuntime()).getUnitOfWorkManager().currentUnitOfWork()
                .intercept(new ProcessInstanceWorkUnit(this, action));
    }

    public void abort() {
        synchronized (this) {
            String pid = processInstance().getId();
            unbind(variables, processInstance().getVariables());
            this.getProcessRuntime().abortProcessInstance(pid);
            this.status = processInstance.getState();
            addToUnitOfWork(pi -> ((MutableProcessInstances<T>) process.instances()).remove(pi.id(), pi));
        }
    }

    @Override
    public <S> void send(Signal<S> signal) {
        synchronized (this) {
            if (signal.referenceId() != null) {
                ((WorkflowProcessInstanceImpl) processInstance()).setReferenceId(signal.referenceId());
            }
            processInstance().signalEvent(signal.channel(), signal.payload());
            removeOnFinish();
        }
    }

    @Override
    public Collection<ProcessInstance<? extends Model>> subprocesses() {
        return Collections.emptyList();
    }

    @Override
    public Process<T> process() {
        return process;
    }

    @Override
    public T variables() {
        return variables;
    }

    @Override
    public int status() {
        return this.status;
    }

    @Override
    public String id() {
        return this.id;
    }

    @Override
    public String businessKey() {
        return this.correlationKey == null ? null : this.correlationKey.getName();
    }

    @Override
    public String description() {
        return this.description;
    }

    @Override
    public String parentProcessInstanceId() {
        return this.parentProcessInstanceId;
    }

    @Override
    public String rootProcessInstanceId() {
        return this.rootProcessInstanceId;
    }

    @Override
    public String rootProcessId() {
        return this.rootProcessId;
    }

    @Override
    public Date startDate() {
        return this.processInstance != null && processInstance instanceof WorkflowProcessInstanceImpl
                ? ((WorkflowProcessInstanceImpl) this.processInstance).getStartDate()
                : null;
    }

    @Override
    public void updateVariables(T updates) {
        synchronized (this) {
            processInstance();
            Map<String, Object> map = bind(updates);

            for (Entry<String, Object> entry : map.entrySet()) {
                ((WorkflowProcessInstance) processInstance()).setVariable(entry.getKey(), entry.getValue());
            }
            syncProcessInstance(((WorkflowProcessInstance) processInstance()));
            unbind(this.variables, processInstance().getVariables());
            addToUnitOfWork(pi -> ((MutableProcessInstances<T>) process.instances()).update(pi.id(), pi));
            removeOnFinish();
        }
    }

    @Override
    public Optional<ProcessError> error() {
        if (this.status == STATE_ERROR) {
            return Optional.of(this.processError != null ? this.processError : buildProcessError());
        }

        return Optional.empty();
    }

    @Override
    public Tags tags() {

        return this.tags != null ? this.tags : buildTags();
    }

    @Override
    public void startFrom(String nodeId) {
        startFrom(nodeId, null);
    }

    @Override
    public void startFrom(String nodeId, String referenceId) {
        synchronized (this) {
            ((WorkflowProcessInstanceImpl) processInstance).setStartDate(new Date());
            ((WorkflowProcessInstanceImpl) processInstance).setState(STATE_ACTIVE);
            ((InternalProcessRuntime) getProcessRuntime()).getProcessInstanceManager()
                    .addProcessInstance(this.processInstance, this.correlationKey);
            this.id = processInstance.getId();
            addCompletionEventListener();
            if (referenceId != null) {
                ((WorkflowProcessInstanceImpl) processInstance).setReferenceId(referenceId);
            }
            ((WorkflowProcessInstanceImpl) processInstance).setMetaData("AutomatikProcessInstance", this);
            triggerNode(nodeId);
            syncProcessInstance((WorkflowProcessInstance) processInstance);
            addToUnitOfWork(pi -> ((MutableProcessInstances<T>) process.instances()).update(pi.id(), pi));
            unbind(variables, processInstance.getVariables());
            if (processInstance != null) {
                this.status = processInstance.getState();
            }
        }
    }

    @Override
    public void triggerNode(String nodeId) {
        synchronized (this) {
            WorkflowProcessInstanceImpl wfpi = ((WorkflowProcessInstanceImpl) processInstance());
            ExecutableProcess rfp = ((ExecutableProcess) wfpi.getProcess());

            Node node = rfp.getNodesRecursively().stream().filter(ni -> nodeId.equals(ni.getMetaData().get("UniqueId")))
                    .findFirst().orElseThrow(() -> new NodeNotFoundException(this.id, nodeId));

            Node parentNode = rfp.getParentNode(node.getId());

            NodeInstanceContainer nodeInstanceContainerNode = parentNode == null ? wfpi
                    : ((NodeInstanceContainer) wfpi.getNodeInstance(parentNode));

            nodeInstanceContainerNode.getNodeInstance(node).trigger(null,
                    io.automatik.engine.workflow.process.core.Node.CONNECTION_DEFAULT_TYPE);
        }
    }

    @Override
    public void cancelNodeInstance(String nodeInstanceId) {
        synchronized (this) {
            NodeInstance nodeInstance = ((WorkflowProcessInstanceImpl) processInstance()).getNodeInstances(true)
                    .stream().filter(ni -> ni.getId().equals(nodeInstanceId)).findFirst()
                    .orElseThrow(() -> new NodeInstanceNotFoundException(this.id, nodeInstanceId));

            nodeInstance.cancel();
            removeOnFinish();
        }
    }

    @Override
    public void retriggerNodeInstance(String nodeInstanceId) {
        synchronized (this) {
            NodeInstance nodeInstance = ((WorkflowProcessInstanceImpl) processInstance()).getNodeInstances(true)
                    .stream().filter(ni -> ni.getId().equals(nodeInstanceId)).findFirst()
                    .orElseThrow(() -> new NodeInstanceNotFoundException(this.id, nodeInstanceId));

            ((NodeInstanceImpl) nodeInstance).retrigger(true);
            removeOnFinish();
        }
    }

    public io.automatik.engine.api.runtime.process.ProcessInstance processInstance() {
        if (this.processInstance == null) {
            this.processInstance = reloadSupplier.get();
            if (this.processInstance == null) {
                throw new ProcessInstanceNotFoundException(id);
            } else if (getProcessRuntime() != null) {
                reconnect();
            }
        }

        return this.processInstance;
    }

    @Override
    public WorkItem workItem(String workItemId, Policy<?>... policies) {
        WorkItemNodeInstance workItemInstance = (WorkItemNodeInstance) ((WorkflowProcessInstanceImpl) processInstance())
                .getNodeInstances(true).stream()
                .filter(ni -> ni instanceof WorkItemNodeInstance
                        && ((WorkItemNodeInstance) ni).getWorkItemId().equals(workItemId)
                        && ((WorkItemNodeInstance) ni).getWorkItem().enforce(policies))
                .findFirst().orElseThrow(() -> new WorkItemNotFoundException(
                        "Work item with id " + workItemId + " was not found in process instance " + id(), workItemId));
        return new BaseWorkItem(workItemInstance.getProcessInstance().getId(), workItemInstance.getId(),
                workItemInstance.getWorkItem().getId(), workItemInstance.buildReferenceId(),
                (String) workItemInstance.getWorkItem().getParameters().getOrDefault("TaskName",
                        workItemInstance.getNodeName()),
                workItemInstance.getWorkItem().getState(), workItemInstance.getWorkItem().getPhaseId(),
                workItemInstance.getWorkItem().getPhaseStatus(), workItemInstance.getWorkItem().getParameters(),
                workItemInstance.getWorkItem().getResults());
    }

    @Override
    public List<WorkItem> workItems(Policy<?>... policies) {
        List<WorkItem> mainProcessInstance = ((WorkflowProcessInstanceImpl) processInstance()).getNodeInstances(true)
                .stream()
                .filter(ni -> ni instanceof WorkItemNodeInstance
                        && ((WorkItemNodeInstance) ni).getWorkItem().enforce(policies))
                .map(ni -> new BaseWorkItem(ni.getProcessInstance().getId(), ni.getId(),
                        ((WorkItemNodeInstance) ni).getWorkItemId(), ((WorkItemNodeInstance) ni).buildReferenceId(),
                        (String) ((WorkItemNodeInstance) ni).getWorkItem().getParameters().getOrDefault("TaskName",
                                ni.getNodeName()),
                        ((WorkItemNodeInstance) ni).getWorkItem().getState(),
                        ((WorkItemNodeInstance) ni).getWorkItem().getPhaseId(),
                        ((WorkItemNodeInstance) ni).getWorkItem().getPhaseStatus(),
                        ((WorkItemNodeInstance) ni).getWorkItem().getParameters(),
                        ((WorkItemNodeInstance) ni).getWorkItem().getResults()))
                .collect(Collectors.toList());

        subprocesses().forEach(pi -> mainProcessInstance.addAll(pi.workItems(policies)));

        return mainProcessInstance;

    }

    @Override
    public void completeWorkItem(String id, Map<String, Object> variables, Policy<?>... policies) {
        synchronized (this) {
            processInstance();
            String[] fragments = id.split("/");

            if (fragments.length > 1) {
                // comes from subprocess
                subprocesses().stream()
                        .filter(pi -> pi.process().id().equals(fragments[0]) && pi.id().equals(fragments[1]))
                        .findFirst().ifPresent(pi -> pi.completeWorkItem(fragments[3], variables, policies));
            } else {

                this.getProcessRuntime().getWorkItemManager().completeWorkItem(id, variables, policies);
                removeOnFinish();
            }
        }
    }

    @Override
    public void abortWorkItem(String id, Policy<?>... policies) {
        synchronized (this) {
            processInstance();
            String[] fragments = id.split("/");

            if (fragments.length > 1) {
                // comes from subprocess
                subprocesses().stream()
                        .filter(pi -> pi.process().id().equals(fragments[0]) && pi.id().equals(fragments[1]))
                        .findFirst().ifPresent(pi -> pi.abortWorkItem(fragments[3], policies));
            } else {

                this.getProcessRuntime().getWorkItemManager().abortWorkItem(id, policies);
                removeOnFinish();
            }
        }
    }

    @Override
    public void transitionWorkItem(String id, Transition<?> transition) {
        synchronized (this) {
            processInstance();
            String[] fragments = id.split("/");

            if (fragments.length > 1) {
                // comes from subprocess
                subprocesses().stream()
                        .filter(pi -> pi.process().id().equals(fragments[0]) && pi.id().equals(fragments[1]))
                        .findFirst().ifPresent(pi -> pi.transitionWorkItem(fragments[3], transition));
            } else {

                this.getProcessRuntime().getWorkItemManager().transitionWorkItem(id, transition);
                removeOnFinish();
            }
        }
    }

    @Override
    public Set<EventDescription<?>> events() {
        return processInstance().getEventDescriptions();
    }

    @Override
    public Collection<Milestone> milestones() {
        return ((WorkflowProcessInstance) processInstance()).milestones();
    }

    @Override
    public Collection<AdHocFragment> adHocFragments() {
        return ((WorkflowProcessInstance) processInstance()).adHocFragments();
    }

    protected void removeOnFinish() {
        io.automatik.engine.api.runtime.process.ProcessInstance processInstance = processInstance();
        if (processInstance.getState() != ProcessInstance.STATE_ACTIVE
                && processInstance.getState() != ProcessInstance.STATE_ERROR) {
            ((WorkflowProcessInstanceImpl) processInstance).removeEventListener(
                    "processInstanceCompleted:" + processInstance.getId(), completionEventListener, false);

            removeCompletionListener();
            syncProcessInstance((WorkflowProcessInstance) processInstance);
            unbind(this.variables, processInstance().getVariables());
            addToUnitOfWork(pi -> ((MutableProcessInstances<T>) process.instances()).remove(pi.id(), pi));

        } else {
            ((WorkflowProcessInstance) processInstance).evaluateTags();
            syncProcessInstance((WorkflowProcessInstance) processInstance);
            unbind(this.variables, processInstance().getVariables());
            addToUnitOfWork(pi -> ((MutableProcessInstances<T>) process.instances()).update(pi.id(), pi));
        }

    }

    // this must be overridden at compile time
    protected Map<String, Object> bind(T variables) {
        HashMap<String, Object> vmap = new HashMap<>();
        if (variables == null) {
            return vmap;
        }
        try {
            for (Field f : variables.getClass().getDeclaredFields()) {
                f.setAccessible(true);
                Object v = f.get(variables);
                vmap.put(f.getName(), v);
            }
        } catch (IllegalAccessException e) {
            throw new Error(e);
        }
        vmap.put("$v", variables);
        return vmap;
    }

    protected void unbind(T variables, Map<String, Object> vmap) {
        if (vmap == null) {
            return;
        }
        try {
            for (Field f : variables.getClass().getDeclaredFields()) {
                f.setAccessible(true);
                f.set(variables, vmap.get(f.getName()));
            }
        } catch (IllegalAccessException e) {
            throw new Error(e);
        }
        vmap.put("$v", variables);
    }

    @SuppressWarnings("unchecked")
    protected void populateChildProcesses(Process<?> process, Collection<ProcessInstance<? extends Model>> collection) {

        WorkflowProcessInstanceImpl instance = (WorkflowProcessInstanceImpl) processInstance();

        List<String> children = instance.getChildren().get(process.id());
        if (children != null && !children.isEmpty()) {

            for (String id : children) {
                process.instances().findById(id)
                        .ifPresent(pi -> collection.add((ProcessInstance<? extends Model>) pi));

            }
        }
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((id == null) ? 0 : id.hashCode());
        result = prime * result + ((status == null) ? 0 : status.hashCode());
        return result;
    }

    @SuppressWarnings("rawtypes")
    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        AbstractProcessInstance other = (AbstractProcessInstance) obj;
        if (id == null) {
            if (other.id != null)
                return false;
        } else if (!id.equals(other.id))
            return false;
        if (status == null) {
            if (other.status != null)
                return false;
        } else if (!status.equals(other.status))
            return false;
        return true;
    }

    private ProcessRuntime getProcessRuntime() {
        if (rt == null) {
            throw new UnsupportedOperationException("Process instance is not connected to a Process Runtime");
        } else {
            return rt;
        }
    }

    protected ProcessError buildProcessError() {
        WorkflowProcessInstanceImpl pi = (WorkflowProcessInstanceImpl) processInstance();

        final String errorMessage = pi.getErrorMessage();
        final String nodeInError = pi.getNodeIdInError();
        return new ProcessError() {

            @Override
            public String failedNodeId() {
                return nodeInError;
            }

            @Override
            public String errorMessage() {
                return errorMessage;
            }

            @Override
            public void retrigger() {
                WorkflowProcessInstanceImpl pInstance = (WorkflowProcessInstanceImpl) processInstance();
                NodeInstance ni = pInstance.getNodeInstanceByNodeDefinitionId(nodeInError,
                        pInstance.getNodeContainer());
                pInstance.setState(STATE_ACTIVE);
                pInstance.internalSetErrorNodeId(null);
                pInstance.internalSetErrorMessage(null);
                ni.trigger(null, io.automatik.engine.workflow.process.core.Node.CONNECTION_DEFAULT_TYPE);
                removeOnFinish();
            }

            @Override
            public void skip() {
                WorkflowProcessInstanceImpl pInstance = (WorkflowProcessInstanceImpl) processInstance();
                NodeInstance ni = pInstance.getNodeInstanceByNodeDefinitionId(nodeInError,
                        pInstance.getNodeContainer());
                pInstance.setState(STATE_ACTIVE);
                pInstance.internalSetErrorNodeId(null);
                pInstance.internalSetErrorMessage(null);
                ((NodeInstanceImpl) ni)
                        .triggerCompleted(io.automatik.engine.workflow.process.core.Node.CONNECTION_DEFAULT_TYPE, true);
                removeOnFinish();
            }
        };
    }

    protected Tags buildTags() {

        return new Tags() {

            @Override
            public Collection<String> values() {
                WorkflowProcessInstanceImpl pi = (WorkflowProcessInstanceImpl) processInstance();
                return pi.getTags().stream().map(t -> t.getValue()).collect(Collectors.toList());
            }

            @Override
            public boolean remove(String id) {
                WorkflowProcessInstanceImpl pi = (WorkflowProcessInstanceImpl) processInstance();
                boolean removed = pi.removedTag(id);
                removeOnFinish();

                return removed;
            }

            @Override
            public Tag get(String id) {
                WorkflowProcessInstanceImpl pi = (WorkflowProcessInstanceImpl) processInstance();
                return pi.getTags().stream().filter(t -> t.getId().equals(id)).findFirst().orElse(null);
            }

            @Override
            public void add(String value) {
                WorkflowProcessInstanceImpl pi = (WorkflowProcessInstanceImpl) processInstance();
                pi.addTag(value);
                removeOnFinish();
            }

            @Override
            public Collection<Tag> get() {
                WorkflowProcessInstanceImpl pi = (WorkflowProcessInstanceImpl) processInstance();
                return pi.getTags();
            }
        };
    }

    private class CompletionEventListener implements EventListener {

        @Override
        public void signalEvent(String type, Object event) {
            removeOnFinish();
        }

        @Override
        public String[] getEventTypes() {
            return new String[] { "processInstanceCompleted:" + processInstance.getId() };
        }
    }

}
