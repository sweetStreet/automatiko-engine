
package io.automatiko.engine.workflow.process.instance.impl;

import static io.automatiko.engine.api.runtime.process.ProcessInstance.STATE_ACTIVE;
import static io.automatiko.engine.workflow.process.executable.core.Metadata.HIDDEN;
import static io.automatiko.engine.workflow.process.executable.core.Metadata.INCOMING_CONNECTION;
import static io.automatiko.engine.workflow.process.executable.core.Metadata.OUTGOING_CONNECTION;
import static io.automatiko.engine.workflow.process.executable.core.Metadata.UNIQUE_ID;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.automatiko.engine.api.definition.process.Connection;
import io.automatiko.engine.api.definition.process.Node;
import io.automatiko.engine.api.runtime.process.NodeInstance;
import io.automatiko.engine.api.runtime.process.NodeInstanceContainer;
import io.automatiko.engine.api.runtime.process.NodeInstanceState;
import io.automatiko.engine.workflow.base.core.Context;
import io.automatiko.engine.workflow.base.core.ContextContainer;
import io.automatiko.engine.workflow.base.core.context.ProcessContext;
import io.automatiko.engine.workflow.base.core.context.exception.ExceptionScope;
import io.automatiko.engine.workflow.base.core.context.exclusive.ExclusiveGroup;
import io.automatiko.engine.workflow.base.core.context.variable.VariableScope;
import io.automatiko.engine.workflow.base.instance.ContextInstance;
import io.automatiko.engine.workflow.base.instance.ContextInstanceContainer;
import io.automatiko.engine.workflow.base.instance.InternalProcessRuntime;
import io.automatiko.engine.workflow.base.instance.ProcessInstance;
import io.automatiko.engine.workflow.base.instance.context.exception.ExceptionScopeInstance;
import io.automatiko.engine.workflow.base.instance.context.exclusive.ExclusiveGroupInstance;
import io.automatiko.engine.workflow.base.instance.context.variable.VariableScopeInstance;
import io.automatiko.engine.workflow.base.instance.impl.Action;
import io.automatiko.engine.workflow.base.instance.impl.ConstraintEvaluator;
import io.automatiko.engine.workflow.process.core.impl.NodeImpl;
import io.automatiko.engine.workflow.process.instance.WorkflowProcessInstance;
import io.automatiko.engine.workflow.process.instance.WorkflowRuntimeException;
import io.automatiko.engine.workflow.process.instance.node.ActionNodeInstance;
import io.automatiko.engine.workflow.process.instance.node.CompositeNodeInstance;

/**
 * Default implementation of a RuleFlow node instance.
 * 
 */
public abstract class NodeInstanceImpl
        implements io.automatiko.engine.workflow.process.instance.NodeInstance, Serializable {

    private static final long serialVersionUID = 510l;
    protected static final Logger logger = LoggerFactory.getLogger(NodeInstanceImpl.class);

    private String id;
    private long nodeId;
    private WorkflowProcessInstance processInstance;
    private io.automatiko.engine.workflow.process.instance.NodeInstanceContainer nodeInstanceContainer;
    private Map<String, Object> metaData = new HashMap<>();
    private int level;

    protected int slaCompliance = ProcessInstance.SLA_NA;
    protected Date slaDueDate;
    protected String slaTimerId;
    protected Date triggerTime;
    protected Date leaveTime;

    protected String retryJobId;
    protected Integer retryAttempts;

    protected NodeInstanceState nodeInstanceState = NodeInstanceState.Created;

    protected transient Map<String, Object> dynamicParameters;

    public void setId(final String id) {
        this.id = id;
    }

    public String getId() {
        return this.id;
    }

    public void setNodeId(final long nodeId) {
        this.nodeId = nodeId;
    }

    public long getNodeId() {
        return this.nodeId;
    }

    public String getNodeName() {
        Node node = getNode();
        return node == null ? "" : node.getName();
    }

    public String getNodeDefinitionId() {
        return (String) getNode().getMetaData().get(UNIQUE_ID);
    }

    public int getLevel() {
        return this.level;
    }

    public void setLevel(int level) {
        this.level = level;
    }

    public void setProcessInstance(final WorkflowProcessInstance processInstance) {
        this.processInstance = processInstance;
    }

    public WorkflowProcessInstance getProcessInstance() {
        return this.processInstance;
    }

    public NodeInstanceContainer getNodeInstanceContainer() {
        return this.nodeInstanceContainer;
    }

    @Override
    public NodeInstanceState getNodeInstanceState() {
        return nodeInstanceState;
    }

    public String getRetryJobId() {
        return retryJobId;
    }

    public void internalSetRetryJobId(String retryJobId) {
        if (retryJobId != null && !retryJobId.isEmpty()) {
            this.retryJobId = retryJobId;
        }
    }

    public Integer getRetryAttempts() {
        return retryAttempts;
    }

    public void internalSetRetryAttempts(Integer retryAttempts) {
        this.retryAttempts = retryAttempts;
    }

    public void registerRetryEventListener() {

    }

    public void setNodeInstanceContainer(NodeInstanceContainer nodeInstanceContainer) {
        this.nodeInstanceContainer = (io.automatiko.engine.workflow.process.instance.NodeInstanceContainer) nodeInstanceContainer;
        if (nodeInstanceContainer != null) {
            this.nodeInstanceContainer.addNodeInstance(this);
        }
    }

    public Node getNode() {
        try {
            return ((io.automatiko.engine.workflow.process.core.NodeContainer) this.nodeInstanceContainer
                    .getNodeContainer()).internalGetNode(this.nodeId);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown node id: " + this.nodeId + " for node instance " + getUniqueId()
                    + " for process instance " + this.processInstance, e);
        }
    }

    public boolean isInversionOfControl() {
        return false;
    }

    public void cancel() {
        leaveTime = new Date();
        internalChangeState(NodeInstanceState.Teminated);
        boolean hidden = false;
        Node node = getNode();
        if (node != null && node.getMetaData().get(HIDDEN) != null) {
            hidden = true;
        }
        if (!hidden) {
            InternalProcessRuntime runtime = getProcessInstance().getProcessRuntime();
            runtime.getProcessEventSupport().fireBeforeNodeLeft(this, runtime);
        }
        nodeInstanceContainer.removeNodeInstance(this);
        if (!hidden) {
            InternalProcessRuntime runtime = getProcessInstance().getProcessRuntime();
            runtime.getProcessEventSupport().fireAfterNodeLeft(this, runtime);
        }
    }

    public final void trigger(NodeInstance from, String type) {
        io.automatiko.engine.workflow.process.core.Node currentNode = (io.automatiko.engine.workflow.process.core.Node) getNode();

        // function flow check
        if (getProcessInstance().isFunctionFlow() && getProcessInstance().isExecutionNode(currentNode)) {
            Integer functionFlowCounter = (Integer) getProcessInstance().getMetaData("ATK_FUNC_FLOW_COUNTER");
            if (functionFlowCounter == null) {
                functionFlowCounter = 1;
                getProcessInstance().getMetaData().put("ATK_FUNC_FLOW_COUNTER", functionFlowCounter);
            } else {
                // function flow already called function
                getProcessInstance().getMetaData().remove("ATK_FUNC_FLOW_COUNTER");
                getProcessInstance().getMetaData().put("ATK_FUNC_FLOW_NEXT", getNodeName());
                nodeInstanceContainer.removeNodeInstance(this);
                return;
            }

        }

        // check activation condition if this can be invoked
        if (currentNode.getActivationCheck().isPresent()) {

            if (!currentNode.getActivationCheck().get().isValid(getProcessInstance().getVariables())) {
                nodeInstanceContainer.removeNodeInstance(this);
                return;
            }
        }
        internalChangeState(NodeInstanceState.Active);

        boolean hidden = false;
        if (getNode().getMetaData().get(HIDDEN) != null) {
            hidden = true;
        }

        if (from != null) {
            int level = ((io.automatiko.engine.workflow.process.instance.NodeInstance) from).getLevel();
            ((io.automatiko.engine.workflow.process.instance.NodeInstanceContainer) getNodeInstanceContainer())
                    .setCurrentLevel(level);
            Collection<Connection> incoming = getNode().getIncomingConnections(type);
            for (Connection conn : incoming) {
                if (conn.getFrom().getId() == from.getNodeId()) {
                    this.metaData.put(INCOMING_CONNECTION, conn.getMetaData().get(UNIQUE_ID));
                    break;
                }
            }
        }
        if (dynamicParameters != null) {
            for (Entry<String, Object> entry : dynamicParameters.entrySet()) {
                setVariable(entry.getKey(), entry.getValue());
            }
        }
        configureSla();

        InternalProcessRuntime runtime = getProcessInstance().getProcessRuntime();
        if (!hidden) {
            runtime.getProcessEventSupport().fireBeforeNodeTriggered(this, runtime);
        }
        try {
            internalTrigger(from, type);
        } catch (Exception e) {
            captureError(e);
            internalChangeState(NodeInstanceState.Failed);
            runtime.getProcessEventSupport().fireAfterNodeInstanceFailed(getProcessInstance(), this, e, runtime);
            // stop after capturing error
            return;
        }
        if (!hidden) {
            runtime.getProcessEventSupport().fireAfterNodeTriggered(this, runtime);
        }
    }

    protected void captureError(Exception e) {
        getProcessInstance().setErrorState(this, e);
    }

    public abstract void internalTrigger(NodeInstance from, String type);

    /**
     * This method is used in both instances of the {@link ExtendedNodeInstanceImpl}
     * and {@link ActionNodeInstance} instances in order to handle exceptions thrown
     * when executing actions.
     * 
     * @param action An {@link Action} instance.
     */
    protected void executeAction(Action action) {
        ProcessContext context = new ProcessContext(getProcessInstance().getProcessRuntime());
        context.setNodeInstance(this);
        context.setProcessInstance(getProcessInstance());
        try {
            action.execute(context);
        } catch (Exception e) {
            String exceptionName = e.getClass().getName();
            ExceptionScopeInstance exceptionScopeInstance = (ExceptionScopeInstance) resolveContextInstance(
                    ExceptionScope.EXCEPTION_SCOPE, exceptionName);
            if (exceptionScopeInstance == null) {
                throw new WorkflowRuntimeException(this, getProcessInstance(),
                        "Unable to execute Action: " + e.getMessage(), e);
            }

            exceptionScopeInstance.handleException(this, exceptionName, e);
            cancel();
        }
    }

    public void triggerCompleted(String type, boolean remove) {
        leaveTime = new Date();
        internalChangeState(NodeInstanceState.Completed);
        Node node = getNode();
        if (node != null) {
            String uniqueId = (String) node.getMetaData().get(UNIQUE_ID);
            if (uniqueId == null) {
                uniqueId = ((NodeImpl) node).getUniqueId();
            }
            ((WorkflowProcessInstanceImpl) processInstance).addCompletedNodeId(uniqueId);
            ((WorkflowProcessInstanceImpl) processInstance).getIterationLevels().remove(uniqueId);
        }

        // if node instance was cancelled, or containing container instance was
        // cancelled
        if ((getNodeInstanceContainer().getNodeInstance(getId()) == null)
                || (((io.automatiko.engine.workflow.process.instance.NodeInstanceContainer) getNodeInstanceContainer())
                        .getState() != STATE_ACTIVE)) {
            return;
        }

        if (remove) {
            ((io.automatiko.engine.workflow.process.instance.NodeInstanceContainer) getNodeInstanceContainer())
                    .removeNodeInstance(this);
        }
        continueToNextNode(type, node);
    }

    protected void continueToNextNode(String type, Node node) {

        List<Connection> connections = null;
        if (node != null) {
            if ("true".equals(System.getProperty("jbpm.enable.multi.con"))
                    && ((NodeImpl) node).getConstraints().size() > 0) {
                int priority;
                connections = ((NodeImpl) node).getDefaultOutgoingConnections();
                boolean found = false;
                List<NodeInstanceTrigger> nodeInstances = new ArrayList<>();
                List<Connection> outgoingCopy = new ArrayList<>(connections);
                while (!outgoingCopy.isEmpty()) {
                    priority = Integer.MAX_VALUE;
                    Connection selectedConnection = null;
                    ConstraintEvaluator selectedConstraint = null;
                    for (final Connection connection : outgoingCopy) {
                        ConstraintEvaluator constraint = (ConstraintEvaluator) ((NodeImpl) node)
                                .getConstraint(connection);
                        if (constraint != null && constraint.getPriority() < priority && !constraint.isDefault()) {
                            priority = constraint.getPriority();
                            selectedConnection = connection;
                            selectedConstraint = constraint;
                        }
                    }
                    if (selectedConstraint == null) {
                        break;
                    }
                    if (selectedConstraint.evaluate(this, selectedConnection, selectedConstraint)) {
                        nodeInstances.add(new NodeInstanceTrigger(followConnection(selectedConnection),
                                selectedConnection.getToType()));
                        found = true;
                    }
                    outgoingCopy.remove(selectedConnection);
                }
                for (NodeInstanceTrigger nodeInstance : nodeInstances) {
                    // stop if this process instance has been aborted / completed
                    if (((io.automatiko.engine.workflow.process.instance.NodeInstanceContainer) getNodeInstanceContainer())
                            .getState() != STATE_ACTIVE) {
                        return;
                    }
                    triggerNodeInstance(nodeInstance.getNodeInstance(), nodeInstance.getToType());
                }
                if (!found) {
                    for (final Connection connection : connections) {
                        ConstraintEvaluator constraint = (ConstraintEvaluator) ((NodeImpl) node)
                                .getConstraint(connection);
                        if (constraint.isDefault()) {
                            triggerConnection(connection);
                            found = true;
                            break;
                        }
                    }
                }
                if (!found) {
                    throw new IllegalArgumentException(
                            "Uncontrolled flow node could not find at least one valid outgoing connection "
                                    + getNode().getName());
                }
                return;
            } else {
                connections = node.getOutgoingConnections(type);
            }
        }
        if (connections == null || connections.isEmpty()) {
            boolean hidden = false;
            Node currentNode = getNode();
            if (currentNode != null && currentNode.getMetaData().get(HIDDEN) != null) {
                hidden = true;
            }
            InternalProcessRuntime runtime = getProcessInstance().getProcessRuntime();
            if (!hidden) {
                runtime.getProcessEventSupport().fireBeforeNodeLeft(this, runtime);
            }
            // notify container
            ((io.automatiko.engine.workflow.process.instance.NodeInstanceContainer) getNodeInstanceContainer())
                    .nodeInstanceCompleted(this, type);
            if (!hidden) {
                runtime.getProcessEventSupport().fireAfterNodeLeft(this, runtime);
            }
        } else {
            Map<io.automatiko.engine.workflow.process.instance.NodeInstance, String> nodeInstances = new HashMap<>();
            for (Connection connection : connections) {
                nodeInstances.put(followConnection(connection), connection.getToType());
            }
            for (Map.Entry<io.automatiko.engine.workflow.process.instance.NodeInstance, String> nodeInstance : nodeInstances
                    .entrySet()) {
                // stop if this process instance has been aborted / completed
                if (((io.automatiko.engine.workflow.process.instance.NodeInstanceContainer) getNodeInstanceContainer())
                        .getState() != STATE_ACTIVE) {
                    return;
                }
                triggerNodeInstance(nodeInstance.getKey(), nodeInstance.getValue());
            }
        }
    }

    protected io.automatiko.engine.workflow.process.instance.NodeInstance followConnection(Connection connection) {
        // check for exclusive group first
        NodeInstanceContainer parent = getNodeInstanceContainer();
        if (parent instanceof ContextInstanceContainer) {
            List<ContextInstance> contextInstances = ((ContextInstanceContainer) parent)
                    .getContextInstances(ExclusiveGroup.EXCLUSIVE_GROUP);
            if (contextInstances != null) {
                for (ContextInstance contextInstance : new ArrayList<>(contextInstances)) {
                    ExclusiveGroupInstance groupInstance = (ExclusiveGroupInstance) contextInstance;
                    if (groupInstance.containsNodeInstance(this)) {
                        for (NodeInstance nodeInstance : groupInstance.getNodeInstances()) {
                            if (nodeInstance != this) {
                                ((io.automatiko.engine.workflow.process.instance.NodeInstance) nodeInstance).cancel();
                            }
                        }
                        ((ContextInstanceContainer) parent).removeContextInstance(ExclusiveGroup.EXCLUSIVE_GROUP,
                                contextInstance);
                    }

                }
            }
        }
        return ((io.automatiko.engine.workflow.process.instance.NodeInstanceContainer) getNodeInstanceContainer())
                .getNodeInstance(connection.getTo());
    }

    protected void triggerNodeInstance(io.automatiko.engine.workflow.process.instance.NodeInstance nodeInstance,
            String type) {
        triggerNodeInstance(nodeInstance, type, true);
    }

    protected void triggerNodeInstance(io.automatiko.engine.workflow.process.instance.NodeInstance nodeInstance,
            String type, boolean fireEvents) {
        if (nodeInstance == null) {
            return;
        }

        leaveTime = new Date();
        boolean hidden = false;
        if (getNode().getMetaData().get(HIDDEN) != null) {
            hidden = true;
        }
        InternalProcessRuntime runtime = getProcessInstance().getProcessRuntime();
        if (!hidden && fireEvents) {
            runtime.getProcessEventSupport().fireBeforeNodeLeft(this, runtime);
        }
        // trigger next node
        nodeInstance.trigger(this, type);
        Collection<Connection> outgoing = getNode().getOutgoingConnections(type);
        for (Connection conn : outgoing) {
            if (conn.getTo().getId() == nodeInstance.getNodeId()) {
                this.metaData.put(OUTGOING_CONNECTION, conn.getMetaData().get(UNIQUE_ID));
                break;
            }
        }
        if (!hidden && fireEvents) {
            runtime.getProcessEventSupport().fireAfterNodeLeft(this, runtime);
        }
    }

    protected void triggerConnection(Connection connection) {
        triggerNodeInstance(followConnection(connection), connection.getToType());
    }

    public void retrigger(boolean remove) {
        if (remove) {
            cancel();
        }
        triggerNode(getNodeId(), !remove);
    }

    public void triggerNode(long nodeId) {
        triggerNode(nodeId, true);
    }

    public void triggerNode(long nodeId, boolean fireEvents) {
        io.automatiko.engine.workflow.process.instance.NodeInstance nodeInstance = ((io.automatiko.engine.workflow.process.instance.NodeInstanceContainer) getNodeInstanceContainer())
                .getNodeInstance(getNode().getParentContainer().getNode(nodeId));
        triggerNodeInstance(nodeInstance, io.automatiko.engine.workflow.process.core.Node.CONNECTION_DEFAULT_TYPE,
                fireEvents);
    }

    public void retry() {

        boolean hidden = false;
        if (getNode().getMetaData().get(HIDDEN) != null) {
            hidden = true;
        }
        InternalProcessRuntime runtime = getProcessInstance().getProcessRuntime();

        try {

            internalChangeState(NodeInstanceState.Active);

            internalTrigger(this, io.automatiko.engine.workflow.process.core.Node.CONNECTION_DEFAULT_TYPE);

            Collection<Connection> outgoing = getNode()
                    .getOutgoingConnections(io.automatiko.engine.workflow.process.core.Node.CONNECTION_DEFAULT_TYPE);
            for (Connection conn : outgoing) {
                if (conn.getTo().getId() == getNodeId()) {
                    this.metaData.put(OUTGOING_CONNECTION, conn.getMetaData().get(UNIQUE_ID));
                    break;
                }
            }
            if (!hidden) {
                runtime.getProcessEventSupport().fireAfterNodeLeft(this, runtime);
            }
        } catch (Exception e) {
            captureError(e);
            internalChangeState(NodeInstanceState.Failed);
            runtime.getProcessEventSupport().fireAfterNodeInstanceFailed(getProcessInstance(), this, e, runtime);
            // stop after capturing error
            return;
        }

    }

    public Context resolveContext(String contextId, Object param) {
        if (getNode() == null) {
            return null;
        }
        return ((NodeImpl) getNode()).resolveContext(contextId, param);
    }

    public ContextInstance resolveContextInstance(String contextId, Object param) {
        Context context = resolveContext(contextId, param);
        if (context == null) {
            return null;
        }
        ContextInstanceContainer contextInstanceContainer = getContextInstanceContainer(context.getContextContainer());
        if (contextInstanceContainer == null) {
            throw new IllegalArgumentException("Could not find context instance container for context");
        }
        return contextInstanceContainer.getContextInstance(context);
    }

    private ContextInstanceContainer getContextInstanceContainer(ContextContainer contextContainer) {
        ContextInstanceContainer contextInstanceContainer;
        if (this instanceof ContextInstanceContainer) {
            contextInstanceContainer = (ContextInstanceContainer) this;
        } else {
            contextInstanceContainer = getEnclosingContextInstanceContainer(this);
        }
        while (contextInstanceContainer != null) {
            if (contextInstanceContainer.getContextContainer() == contextContainer) {
                return contextInstanceContainer;
            }
            contextInstanceContainer = getEnclosingContextInstanceContainer((NodeInstance) contextInstanceContainer);
        }
        return null;
    }

    private ContextInstanceContainer getEnclosingContextInstanceContainer(NodeInstance nodeInstance) {
        NodeInstanceContainer nodeInstanceContainer = nodeInstance.getNodeInstanceContainer();
        while (true) {
            if (nodeInstanceContainer instanceof ContextInstanceContainer) {
                return (ContextInstanceContainer) nodeInstanceContainer;
            }
            if (nodeInstanceContainer instanceof NodeInstance) {
                nodeInstanceContainer = ((NodeInstance) nodeInstanceContainer).getNodeInstanceContainer();
            } else {
                return null;
            }
        }
    }

    public Object getVariable(String variableName) {
        VariableScopeInstance variableScope = (VariableScopeInstance) resolveContextInstance(
                VariableScope.VARIABLE_SCOPE, variableName);
        if (variableScope == null) {
            variableScope = (VariableScopeInstance) getProcessInstance()
                    .getContextInstance(VariableScope.VARIABLE_SCOPE);
        }
        return variableScope.getVariable(variableName);
    }

    public void setVariable(String variableName, Object value) {
        VariableScopeInstance variableScope = (VariableScopeInstance) resolveContextInstance(
                VariableScope.VARIABLE_SCOPE, variableName);
        if (variableScope == null) {
            variableScope = (VariableScopeInstance) getProcessInstance()
                    .getContextInstance(VariableScope.VARIABLE_SCOPE);
            if (variableScope.getVariableScope().findVariable(variableName) == null) {
                variableScope = null;
            }
        }
        if (variableScope == null) {
            logger.error("Could not find variable {}", variableName);
            logger.error("Using process-level scope");
            variableScope = (VariableScopeInstance) getProcessInstance()
                    .getContextInstance(VariableScope.VARIABLE_SCOPE);
        }
        variableScope.setVariable(this, variableName, value);
    }

    public String getUniqueId() {
        String result = "" + getId();
        NodeInstanceContainer parent = getNodeInstanceContainer();
        while (parent instanceof CompositeNodeInstance) {
            CompositeNodeInstance nodeInstance = (CompositeNodeInstance) parent;
            result = nodeInstance.getId() + ":" + result;
            parent = nodeInstance.getNodeInstanceContainer();
        }
        return result;
    }

    public boolean exitOnCompletionCondition() {
        return true;
    }

    public Map<String, Object> getMetaData() {
        return this.metaData;
    }

    public Object getMetaData(String name) {
        return this.metaData.get(name);
    }

    public void setMetaData(String name, Object data) {
        this.metaData.put(name, data);
    }

    protected static class NodeInstanceTrigger {
        private io.automatiko.engine.workflow.process.instance.NodeInstance nodeInstance;
        private String toType;

        public NodeInstanceTrigger(io.automatiko.engine.workflow.process.instance.NodeInstance nodeInstance,
                String toType) {
            this.nodeInstance = nodeInstance;
            this.toType = toType;
        }

        public io.automatiko.engine.workflow.process.instance.NodeInstance getNodeInstance() {
            return nodeInstance;
        }

        public String getToType() {
            return toType;
        }
    }

    public void setDynamicParameters(Map<String, Object> dynamicParameters) {
        this.dynamicParameters = dynamicParameters;
    }

    protected void configureSla() {

    }

    public int getSlaCompliance() {
        return slaCompliance;
    }

    public void internalSetSlaCompliance(int slaCompliance) {
        this.slaCompliance = slaCompliance;
    }

    public Date getSlaDueDate() {
        return slaDueDate;
    }

    public void internalSetSlaDueDate(Date slaDueDate) {
        this.slaDueDate = slaDueDate;
    }

    public String getSlaTimerId() {
        return slaTimerId;
    }

    public void internalSetSlaTimerId(String slaTimerId) {
        this.slaTimerId = slaTimerId;
    }

    public Date getTriggerTime() {
        return triggerTime;
    }

    public void internalSetTriggerTime(Date triggerTime) {
        this.triggerTime = triggerTime;
    }

    public Date getLeaveTime() {
        return leaveTime;
    }

    protected void internalChangeState(NodeInstanceState newState) {
        this.nodeInstanceState = newState;
        ((WorkflowProcessInstanceImpl) getProcessInstance()).broadcaseNodeInstanceStateChange(this);
    }

}
