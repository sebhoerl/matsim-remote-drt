package org.matsim.remote_drt;

import java.io.File;

import org.matsim.contrib.dvrp.run.AbstractDvrpModeModule;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.remote_drt.services.manager.ServiceManager;
import org.matsim.remote_drt.services.manager.ServiceModule;

public class RemoteDrtModeModule extends AbstractDvrpModeModule {
    static public final String REMOTE_PORT_PATH_PREFIX = "remote_port_";

    private final RemoteDrtModeParameters parameters;

    public RemoteDrtModeModule(RemoteDrtModeParameters parameters) {
        super(parameters.getMode());
        this.parameters = parameters;
    }

    @Override
    public void install() {
        install(new ServiceModule(getMode()));

        bindModal(RemoteDispatchingManager.class).toProvider(modalProvider(getter -> {
            ServiceManager serviceManager = getter.getModal(ServiceManager.class);

            OutputDirectoryHierarchy outputDirectoryHierarchy = getter.get(OutputDirectoryHierarchy.class);
            File portPath = new File(outputDirectoryHierarchy.getTempPath(), REMOTE_PORT_PATH_PREFIX + getMode());

            return new RemoteDispatchingManager(parameters.getPort(), portPath.toString(), parameters.getTimeout(), serviceManager);
        })).asEagerSingleton();

        addControlerListenerBinding().to(modalKey(RemoteDispatchingManager.class));
    }
}
