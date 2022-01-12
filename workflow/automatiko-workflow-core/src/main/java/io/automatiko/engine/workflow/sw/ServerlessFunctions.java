package io.automatiko.engine.workflow.sw;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import io.automatiko.engine.api.Functions;
import io.automatiko.engine.api.expression.ExpressionEvaluator;
import io.automatiko.engine.api.runtime.process.ProcessContext;
import io.automatiko.engine.workflow.base.core.context.variable.JsonVariableScope;
import io.automatiko.engine.workflow.process.core.WorkflowProcess;

@SuppressWarnings({ "unchecked", "rawtypes" })
public class ServerlessFunctions implements Functions {

    private static ObjectMapper mapper = new ObjectMapper();

    public static void inject(ProcessContext context, String json) {

        try {
            ObjectNode data = (ObjectNode) mapper.readTree(json);
            data.fields().forEachRemaining(field -> context.setVariable(field.getKey(), field.getValue()));
        } catch (Exception e) {
            throw new RuntimeException("Error injecting data into state data", e);
        }
    }

    public static Object expression(ProcessContext context, String expression) {

        ExpressionEvaluator evaluator = (ExpressionEvaluator) ((WorkflowProcess) context.getProcessInstance()
                .getProcess())
                        .getDefaultContext(ExpressionEvaluator.EXPRESSION_EVALUATOR);
        Map<String, Object> vars = new HashMap<>();
        vars.put("workflowdata", context.getVariable(JsonVariableScope.WORKFLOWDATA_KEY));
        if (context.getVariable("$CONST") != null) {
            vars.put("workflow_variables", Collections.singletonMap("CONST", context.getVariable("$CONST")));
        }
        Object content = evaluator.evaluate(expression, vars);

        return content;
    }

    public static String expressionAsString(ProcessContext context, String expression) {

        ExpressionEvaluator evaluator = (ExpressionEvaluator) ((WorkflowProcess) context.getProcessInstance()
                .getProcess())
                        .getDefaultContext(ExpressionEvaluator.EXPRESSION_EVALUATOR);
        Map<String, Object> vars = new HashMap<>();
        vars.put("workflowdata", context.getVariable(JsonVariableScope.WORKFLOWDATA_KEY));
        if (context.getVariable("$CONST") != null) {
            vars.put("workflow_variables", Collections.singletonMap("CONST", context.getVariable("$CONST")));
        }
        Object content = evaluator.evaluate(expression, vars);

        if (content == null) {
            return null;
        }
        if (content instanceof TextNode) {
            return ((TextNode) content).asText();
        }

        return content.toString();
    }

    public static void expression(ProcessContext context, String expression, String inputFilter) {

        ExpressionEvaluator evaluator = (ExpressionEvaluator) ((WorkflowProcess) context.getProcessInstance()
                .getProcess())
                        .getDefaultContext(ExpressionEvaluator.EXPRESSION_EVALUATOR);
        Map<String, Object> vars = new HashMap<>();
        vars.put("workflowdata", context.getVariable(JsonVariableScope.WORKFLOWDATA_KEY));
        if (context.getVariable("$CONST") != null) {
            vars.put("workflow_variables", Collections.singletonMap("CONST", context.getVariable("$CONST")));
        }

        if (inputFilter != null) {
            Object filteredInput = evaluator.evaluate(inputFilter, vars);
            vars.put("workflowdata", filteredInput);
        }
        evaluator.evaluate(expression, vars);

    }

    public static Object expression(ProcessContext context, String expression, String inputFilter, String outputFilter,
            String scope) {

        ExpressionEvaluator evaluator = (ExpressionEvaluator) ((WorkflowProcess) context.getProcessInstance()
                .getProcess())
                        .getDefaultContext(ExpressionEvaluator.EXPRESSION_EVALUATOR);
        Map<String, Object> vars = new HashMap<>();
        Map<String, Object> variables = new HashMap<>();
        vars.put("workflowdata", context.getVariable(JsonVariableScope.WORKFLOWDATA_KEY));
        if (context.getVariable("$CONST") != null) {
            variables.put("CONST", context.getVariable("$CONST"));
            vars.put("workflow_variables", variables);
        }

        if (inputFilter != null) {
            Object filteredInput = evaluator.evaluate(inputFilter, vars);
            vars.put("workflowdata", filteredInput);
        }
        Object content = evaluator.evaluate(expression, vars);

        if (outputFilter != null) {
            vars.put("workflowdata", content);
            content = evaluator.evaluate(outputFilter, vars);

        }
        JsonNode outcome;
        if (scope != null) {
            vars.put("workflowdata", context.getVariable(JsonVariableScope.WORKFLOWDATA_KEY));
            variables.put("v", content);
            vars.put("workflow_variables", variables);
            outcome = (JsonNode) evaluator.evaluate(scope + "=$v", vars);

        } else {
            outcome = (JsonNode) context.getVariable(JsonVariableScope.WORKFLOWDATA_KEY);
        }

        try {
            Object updated = mapper.readerForUpdating(context.getVariable(JsonVariableScope.WORKFLOWDATA_KEY))
                    .readValue(outcome);

            context.setVariable(JsonVariableScope.WORKFLOWDATA_KEY, updated);

            return content;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static JsonNode fromJson(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException("Error transforming json string to object", e);
        }
    }
}