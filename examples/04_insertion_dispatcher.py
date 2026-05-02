import zmq, json, sys

import numpy as np
import networkx as nx

from typing import TypedDict
from enum import Enum

from sklearn.mixture import GaussianMixture
from sklearn.neighbors import KDTree

import numpy.linalg as la
import scipy.optimize as sopt


"""
# Insertion dispatcher

This dispatcher is similar to the DRT algorithm that is implemented in MATSim. For
each incoming request, it evaluates all feasible insertion locations of the 
pickup and the dropoff in all vehicles. An insertion is feasible if it doesn't
violate the latest pickup and dropoff constraints of the incoming and all assigned
requests. Finally, the insertion with the least wait time for the customer is greedily
inserted (this is simpler than the cost objective in MATSim).

Althrough we perform routing to evaluate the insertions using networkx, we never
communicate the actual routes to limit the communication overhead.

In a configurable interval, we estimate a Gaussian Mixture Model on all rejected
requests and send idle vehicles to locations sampled from its density. Play with 
the relocation_interval to see the impact.
"""

# Entity definition: we follow a more structured appraoch in this example

class Request(TypedDict):
    # identification
    id: str

    # origin / destination
    pickup_link: str
    dropoff_link: str

    # service constraints
    latest_pickup_time: float
    latest_dropoff_time: float

    # whether already assigned or not
    assignable: bool 

    @staticmethod
    def create(data):
        return Request(
            id = data["id"],
            pickup_link = data["originLink"],
            dropoff_link = data["destinationLink"],
            latest_pickup_time = data["latestPickupTime"],
            latest_dropoff_time = data["latestArrivalTime"],
            assignable = True
        )

class StopType(Enum):
    PICKUP = 1
    DROPOFF = 2
    RELOCATION = 3

# Each stop represents one pickup or dropoff of a customer. 
# We don't handle multiple customers in the same stop (even if the simulator may be able to do that).
class Stop(TypedDict):
    # identification (used for bookkeeping and removing already processed stops from the sequence)
    id: str

    # instructions
    request: Request
    type: StopType

    # where and when
    link: str
    arrival_time: float

class Vehicle(TypedDict):
    # identification
    id: str

    # current (assignable) link (where the vehicle can update the route)
    link: str

    # when the vehicle can update the route
    diversion_time: float

    # stop sequence
    schedule: list[Stop]

    # whether is active or not
    active: bool

    @staticmethod
    def create(data):
        return Vehicle(
            id = data["id"],
            link = data["startLink"],
            schedule = [],
            active = False,
            diversion_time = 0.0
        )

class Router:
    """This class processes the network data of the simulator and allows to calculate network routes and, in particular,
    can estimate travel times."""

    def __init__(self, data):
        # don't calcualte any route twice
        self.cache = {}

        # references the pair of nodes (start, end) for a given link id
        self.edges = {}

        # the network graph for routing
        self.graph = nx.DiGraph()

        # track coordinates of all links
        self._coordinates = {}

        # lookup for closest link given a coordinate
        self.links = []
        index_data = []

        node_coordinates = {}
        for node_id, node_data in data["nodes"].items():
            node_coordinates[node_id] = np.array([node_data["x"], node_data["y"]])

        for link_id, link_data in data["links"].items():
            # add an edge with travel time attribute
            travel_time = 3600 * link_data["length_m"] * 1e-3 / link_data["freespeed_km_h"]
            self.graph.add_edge(link_data["fromId"], link_data["toId"], travel_time = travel_time)
            
            # mapping of link id to edges
            self.edges[link_id] = (link_data["fromId"], link_data["toId"])

            # coordinates (we take the from node as represetative of the link)
            location = node_coordinates[link_data["fromId"]]
            self._coordinates[link_id] = location

            # index
            self.links.append(link_id)
            index_data.append(location)
        
        # build a spatial index for lookup
        self.spatial_index = KDTree(np.array(index_data))

    def calculate_travel_time(self, origin_link: str, destination_link: str) -> float:
        # first, try to hit the cache
        key = (origin_link, destination_link)

        if key in self.cache:
            return self.cache[key]
        
        # "to" node of the origin link
        origin_node = self.edges[origin_link][1]

        # "from" node of the destination link
        destination_node = self.edges[destination_link][0]

        # calculate the path using networkx
        path = nx.shortest_path(self.graph, origin_node, destination_node, "travel_time")
        travel_time =  nx.path_weight(self.graph, path, "travel_time")

        # finish calculation
        self.cache[key] = travel_time
        return travel_time

    def coordinates(self, link: str):
        """Returns the coordinates of a link given by identifier (for relocation)"""
        return self._coordinates[link]

    def closest(self, location: np.array):
        """Returns the closest link of a coordinate (for relocation)"""
        index = self.spatial_index.query(location.reshape((1, 2)), return_distance = False)[0][0]
        return self.links[index]

class Insertion(TypedDict):
    # vehicle
    vehicle: Vehicle
    
    # indices
    pickup_index: int
    dropoff_index: int

    # timing
    pickup_arrival_time: float
    dropoff_arrival_time: float

    # shifting of other stops
    pickup_delay: float
    dropoff_delay: float

class InsertionManager:
    """This class is used to generate feasible insertions into vehicle schedules and to apply them.
    """

    def __init__(self, router: Router, stop_duration: float):
        self.router = router
        self.stop_duration = stop_duration

    def check_feasibility(self, sequence: list[Stop], index: int, delay: float):
        """Given a vehicle's stop sequence, this method checks whether an inertion at the given 
        index (and with the given incurred delay) would still allow all following requests to 
        be picked up and dropped off on time.
        """
        for stop in sequence[index:]:
            updated_arrival_time = stop["arrival_time"] + delay

            if stop["type"] == StopType.PICKUP:
                # pickup at the end of the stop (after duration)
                if updated_arrival_time + self.stop_duration > stop["request"]["latest_pickup_time"]:
                    return False # shifting pickup too far
                
            elif stop["type"] == StopType.DROPOFF:
                # dropoff at the beginning of the stop (before duration)
                if updated_arrival_time > stop["request"]["latest_dropoff_time"]:
                    return False # shifting dropoff too far
        
        return True # did exit so far, so insertion looks good

    def find_insertions(self, vehicles: list[Vehicle], request: Request):
        insertions = []

        for vehicle in vehicles:
            # temporarily remove relocation from the schedule
            relocation = None
            
            if len(vehicle["schedule"]) > 0 and vehicle["schedule"][0]["type"] == StopType.RELOCATION:
                relocation = vehicle["schedule"][0]
                vehicle["schedule"].pop(0)
                assert len(vehicle["schedule"]) == 0

            for pickup_index in range(len(vehicle["schedule"]) + 1):
                # trying to insert pickup before pickup_index

                is_pickup_start = pickup_index == 0
                if is_pickup_start:
                    # route to pickup starts at vehicle location and next possible assignment time
                    pickup_preceding_link = vehicle["link"]
                    pickup_preceding_departure_time = vehicle["diversion_time"]
                else:
                    # route to pickup starts at an existing stop
                    preceding_stop: Stop = vehicle["schedule"][pickup_index - 1]
                    pickup_preceding_link = preceding_stop["link"]
                    pickup_preceding_departure_time = preceding_stop["arrival_time"] + self.stop_duration

                # initialize pickup information
                pickup_link = request["pickup_link"]
                pickup_delay = 0.0 # delay that is imposed on following stops after the pickup
                pickup_arrival_time = None # time when vehicle arrives at the pickup

                # calculate travel time to pickup
                to_pickup_travel_time = self.router.calculate_travel_time(pickup_preceding_link, pickup_link)
                pickup_arrival_time = pickup_preceding_departure_time + to_pickup_travel_time

                # pickup happens after the stop duration, check if it is feasible
                if pickup_arrival_time + self.stop_duration > request["latest_pickup_time"]:
                    break # no need to explore further

                # shift all following stops by the drive time plus stop duration
                pickup_delay += to_pickup_travel_time + self.stop_duration

                # now check if the following stops are still feasible
                if not self.check_feasibility(vehicle["schedule"], pickup_index, pickup_delay):
                    continue # not feasible, but other insertions may be
                
                # now go through dropoffs following the pickup
                for dropoff_index in range(pickup_index, len(vehicle["schedule"]) + 1):
                    # check if the dropoff comes directly after the pickup
                    is_direct = pickup_index == dropoff_index
                    updated_pickup_delay = pickup_delay

                    if not is_direct: 
                        # we still need to take into account the drive from the pickup to the following stop
                        # to know if the pickup is really feasible

                        # calculate travel time from pickup to following (not the request's dropoff) stop
                        pickup_following_link = vehicle["schedule"][pickup_index]["link"]
                        from_pickup_travel_time = self.router.calculate_travel_time(pickup_link, pickup_following_link)
                        
                        # all following stops are further shifted by that drive time
                        updated_pickup_delay += from_pickup_travel_time

                        # now check if the following stops are still feasible
                        if not self.check_feasibility(vehicle["schedule"], pickup_index, updated_pickup_delay):
                            continue # not feasible, but other insertions may be
                    
                    # figure out where and when we start the drive to the dropoff
                    if is_direct:
                        # dropoff comes directly after pickup
                        dropoff_preceding_link = pickup_link
                        dropoff_preceding_departure_time = pickup_arrival_time + updated_pickup_delay
                    else:
                        # dropoff is after an existing stop
                        preceding_stop: Stop = vehicle["schedule"][dropoff_index - 1]
                        dropoff_preceding_link = preceding_stop["link"]
                        dropoff_preceding_departure_time = preceding_stop["arrival_time"] + self.stop_duration + updated_pickup_delay
                    
                    # initialize dropoff information
                    dropoff_link = request["dropoff_link"]
                    dropoff_delay = updated_pickup_delay # (total) delay that is imposed on following stops after the dropoff
                    dropoff_arrival_time = None # time when vehicle arrives at the dropoff
                        
                    # calculate travel time to dropoff
                    to_dropoff_travel_time = self.router.calculate_travel_time(dropoff_preceding_link, dropoff_link)
                    dropoff_arrival_time = dropoff_preceding_departure_time + to_dropoff_travel_time

                    # dropoff happens before the stop duration, check if it is feasible
                    if dropoff_arrival_time > request["latest_dropoff_time"]:
                        break # no need to explore further

                    # shift all following stops by the drive time plus stop duration
                    dropoff_delay += to_dropoff_travel_time + self.stop_duration

                    # check if dropoff is last stop
                    is_dropoff_end = dropoff_index == len(vehicle["schedule"])

                    # still need to go to the next stop
                    if not is_dropoff_end:
                        # calculate travel time from dropoff to following stop
                        dropoff_following_link = vehicle["schedule"][dropoff_index]["link"]
                        from_dropoff_travel_time = self.router.calculate_travel_time(dropoff_link, dropoff_following_link)
                        
                        # all following stops are further shifted by that drive time
                        dropoff_delay += from_dropoff_travel_time

                        # now check if the following stops are still feasible
                        if not self.check_feasibility(vehicle["schedule"], dropoff_index, dropoff_delay):
                            continue # not feasible, but other insertions may be
                    
                    # at this point, we found a feasible insertion!
                    insertions.append(Insertion(
                        vehicle = vehicle, 
                        pickup_index = pickup_index, dropoff_index = dropoff_index,
                        pickup_delay = updated_pickup_delay, dropoff_delay = dropoff_delay,
                        pickup_arrival_time = pickup_arrival_time, dropoff_arrival_time = dropoff_arrival_time
                    ))

            if relocation: # add it back
                vehicle["schedule"].append(relocation)
                assert len(vehicle["schedule"]) == 1
        
        return insertions
    
    def apply(self, request: Request, insertion: Insertion, stop_index: int):
        """Integrates a feasible insertion into a vehicle by correctly shifting the stop timing.

        The stop index is used to give unique identifiers to the created stops. The method will
        increase the index and return the updated value.
        """
        schedule: list[Stop] = insertion["vehicle"]["schedule"]

        # first remove any relocation stop
        while len(schedule) > 0 and schedule[0]["type"] == StopType.RELOCATION:
            schedule.pop(0)

        # second shift the timing
        for k in range(insertion["pickup_index"], insertion["dropoff_index"]):
            schedule[k]["arrival_time"] += insertion["pickup_delay"]

        for k in range(insertion["dropoff_index"], len(schedule)):
            schedule[k]["arrival_time"] += insertion["dropoff_delay"]

        # then insert the new stops
        schedule.insert(insertion["pickup_index"], Stop(
            type = StopType.PICKUP,
            request = request,
            link = request["pickup_link"],
            arrival_time = insertion["pickup_arrival_time"],
            id = str(stop_index)
        ))

        # shift index by one since we already inserted the pickup
        schedule.insert(insertion["dropoff_index"] + 1, Stop(
            type = StopType.DROPOFF,
            request = request,
            link = request["dropoff_link"],
            arrival_time = insertion["dropoff_arrival_time"],
            id = str(stop_index + 1)
        ))

        return stop_index + 2

class RelocationManager:
    """Manages relocation estimation and instructions"""

    def __init__(self):
        self.rejections = []
        self.model = None # see update

    def notify_rejection(self, location: np.array):
        """Tracks the location of rejected requests"""
        # track the rejection
        self.rejections.append(location)

    def update(self):
        """Estimates a 2D density model of the rejected requests. The density is used
        to propose relocation destinations. We assume that where requests have been rejected
        there has been a lack of vehicles in the last decision period, so we send more."""

        if len(self.rejections) < 2: # need at least 2 for mixture
            self.model = None

        else:
            X = np.array(self.rejections)

            # parameter selection
            best_aic = np.inf
            best_candidate = None
            best_components = None

            for components in range(1, min(8, len(self.rejections))):
                candidate = GaussianMixture(components)
                candidate.fit(X)

                aic = candidate.aic(X)

                if aic < best_aic:
                    best_aic, best_candidate, best_components = aic, candidate, components
                
            self.model = best_candidate
            print("Reloation: N={} AIC={} components={}".format(len(self.rejections), best_aic, best_components))

            # reset for next decision period
            self.rejections = []

    def apply(self, vehicles: list[Vehicle], router: Router, stop_index: int):
        """Integrate new destinations for a list of vehicles"""
        if self.model:
            # vehicle locations
            vehicle_locations = [router.coordinates(v["link"]) for v in vehicles]
            
            # sample locations
            destinations = self.model.sample(len(vehicles))[0]
            assert destinations.shape == (len(vehicles), 2)

            # calculate VxD distance matrix between all vehicles and destinations
            distances = np.array([
                la.norm(destinations - vehicle_location, axis = 1)
                for vehicle_location in vehicle_locations
            ])

            # perform bipartite matching to find the closest vehicle to each destination
            matches = [pair for pair in zip(*sopt.linear_sum_assignment(distances))]

            # perform the assignments
            for vehicle_index, destination_index in matches:
                vehicle: Vehicle = vehicles[vehicle_index]
                
                vehicle["schedule"].append(Stop(
                    # get the closest link to the coordinate
                    link = router.closest(destinations[destination_index]),
                    type = StopType.RELOCATION,
                    id = str(stop_index)
                ))

                stop_index += 1
        
        return stop_index

class InsertionDispatcher:
    def __init__(self, socket: zmq.Socket):
        """Initialize the dispatcher"""
        self.socket = socket

        # track vehicles
        self.vehicles: dict[str, Vehicle] = {}

        # track requests
        self.requests: dict[str, Request] = {}
        self.pending: list[Request] = [] # recently submitted

        # declare router (initialized later)
        self.router: Router = None

        # running stop index: Each stop that we create and assign to a vehicle will have 
        # an identifier. This way, we can remove stops from our local schedules as soon
        # as they are processed in the simulation.
        self.stop_index = 0

        # each stop has a specific duration (simulator default)
        # dropoffs happen at the beginning of a stop
        # pickups happen at the end of a stop
        self.stop_duration = 30.0

        # settings
        self.dispatching_interval = 30.0
        self.relocation_interval = 1200.0

        # track statistics
        self.statistics = {
            "pending": 0, "rejected": 0, "assigned": 0, "onboard": 0, "finished": 0
        }

        # relocation
        self.relocation_manager = RelocationManager()

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
                self.initialize_router()
                self.initialize_requests()
                self.initialize_vehicles()

                # we are ready to start the iteration
                self.send_message("start_iteration")
            
            elif message["@message"] == "state":
                # update our internal state based on incoming information
                self.update_requests(message)
                self.update_vehicles(message)

                # perform the actual assignment
                assignment, rejections = self.perform_assignment(message["time"])
                print(self.statistics, "@t=", message["time"])

                response = {
                    "@message": "assignment",
                    "rejections": rejections,
                    "stops": assignment,
                    "waitFor": self.dispatching_interval
                }

                # send the assignment to the simulator
                self.send_message("assignment", response)

            else:
                raise RuntimeError("Unknown message type: {}".format(message["@message"]))

    def initialize_router(self):
        """Initialies the router (once)"""
        if self.router is None:
            data = self.request_data("network_request", "network_response")
            self.router = Router(data)

    def initialize_requests(self):
        """Initializes the requests."""

        # reset
        self.requests = {}
        self.pending = []

    def initialize_vehicles(self):
        """Initializes the vehicles. Called during iteration preparation."""
        
        # reset
        self.vehicles = {}

        # request fleet data
        fleet_data = self.request_data("fleet_request", "fleet_response")

        for vehicle_data in fleet_data["vehicles"]:
            self.vehicles[vehicle_data["id"]] = Vehicle.create(vehicle_data)

    def update_requests(self, state):
        # check for pickups and dropoffs
        for pickup in state["pickups"]:
            # statistics are tracked when the pickup is actually finished
            if not pickup["ongoing"]:
                self.statistics["pending"] -= 1
                self.statistics["onboard"] += 1

        for dropoff in state["dropoffs"]:
            # statistics are tracked when the dropoff is actually finished
            if not dropoff["ongoing"]:
                # we don't need the request anymore
                del self.requests[dropoff["request"]]

                self.statistics["onboard"] -= 1
                self.statistics["finished"] += 1
                
        # check for new incoming requests
        self.pending = []

        for request_data in state["submitted"]:
            request = Request.create(request_data)
            self.requests[request["id"]] = request

            self.pending.append(request)
            self.statistics["pending"] += 1

    def update_vehicles(self, state):
        # update all vehicles
        for vehicle_data in state["vehicles"]:
            # get our local vehicle
            vehicle: Vehicle = self.vehicles[vehicle_data["id"]]

            # obtain all finished and ongoing stops, since we need to
            # delete them from our planned schedules, that's why each stop
            # has an identifier
            done = set(vehicle_data["finished"]) | set(vehicle_data["ongoing"])

            while len(vehicle["schedule"]) > 0 and vehicle["schedule"][0]["id"] in done:
                vehicle["schedule"].pop(0)

            # Update the current link, we use the diverge link, which is the next
            # from which we can change the vehicle's route. Likewise, we use the
            # diversionTime to indicate when the vehicle can start a new route at the 
            # top of the task sequence.
            vehicle["link"] = vehicle_data["diversionLink"]
            vehicle["diversion_time"] = vehicle_data["diversionTime"]

            # update status
            vehicle["active"] = vehicle_data["state"] != "inactive"

    def perform_assignment(self, now):
        insertion_manager = InsertionManager(self.router, self.stop_duration)

        # find all active vehicles
        vehicles = [v for v in self.vehicles.values() if v["active"]]

        # track rejections
        rejected = []

        # go through all pending requests
        for request in self.pending:
            # find fleet insertions
            insertions: list[Insertion] = insertion_manager.find_insertions(vehicles, request)

            if len(insertions) == 0:
                # no insertions, we reject the requests
                rejected.append(request["id"])
            else:
                # find the insertion with the earliest pickup
                earliest_pickup_time = np.inf
                earliest_insertion = None

                for insertion in insertions:
                    if insertion["pickup_arrival_time"] < earliest_pickup_time:
                        earliest_pickup_time = insertion["pickup_arrival_time"]
                        earliest_insertion = insertion
                
                # apply the insertion
                self.stop_index = insertion_manager.apply(
                    request, earliest_insertion, self.stop_index)
        
        # go through all requests to post-process rejections
        for id in rejected:
            # notify relocation
            location = self.router.coordinates(self.requests[id]["pickup_link"])
            self.relocation_manager.notify_rejection(location)

            # drop request
            del self.requests[id]

            # bookkeeping
            self.statistics["pending"] -= 1
            self.statistics["rejected"] += 1

        # relocation from now and then
        if now % self.relocation_interval == 0:
            # find vehicles that have nothing else to do
            relocation_vehicles = [v for v in vehicles if len(v["schedule"]) == 0]

            if len(relocation_vehicles) > 0:
                self.relocation_manager.update()
                self.stop_index = self.relocation_manager.apply(
                    relocation_vehicles, self.router, self.stop_index)

        # construct vehicle sequences (all of them, also inactive)
        sequences = {}

        for vehicle in self.vehicles.values():
            # prepare sequence
            sequence = []
            sequences[vehicle["id"]] = sequence

            for stop in vehicle["schedule"]:
                if stop["type"] == StopType.PICKUP:
                    sequence.append({
                        "link": stop["link"],
                        "pickup": [stop["request"]["id"]],
                        "id": stop["id"]
                    })

                elif stop["type"] == StopType.DROPOFF:
                    sequence.append({
                        "link": stop["link"],
                        "dropoff": [stop["request"]["id"]],
                        "id": stop["id"]
                    })

                elif stop["type"] == StopType.RELOCATION:
                    sequence.append({
                        "link": stop["link"],
                        "id": stop["id"]
                    })
        
        return sequences, rejected

if __name__ == "__main__":
    # initialize zmq
    context = zmq.Context()

    # obtain the port from the command line
    port = int(sys.argv[1])

    # open the connection
    socket = context.socket(zmq.REQ)
    socket.connect("tcp://localhost:{}".format(port))

    InsertionDispatcher(socket).run()
