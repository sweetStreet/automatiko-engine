package io.automatiko.engine.workflow.builder;

import io.automatiko.engine.workflow.base.core.context.variable.Variable;
import io.automatiko.engine.workflow.base.core.event.EventTypeFilter;
import io.automatiko.engine.workflow.process.core.Node;
import io.automatiko.engine.workflow.process.core.node.DataAssociation;
import io.automatiko.engine.workflow.process.core.node.EventTrigger;
import io.automatiko.engine.workflow.process.core.node.StartNode;
import io.automatiko.engine.workflow.process.executable.core.Metadata;

/**
 * Builder responsible for building an start node
 */
public class StartOnMessageNodeBuilder extends AbstractNodeBuilder {

    private StartNode node;

    private EventTrigger trigger;

    private EventTypeFilter eventFilter;

    public StartOnMessageNodeBuilder(String name, WorkflowBuilder workflowBuilder) {
        super(workflowBuilder);
        this.node = new StartNode();

        this.node.setId(ids.incrementAndGet());
        this.node.setName(name);
        this.node.setMetaData("UniqueId", generateUiqueId(this.node));

        this.node.setMetaData(Metadata.TRIGGER_TYPE, "ConsumeMessage");
        this.node.setMetaData(Metadata.TRIGGER_REF, name);
        this.trigger = new EventTrigger();
        this.eventFilter = new EventTypeFilter();
        eventFilter.setType("Message-" + name);
        trigger.addEventFilter(eventFilter);

        this.node.addTrigger(trigger);

        workflowBuilder.get().addNode(node);
    }

    /**
     * Specifies the type of the message content. If not given it is taken from the data object payload will be mapped to.
     * 
     * @param type class of the message payload
     * @return the builder
     */
    public StartOnMessageNodeBuilder type(Class<?> type) {
        node.setMetaData(Metadata.MESSAGE_TYPE, type.getCanonicalName());
        return this;
    }

    /**
     * Optional connector name to be used if there are more connectors used in the project.
     * If only one is defined as project dependency it is auto discovered.<br/>
     * Supported connectors are:
     * <ul>
     * <li>kafka</li>
     * <li>mqtt</li>
     * <li>amqp</li>
     * <li>camel</li>
     * <li>http</li>
     * <li>jms</li>
     * </ul>
     * 
     * @param connector one of the supported connectors
     * @return the builder
     */
    public StartOnMessageNodeBuilder connector(String connector) {
        node.setMetaData("connector", connector);
        return this;
    }

    /**
     * Expression to be used to extract correlation key to be used to look up workflow instances for the message
     * 
     * @param expression correlation key expression
     * @return the builder
     */
    public StartOnMessageNodeBuilder correlation(String expression) {
        node.setMetaData(Metadata.TRIGGER_CORRELATION_EXPR, expression);
        return this;
    }

    /**
     * Filter expression to be used to identify if given message should be processed
     * 
     * @param expression the filter expression
     * @return the builder
     */
    public StartOnMessageNodeBuilder filter(String expression) {
        node.setMetaData(Metadata.TRIGGER_FILTER_EXPR, expression);
        return this;
    }

    /**
     * Name of the topic to listen on
     * 
     * @param topic name of the topic
     * @return the builder
     */
    public StartOnMessageNodeBuilder topic(String topic) {
        node.setMetaData("topic", topic);
        return this;
    }

    /**
     * Name of the data object the message payload should be mapped to
     * 
     * @param name data object name
     * @return the builder
     */
    public StartOnMessageNodeBuilder toDataObject(String name) {
        if (name != null) {

            Variable var = workflowBuilder.get().getVariableScope().findVariable(name);
            if (var == null) {
                throw new IllegalArgumentException("No data object with name '" + name + " found");
            }
            node.setMetaData(Metadata.TRIGGER_MAPPING, name);
            node.addOutAssociation(new DataAssociation("event", name, null, null));
            trigger.addInMapping(name, node.getOutMapping(name));
            node.setMetaData(Metadata.MESSAGE_TYPE, var.getType().getClassType().getCanonicalName());
        }
        return this;
    }

    @Override
    protected Node getNode() {
        return this.node;
    }

}