package org.matsim.remote_drt.messages;

import java.util.LinkedList;
import java.util.List;

public class State implements Message {
    public double time;

    static public class Pickup {
        public String request;
        public String vehicle;
        public boolean ongoing;
    }

    static public class Dropoff {
        public String request;
        public String vehicle;
        public boolean ongoing;
    }

    public List<Pickup> pickups = new LinkedList<>();
    public List<Dropoff> dropoffs = new LinkedList<>();

    static public class Vehicle {
        public String id;

        public String currentLink;
        public double currentExitTime = Double.NEGATIVE_INFINITY;

        public String diversionLink = null;
        public double diversionTime = Double.NEGATIVE_INFINITY;

        public String state; // stay, stop, drive, inactive

        public List<String> ongoing = new LinkedList<>();
        public List<String> finished = new LinkedList<>();

        public List<String> onboard = new LinkedList<>();
    }

    public List<Vehicle> vehicles = new LinkedList<>();

    static public class Request {
        public String id;
        public String originLink;
        public String destinationLink;

        public double earliestPickupTime;
        public double latestPickupTime;
        public double latestArrivalTime; // TODO Rename dropoff

        public double pickupDuration;
        public double dropoffDuration;

        final public int size = 1;
    }

    public List<Request> submitted = new LinkedList<>();

    public List<String> rejected = new LinkedList<>();
}
