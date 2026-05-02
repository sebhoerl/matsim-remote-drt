package org.matsim.remote_drt.messages;

import java.util.LinkedList;
import java.util.List;

public class TravelTimeRequest implements Message {
    public List<String> links = new LinkedList<>();
}
