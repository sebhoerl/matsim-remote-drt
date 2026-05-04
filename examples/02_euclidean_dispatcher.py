import zmq, json, sys

import numpy as np
import numpy.linalg as la
import scipy.optimize as sopt

from typing import TypedDict

"""
# Euclidean bipartite matching dispatcher with rejections

This dispatcher considers the maximum wait time of the customers. If the time has passed
and they have not been picked up, they will be rejected. The dispatching is based on a 
dynamic bipartite matching approach: In every time step, we look at all the requests
that have not yet been picked up and all the vehicles that have no passenger on board.
We then calculate the Euclidean distance between each request origin and each vehicle
location. A bipartite matching problem is solved (using scipy) which minimizes the overall
empty distance. Note that to do so, we first need to extract the coordinates of all links
from the network, which we request from the simulator.
"""

# holds our local state of a vehicle
class Vehicle(TypedDict):
    id: str # identifier
    location: np.array # coordinates

    request: str # id of the assigned request if any
    occupied: bool # whether the request is on-board or not
    assignable: bool # whether vehicle can take part in dispatching

# holds local state of a request
class Request(TypedDict):
    id: str # identifier
    origin: np.array # coordinates (for matching)

    assignable: str # whether can still be assigned (not yet picked up)

    latest_pickup_time: float # used for rejection

    origin_link: str # identifier of origin link, for constructing the stop sequence
    destination_link: str # same for destination


class EuclideanDispatcher:
    def __init__(self, socket: zmq.Socket):
        """Initialize the dispatcher"""

        self.socket = socket

        # settings
        self.dispatching_interval = 30.0 # communicate every 30s

        # tracking the state of the vehicles 
        self.vehicles: dict[str, Vehicle] = {}

        self.requests: dict[str, Request] = {}

        # holds the network link coordinates (requested and initialized later)
        # map of link id to a numpy array holding coordinates
        self.coordinates: dict[str, np.array] = None 

        # track statistics
        self.statistics = {
            "pending": 0, "rejected": 0, "onboard": 0, "finished": 0
        }

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
                rejections = self.perform_rejections(message["time"])
                assignment = self.perform_assignment()
                print(self.statistics, "@t=", message["time"])

                response = {
                    "@message": "assignment",
                    "rejections": rejections,
                    "stops": assignment,
                    "waitFor": self.dispatching_interval # until next communication
                }

                # send the assignment to the simulator
                self.send_message("assignment", response)

            else:
                raise RuntimeError("Unknown message type: {}".format(message["@message"]))

    def initialize_requests(self):
        """Initializes the requests."""

        # reset
        self.requests = {}

    def initialize_network(self):
        """Initialies the network (once)"""
        if self.coordinates is None:
            data = self.request_data("network_request", "network_response")

            self.coordinates = {}
            for link_id, link_data in data["links"].items():
                # we get from node and to node for each link
                from_node = data["nodes"][link_data["fromId"]]
                to_node = data["nodes"][link_data["toId"]]
                
                # we get the nodes' coordinates
                from_coordindate = np.array([from_node["x"], from_node["y"]])
                to_coordindate = np.array([to_node["x"], to_node["y"]])

                # we take the midpoint between the nodes as representative for a link
                self.coordinates[link_id] = 0.5 * from_coordindate + 0.5 * to_coordindate

    def initialize_vehicles(self):
        """Initializes the vehicles. Called during iteration preparation."""
        
        # reset
        self.vehicles = {}

        # request fleet data
        fleet_data = self.request_data("fleet_request", "fleet_response")

        for vehicle_data in fleet_data["vehicles"]:
            self.vehicles[vehicle_data["id"]] = Vehicle(
                id = vehicle_data["id"], 
                occupied = False, request = None,
                assignable = False, # see update later

                # make use of network coordinates
                location = self.coordinates[vehicle_data["startLink"]]
            )

    def update_requests(self, state):
        # check for pickups and dropoffs
        for pickup in state["pickups"]:
            # A pickup can either be ongoing or finished. We already make a request
            # unassignable and a vehicel occupied once the pickup is ongoing, because
            # we cannot abort an ongoing pickup and avoid any reassignment.

            request = self.requests[pickup["request"]]
            request["assignable"] = False
            
            vehicle = self.vehicles[pickup["vehicle"]]
            vehicle["occupied"] = True

            if not pickup["ongoing"]: # bookkeeping
                self.statistics["pending"] -= 1
                self.statistics["onboard"] += 1

        for dropoff in state["dropoffs"]:
            if not dropoff["ongoing"]: # finished
                # reset vehicle
                vehicle = self.vehicles[dropoff["vehicle"]]
                vehicle["occupied"] = False
                vehicle["request"] = None

                self.vehicles[dropoff["vehicle"]]["occupied"] = False
                self.vehicles[dropoff["vehicle"]]["request"] = None

                # dump request
                del self.requests[dropoff["request"]]

                # bookkeeping
                self.statistics["onboard"] -= 1
                self.statistics["finished"] += 1

        # treat simulator-rejected requests if any
        for request_id in state["rejected"]:
            assert self.requests[request_id]["assignable"]
            del self.requests[request_id]

            self.statistics["pending"] -= 1
            self.statistics["rejected"] += 1

        # check for new incoming requests
        for request_data in state["submitted"]:
            # initialize request
            self.requests[request_data["id"]] = Request(
                id = request_data["id"],
                assignable = True, vehicle = None,
                latest_pickup_time =  request_data["latestPickupTime"],

                origin_link = request_data["originLink"],
                destination_link = request_data["destinationLink"],

                # lookup from network coordinates
                location = self.coordinates[request_data["originLink"]]
            )

            self.statistics["pending"] += 1

    def update_vehicles(self, state):
        # update our local vehicles from the transmitted vehicle state
        for vehicle_data in state["vehicles"]:
            # get local vehicle
            vehicle: Vehicle = self.vehicles[vehicle_data["id"]]

            # update location from state
            vehicle["location"] = self.coordinates[vehicle_data["currentLink"]]

            # vehicle is not assignable if it is inactive (simulator state)
            vehicle["assignable"] = vehicle_data["state"] != "inactive"

            # and it is only assignable if it is not already occupied (our logic)
            vehicle["assignable"] &= not vehicle["occupied"]
    
    def perform_rejections(self, time):
        rejections = set()

        for request in self.requests.values():
            # only assignable (not picked up) requests should be rejected
            if request["assignable"] and request["latest_pickup_time"] < time:
                rejections.add(request["id"])

        # delete rejected requests
        for request_id in rejections:
            del self.requests[request_id]

            # bookkeeping
            self.statistics["pending"] -= 1
            self.statistics["rejected"] += 1

        return list(rejections)

    def perform_assignment(self):
        # find all assignable requests (not yet picked up)
        requests = [r for r in self.requests.values() if r["assignable"]]

        # find all assignable vehicles (not occupied and active)
        vehicles = [v for v in self.vehicles.values() if v["assignable"]]

        # first, we reset all existing assignments
        for v in vehicles:
            v["request"] = None

        # initialize empty sequences for all assignable vehicles
        # we do this so that vehicles that don't obtain a new assignment will
        # lose their former stop sequence that was known to the simulator
        sequences = { v["id"]: [] for v in vehicles }

        if len(requests) > 0 and len(vehicles) > 0:
            # create location vectors
            request_locations = np.array([r["location"] for r in requests])
            vehicle_locations = np.array([v["location"] for v in vehicles])

            # calculate RxV distance matrix between all requests and vehicles
            distances = np.array([
                la.norm(vehicle_locations - request_location, axis = 1)
                for request_location in request_locations
            ])

            # perform bipartite matching using scipy
            # this gives us pairs of (request_index, vehicle_index)
            # that minimize the overall distance
            matches = [pair for pair in zip(*sopt.linear_sum_assignment(distances))]

            # now costruct new sequences for the assigned vehicles
            for request_index, vehicle_index in matches:
                # obtain match
                request = requests[request_index]
                vehicle = vehicles[vehicle_index]

                # implement matching
                vehicle["request"] = request["id"]

                # create sequence
                sequences[vehicle["id"]] = [{
                    "link": request["origin_link"],
                    "pickup": [request["id"]]
                }, {
                    "link": request["destination_link"],
                    "dropoff": [request["id"]]
                }]
            
        return sequences

if __name__ == "__main__":
    # initialize zmq
    context = zmq.Context()

    # obtain the port from the command line
    port = int(sys.argv[1])

    # open the connection
    socket = context.socket(zmq.REQ)
    socket.connect("tcp://localhost:{}".format(port))

    EuclideanDispatcher(socket).run()
