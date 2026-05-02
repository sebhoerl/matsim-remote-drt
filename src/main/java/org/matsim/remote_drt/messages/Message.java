package org.matsim.remote_drt.messages;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonSubTypes.Type;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, //
        include = JsonTypeInfo.As.PROPERTY, //
        property = "@message")
@JsonSubTypes({ //
                // simulation management
        @Type(value = Initialization.class, name = "initialization"), //
        @Type(value = Finalization.class, name = "finalization"), //
        @Type(value = ErrorMessage.class, name = "error"), //

        // iteration management
        @Type(value = PrepareIteration.class, name = "prepare_iteration"), //
        @Type(value = StartIterationRequest.class, name = "start_iteration"), //

        // time step mangement
        @Type(value = Assignment.class, name = "assignment"), //
        @Type(value = State.class, name = "state"), //

        // services
        @Type(value = TravelTimeRequest.class, name = "travel_time_request"), //
        @Type(value = TravelTimeResponse.class, name = "travel_time_response"), //

        @Type(value = NetworkRequest.class, name = "network_request"), //
        @Type(value = NetworkResponse.class, name = "network_response"), //

        @Type(value = FleetRequest.class, name = "fleet_request"), //
        @Type(value = FleetResponse.class, name = "fleet_response"), //
})
public interface Message {
}
