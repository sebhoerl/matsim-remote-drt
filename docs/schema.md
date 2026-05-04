# Communication schema

This document covers the entire list of messages that can be exchanged by the dispatching server and the dispatcher:

- The simulation loop that manages initilizing and ending the whole simulation and individual iterations.
- The iteration loop that handles the exchange of simulator state and dispatcher instructions.
- The utility backend that allows the client to request useful information at any time.

Communication happens in a request-response scheme. Each time the client sends a message, the server will respond. There are never two messages send in a row neither by the server nor by the client.

## Simulation loop

The simulation loop starts with an `initialization` message from the client to the server and a `finalization` loop from the server to the client.

An iteration is performed either after the `start_iteration` message from the client or directly after `initialization` in case no iteration preparation phase is requested by the client. For the communication loop within an iteration, see the next section.

### initialization

Sent *client to server* to announce that the dispatching can start.

Fields:
- `prepareIterations` (*Boolean*, default *true*): Defines whether the client needs a preparational phase at the beginning of each iteration, for instance, to request additional information. 

Example:
```json
{ "@message": "initialization", "prepareIterations": true }
```

### error

Sent *server to client* whenever an error has occured.

Fields:
- `type` (*Enum["Format", "Sequence"]*): Gives the type of the error. *Format* indicates that the received message could not be serialized by the server. *Sequence* indicates that an unexpected type of message was sent by the client.
- `message` (*String*): A freetext description of the error with additional details.

Example:
```json
{ "@message": "error", "type": "Format", "message": "An error occured ..." }
```

### prepare_iteration

Sent *server to client* when the preparation phase of an iteration starts.

Fields:
- `iteration` (*Integer*): The running index of the iteration.

Example:
```json
{ "@message": "prepare_iteration", "iteration": 4 }
```

### start_iteration

Sent *client to server* in case an iteration phase had been started. This is for the client to indicate to the server that the iteration can now begin.

Fields:
- None

Example:
```json
{ "@message": "start_iteration" }
```

### finalization

Sent *server to client* to announce that all iterations have been performed and that the server will shut down.

Fields:
- None

Example:
```json
{ "@message": "finalization", "prepareIterations": true }
```

## Iteration loop

The main exchange messages during an iteration is the `state` sent by the server and the `assignment` sent in response by the client. There are several utility backends available that can be called during the iteration and in preapration, which are described in the following section.

### state

Sent *server to client* to give information of the current state of the system and to request assignment instructions.

The state of all **vehicles** is sent. Each vehicle has a *current* link and a *diversion* link. Both may not be the same. While the first gives the current location of the vehicle, it may be in the process of traversing this link or is just about to leave it. Dependent on the situation, the next link where the route can be altered may vary. The next link after which a diversion is possible (and which can thus be taken as the starting link of a newly calculated route) is the *diversion* link.

Vehicles have *stop sequences* (see `assignment`). When assigning a sequence to a vehicle, each stop can be given an identifier by the dispatcher or not. In case a stop has an identifier, the `ongoing` and `finished` fields per vehicle (see below) indicate what has happened with these stops (or not) since the last state update.

Vehicles have a `state` that indicates if they are currently in a `stop` (pickup and/or dropoff), `stay`ing idle or `drive`ing. Vehicles that are `inactive` cannot receive any instructions in the `assignment`.

Each request is only communicated to the dispatcher once when it is submitted (see below). Service level constraints like the latest pickup time depended on the simulator configuration (and can be ignored by the dispatcher).

Ongoing pickups and dropoffs cannot be revoked. This is why their state is transmitted in the `state` already when the process has started, and also when it has ended.

Requests that are not yet picked up may be auto-rejected by the simulator if the latest pickup time has passed. This happens if the simulator is started with `useAutomaticRejection`. In that case, the auto-rejected requests are contained in the `rejected` list.

Fields:
- `time` (*Real*): Current time of day (in seconds after start) in simulation
- `vehicles` (*List[...]*): A list of vehicle objects
  - `id` (*String*): Identifier of the vehicle
  - `currentLink` (*String*) Link identifier of the vehicle's current location
  - `currentExitTime` (*Real*) Time at which the vehicle is expected to leave the current link
  - `diversionLink` (*String*) Identifier of next link that the vehicle is able to change along the route
  - `diversionTime` (*Real*) Time at which the vehicle is next able to change the route
  - `state` (*Enum[stay, stop, drive, inactive]*): The current state of the vehicle
  - `ongoing` (*List[String]*): A list of identiifers of ongoing stops (see `assignment`)
  - `finished` (*List[String]*): A list of identiifers of ongoing stops (see `assignment`)
- `submitted` (*List[...]*): A list of newly submitted requests since the last state update
  - `id` (*String*): Identifier of the request
  - `originLink` (*String*): Identifier of the request's origin link
  - `destinationLink` (*String*): Identifier of the request's destination link
  - `earliestPickupTime` (*Real*): Earliest time when the request wants to be picked up
  - `latestPickupTime` (*Real*): Latest time when the request wants to be picked up
  - `latestArrivalTime` (*Real*): Latest time when the request wants to be dropped off
- `rejected` (*List[...]*): List of request IDs that have been rejected simulator-side
- `pickups` (*List[...]*): A list of ongoing or finished pickups
  - `request` (*String*): Identifier of the picked up request
  - `vehicle` (*String*): Identifier of the picking up vehicle
  - `ongoing` (*Boolean*): Whether the process is ongoing or is **finished**
- `dropoffs` (*List[...]*): A list of ongoing or finished dropoffs
  - `request` (*String*): Identifier of the dropped off request
  - `vehicle` (*String*): Identifier of the dropping up vehicle
  - `ongoing` (*Boolean*): Whether the process is ongoing or is **finished**

Example:
```json
{
    "@message": "state",
    "time": 7200.0,
    "pickups": [
        { "request": "req1", "vehicle": "veh1", "ongoing": true },
        { "request": "req2", "vehicle": "veh62", "ongoing": false }
    ],
    "dropoffs": [
        { "request": "req5", "vehicle": "veh10", "ongoing": false }
    ],
    "vehicles": [
        { 
            "id": "veh62",
            "currentLink": "512", 
            "curentExitTime": 7201.0, 
            "diversionLink": "513", 
            "diversionTime": 7211.0, 
            "state": "drive",
            "finished": ["pu2"]
        }
    ],
    "submitted": [
        { 
            "id": "req55",  
            "originLink": "5521",
            "destinationLink": "8573",
            "earliestPickupTime": 0.0,
            "latestPickupTime": 7300.0,
            "latestArrivalTime": 8000.0
        }
    ]
}
```

### assignment

Sent *client to server* to give instructions to the fleet vehicles and/or reject requests.

The dispatcher is supposed to send stop sequences (with, at minimum, a link) to the simulator. Stops can contain pickup and/or dropoff instructions. Note that the dispatcher can choose for which vehicles to send an *update* of the sequence. Vehicles that are not contained in the provided sequences will not receive an empty sequence. Instead, their **previously assigned sequence** will be kept unless an empty list is sent for them.

The dispatcher can send a *custom route* for each stop. These routes are sequences of link identifiers and must include both the originating link (previous stop or current vehicle *diversion*) and the end link (same as the stop's `link`).

Fields:
- `waitFor` (*Real*, default `0.0`): Instructs the simulator to wait until the given timestamp (in simulation time) before a new assignment is requested from the dispatcher.
- `rejections` (*List[String]*): Identifier list of requests that should be rejected.
- `stops` (*Map[String, ...]): Assignment of a stop sequence by vehicle identifier 
  - `link` (*String*): Link identifier of the stop (at minima, relocation of the vehicle)
  - `pickup` (*List[String]*): A list of request identifiers that should be picked up upon arrival
  - `dropoff` (*List[String]*): A list of request identifiers that should be dropped off upon arrival
  - `route` (*List[String]*, Optional): A custom route from the previous location of the vehicle to the indicated `link` as a sequence of link identifiers.
  - `id` (*String*, Optional): A custom identifier of the stop to track its progress (see `state`)
  - `earliestStartTime` (*Real*, default `-Inf`): Defines the earliest start time of the stop (which might incur a wait time at the link location).

## Utility backend

The following *request* messages by the client can be sent instead of sending an `assignment`. In that case, the server will respond with the requested information.

### network_request

Sent *client to server* to request the road network on which the fleet is simulated.

Fields:
- None

Example:
```json
{ "@message": "network_request" }
```

### network_response

Sent *server to client* as a response to `network_request`.

Fields:
- `nodes` (*Map[String, ...]*): Mapping each node identifier to coordinates
  - `x` (*Real*)
  - `y` (*Real*)
- `links` (*Map[String, ...]*): mapping each link identifier to link information
  - `fromId` (*String): node identifier of the link's start location
  - `toId` (*String): node identifier of the link's end location
  - `freespeed_km_h` (*Real*): nominal speed without congestion
  - `length_m` (*Real*): length of the link (between start/end nodes)

Example:
```json
{
    "@message": "network_response",
    "nodes": {
        "n1": { "x": 50.0, "y": 100.0 },
        "n513": { "x": 5510.0, "y": 106.5 },
    },
    "links": {
        "link1": { "toId": "n1", "toId": "n513", "length_m": 500.0, "freespeed_km_h": 30.0 },
        "link1_back": { "toId": "n513", "toId": "n1", "length_m": 500.0, "freespeed_km_h": 30.0 },        
    }
}
```

### fleet_request

Sent *client to server* to request the current state of the vehicle fleet. It is especially useful to obtain the start links of the vehicles in the iteration preparation phase. Note that the *current* link of a vehicle is sent with the `state`.

Fields:
- None

Example:
```json
{ "@message": "fleet_request" }
```

### fleet_response

Sent *server to client* as a response to `fleet_request`.

Fields:
- `vehicles` (*List[...]*): List of vehicle objects
  - `id` (*String*): Identifier of the vehicle
  - `startLink` (*String*): Identifier of the start link of the vehicle
  - `capacity` (*Integer*): Passenger capacity (seats) of the vehicle

Example:
```json
{
    "@message": "fleet_response",
    "vehicles": [
        { "id": "v1", "startLink": "l42", "capacity": 4 },
        { "id": "v2", "startLink": "l633", "capacity": 4 },
        { "id": "v3", "startLink": "q663f", "capacity": 8 },
    ]
}
```

### travel_time_request

Sent *client to server* to request current (potentially congested) travel times on a given list of links.

Fields:
- `links` (*List[String]*): A list of link identifiers.

Example:
```json
{ "@message": "travel_time_request", "links": ["l51", "L66"] }
```

### travel_time_response

Sent *server to client* as a response to `travel_time_request`.

Fields:
- `travelTimes` (*Map[String, Real]*): A map of link identifiers to current traversal times in seconds.

Example:
```json
{ "@message": "travel_time_response", "travelTimes": { "l51": 21.0, "L66": 55.0 } }
```

