package org.matsim.remote_drt;

import org.matsim.api.core.v01.network.Network;
import org.matsim.contrib.drt.optimizer.DrtOptimizer;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.contrib.drt.schedule.DrtTaskFactory;
import org.matsim.contrib.dvrp.fleet.Fleet;
import org.matsim.contrib.dvrp.run.AbstractDvrpModeQSimModule;
import org.matsim.contrib.dvrp.schedule.ScheduleTimingUpdater;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.router.costcalculators.OnlyTimeDependentTravelDisutility;
import org.matsim.core.router.speedy.SpeedyALTFactory;
import org.matsim.core.router.util.LeastCostPathCalculator;
import org.matsim.core.router.util.TravelTime;
import org.matsim.remote_drt.optimizer.RemoteDispatchingOptimizer;

import com.google.inject.Singleton;

public class RemoteDrtQSimModeModule extends AbstractDvrpModeQSimModule {
    private final DrtConfigGroup drtConfig;
    private final RemoteDrtModeParameters parameters;

    public RemoteDrtQSimModeModule(DrtConfigGroup drtConfig, RemoteDrtModeParameters parameters) {
        super(drtConfig.getMode());
        this.drtConfig = drtConfig;
        this.parameters = parameters;
    }

    @Override
    protected void configureQSim() {
        bindModal(RemoteDispatchingOptimizer.class).toProvider(modalProvider(getter -> {
            Network network = getter.getModal(Network.class);
            TravelTime travelTime = getter.getModal(TravelTime.class);

            LeastCostPathCalculator router = new SpeedyALTFactory().createPathCalculator(network,
                    new OnlyTimeDependentTravelDisutility(travelTime), travelTime);

            return new RemoteDispatchingOptimizer(
                    getter.getModal(RemoteDispatchingManager.class), //
                    getter.getModal(ScheduleTimingUpdater.class), //
                    getter.getModal(Fleet.class), //
                    network, //
                    getter.getModal(DrtTaskFactory.class), //
                    travelTime, //
                    router, //
                    getter.get(EventsManager.class), //
                    getMode(), drtConfig.getStopDuration(), //
                    parameters.getUseAutomaticRejection());
        })).in(Singleton.class);

        addMobsimScopeEventHandlerBinding().to(modalKey(RemoteDispatchingOptimizer.class));
        addModalComponent(DrtOptimizer.class, modalKey(RemoteDispatchingOptimizer.class));
    }
}
