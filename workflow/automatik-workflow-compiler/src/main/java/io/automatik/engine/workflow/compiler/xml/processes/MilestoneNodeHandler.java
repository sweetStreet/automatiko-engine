
package io.automatik.engine.workflow.compiler.xml.processes;

import org.w3c.dom.Element;
import org.xml.sax.SAXException;

import io.automatik.engine.workflow.compiler.xml.ExtensibleXmlParser;
import io.automatik.engine.workflow.compiler.xml.XmlDumper;
import io.automatik.engine.workflow.process.core.Node;
import io.automatik.engine.workflow.process.core.node.MilestoneNode;

public class MilestoneNodeHandler extends AbstractNodeHandler {

    protected Node createNode() {
        return new MilestoneNode();
    }

    @SuppressWarnings("unchecked")
    public Class generateNodeFor() {
        return MilestoneNode.class;
    }

    public void handleNode(final Node node, final Element element, final String uri, final String localName,
            final ExtensibleXmlParser parser) throws SAXException {
        super.handleNode(node, element, uri, localName, parser);
        MilestoneNode milestoneNode = (MilestoneNode) node;
        for (String eventType : milestoneNode.getActionTypes()) {
            handleAction(milestoneNode, element, eventType);
        }
    }

    public void writeNode(Node node, StringBuilder xmlDump, boolean includeMeta) {
        MilestoneNode milestoneNode = (MilestoneNode) node;
        writeNode("milestone", milestoneNode, xmlDump, includeMeta);
        String constraint = milestoneNode.getConditionExpression();
        if (constraint != null || milestoneNode.getTimers() != null || milestoneNode.containsActions()) {
            xmlDump.append(">\n");
            if (includeMeta) {
                writeMetaData(milestoneNode, xmlDump);
            }
            if (constraint != null) {
                xmlDump.append("      <constraint type=\"rule\" dialect=\"mvel\" >"
                        + XmlDumper.replaceIllegalChars(constraint.trim()) + "</constraint>" + EOL);
            }
            for (String eventType : milestoneNode.getActionTypes()) {
                writeActions(eventType, milestoneNode.getActions(eventType), xmlDump);
            }
            writeTimers(milestoneNode.getTimers(), xmlDump);
            endNode("milestone", xmlDump);
        } else {
            endNode(xmlDump);
        }
    }

}
