package org.matsim.remote_drt.services;

import java.util.List;

import org.matsim.contrib.dvrp.fleet.DvrpVehicleSpecification;
import org.matsim.contrib.dvrp.fleet.FleetSpecification;
import org.matsim.remote_drt.messages.FleetRequest;
import org.matsim.remote_drt.messages.FleetResponse;
import org.matsim.remote_drt.messages.Message;
import org.matsim.remote_drt.services.manager.Service;

import com.google.common.base.Preconditions;

public class FleetService implements Service {
    private final FleetSpecification fleet;

    public FleetService(FleetSpecification fleet) {
        this.fleet = fleet;
    }

    @Override
    public Class<? extends Message> getMessageType() {
        return FleetRequest.class;
    }

    @Override
    public Message process(Message request, double now) {
        Preconditions.checkArgument(request instanceof FleetRequest);

        FleetResponse response = new FleetResponse();
        process(fleet, response.vehicles);
        return response;
    }

    static public void process(FleetSpecification fleet, List<FleetResponse.Vehicle> vehicles) {
        for (DvrpVehicleSpecification vehicle : fleet.getVehicleSpecifications().values()) {
            FleetResponse.Vehicle initialVehicle = new FleetResponse.Vehicle();
            vehicles.add(initialVehicle);

            initialVehicle.id = vehicle.getId().toString();
            initialVehicle.capacity = (int) vehicle.getCapacity().getElement(0);
            initialVehicle.startLink = vehicle.getStartLinkId().toString();
        }
    }
}
