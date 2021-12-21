package com.myspace.demo;


import java.util.Optional;
import java.util.TimeZone;

import io.automatiko.engine.api.runtime.process.ProcessInstance;
import io.automatiko.engine.workflow.process.instance.WorkflowProcessInstance;
import io.automatiko.engine.api.event.DataEvent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.util.StdDateFormat;

import org.eclipse.microprofile.reactive.messaging.Message;

@SuppressWarnings({ "rawtypes", "unchecked" })
public class MessageProducer {
    
    org.eclipse.microprofile.reactive.messaging.Emitter<String> emitter;
    
    Optional<Boolean> useCloudEvents = Optional.of(true);
    
    javax.enterprise.inject.Instance<io.automatiko.engine.api.io.OutputConverter<$Type$, String>> converter;    
    
    @javax.inject.Inject
    ObjectMapper json;
    
    @javax.inject.Inject
    io.automatiko.engine.service.metrics.ProcessMessagingMetrics metrics;

    public void configure() {
		
    }
    
	public void produce(ProcessInstance pi, $Type$ eventData) {
	    metrics.messageProduced(CONNECTOR, MESSAGE, pi.getProcess());
	    Message<String> msg = Message.of(this.marshall(pi, eventData));
	    
	    io.smallrye.reactive.messaging.amqp.OutgoingAmqpMetadata metadata = null;
	    if (converter != null && !converter.isUnsatisfied()) {                    
            
            metadata = converter.get().metadata(pi, io.smallrye.reactive.messaging.amqp.OutgoingAmqpMetadata.class);
        } 
        if (metadata == null) {
	    
    	    String addressName = address(pi);
    	    if (addressName != null) {
    	        metadata = io.smallrye.reactive.messaging.amqp.OutgoingAmqpMetadata.builder()
    	                .withAddress(addressName)
    	                .build();
    	        msg = msg.addMetadata(metadata);
    	    }
	    } else {
	        msg = msg.addMetadata(metadata);
	    }
	    emitter.send(msg);
    }
	    
	private String marshall(ProcessInstance pi, $Type$ eventData) {
	    try {
	        Object payload = eventData;
	        	        
	        if (useCloudEvents.orElse(true)) {
	            
        	    $DataEventType$ event = new $DataEventType$("",
        	                                                    eventData,
        	                                                    pi.getId(),
        	                                                    pi.getParentProcessInstanceId(),
        	                                                    pi.getRootProcessInstanceId(),
        	                                                    pi.getProcessId(),
        	                                                    pi.getRootProcessId(),
        	                                                    String.valueOf(pi.getState()));
        	    if (pi.getReferenceId() != null && !pi.getReferenceId().isEmpty()) {
        	        event.setAutomatikReferenceId(pi.getReferenceId());
        	    }
        	    return json.writeValueAsString(event);
            } else {
                
                if (converter != null && !converter.isUnsatisfied()) {                    
                                
                    return converter.get().convert(eventData);
                } else {
                    return json.writeValueAsString(payload);
                }
            }
	    } catch (Exception e) {
	        throw new RuntimeException(e);
	    }
	}
	
	protected String address(ProcessInstance pi) {
	    return null;
	}
}