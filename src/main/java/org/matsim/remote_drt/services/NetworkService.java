package org.matsim.remote_drt.services;

import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.remote_drt.messages.Message;
import org.matsim.remote_drt.messages.NetworkRequest;
import org.matsim.remote_drt.messages.NetworkResponse;
import org.matsim.remote_drt.services.manager.Service;

import com.google.common.base.Preconditions;

public class NetworkService implements Service {
    private final Network network;

    public NetworkService(Network network) {
        this.network = network;
    }

    private NetworkResponse.Node transform(Node node) {
        NetworkResponse.Node target = new NetworkResponse.Node();
        target.x = node.getCoord().getX();
        target.y = node.getCoord().getY();
        return target;
    }

    private NetworkResponse.Link transform(Link link) {
        NetworkResponse.Link target = new NetworkResponse.Link();
        target.fromId = link.getFromNode().getId().toString();
        target.toId = link.getToNode().getId().toString();
        target.length_m = link.getLength();
        target.freespeed_km_h = link.getFreespeed() * 3.6;
        return target;
    }

    @Override
    public Class<? extends Message> getMessageType() {
        return NetworkRequest.class;
    }

    @Override
    public Message process(Message request, double time) {
        Preconditions.checkArgument(request instanceof NetworkRequest);
        NetworkResponse response = new NetworkResponse();

        for (Node node : network.getNodes().values()) {
            response.nodes.put(node.getId().toString(), transform(node));
        }

        for (Link link : network.getLinks().values()) {
            response.links.put(link.getId().toString(), transform(link));
        }

        return response;
    }
}
