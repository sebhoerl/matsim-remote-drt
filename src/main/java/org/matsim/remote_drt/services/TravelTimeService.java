package org.matsim.remote_drt.services;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.IdSet;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.router.util.TravelTime;
import org.matsim.remote_drt.messages.Message;
import org.matsim.remote_drt.messages.TravelTimeRequest;
import org.matsim.remote_drt.messages.TravelTimeResponse;
import org.matsim.remote_drt.services.manager.Service;

public class TravelTimeService implements Service {
    private final Network network;
    private final TravelTime travelTime;

    public TravelTimeService(Network network, TravelTime travelTime) {
        this.network = network;
        this.travelTime = travelTime;
    }

    @Override
    public Class<? extends Message> getMessageType() {
        return TravelTimeRequest.class;
    }

    @Override
    public Message process(Message request, double now) {
        TravelTimeRequest query = (TravelTimeRequest) request;
        IdSet<Link> links = new IdSet<>(Link.class);

        for (String rawLink : query.links) {
            links.add(Id.createLinkId(rawLink));
        }

        if (links.size() == 0) {
            links.addAll(network.getLinks().keySet());
        }

        TravelTimeResponse response = new TravelTimeResponse();

        for (Id<Link> linkId : links) {
            Link link = network.getLinks().get(linkId);
            double value = travelTime.getLinkTravelTime(link, now, null, null);
            response.travelTimes.put(linkId.toString(), value);
        }

        return response;
    }
}
