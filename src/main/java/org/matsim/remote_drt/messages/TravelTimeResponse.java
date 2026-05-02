package org.matsim.remote_drt.messages;

import java.util.HashMap;
import java.util.Map;

public class TravelTimeResponse implements Message {
    public Map<String, Double> travelTimes = new HashMap<>();
}
