
package io.automatik.engine.workflow;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import io.automatik.engine.api.workflow.MutableProcessInstances;
import io.automatik.engine.api.workflow.ProcessInstance;
import io.automatik.engine.api.workflow.ProcessInstanceDuplicatedException;
import io.automatik.engine.api.workflow.ProcessInstanceReadMode;

public class MapProcessInstances<T> implements MutableProcessInstances<T> {

    private final ConcurrentHashMap<String, ProcessInstance<T>> instances = new ConcurrentHashMap<>();

    public Integer size() {
        return instances.size();
    }

    @Override
    public Optional<ProcessInstance<T>> findById(String id, ProcessInstanceReadMode mode) {
        return Optional.ofNullable(instances.get(resolveId(id)));
    }

    @Override
    public Collection<ProcessInstance<T>> values(ProcessInstanceReadMode mode) {
        return instances.values();
    }

    @Override
    public void create(String id, ProcessInstance<T> instance) {
        if (isActive(instance)) {
            ProcessInstance<T> existing = instances.putIfAbsent(resolveId(id, instance), instance);
            if (existing != null) {
                throw new ProcessInstanceDuplicatedException(id);
            }
        }
    }

    @Override
    public void update(String id, ProcessInstance<T> instance) {
        String resolvedId = resolveId(id, instance);
        if (isActive(instance) && instances.containsKey(resolvedId)) {
            instances.put(resolvedId, instance);
        }
    }

    @Override
    public void remove(String id, ProcessInstance<T> instance) {
        instances.remove(resolveId(id, instance));
    }

    @Override
    public boolean exists(String id) {
        return instances.containsKey(id);
    }

    @Override
    public Collection<? extends ProcessInstance<T>> findByIdOrTag(ProcessInstanceReadMode mode, String... values) {
        List<ProcessInstance<T>> collected = new ArrayList<>();
        for (String idOrTag : values) {

            instances.values().stream().filter(pi -> pi.id().equals(resolveId(idOrTag)) || pi.tags().values().contains(idOrTag))
                    .forEach(pi -> collected.add(pi));
        }
        return collected;
    }

}
