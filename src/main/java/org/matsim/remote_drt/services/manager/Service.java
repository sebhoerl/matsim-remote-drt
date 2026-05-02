package org.matsim.remote_drt.services.manager;

import org.matsim.remote_drt.messages.Message;

public interface Service {
    Class<? extends Message> getMessageType();
    Message process(Message request, double now);
}
