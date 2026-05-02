package org.matsim.remote_drt.messages;

import java.util.HashMap;
import java.util.Map;

public class NetworkResponse implements Message {
    static public class Node {
        public double x;
        public double y;
    }

    static public class Link {
        public String fromId;
        public String toId;

        public double freespeed_km_h;
        public double length_m;
    }

    public Map<String, Node> nodes = new HashMap<>();
    public Map<String, Link> links = new HashMap<>();
}
