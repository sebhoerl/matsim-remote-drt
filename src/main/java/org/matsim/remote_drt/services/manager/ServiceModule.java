package org.matsim.remote_drt.services.manager;

import org.matsim.api.core.v01.network.Network;
import org.matsim.contrib.dvrp.fleet.FleetSpecification;
import org.matsim.contrib.dvrp.run.AbstractDvrpModeModule;
import org.matsim.contrib.dvrp.run.AbstractDvrpModeQSimModule;
import org.matsim.core.mobsim.framework.events.MobsimBeforeCleanupEvent;
import org.matsim.core.mobsim.framework.events.MobsimInitializedEvent;
import org.matsim.core.mobsim.framework.listeners.MobsimBeforeCleanupListener;
import org.matsim.core.mobsim.framework.listeners.MobsimInitializedListener;
import org.matsim.core.router.util.TravelTime;
import org.matsim.remote_drt.services.FleetService;
import org.matsim.remote_drt.services.NetworkService;
import org.matsim.remote_drt.services.TravelTimeService;

import com.google.inject.Singleton;

public class ServiceModule extends AbstractDvrpModeModule {
    private final String mode;

    public ServiceModule(String mode) {
        super(mode);
        this.mode = mode;
    }

    @Override
    public void install() {
        bindModal(TravelTimeService.class).toProvider(modalProvider(getter -> {
            Network network = getter.getModal(Network.class);
            TravelTime travelTime = getter.getModal(TravelTime.class);
            return new TravelTimeService(network, travelTime);
        }));

        bindModal(NetworkService.class).toProvider(modalProvider(getter -> {
            Network network = getter.getModal(Network.class);
            return new NetworkService(network);
        }));

        bindModal(FleetService.class).toProvider(modalProvider(getter -> {
            FleetSpecification fleet = getter.getModal(FleetSpecification.class);
            return new FleetService(fleet);
        }));

        bindModal(ServiceManager.class).toProvider(modalProvider(getter -> {
            ServiceManager manager = new ServiceManager();

            manager.addService(getter.getModal(TravelTimeService.class));
            manager.addService(getter.getModal(NetworkService.class));
            manager.addService(getter.getModal(FleetService.class));

            return manager;
        })).in(Singleton.class);

        installQSimModule(new AbstractDvrpModeQSimModule(mode) {
            @Override
            protected void configureQSim() {
                bindModal(IterationServiceManager.class).toProvider(modalProvider(getter -> {
                    IterationServiceManager manager = new IterationServiceManager();

                    // manager.addService(getter.getModal(FleetService.class));

                    return manager;
                }));

                addModalComponent(ServiceUpdater.class, modalProvider(getter -> {
                    ServiceManager mainManager = getter.getModal(ServiceManager.class);
                    IterationServiceManager qsimManager = getter.getModal(IterationServiceManager.class);
                    return new ServiceUpdater(mainManager, qsimManager);
                }));
            }
        });
    }

    static private class ServiceUpdater implements MobsimInitializedListener, MobsimBeforeCleanupListener {
        private final ServiceManager mainManager;
        private final IterationServiceManager qsimManager;

        ServiceUpdater(ServiceManager mainManager, IterationServiceManager qsimManager) {
            this.mainManager = mainManager;
            this.qsimManager = qsimManager;
        }

        @Override
        public void notifyMobsimInitialized(@SuppressWarnings("rawtypes") MobsimInitializedEvent e) {
            mainManager.setIterationManager(qsimManager);
        }

        @Override
        public void notifyMobsimBeforeCleanup(@SuppressWarnings("rawtypes") MobsimBeforeCleanupEvent e) {
            mainManager.setIterationManager(null);
        }
    }
}
