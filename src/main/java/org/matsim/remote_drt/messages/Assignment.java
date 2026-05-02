package org.matsim.remote_drt.messages;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

public class Assignment implements Message {
    static public class Stop {
        public String link;

        public List<String> pickup = new LinkedList<>();
        public List<String> dropoff = new LinkedList<>();

        public List<String> route = null;

        public double earliestStartTime = Double.NEGATIVE_INFINITY;
        public double stopDuration;

        public String id = null;
    }

    public Map<String, List<Stop>> stops = new HashMap<>();
    public List<String> rejections = new LinkedList<>();

    public double waitFor = 0.0;
}
