import zmq, json, sys

import numpy as np
import networkx as nx

from typing import TypedDict

"""
# Roaming dispatcher with routing and pooling

This dispatcher lets idle vehicles roam randomly in the network. As soon as they 
pass through a link where a customer is waiting, the customer is assigned and the
vehicle will go to drop off this customer. Each time the vehicle meets another 
customer on the way, the customer will be picked up (until the vehicle capacity
is reached) and the dropoff is added to the vehicle's route. We make use of the
networkx library to perform the routing of the relocation path instead of letting 
the simulator assign the shortest paths implicitly.

Note that transferring continously all routes for all stops implies a large volume
of data for large networks, which can deteriorate communication performance. If not 
explicitly custom routes are required, it is better to let the simulator perform the
shortest path calculation (and caching).

Check use_local_routing in the RoamingDispatcher. If enabled, just communicating the
route causes a large overhead.
"""

class Network:
    """
    This is a convenience class that processes the network data from the simuator and provides
    the functionality to perform routing."""
    def __init__(self, data):
        # a back reference of a pair of nodes (start, end) to the link id
        self.links = {}

        # references the pair of nodes (start, end) for a given link id
        self.edges = {}

        # the network graph for routign
        self.graph = nx.DiGraph()

        for link_id, link_data in data["links"].items():
            # add an edge with travel time attribute
            travel_time = 3600 * link_data["length_m"] * 1e-3 / link_data["freespeed_km_h"]
            self.graph.add_edge(link_data["fromId"], link_data["toId"], travel_time = travel_time)
            
            # mapping of link id to edges
            self.edges[link_id] = (link_data["fromId"], link_data["toId"])

            # mapping of edges to link id
            self.links[(link_data["fromId"], link_data["toId"])] = link_id
    
    def calculate_route(self, origin_link, destination_link):
        # we need the "to" node of the origin link
        origin_node = self.edges[origin_link][1]

        # and the "from" node of the destination link
        destination_node = self.edges[destination_link][0]

        # calculate the (quickest) path using networkx
        path = nx.shortest_path(self.graph, origin_node, destination_node, "travel_time")

        # now construct the route for the simulator:
        # - include the origin link
        # - then add all the intermediary links
        # - end with the destination link
        route = [origin_link] + [
            self.links[edge] for edge in zip(path[:-1], path[1:])
        ] + [destination_link]

        return route

# holds local vehicle state
class Vehicle(TypedDict):
    id: str # identifier
    link: str # current link id

    pickup: list[str] # list of pickups that the vehicle still needs to perform
    dropoff: list[str] # list of dropoffs that the vehicle needs to perform

    assignable: bool # whether vehicle can be assigned a new request

    capacity: int # number of seats; fixed throghout the simulation

    relocation_link: str # currently assigned destination for relocation
    relocation_route: list[str] # currently assigned route (as link ids) for caching

# holds local request state
class Request(TypedDict):
    id: str # identifier
    assignable: bool # whether request can still be assigned

    origin_link: str # origin link for constructing the stop sequence and detecting passing vehicles
    destination_link: str # destination link for constructing the stop sequence

class RoamingDispatcher:
    def __init__(self, socket: zmq.Socket):
        """Initialize the dispatcher"""

        self.socket = socket

        # settings

        # querying locations and dispatching every N seconds
        # should not be too high so we don't "miss" waiting requsts on the current link
        self.dispatching_interval = 5.0 

        # enable or disable local routing
        self.use_local_routing = True 

        # data

        # tracking the state of the vehicles 
        self.vehicles: dict[str, Vehicle] = {}

        self.requests: dict[str, Request] = {}

        # holds information on the network, initialized later
        self.network: Network = None 

        # holds a sorted list of links for random sampling of the relocation links
        self.links: list[str] = None

        # track statistics
        self.statistics = {
            "pending": 0, "onboard": 0, "finished": 0, "max. occupancy": 0
        }

        # random state
        self.random = np.random.default_rng(42) # random seed 42

    def send_message(self, name, content = {}):
        """Simplifies sending messages"""
        message = { "@message": name }
        message.update(content)
        self.socket.send(json.dumps(message).encode())

    def read_message(self, expected = None):
        """Simplifies reading messages"""
        response = json.loads(self.socket.recv())

        if expected is not None:
            assert response["@message"] == expected

        return response
    
    def request_data(self, request_name, expected_response):
        """Simplifies requesting information from the simulator"""
        self.send_message(request_name)
        return self.read_message(expected_response)

    def run(self):
        """This is the main loop of our dispatcher"""
        running = True

        # initialize communication
        self.send_message("initialization")

        while running:
            message = self.read_message()

            if message["@message"] == "finalization":
                running = False # end dispatcher

            elif message["@message"] == "error":
                raise RuntimeError(message) # error

            elif message["@message"] == "prepare_iteration":
                self.initialize_network()
                self.initialize_requests()
                self.initialize_vehicles()

                # we are ready to start the iteration
                self.send_message("start_iteration")
            
            elif message["@message"] == "state":
                # update our internal state based on incoming information
                self.update_requests(message)
                self.update_vehicles(message)

                # perform the actual assignment
                assignment = self.perform_assignment()
                print(self.statistics, "@t=", message["time"])

                response = {
                    "@message": "assignment",
                    "stops": assignment,
                    "waitFor": self.dispatching_interval # until next communication
                }

                # send the assignment to the simulator
                self.send_message("assignment", response)

            else:
                raise RuntimeError("Unknown message type: {}".format(message["@message"]))

    def initialize_network(self):
        """Initialies the network (once)"""
        if self.network is None:
            network_data = self.request_data("network_request", "network_response")
            self.network = Network(network_data)

            # obtain a sorted list of link ids for random sampling
            self.links = sorted(list(self.network.links.values()))

    def initialize_requests(self):
        """Initializes the requests."""

        # reset
        self.requests = {}

    def initialize_vehicles(self):
        """Initializes the vehicles. Called during iteration preparation."""
        
        # reset
        self.vehicles = {}

        # request fleet data
        fleet_data = self.request_data("fleet_request", "fleet_response")

        for vehicle_data in fleet_data["vehicles"]:
            # initialize vehicle
            self.vehicles[vehicle_data["id"]] = Vehicle(
                id = vehicle_data["id"],
                link = vehicle_data["startLink"],
                pickup = [], dropoff = [],
                assignable = False, # see update later
                capacity = vehicle_data["capacity"],
                relocation_link = None, relocation_route = None
            )

    def update_requests(self, state):
        # check for pickups and dropoffs
        for pickup in state["pickups"]:
            # remove ongoing pickup from vehicle to avoid recreating it in the stop sequence
            vehicle: Vehicle = self.vehicles[pickup["vehicle"]]

            if pickup["request"] in vehicle["pickup"]:
                vehicle["pickup"].remove(pickup["request"])

            # statistics are tracked when the pickup is actually finished
            if not pickup["ongoing"]:
                self.statistics["pending"] -= 1
                self.statistics["onboard"] += 1

        for dropoff in state["dropoffs"]:
            # remove ongoing dropoff from vehicle to avoid recreating it in the stop sequence
            vehicle: Vehicle = self.vehicles[dropoff["vehicle"]]
            
            if dropoff["request"] in vehicle["dropoff"]:
                vehicle["dropoff"].remove(dropoff["request"])

            # statistics are tracked when the dropoff is actually finished
            if not dropoff["ongoing"]:
                # drop request
                del self.requests[dropoff["request"]]

                self.statistics["onboard"] -= 1
                self.statistics["finished"] += 1
                
        # check for new incoming requests
        for request_data in state["submitted"]:
            self.requests[request_data["id"]] = Request(
                id = request_data["id"],
                assignable = True,
                origin_link = request_data["originLink"],
                destination_link = request_data["destinationLink"],
            )

            self.statistics["pending"] += 1

    def update_vehicles(self, state):
        # for statistics and showing pooling, track the current max. occupancy across vehicles
        maximum_occupancy = 0

        # update our local vehicles from the transmitted vehicle state
        for vehicle_data in state["vehicles"]:
            # get local vehicle
            vehicle: Vehicle = self.vehicles[vehicle_data["id"]]

            # update location from state
            vehicle["link"] = vehicle_data["diversionLink"]

            # Attention: Instead of using the currentLink which describes the link
            # that the vehicle is currently traversing, we use diversionLink. It describes
            # the next link at which the route of the vehicle can be changed again. Usually
            # this is the next downstream link along the vehicle's current route.

            # occupancy of the vehicle is reflected by pending dropoffs - pending pickups
            occupancy = len(vehicle["dropoff"]) - len(vehicle["pickup"])

            # vehicle is assignable only if it is active
            vehicle["assignable"] = vehicle_data["state"] != "inactive"

            # and if it is not fully occupied
            vehicle["assignable"] &= occupancy < vehicle["capacity"]

            # track
            maximum_occupancy = max(maximum_occupancy, occupancy)

            # if vehicle has arrived at relocation destination, reset
            if vehicle["relocation_link"] == vehicle["link"]:
                vehicle["relocation_link"] = None
                vehicle["relocation_route"] = None
        
        self.statistics["max. occupancy"] = maximum_occupancy

    def perform_assignment(self):
        # find all assignable requests (not yet picked up)
        requests = [r for r in self.requests.values() if r["assignable"]]

        # find all assignable vehicles (not occupied and active)
        vehicles = [v for v in self.vehicles.values() if v["assignable"]]

        # loop over all requests and all vehicles and check if the vehicle is on 
        # the request's origin link
        for vehicle in vehicles:
            for request in requests:
                if request["origin_link"] == vehicle["link"]:
                    # we found a match

                    # add the request to the pending pickup and dropoff lists
                    vehicle["pickup"].append(request["id"])
                    vehicle["dropoff"].append(request["id"])

                    # lock the request (fixed assignment)
                    request["assignable"] = False

            # if the vehicle has no planned relocation link, reassign
            if vehicle["relocation_link"] is None:
                # sample randomly
                destination = self.random.choice(self.links)

                # save for sequence integration later
                vehicle["relocation_link"] = destination

        # now, translate the vehicle states into stop sequences
        sequences = {}

        for vehicle in vehicles:
            sequence = []
            sequences[vehicle["id"]] = sequence

            # First, we add all the pickups. Mostly we will we have one per link, but
            # we can have the special case where the vehicle encountered more than one
            # request on the same link. We add the respective stops in the beginning of
            # the stop sequence. So only once the simulator has processed all of them
            # the vehicle will continue its journey.

            # tracking the current link for routing
            for request_id in vehicle["pickup"]:
                request = self.requests[request_id]
                
                sequence.append({
                    "link": request["origin_link"],
                    "pickup": [request["id"]]
                })

            # Then, we add the dropoffs in the order in which we discovered the
            # requests.

            for request_id in vehicle["dropoff"]:
                request = self.requests[request_id]

                sequence.append({
                    "link": request["destination_link"],
                    "dropoff": [request["id"]]
                })

            # finally, handle relocation
            if len(vehicle["pickup"]) == 0 and len(vehicle["dropoff"]) == 0:
                if self.use_local_routing:
                    # Since the vehicle is moving, the starting point of the relocation 
                    # routing is different in every time step. Therefore, we save the route
                    # and reuse it as much as possible.

                    route = vehicle["relocation_route"]

                    origin_link = vehicle["link"]
                    destination_link = vehicle["relocation_link"]

                    if route and origin_link in route and destination_link in route:
                        start_index = route.index(origin_link)
                        end_index = route.index(destination_link)

                        # trim the route
                        route = route[start_index:end_index + 1]

                    else:
                        # calculate path
                        route = self.network.calculate_route(origin_link, destination_link)

                    # add the relocation stop
                    sequence.append({
                        "link": destination_link,
                        "route": route
                    })

                    # update cached route
                    vehicle["relocation_route"] = route

                else: # no local routing, let the simulator create the shortest path
                    sequence.append({
                        "link": vehicle["relocation_link"]
                    })

        return sequences

if __name__ == "__main__":
    # initialize zmq
    context = zmq.Context()

    # obtain the port from the command line
    port = int(sys.argv[1])

    # open the connection
    socket = context.socket(zmq.REQ)
    socket.connect("tcp://localhost:{}".format(port))

    RoamingDispatcher(socket).run()
