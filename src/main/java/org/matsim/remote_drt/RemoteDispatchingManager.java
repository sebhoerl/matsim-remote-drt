package org.matsim.remote_drt;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.core.controler.events.IterationStartsEvent;
import org.matsim.core.controler.events.ShutdownEvent;
import org.matsim.core.controler.events.StartupEvent;
import org.matsim.core.controler.listener.IterationStartsListener;
import org.matsim.core.controler.listener.ShutdownListener;
import org.matsim.core.controler.listener.StartupListener;
import org.matsim.core.utils.io.IOUtils;
import org.matsim.remote_drt.messages.Assignment;
import org.matsim.remote_drt.messages.ErrorMessage;
import org.matsim.remote_drt.messages.Finalization;
import org.matsim.remote_drt.messages.Initialization;
import org.matsim.remote_drt.messages.Message;
import org.matsim.remote_drt.messages.PrepareIteration;
import org.matsim.remote_drt.messages.StartIterationRequest;
import org.matsim.remote_drt.messages.State;
import org.matsim.remote_drt.services.manager.ServiceManager;
import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;

import com.fasterxml.jackson.databind.ObjectMapper;

public class RemoteDispatchingManager
        implements StartupListener, ShutdownListener, IterationStartsListener {
    private final static ObjectMapper mapper = new ObjectMapper();
    private final static Logger logger = LogManager.getLogger(RemoteDispatchingManager.class);

    private final int port;
    private final String portPath;

    private final double timeout;

    private ZContext context;
    private ZMQ.Socket socket;

    private final ServiceManager serviceManager;

    public RemoteDispatchingManager(int port, String portPath, double timeout, ServiceManager serviceManager) {
        this.port = port;
        this.portPath = portPath;
        this.serviceManager = serviceManager;
        this.timeout = timeout;
    }

    private void writePort(int activePort) {
        try {
            BufferedWriter writer = IOUtils.getBufferedWriter(portPath);
            writer.write(String.valueOf(activePort));
            writer.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void initializeAtStartup() {
        logger.info("Initializing socket ...");

        context = new ZContext();
        socket = context.createSocket(SocketType.REP);

        int activePort = port;
        if (port == 0) {
            activePort = socket.bindToRandomPort("tcp://*");
        } else {
            socket.bind("tcp://*:" + port);
        }

        if (timeout > 0.0) {
            socket.setReceiveTimeOut((int) (timeout * 1e3));
        }

        writePort(activePort);
        logger.info("  Port: " + activePort);
    }

    private void finalizeAtShutdown() {
        try {
            logger.info("Finalizing ...");
            socket.send(mapper.writeValueAsBytes(new Finalization()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        if (context != null) {
            context.close();
        }
    }

    private Message read() {
        try {
            byte[] message = socket.recv();

            if (message == null) {
                throw new IOException("Received 'null' from algorithm. Communication seems to have ended unexpectedly.");
            }

            return mapper.readValue(message, Message.class);
        } catch (IOException e) {
            ErrorMessage message = new ErrorMessage();
            message.type = ErrorMessage.Type.Format;
            message.message = e.getMessage();
            send(message);

            throw new UncheckedIOException(e);
        }
    }

    private void send(Message message) {
        try {
            socket.send(mapper.writeValueAsBytes(message));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private <T extends Message> T checkMessage(Message message, Class<T> messageType) {
        if (!messageType.isInstance(message)) {
            sendError("Expected message of type: " + messageType, ErrorMessage.Type.Sequence);
            throw new IllegalStateException("Expected message of type " + messageType);
        }

        return messageType.cast(message);
    }

    private void sendError(String errorMessage, ErrorMessage.Type type) {
        ErrorMessage message = new ErrorMessage();
        message.type = type;
        message.message = errorMessage;
        send(message);
    }

    private Initialization initializationMessage = null;

    private void waitForRemoteInitialization() {
        logger.info("Waiting for dispatcher ...");

        Message input = read();
        initializationMessage = checkMessage(input, Initialization.class);

        logger.info("Dispatcher has initialized the connection.");
    }

    private void startIteration(int iteration) {
        if (initializationMessage == null) {
            // we have not yet initialized the connection
            waitForRemoteInitialization();
        }

        if (initializationMessage.prepareIterations) {
            PrepareIteration prepareIteration = new PrepareIteration();
            prepareIteration.iteration = iteration;
            send(prepareIteration);

            // processing service messages until non-service message arrives
            Message input = processServiceMessages(0.0);
            checkMessage(input, StartIterationRequest.class);
        }
    }

    public Assignment performAssignment(Message message) {
        // send the current system state
        send(message);

        double time = 0.0;
        if (message instanceof State) {
            time = ((State) message).time;
        }

        // wait for next non-service message
        Message input = processServiceMessages(time);

        // should be the assignment
        Assignment assignment = checkMessage(input, Assignment.class);

        return assignment;
    }

    private Message processServiceMessages(double now) {
        Message input = null;
        Message output = null;

        do {
            input = read();
            output = serviceManager.process(input, now);

            if (output != null) {
                send(output);
            }
        } while (output != null);

        return input;
    }

    @Override
    public void notifyStartup(StartupEvent event) {
        initializeAtStartup();
    }

    @Override
    public void notifyShutdown(ShutdownEvent event) {
        finalizeAtShutdown();
    }

    @Override
    public void notifyIterationStarts(IterationStartsEvent event) {
        startIteration(event.getIteration());
    }
}
