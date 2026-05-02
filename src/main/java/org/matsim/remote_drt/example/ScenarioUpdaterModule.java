package org.matsim.remote_drt.example;

import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Population;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.contrib.dvrp.fleet.FleetSpecification;
import org.matsim.contrib.dvrp.run.AbstractDvrpModeModule;
import org.matsim.core.controler.PrepareForSim;

import com.google.inject.Singleton;

public class ScenarioUpdaterModule extends AbstractDvrpModeModule {
    private final DrtConfigGroup drtConfig; 

    public ScenarioUpdaterModule(DrtConfigGroup drtConfig) {
        super(drtConfig.getMode());
        this.drtConfig = drtConfig;
    }

    @Override
    public void install() {
        bindModal(ScenarioUpdater.class).toProvider(modalProvider(getter -> {
            PrepareForSim prepare = getter.get(PrepareForSim.class);
            Population population = getter.get(Population.class);
            Network network = getter.get(Network.class);

            return new ScenarioUpdater(prepare, population, network, drtConfig);
        })).in(Singleton.class);

        addControllerListenerBinding().to(ScenarioUpdater.class);

        bindModal(FleetSpecification.class).toProvider(modalProvider(getter -> {
            return getter.get(ScenarioUpdater.class).getFleetSpecification();
        }));
    }
}
