
package io.automatik.engine.codegen.tests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.automatik.engine.api.Application;
import io.automatik.engine.api.Model;
import io.automatik.engine.api.workflow.Process;
import io.automatik.engine.api.workflow.ProcessInstance;
import io.automatik.engine.api.workflow.WorkItem;
import io.automatik.engine.api.workflow.flexible.AdHocFragment;
import io.automatik.engine.codegen.AbstractCodegenTest;
import io.automatik.engine.workflow.Sig;
import io.automatik.engine.workflow.process.core.node.ActionNode;
import io.automatik.engine.workflow.process.core.node.HumanTaskNode;
import io.automatik.engine.workflow.process.core.node.MilestoneNode;
import io.automatik.engine.workflow.process.core.node.WorkItemNode;

class AdHocFragmentsTest extends AbstractCodegenTest {

	@Test
	void testAdHocFragments() throws Exception {
		Application app = generateCodeProcessesOnly("cases/AdHocFragments.bpmn");
		assertThat(app).isNotNull();

		Process<? extends Model> p = app.processes().processById("TestCase.AdHocFragments_1_0");
		ProcessInstance<?> processInstance = p.createInstance(p.createModel());
		Collection<AdHocFragment> adHocFragments = processInstance.adHocFragments();
		List<AdHocFragment> expected = new ArrayList<>();
		expected.add(
				new AdHocFragment.Builder(MilestoneNode.class).withName("AdHoc Milestone").withAutoStart(true).build());
		expected.add(new AdHocFragment.Builder(ActionNode.class).withName("AdHoc Script").withAutoStart(false).build());
		expected.add(new AdHocFragment.Builder(HumanTaskNode.class).withName("AdHoc User Task").withAutoStart(false)
				.build());
		expected.add(
				new AdHocFragment.Builder(WorkItemNode.class).withName("Service Task").withAutoStart(false).build());
		assertAdHocFragments(expected, adHocFragments);
	}

	@Test
	void testStartUserTask() throws Exception {
		String taskName = "AdHoc User Task";
		Application app = generateCodeProcessesOnly("cases/AdHocFragments.bpmn");
		assertThat(app).isNotNull();

		Process<? extends Model> p = app.processes().processById("TestCase.AdHocFragments_1_0");
		ProcessInstance<? extends Model> processInstance = p.createInstance(p.createModel());
		processInstance.start();

		Optional<WorkItem> workItem = processInstance.workItems().stream()
				.filter(wi -> wi.getParameters().get("NodeName").equals(taskName)).findFirst();
		assertThat(workItem).isNotPresent();

		processInstance.send(Sig.of(taskName, p.createModel()));

		assertEquals(ProcessInstance.STATE_ACTIVE, processInstance.status());
		workItem = processInstance.workItems().stream()
				.filter(wi -> wi.getParameters().get("NodeName").equals(taskName)).findFirst();
		assertThat(workItem).isPresent();
		assertThat(workItem.get().getId()).isNotBlank();
		assertThat(workItem.get().getName()).isNotBlank();
	}

	@Test
	void testStartFragments() throws Exception {
		Application app = generateCodeProcessesOnly("cases/AdHocFragments.bpmn");
		assertThat(app).isNotNull();

		Process<? extends Model> p = app.processes().processById("TestCase.AdHocFragments_1_0");
		ProcessInstance<? extends Model> processInstance = p.createInstance(p.createModel());
		processInstance.start();
		Map<String, Object> params = new HashMap<>();
		params.put("user", "Juan");
		processInstance.send(Sig.of("Service Task", params));

		Model result = processInstance.variables();
		assertEquals("Hello Juan 5!", result.toMap().get("var1"));

		assertEquals(ProcessInstance.STATE_ACTIVE, processInstance.status());
	}

	@Test
	void testProcessAutoStart() throws Exception {
		Application app = generateCodeProcessesOnly("cases/AdHocProcess.bpmn");
		assertThat(app).isNotNull();

		Process<? extends Model> p = app.processes().processById("AdHocProcess_1_0");
		Model model = p.createModel();
		Map<String, Object> params = new HashMap<>();
		params.put("var1", "Pablo");
		params.put("var2", "Luis");
		model.fromMap(params);
		ProcessInstance<? extends Model> processInstance = p.createInstance(model);

		processInstance.start();

		Model result = processInstance.variables();
		assertEquals("Hello Pablo! Script", result.toMap().get("var1"));
		assertEquals("Luis Script 2", result.toMap().get("var2"));

		assertEquals(ProcessInstance.STATE_ACTIVE, processInstance.status());
	}

	private static void assertAdHocFragments(Collection<AdHocFragment> expected, Collection<AdHocFragment> current) {
		if (expected == null) {
			assertThat(current).isNull();
		}
		assertThat(current).isNotNull();
		assertThat(current.size()).isEqualTo(expected.size());
		expected.forEach(e -> assertTrue(
				current.stream()
						.anyMatch(c -> c.getName().equals(e.getName()) && c.getType().equals(e.getType())
								&& c.isAutoStart() == e.isAutoStart()),
				"Expected: " + e.toString() + ", Got: " + current.toString()));
	}

}
