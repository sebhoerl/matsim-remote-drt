package org.matsim.remote_drt.example;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.contrib.dvrp.fleet.DvrpVehicle;
import org.matsim.contrib.dvrp.fleet.DvrpVehicleSpecification;
import org.matsim.contrib.dvrp.fleet.FleetSpecification;
import org.matsim.contrib.dvrp.fleet.FleetSpecificationImpl;
import org.matsim.contrib.dvrp.fleet.ImmutableDvrpVehicleSpecification;

public class FleetGenerator {
    private static final String VEHICLE_PREFIX = "vehicle:";

    private final Network network;
    private final Random random;

    public FleetGenerator(Network network, Random random) {
        this.network = network;
        this.random = random;
    }

    public FleetSpecification run(int vehicles, int seats) {
        FleetSpecificationImpl fleet = new FleetSpecificationImpl();

        List<Id<Link>> linkIds = new LinkedList<>(network.getLinks().keySet());
        Collections.sort(linkIds);

        for (int i = 0; i < vehicles; i++) {
            Id<Link> startLinkId = linkIds.get(random.nextInt(linkIds.size()));

            DvrpVehicleSpecification vehicle = ImmutableDvrpVehicleSpecification.newBuilder() //
                    .id(Id.create(VEHICLE_PREFIX + i, DvrpVehicle.class)) //
                    .serviceBeginTime(0.0) //
                    .serviceEndTime(24.0 * 3600.0) //
                    .capacity(seats) //
                    .startLinkId(startLinkId) //
                    .build();

            fleet.addVehicleSpecification(vehicle);
        }

        return fleet;
    }
}
