package org.matsim.remote_drt.example.dynamic;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.util.Map;
import java.util.Objects;

import org.matsim.api.core.v01.Id;
import org.matsim.contrib.dvrp.fleet.DvrpVehicle;
import org.matsim.contrib.dvrp.fleet.DvrpVehicleSpecification;
import org.matsim.contrib.dvrp.fleet.FleetReader;
import org.matsim.contrib.dvrp.fleet.FleetSpecification;
import org.matsim.contrib.dvrp.fleet.FleetSpecificationImpl;
import org.matsim.contrib.dvrp.load.DvrpLoadType;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigGroup;

public class DynamicFleetSpecification implements FleetSpecification {
    private final String path;
    private final Config config;
    private final DvrpLoadType dvrpLoadType;

    private FleetSpecification active = null;

    public DynamicFleetSpecification(Config config, String path, DvrpLoadType dvrpLoadType) {
        this.config = config;
        this.path = path;
        this.dvrpLoadType = dvrpLoadType;

        this.updateIteration(config.controller().getFirstIteration());
    }

    public void updateIteration(int iteration) {
        FleetSpecificationImpl fleet = new FleetSpecificationImpl();

        try {
            String iterationPath = path.replace("__it__", String.valueOf(iteration));
            URL iterationURL = ConfigGroup.getInputFileURL(config.getContext(), iterationPath);
            new FleetReader(fleet, dvrpLoadType).readURL(iterationURL);

            this.active = fleet;
        } catch (UncheckedIOException e) {
            this.active = null;
        }
    }

    @Override
    public Map<Id<DvrpVehicle>, DvrpVehicleSpecification> getVehicleSpecifications() {
        return Objects.requireNonNull(active).getVehicleSpecifications();
    }

    @Override
    public void addVehicleSpecification(DvrpVehicleSpecification specification) {
        throw new IllegalStateException();
    }

    @Override
    public void replaceVehicleSpecification(DvrpVehicleSpecification specification) {
        throw new IllegalStateException();
    }

    @Override
    public void removeVehicleSpecification(Id<DvrpVehicle> vehicleId) {
        throw new IllegalStateException();
    }
}
