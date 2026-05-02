package org.matsim.remote_drt.services.manager;

import java.util.HashMap;
import java.util.Map;

import org.matsim.remote_drt.messages.Message;

public class ServiceManager {
    private final Map<Class<? extends Message>, Service> services = new HashMap<>();
    private IterationServiceManager iterationManager;

    public void addService(Service service) {
        services.put(service.getMessageType(), service);
    }

    public Message process(Message message, double now) {
        Class<? extends Message> messageType = message.getClass();

        if (iterationManager != null) {
            Message output = iterationManager.process(message, now);

            if (output != null) {
                return output;
            }
        }

        Service service = services.get(messageType);
        return service == null ? null : service.process(message, now);
    }

    public void setIterationManager(IterationServiceManager iterationManager) {
        this.iterationManager = iterationManager;
    }
}
