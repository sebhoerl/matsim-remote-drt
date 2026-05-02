package org.matsim.remote_drt.example;

import java.util.Objects;
import java.util.Random;

import org.matsim.api.core.v01.IdSet;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.contrib.dvrp.fleet.FleetReader;
import org.matsim.contrib.dvrp.fleet.FleetSpecification;
import org.matsim.contrib.dvrp.fleet.FleetSpecificationImpl;
import org.matsim.contrib.dvrp.load.IntegerLoad;
import org.matsim.contrib.dvrp.load.IntegerLoadType;
import org.matsim.core.controler.PrepareForSim;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.controler.events.StartupEvent;
import org.matsim.core.controler.listener.IterationEndsListener;
import org.matsim.core.controler.listener.StartupListener;

public class ScenarioUpdater implements StartupListener, IterationEndsListener {
    private final Random random = new Random(0);

    private final PrepareForSim prepare;
    private final Population population;
    private final Network network;

    private final double minimumDistance;
    private final int requests;
    private final int seats;
    private final int vehicles;

    private FleetSpecification fleetSpecification;

    public ScenarioUpdater(PrepareForSim prepare, Population population, Network network, DrtConfigGroup drtConfig) {
        this.prepare = prepare;
        this.population = population;
        this.network = network;

        minimumDistance = Objects.requireNonNull(
                (Double) population.getAttributes().getAttribute(DemandGenerator.GENERATOR_DISTANCE_ATTRIBUTE),
                "The ScenarioUpdater only works with a population generated from this repository.");

        requests = Objects.requireNonNull(
                (Integer) population.getAttributes().getAttribute(DemandGenerator.GENERATOR_REQUESTS_ATTRIBUTE),
                "The ScenarioUpdater only works with a population generated from this repository.");

        FleetSpecificationImpl initialSpecification = new FleetSpecificationImpl();
        new FleetReader(initialSpecification, new IntegerLoadType("passengers")).readFile(drtConfig.getVehiclesFile());
        vehicles = initialSpecification.getVehicleSpecifications().size();
        seats = ((IntegerLoad) initialSpecification.getVehicleSpecifications().values().iterator().next().getCapacity())
                .getValue();
    }

    private void updateDemand() {
        cleanPopulation();
        new DemandGenerator(network, random, minimumDistance).run(population, requests);
        prepare.run();

        fleetSpecification = new FleetGenerator(network, random).run(vehicles, seats);
    }

    private void cleanPopulation() {
        IdSet<Person> all = new IdSet<>(Person.class);
        all.addAll(population.getPersons().keySet());
        all.forEach(population::removePerson);
    }

    public FleetSpecification getFleetSpecification() {
        return fleetSpecification;
    }

    @Override
    public void notifyIterationEnds(IterationEndsEvent event) {
        updateDemand();
    }

    @Override
    public void notifyStartup(StartupEvent event) {
        updateDemand();
    }
}
