package org.matsim.remote_drt.example.dynamic;

import org.matsim.api.core.v01.Scenario;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.contrib.dvrp.fleet.FleetSpecification;
import org.matsim.contrib.dvrp.load.DvrpLoadType;
import org.matsim.contrib.dvrp.run.AbstractDvrpModeModule;
import org.matsim.core.config.Config;
import org.matsim.core.controler.PrepareForSim;
import org.matsim.core.controler.events.IterationStartsEvent;
import org.matsim.core.controler.listener.IterationStartsListener;

import com.google.inject.Singleton;

public class DynamicScenarioModule extends AbstractDvrpModeModule {
    private final Config config;
    private final DrtConfigGroup drtConfig;

    private final String demandPath;
    private final String fleetPath;

    public DynamicScenarioModule(Config config, DrtConfigGroup drtConfig, String demandPath, String fleetPath) {
        super(drtConfig.getMode());

        this.config = config;
        this.drtConfig = drtConfig;
        this.demandPath = demandPath;
        this.fleetPath = fleetPath;
    }

    @Override
    public void install() {
        if (demandPath.contains("__it__")) {
            bind(DynamicDemand.class).toProvider(modalProvider(getter -> {
                Scenario scenario = getter.get(Scenario.class);
                PrepareForSim prepare = getter.get(PrepareForSim.class);

                return new DynamicDemand(config, demandPath, scenario, prepare);
            })).in(Singleton.class);

            addControllerListenerBinding().toProvider(modalProvider(getter -> {
                DynamicDemand demand = getter.get(DynamicDemand.class);

                return new IterationStartsListener() {
                    @Override
                    public void notifyIterationStarts(IterationStartsEvent event) {
                        demand.updateDemand(event.getIteration());
                    }
                };
            })).in(Singleton.class);
        }

        if (fleetPath.contains("__it__")) {
            bindModal(DynamicFleetSpecification.class).toProvider(modalProvider(getter -> {
                DvrpLoadType dvrpLoadType = getter.getModal(DvrpLoadType.class);
                return new DynamicFleetSpecification(config, fleetPath, dvrpLoadType);
            })).in(Singleton.class);

            addControllerListenerBinding().toProvider(modalProvider(getter -> {
                DynamicFleetSpecification fleet = getter.getModal(DynamicFleetSpecification.class);

                return new IterationStartsListener() {
                    @Override
                    public void notifyIterationStarts(IterationStartsEvent event) {
                        fleet.updateIteration(event.getIteration());
                    }
                };
            })).in(Singleton.class);

            bindModal(FleetSpecification.class).to(modalKey(DynamicFleetSpecification.class)).in(Singleton.class);
        }
    }
}
