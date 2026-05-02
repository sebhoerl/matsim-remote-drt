package org.matsim.remote_drt.messages;

import java.util.LinkedList;
import java.util.List;

public class FleetResponse implements Message {
    public List<Vehicle> vehicles = new LinkedList<>();

    static public class Vehicle {
        public String id;
        public String startLink;
        public int capacity;
    }
}
