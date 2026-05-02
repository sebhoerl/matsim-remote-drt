package org.matsim.remote_drt.messages;

import java.util.LinkedList;
import java.util.List;

public class IterationMessage implements Message {
    public List<FleetResponse.Vehicle> vehicles = new LinkedList<>();
}
