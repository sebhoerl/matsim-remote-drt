package org.matsim.remote_drt.example;

import java.util.Collections;

import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.algorithms.NetworkCleaner;
import org.matsim.core.network.algorithms.TransportModeNetworkFilter;
import org.matsim.core.network.io.MatsimNetworkReader;
import org.matsim.core.network.io.NetworkWriter;

public class SimpleNetwork { // TODO REMOVE
    static public void main(String[] args) {
        Network network = NetworkUtils.createNetwork();
        new MatsimNetworkReader(network).readFile("scenario/network.xml.gz");

        Network cleaned = NetworkUtils.createNetwork();
        new TransportModeNetworkFilter(network).filter(cleaned, Collections.singleton("car"));

        NetworkUtils.cleanNetwork(network, Collections.singleton("car"));

        for (Link link : cleaned.getLinks().values()) {
            link.getAttributes().clear();
        }

        new NetworkWriter(cleaned).write("paris.xml.zst");
    }
}
