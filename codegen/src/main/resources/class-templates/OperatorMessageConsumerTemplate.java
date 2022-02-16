package com.myspace.demo;

import java.util.Collection;
import java.util.Collections;
import java.util.Optional;

import javax.inject.Inject;
import javax.inject.Named;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.automatiko.engine.api.Application;
import io.automatiko.engine.api.Model;
import io.automatiko.engine.api.auth.IdentityProvider;
import io.automatiko.engine.api.auth.TrustedIdentityProvider;
import io.automatiko.engine.api.workflow.Process;
import io.automatiko.engine.api.workflow.ProcessInstance;
import io.automatiko.engine.api.workflow.ProcessInstanceReadMode;
import io.automatiko.engine.workflow.AbstractProcessInstance;
import io.automatiko.engine.workflow.Sig;
import io.automatiko.engine.workflow.process.instance.impl.WorkflowProcessInstanceImpl;
import io.javaoperatorsdk.operator.api.Context;
import io.javaoperatorsdk.operator.api.Controller;
import io.javaoperatorsdk.operator.api.DeleteControl;
import io.javaoperatorsdk.operator.api.ResourceController;
import io.javaoperatorsdk.operator.api.UpdateControl;
import io.javaoperatorsdk.operator.processing.event.EventSourceManager;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.internal.KubernetesDeserializer;

@SuppressWarnings({ "unchecked", "rawtypes" })
@Controller(namespaces=$ControllerParam$, name="$ProcessId$", generationAwareEventProcessing=$GenControllerParam$)
public class Controller implements ResourceController<$DataType$> {

    private static final Logger LOGGER = LoggerFactory.getLogger("MessageConsumer");

    Process<$Type$> process;

    Application application;
    
    
    public void init(EventSourceManager eventSourceManager) {
        KubernetesDeserializer.registerCustomKind(HasMetadata.getApiVersion($DataType$.class), $DataType$.class.getSimpleName(), $DataType$.class);
    }

    @Override
    public DeleteControl deleteResource($DataType$ resource, Context<$DataType$> context) {
        try {
            String trigger = "deleted";
            IdentityProvider.set(new TrustedIdentityProvider("System<messaging>"));
            io.automatiko.engine.services.uow.UnitOfWorkExecutor.executeInUnitOfWork(application.unitOfWorkManager(), () -> {
                String correlation = resource.getMetadata().getName();
                if (correlation != null) {
                    LOGGER.debug("Correlation ({}) is set, attempting to find if there is matching instance already active",
                            correlation);
                    Optional<? extends ProcessInstance> possiblyFound = (Optional<? extends ProcessInstance>) process.instances()
                            .findById(correlation);
                    
                    if (possiblyFound.isEmpty()) {
                        possiblyFound = (Optional<? extends ProcessInstance>) process.instances()
                                .findById(correlation, ProcessInstance.STATE_ERROR, ProcessInstanceReadMode.MUTABLE);
                    }
    
                    possiblyFound.ifPresent(pi -> {
                        ProcessInstance pInstance = (ProcessInstance) pi;
                        LOGGER.debug("Found process instance {} matching correlation {}, signaling deletion to allow cleanup",
                                pInstance.id(), correlation);
                        pInstance.send(Sig.of("Message-" + trigger, resource));
    
                        if (pInstance.status() == ProcessInstance.STATE_ACTIVE) {
                            LOGGER.debug("Instance is still active after signal, aborting...");
                            pInstance.abort();
                        }
                    });
                    return null;
                }
    
                return null;
            });
        } catch(Throwable t) {
            LOGGER.error("Encountered problems while deleting instance", t);
        }
        return DeleteControl.DEFAULT_DELETE;

    }

    @Override
    public synchronized UpdateControl<$DataType$> createOrUpdateResource($DataType$ resource, Context<$DataType$> context) {
        
        
        if (!acceptedPayload(resource)) {
            LOGGER.debug("Event has been rejected by the filter expression"); 
            
            return UpdateControl.noUpdate();
        }
        
        String trigger = "$Trigger$";
        IdentityProvider.set(new TrustedIdentityProvider("System<messaging>"));
        final $Type$ model = new $Type$(); 
        return io.automatiko.engine.services.uow.UnitOfWorkExecutor.executeInUnitOfWork(application.unitOfWorkManager(), () -> {
            try {
                String correlation = resource.getMetadata().getName();
                if (correlation != null) {
                    LOGGER.debug("Correlation ({}) is set, attempting to find if there is matching instance already active",
                            correlation);
    
                    Optional<? extends ProcessInstance> possiblyFound = (Optional<? extends ProcessInstance>) process.instances()
                            .findById(correlation);
                    if (possiblyFound.isPresent()) {
                        ProcessInstance pInstance = (ProcessInstance) possiblyFound.get();
                        LOGGER.debug(
                                "Found process instance {} matching correlation {}, signaling instead of starting new instance",
                                pInstance.id(), correlation);
                        pInstance.send(Sig.of("Message-updated", resource));
    
                        $DataType$ updated = ($DataType$) ((Model) pInstance
                                .variables()).toMap().get("resource");
                        
                        if (updated == null || Boolean.TRUE.equals(((WorkflowProcessInstanceImpl)((AbstractProcessInstance<?>) pInstance).processInstance()).getVariable("skipResourceUpdate"))) {
                            LOGGER.debug("Signalled and returned updated {} no need to updated custom resource", updated);
                            return UpdateControl.noUpdate();
                        }
                        LOGGER.debug("Signalled and returned updated {} that requires update of the custom resource", updated);
                        return UpdateControl.updateStatusSubResource(updated);
                    }
                }
                if (canStartInstance()) {
                    LOGGER.debug(
                            "Received message without reference id and no correlation is set/matched, staring new process instance with trigger '{}'",
                            trigger);                             
                    ProcessInstance<?> pi = process.createInstance(correlation, model);
                    pi.start(trigger, null, resource);
                    
                    $DataType$ updated = ($DataType$) ((Model) pi.variables()).toMap().get("resource");
                                        
                    if (updated == null || Boolean.TRUE.equals(((WorkflowProcessInstanceImpl)((AbstractProcessInstance<?>) pi).processInstance()).getVariable("skipResourceUpdate"))) {
                        LOGGER.debug("New instance started and not need to update custom resource");
                        return UpdateControl.noUpdate();
                    }
                    LOGGER.debug("New instance started and with the need to update custom resource");
                    return UpdateControl.updateStatusSubResource(updated);
                } else {
                    LOGGER.warn(
                            "Received message without reference id and no correlation is set/matched, for trigger not capable of starting new instance '{}'",
                            trigger);
                }
            } catch(Throwable t) {
                LOGGER.error("Encountered problems while creating/updating instance", t);
            }
            return UpdateControl.noUpdate();
        });

    }
    
    protected boolean acceptedPayload(Object eventData) {
        return true;
    }


}