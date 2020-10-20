
package io.automatik.engine.workflow.compiler.canonical;

import static com.github.javaparser.StaticJavaParser.parseClassOrInterfaceType;
import static io.automatik.engine.workflow.process.executable.core.Metadata.CUSTOM_AUTO_START;
import static io.automatik.engine.workflow.process.executable.core.Metadata.HIDDEN;
import static io.automatik.engine.workflow.process.executable.core.factory.MappableNodeFactory.METHOD_IN_MAPPING;
import static io.automatik.engine.workflow.process.executable.core.factory.MappableNodeFactory.METHOD_OUT_MAPPING;
import static io.automatik.engine.workflow.process.executable.core.factory.NodeFactory.METHOD_DONE;
import static io.automatik.engine.workflow.process.executable.core.factory.NodeFactory.METHOD_NAME;

import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;

import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.LongLiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.UnknownType;

import io.automatik.engine.api.definition.process.Connection;
import io.automatik.engine.api.definition.process.Node;
import io.automatik.engine.api.definition.process.WorkflowProcess;
import io.automatik.engine.services.utils.StringUtils;
import io.automatik.engine.workflow.base.core.context.variable.Mappable;
import io.automatik.engine.workflow.base.core.context.variable.Variable;
import io.automatik.engine.workflow.base.core.context.variable.VariableScope;
import io.automatik.engine.workflow.process.core.impl.ConnectionImpl;
import io.automatik.engine.workflow.process.core.node.BoundaryEventNode;
import io.automatik.engine.workflow.process.core.node.HumanTaskNode;
import io.automatik.engine.workflow.process.core.node.StartNode;

public abstract class AbstractNodeVisitor<T extends Node> extends AbstractVisitor {

    protected abstract String getNodeKey();

    public void visitNode(WorkflowProcess process, T node, BlockStmt body, VariableScope variableScope,
            ProcessMetaData metadata) {
        visitNode(process, FACTORY_FIELD_NAME, node, body, variableScope, metadata);
        if (isAdHocNode(node) && !(node instanceof HumanTaskNode) && !(node instanceof BoundaryEventNode)) {
            metadata.addSignal(node.getName(), null);
        }
    }

    private boolean isAdHocNode(Node node) {
        return (node.getIncomingConnections() == null || node.getIncomingConnections().isEmpty())
                && !(node instanceof StartNode)
                && !Boolean.parseBoolean((String) node.getMetaData().get(CUSTOM_AUTO_START));
    }

    protected String getNodeId(T node) {
        return getNodeKey() + node.getId();
    }

    public void visitNode(WorkflowProcess process, String factoryField, T node, BlockStmt body,
            VariableScope variableScope, ProcessMetaData metadata) {
    }

    protected MethodCallExpr getNameMethod(T node, String defaultName) {
        return getFactoryMethod(getNodeId(node), METHOD_NAME,
                new StringLiteralExpr(getOrDefault(node.getName(), defaultName)));
    }

    protected MethodCallExpr getDoneMethod(String object) {
        return getFactoryMethod(object, METHOD_DONE);
    }

    protected AssignExpr getAssignedFactoryMethod(String factoryField, Class<?> typeClass, String variableName,
            String methodName, Expression... args) {
        ClassOrInterfaceType type = new ClassOrInterfaceType(null, typeClass.getCanonicalName());

        MethodCallExpr variableMethod = new MethodCallExpr(new NameExpr(factoryField), methodName);

        for (Expression arg : args) {
            variableMethod.addArgument(arg);
        }

        return new AssignExpr(new VariableDeclarationExpr(type, variableName), variableMethod,
                AssignExpr.Operator.ASSIGN);
    }

    public static Statement makeAssignment(Variable v) {
        String name = v.getSanitizedName();
        return makeAssignment(name, v);
    }

    public static Statement makeAssignment(String targetLocalVariable, Variable processVariable) {
        ClassOrInterfaceType type = parseClassOrInterfaceType(processVariable.getType().getStringType());
        // `type` `name` = (`type`) `kcontext.getVariable
        AssignExpr assignExpr = new AssignExpr(new VariableDeclarationExpr(type, targetLocalVariable),
                new CastExpr(type, new MethodCallExpr(new NameExpr(KCONTEXT_VAR), "getVariable")
                        .addArgument(new StringLiteralExpr(targetLocalVariable))),
                AssignExpr.Operator.ASSIGN);
        return new ExpressionStmt(assignExpr);
    }

    public static Statement makeAssignmentFromMap(Variable v) {
        String name = v.getSanitizedName();
        return makeAssignmentFromMap(name, v);
    }

    public static Statement makeAssignmentFromMap(String targetLocalVariable, Variable processVariable) {
        ClassOrInterfaceType type = parseClassOrInterfaceType(processVariable.getType().getStringType());
        AssignExpr assignExpr = new AssignExpr(new VariableDeclarationExpr(type, targetLocalVariable),
                new CastExpr(type, new MethodCallExpr(new NameExpr("variables"), "get")
                        .addArgument(new StringLiteralExpr(targetLocalVariable))),
                AssignExpr.Operator.ASSIGN);
        return new ExpressionStmt(assignExpr);
    }

    protected Statement makeAssignmentFromModel(Variable v) {
        return makeAssignmentFromModel(v, v.getSanitizedName());
    }

    protected Statement makeAssignmentFromModel(Variable v, String name) {
        ClassOrInterfaceType type = parseClassOrInterfaceType(v.getType().getStringType());
        // `type` `name` = (`type`) `model.get<Name>
        AssignExpr assignExpr = new AssignExpr(new VariableDeclarationExpr(type, name),
                new CastExpr(type, new MethodCallExpr(new NameExpr("model"), "get" + StringUtils.capitalize(name))),
                AssignExpr.Operator.ASSIGN);

        return new ExpressionStmt(assignExpr);
    }

    protected void addNodeMappings(Mappable node, BlockStmt body, String variableName) {
        for (Entry<String, String> entry : node.getInMappings().entrySet()) {
            body.addStatement(getFactoryMethod(variableName, METHOD_IN_MAPPING, new StringLiteralExpr(entry.getKey()),
                    new StringLiteralExpr(entry.getValue())));
        }
        for (Entry<String, String> entry : node.getOutMappings().entrySet()) {
            body.addStatement(getFactoryMethod(variableName, METHOD_OUT_MAPPING, new StringLiteralExpr(entry.getKey()),
                    new StringLiteralExpr(entry.getValue())));
        }
    }

    protected String extractVariableFromExpression(String variableExpression) {
        if (variableExpression.startsWith("#{")) {
            return variableExpression.substring(2, variableExpression.indexOf('.'));
        }
        return variableExpression;
    }

    protected void visitConnections(String factoryField, Node[] nodes, BlockStmt body) {
        List<Connection> connections = new ArrayList<>();
        for (Node node : nodes) {
            for (List<Connection> connectionList : node.getIncomingConnections().values()) {
                connections.addAll(connectionList);
            }
        }
        for (Connection connection : connections) {
            visitConnection(factoryField, connection, body);
        }
    }

    protected void visitConnection(String factoryField, Connection connection, BlockStmt body) {
        // if the connection is a hidden one (compensations), don't dump
        Object hidden = ((ConnectionImpl) connection).getMetaData(HIDDEN);
        if (hidden != null && ((Boolean) hidden)) {
            return;
        }

        body.addStatement(getFactoryMethod(factoryField, "connection",
                new LongLiteralExpr(connection.getFrom().getId()), new LongLiteralExpr(connection.getTo().getId()),
                new StringLiteralExpr(getOrDefault((String) connection.getMetaData().get("UniqueId"), ""))));
    }

    protected static LambdaExpr createLambdaExpr(String consequence, VariableScope scope) {
        BlockStmt conditionBody = new BlockStmt();
        List<Variable> variables = scope.getVariables();
        variables.stream().map(ActionNodeVisitor::makeAssignment).forEach(conditionBody::addStatement);

        conditionBody.addStatement(new ReturnStmt(new EnclosedExpr(new NameExpr(consequence))));

        return new LambdaExpr(new Parameter(new UnknownType(), KCONTEXT_VAR), // (kcontext) ->
                conditionBody);
    }

}
