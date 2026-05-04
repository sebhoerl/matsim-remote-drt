import zmq, json, sys

"""
# Random assignment dispatcher

This is a random dispatcher. In every decision step, it iterates through all the 
idle vehicles and the pending requests and assigns them randomly until either all 
vehicles or requests are processed. Assignments are binding, which means that they 
are kept until the request has been picked up and dropped off.

The whole implementation is voluntarily kept very low-level. See the other examples
for a more structured approach.
"""

# First, we need to open the ZeroMQ context in which communication is handled.
context = zmq.Context()

# We obtain the connection port from the command line.
port = int(sys.argv[1])

# Now we can open the connection to the simulation.
socket = context.socket(zmq.REQ)
socket.connect("tcp://localhost:{}".format(port))

# We (this dispatcher) start the communication by sending the initialization message.
print("initializing the connection ...")

# All messages are JSON-encoded and contain the @message field which describes the type of the message.
socket.send(json.dumps({
    "@message": "initialization"
}).encode())

# We track the pending requests that have not been assigned by our dispatcher
pending_requests = []

# We prepare some statistics for logging
statistics = { "waiting": 0, "rejected": 0, "onboard": 0, "finished": 0, "idle_vehicles": 0 }

# Now start looping over the time steps
while True:
    # We wait for a message from the simulation. Note that we are in a strict request-response mode. Neither side (dispatcher or simulation) ever sends two messages in a row.
    message = json.loads(socket.recv())

    # Again, each message describes its type through the @message field.
    if message["@message"] == "finalization":
        # The simulation is over, let's stop our loop.
        break

    elif message["@message"] == "prepare_iteration":
        assert message["iteration"] == 0 # we only allow running one iteration

        # In an advanced case, we could set up more obtain or obtain additional information.
        # Here we just tell the simulation to start the iteration.
        socket.send(json.dumps({ "@message": "start_iteration" }).encode())

    elif message["@message"] == "state":
        # Here we handle a state, the most important object coming from the simulation

        # First, we register all new incoming requests
        for request in message["submitted"]:
            statistics["waiting"] += 1
            pending_requests.append(request)

            # Each request has an identifier, an origin and destination link, and other information
            # { "id": "request:5", "originLink": "...", "destinationLink": "..." }

        # Second, we go through the pick-ups during the last time step and print them, just to see what's going on
        for pickup in message["pickups"]:
            if not pickup["ongoing"]: # consider when finished
                statistics["waiting"] -= 1
                statistics["onboard"] += 1

        # Third, we go through the drop-offs to print them
        for dropoff in message["dropoffs"]:
            if not dropoff["ongoing"]: # consider when finished
                statistics["onboard"] -= 1
                statistics["finished"] += 1

        # Fourth, we go through the simulator-side rejected requests if any
        statistics["rejected"] += len(message["rejected"])
        statistics["waiting"] -= len(message["rejected"])
        pending_requests = [r for r in pending_requests if r["id"] not in message["rejected"]]

        # Fifth, we go through the vehicle and find those that are currently idle
        unassigned_vehicles = []
        for vehicle in message["vehicles"]:
            if vehicle["state"] == "stay":
                unassigned_vehicles.append(vehicle["id"])

        statistics["idle_vehicles"] = len(unassigned_vehicles)

        # Logging
        print(statistics, "@", message["time"])

        # We need to respond with an "assignment" message to advance to the next time step.
        # It allows us to modify the stop sequence (pickup, dropoff, relocation) of the vehicles
        assignment = { "@message": "assignment", "stops": dict() }

        # As long as we have pending requests or assignable vehicles in our lists ...
        while len(pending_requests) > 0 and len(unassigned_vehicles) > 0:
            # Pop the top request and vehicle
            request = pending_requests.pop(0)
            vehicle_id = unassigned_vehicles.pop(0)
            
            # Each vehicle that we want to modify needs to get a new sequence of stops
            stops = []

            # We add one pick-up
            stops.append({
                "link": request["originLink"], # get origin from our saved request
                "pickup": [request["id"]], # could add multiple requests here
            })

            # And the dropoff just after
            stops.append({
                "link": request["destinationLink"], # get destination from our saved request
                "dropoff": [request["id"]], # could add multiple requests here
            })

            # Note that we do not provide any route between pickup and dropoff. We can do so to impose a specific path, otherwise the simulator will perform a shortest-path routing.

            # Put the sequence for this vehicle into the assignment
            assignment["stops"][vehicle_id] = stops
            
        # Send all the update assignments to the simulator to end the timestep
        socket.send(json.dumps(assignment).encode())

    elif message["@message"] == "error":
        print(message)
        raise RuntimeError("An error occured")

    else:
        raise RuntimeError("Unknown message received")
