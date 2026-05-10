package org.matsim.remote_drt.optimizer;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.tuple.Pair;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.IdMap;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.contrib.drt.optimizer.DrtOptimizer;
import org.matsim.contrib.drt.passenger.AcceptedDrtRequest;
import org.matsim.contrib.drt.passenger.DrtRequest;
import org.matsim.contrib.drt.schedule.DrtDriveTask;
import org.matsim.contrib.drt.schedule.DrtStayTask;
import org.matsim.contrib.drt.schedule.DrtStopTask;
import org.matsim.contrib.drt.schedule.DrtTaskBaseType;
import org.matsim.contrib.drt.schedule.DrtTaskFactory;
import org.matsim.contrib.dvrp.fleet.DvrpVehicle;
import org.matsim.contrib.dvrp.fleet.Fleet;
import org.matsim.contrib.dvrp.optimizer.Request;
import org.matsim.contrib.dvrp.passenger.PassengerDroppedOffEvent;
import org.matsim.contrib.dvrp.passenger.PassengerDroppedOffEventHandler;
import org.matsim.contrib.dvrp.passenger.PassengerPickedUpEvent;
import org.matsim.contrib.dvrp.passenger.PassengerPickedUpEventHandler;
import org.matsim.contrib.dvrp.passenger.PassengerRequestRejectedEvent;
import org.matsim.contrib.dvrp.passenger.PassengerRequestScheduledEvent;
import org.matsim.contrib.dvrp.path.VrpPath;
import org.matsim.contrib.dvrp.path.VrpPathWithTravelData;
import org.matsim.contrib.dvrp.path.VrpPathWithTravelDataImpl;
import org.matsim.contrib.dvrp.path.VrpPaths;
import org.matsim.contrib.dvrp.schedule.DriveTask;
import org.matsim.contrib.dvrp.schedule.Schedule;
import org.matsim.contrib.dvrp.schedule.Schedule.ScheduleStatus;
import org.matsim.contrib.dvrp.schedule.ScheduleTimingUpdater;
import org.matsim.contrib.dvrp.schedule.Schedules;
import org.matsim.contrib.dvrp.schedule.StayTask;
import org.matsim.contrib.dvrp.schedule.Task;
import org.matsim.contrib.dvrp.schedule.Task.TaskStatus;
import org.matsim.contrib.dvrp.tracker.OnlineDriveTaskTracker;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.events.MobsimScopeEventHandler;
import org.matsim.core.mobsim.framework.events.MobsimBeforeSimStepEvent;
import org.matsim.core.router.util.LeastCostPathCalculator;
import org.matsim.core.router.util.TravelTime;
import org.matsim.remote_drt.RemoteDispatchingManager;
import org.matsim.remote_drt.messages.Assignment;
import org.matsim.remote_drt.messages.State;

import com.google.common.base.Preconditions;
import com.google.common.base.Verify;

public class RemoteDispatchingOptimizer
        implements DrtOptimizer, PassengerPickedUpEventHandler, PassengerDroppedOffEventHandler,
        MobsimScopeEventHandler {
    private final RemoteDispatchingManager manager;
    private final ScheduleTimingUpdater scheduleTimingUpdater;
    private final Fleet fleet;
    private final Network network;
    private final DrtTaskFactory taskFactory;
    private final TravelTime travelTime;
    private final LeastCostPathCalculator router;
    private final EventsManager eventsManager;
    private final String mode;
    private final double defaultPickupDuration;
    private final double defaultDropoffDuration = 1.0;
    private final boolean useAutomaticRejection;

    public RemoteDispatchingOptimizer(RemoteDispatchingManager manager,
            ScheduleTimingUpdater scheduleTimingUpdater,
            Fleet fleet, Network network, DrtTaskFactory taskFactory, TravelTime travelTime,
            LeastCostPathCalculator router, EventsManager eventsManager, String mode, double defaultStopDuration,
            boolean useAutomaticRejection) {
        this.manager = manager;
        this.scheduleTimingUpdater = scheduleTimingUpdater;
        this.fleet = fleet;
        this.network = network;
        this.taskFactory = taskFactory;
        this.travelTime = travelTime;
        this.router = router;
        this.eventsManager = eventsManager;
        this.mode = mode;
        this.defaultPickupDuration = defaultStopDuration;
        this.useAutomaticRejection = useAutomaticRejection;

        for (Id<DvrpVehicle> vehicleId : fleet.getVehicles().keySet()) {
            onboard.put(vehicleId, new HashSet<>());
        }
    }

    private double nextStep = Double.NEGATIVE_INFINITY;

    @Override
    public void notifyMobsimBeforeSimStep(@SuppressWarnings("rawtypes") MobsimBeforeSimStepEvent e) {
        // if (!isFirstStep) {
        if (e.getSimulationTime() >= nextStep) {
            Assignment assignment = update(e.getSimulationTime());
            nextStep = e.getSimulationTime() + assignment.waitFor;

            implement(assignment, e.getSimulationTime());
        }
    }

    private List<Request> submitted = new LinkedList<>();
    private IdMap<Request, AcceptedDrtRequest> requests = new IdMap<>(Request.class);

    @Override
    public void requestSubmitted(Request request) {
        synchronized (submitted) {
            submitted.add(request);
        }
    }

    private IdMap<DvrpVehicle, Integer> vehicleOccupancy = new IdMap<>(DvrpVehicle.class);
    private IdMap<DvrpVehicle, Set<Id<Request>>> onboard = new IdMap<>(DvrpVehicle.class);

    private IdMap<Request, Id<DvrpVehicle>> pickedUp = new IdMap<>(Request.class);
    private IdMap<Request, Id<DvrpVehicle>> droppedOff = new IdMap<>(Request.class);

    public Assignment update(double time) {
        State state = new State();
        state.time = time;

        if (useAutomaticRejection) {
            state.rejected = performAutomaticRejection(time);
        }

        Map<Pair<Id<Request>, Id<DvrpVehicle>>, State.Pickup> pickupData = new HashMap<>();
        Map<Pair<Id<Request>, Id<DvrpVehicle>>, State.Dropoff> dropoffData = new HashMap<>();

        for (DvrpVehicle vehicle : fleet.getVehicles().values()) {
            State.Vehicle vehicleState = new State.Vehicle();
            vehicleState.id = vehicle.getId().toString();
            state.vehicles.add(vehicleState);

            if (vehicle.getSchedule().getStatus().equals(ScheduleStatus.STARTED)) {
                Task currentTask = vehicle.getSchedule().getCurrentTask();
                if (DrtTaskBaseType.STAY.isBaseTypeOf(currentTask)) {
                    vehicleState.state = "stay";

                    StayTask stayTask = (StayTask) currentTask;
                    vehicleState.currentLink = stayTask.getLink().getId().toString();
                    vehicleState.currentExitTime = stayTask.getEndTime();

                    vehicleState.diversionLink = vehicleState.currentLink;
                    vehicleState.diversionTime = time;
                } else if (DrtTaskBaseType.STOP.isBaseTypeOf(currentTask)) {
                    vehicleState.state = "stop";

                    DrtStopTask stopTask = (DrtStopTask) currentTask;
                    vehicleState.currentLink = stopTask.getLink().getId().toString();
                    vehicleState.currentExitTime = stopTask.getEndTime();

                    vehicleState.diversionLink = vehicleState.currentLink;
                    vehicleState.diversionTime = vehicleState.currentExitTime;

                    for (Id<Request> requestId : stopTask.getPickupRequests().keySet()) {
                        State.Pickup pickup = new State.Pickup();

                        pickup.request = requestId.toString();
                        pickup.vehicle = vehicle.getId().toString();
                        pickup.ongoing = true;

                        pickupData.put(Pair.of(requestId, vehicle.getId()), pickup);

                        synchronized (requests) {
                            requestEntries.get(requestId).autoRejectable = false;
                        }

                        onboard.get(vehicle.getId()).add(requestId);
                    }

                    for (Id<Request> requestId : stopTask.getDropoffRequests().keySet()) {
                        State.Dropoff dropoff = new State.Dropoff();

                        dropoff.request = requestId.toString();
                        dropoff.vehicle = vehicle.getId().toString();
                        dropoff.ongoing = true;

                        dropoffData.put(Pair.of(requestId, vehicle.getId()), dropoff);
                    }
                } else if (DrtTaskBaseType.DRIVE.isBaseTypeOf(currentTask)) {
                    vehicleState.state = "drive";

                    DriveTask driveTask = (DriveTask) currentTask;
                    OnlineDriveTaskTracker tracker = (OnlineDriveTaskTracker) driveTask.getTaskTracker();

                    VrpPath path = tracker.getPath();
                    vehicleState.currentLink = path.getLink(tracker.getCurrentLinkIdx()).getId().toString();

                    vehicleState.currentExitTime = tracker.getCurrentLinkEnterTime();
                    vehicleState.currentExitTime += path.getLinkTravelTime(tracker.getCurrentLinkIdx());

                    vehicleState.diversionLink = tracker.getDiversionPoint().link.getId().toString();
                    vehicleState.diversionTime = tracker.getDiversionPoint().time;
                } else {
                    throw new IllegalStateException();
                }
            } else {
                Task lastTask = Schedules.getLastTask(vehicle.getSchedule());

                if (lastTask instanceof StayTask stayTask) {
                    vehicleState.state = "inactive";
                    vehicleState.currentLink = stayTask.getLink().getId().toString();
                    vehicleState.currentExitTime = Double.POSITIVE_INFINITY;
                    vehicleState.diversionLink = vehicleState.currentLink;
                    vehicleState.diversionTime = Double.POSITIVE_INFINITY;
                } else {
                    DriveTask driveTask = (DriveTask) lastTask;
                    vehicleState.state = "inactive";
                    vehicleState.currentLink = driveTask.getPath().getToLink().getId().toString();
                    vehicleState.currentExitTime = Double.POSITIVE_INFINITY;
                    vehicleState.diversionLink = vehicleState.currentLink;
                    vehicleState.diversionTime = Double.POSITIVE_INFINITY;
                }
            }

            Map<String, List<Task>> stopTracker = taskTrackers.getOrDefault(vehicle.getId(),
                    Collections.emptyMap());
            for (var item : stopTracker.entrySet()) {
                String stopId = item.getKey();

                boolean hasFinished = true;
                boolean isOngoing = false;

                for (Task task : item.getValue()) {
                    hasFinished &= task.getStatus().equals(TaskStatus.PERFORMED);
                    isOngoing |= task.getStatus().equals(TaskStatus.STARTED);
                }

                if (hasFinished) {
                    vehicleState.finished.add(stopId);
                }

                if (isOngoing) {
                    vehicleState.ongoing.add(stopId);
                }
            }

            for (Id<Request> requestId : onboard.get(vehicle.getId())) {
                vehicleState.onboard.add(requestId.toString());
            }
        }

        for (Request request : submitted) {
            DrtRequest drtRequest = (DrtRequest) request;

            State.Request requestState = new State.Request();
            state.submitted.add(requestState);
            requestState.id = drtRequest.getId().toString();

            requestState.originLink = drtRequest.getFromLink().getId().toString();
            requestState.destinationLink = drtRequest.getToLink().getId().toString();

            requestState.earliestPickupTime = drtRequest.getConstraints().earliestStartTime();
            requestState.latestPickupTime = drtRequest.getConstraints().latestStartTime();
            requestState.latestArrivalTime = drtRequest.getConstraints().latestArrivalTime();

            requestState.pickupDuration = defaultPickupDuration;
            requestState.dropoffDuration = defaultDropoffDuration;

            AcceptedDrtRequest acceptedRequest = AcceptedDrtRequest.createFromOriginalRequest(drtRequest);
            requests.put(request.getId(), acceptedRequest);
            requestEntries.put(request.getId(), new RequestEntry());
        }

        submitted.clear();

        for (var entry : pickedUp.entrySet()) {
            State.Pickup pickup = new State.Pickup();

            pickup.request = entry.getKey().toString();
            pickup.vehicle = entry.getValue().toString();
            pickup.ongoing = false;

            pickupData.put(Pair.of(entry.getKey(), entry.getValue()), pickup);
        }

        for (var entry : droppedOff.entrySet()) {
            State.Dropoff dropoff = new State.Dropoff();

            dropoff.request = entry.getKey().toString();
            dropoff.vehicle = entry.getValue().toString();
            dropoff.ongoing = false;

            dropoffData.put(Pair.of(entry.getKey(), entry.getValue()), dropoff);
        }

        state.pickups.addAll(pickupData.values());
        state.dropoffs.addAll(dropoffData.values());

        pickedUp.clear();
        droppedOff.clear();

        return manager.performAssignment(state);
    }

    private List<String> performAutomaticRejection(double now) {
        Set<Id<Request>> automaticallyRejected = new HashSet<>();

        for (var entry : requestEntries.entrySet()) {
            if (entry.getValue().autoRejectable) {
                AcceptedDrtRequest request = requests.get(entry.getKey());

                if (now > request.getLatestStartTime()) {
                    // list of cleanup
                    automaticallyRejected.add(request.getId());

                    eventsManager.processEvent(new PassengerRequestRejectedEvent(now, mode, request.getId(),
                            requests.get(request.getId()).getPassengerIds(), "auto-reject"));

                    // remove from stop sequences, we just keep the empty stops
                    if (entry.getValue().pickupVehicleId != null) {
                        Schedule schedule = fleet.getVehicles().get(entry.getValue().pickupVehicleId).getSchedule();
                        for (Task task : schedule.getTasks()) {
                            if (task instanceof DrtStopTask stopTask) {
                                if (stopTask.getPickupRequests().containsKey(request.getId())) {
                                    stopTask.removePickupRequest(request.getId());
                                }
                            }
                        }
                    }

                    if (entry.getValue().dropoffVehicleId != null) {
                        Schedule schedule = fleet.getVehicles().get(entry.getValue().pickupVehicleId).getSchedule();
                        for (Task task : schedule.getTasks()) {
                            if (task instanceof DrtStopTask stopTask) {
                                if (stopTask.getDropoffRequests().containsKey(request.getId())) {
                                    stopTask.removeDropoffRequest(request.getId());
                                }
                            }
                        }
                    }
                }
            }
        }

        List<String> result = new LinkedList<>();

        for (Id<Request> requestId : automaticallyRejected) {
            requests.remove(requestId);
            requestEntries.remove(requestId);
            result.add(requestId.toString());
        }

        Collections.sort(result);
        return result;
    }

    @Override
    public void nextTask(DvrpVehicle vehicle) {
        scheduleTimingUpdater.updateBeforeNextTask(vehicle);
        vehicle.getSchedule().nextTask();
    }

    @Override
    public void handleEvent(PassengerPickedUpEvent event) {
        synchronized (pickedUp) {
            pickedUp.put(event.getRequestId(), event.getVehicleId());
            onboard.get(event.getVehicleId()).add(event.getRequestId());
        }

        synchronized (requests) {
            requestEntries.get(event.getRequestId()).autoRejectable = false;
        }

        synchronized (vehicleOccupancy) {
            int occupancy = vehicleOccupancy.compute(event.getVehicleId(), (id, val) -> {
                return val == null ? 1 : val + 1;
            });

            Verify.verify(occupancy <= (int) fleet.getVehicles().get(event.getVehicleId()).getCapacity().getElement(0),
                    "Capacity of vehicle " + event.getVehicleId() + " has been exceeded by picking up request "
                            + event.getRequestId());
        }
    }

    @Override
    public void handleEvent(PassengerDroppedOffEvent event) {
        synchronized (droppedOff) {
            droppedOff.put(event.getRequestId(), event.getVehicleId());
            onboard.get(event.getVehicleId()).remove(event.getRequestId());
        }

        synchronized (requests) {
            requests.remove(event.getRequestId());
            requestEntries.remove(event.getRequestId());
        }

        synchronized (vehicleOccupancy) {
            vehicleOccupancy.compute(event.getVehicleId(), (id, val) -> {
                return val - 1;
            });
        }
    }

    private class RequestEntry {
        Id<DvrpVehicle> pickupVehicleId;
        Id<DvrpVehicle> dropoffVehicleId;
        boolean scheduled = false;
        boolean autoRejectable = true;
    }

    private IdMap<Request, RequestEntry> requestEntries = new IdMap<>(Request.class);

    private IdMap<DvrpVehicle, Map<String, List<Task>>> taskTrackers = new IdMap<>(DvrpVehicle.class);

    private void trackTask(Id<DvrpVehicle> vehicleId, String stopId, Task task) {
        if (stopId != null) {
            taskTrackers //
                    .computeIfAbsent(vehicleId, id -> new HashMap<>()) //
                    .computeIfAbsent(stopId, id -> new LinkedList<>()) //
                    .add(task);
        }
    }

    private void clearUnplannedEmptySchedules(Assignment assignment) {
        Set<String> clear = new HashSet<>();

        for (var item : assignment.stops.entrySet()) {
            DvrpVehicle vehicle = fleet.getVehicles().get(Id.create(item.getKey(), DvrpVehicle.class));
            Schedule schedule = vehicle.getSchedule();

            if (!schedule.getStatus().equals(ScheduleStatus.STARTED)) {
                if (item.getValue().size() == 0) {
                    clear.add(item.getKey());
                }
            }
        }

        for (String vehicleId : clear) {
            assignment.stops.remove(vehicleId);
        }
    }

    private void implement(Assignment assignment, double now) {
        clearUnplannedEmptySchedules(assignment);

        // first, clear the schedules of vehicles that get things rearranged
        for (String vehicleId : assignment.stops.keySet()) {
            DvrpVehicle vehicle = fleet.getVehicles().get(Id.create(vehicleId, DvrpVehicle.class));
            Schedule schedule = vehicle.getSchedule();

            Verify.verify(schedule.getStatus().equals(ScheduleStatus.STARTED),
                    "Sent instructions for inactive vehicle " + vehicleId);

            Task currentTask = schedule.getCurrentTask();

            // book-keeping
            for (int i = currentTask.getTaskIdx() + 1; i < schedule.getTaskCount(); i++) {
                Task task = schedule.getTasks().get(i);

                if (task instanceof DrtStopTask stopTask) {
                    for (Id<Request> requestId : stopTask.getPickupRequests().keySet()) {
                        requestEntries.get(requestId).pickupVehicleId = null;
                    }

                    for (Id<Request> requestId : stopTask.getDropoffRequests().keySet()) {
                        requestEntries.get(requestId).dropoffVehicleId = null;
                    }
                }
            }

            // clearing
            while (currentTask != Schedules.getLastTask(schedule)) {
                schedule.removeLastTask();
            }

            if (currentTask instanceof DrtStayTask) {
                currentTask.setEndTime(now);
            }
        }

        // next, rejections
        for (String rawRequestId : assignment.rejections) {
            Id<Request> requestId = Id.create(rawRequestId, Request.class);

            Preconditions.checkState(requestEntries.containsKey(requestId),
                    "Rejected request " + requestId + " doesn't exist anymore. Already rejected or dropped off?");

            Preconditions.checkState(requestEntries.get(requestId).pickupVehicleId == null,
                    "Request " + requestId + " is rejected but it is still assigned to vehicle "
                            + requestEntries.get(requestId).pickupVehicleId + " (onboard?)");

            eventsManager.processEvent(new PassengerRequestRejectedEvent(now, mode, requestId,
                    requests.get(requestId).getPassengerIds(), "external"));

            requests.remove(requestId);
            requestEntries.remove(requestId);
        }

        // next, reconstruct the schedules
        taskTrackers.clear();

        for (var vehicleEntry : assignment.stops.entrySet()) {
            DvrpVehicle vehicle = fleet.getVehicles().get(Id.create(vehicleEntry.getKey(), DvrpVehicle.class));
            Schedule schedule = vehicle.getSchedule();
            Task currentTask = schedule.getCurrentTask();

            if (vehicleEntry.getValue().size() > 0) {
                for (int i = 0; i < vehicleEntry.getValue().size(); i++) {
                    var stop = vehicleEntry.getValue().get(i);
                    Link stopLink = network.getLinks().get(Id.createLinkId(stop.link));

                    // move to the next location
                    if (i == 0 && currentTask instanceof DriveTask driveTask) {
                        boolean needsDiversion = stop.route != null || driveTask.getPath().getToLink() != stopLink;

                        if (needsDiversion) {
                            // we need to divert the current drive
                            OnlineDriveTaskTracker tracker = (OnlineDriveTaskTracker) driveTask.getTaskTracker();

                            final VrpPathWithTravelData path;
                            if (stop.route == null) {
                                path = VrpPaths.calcAndCreatePathForDiversion(tracker.getDiversionPoint(), stopLink,
                                        router,
                                        travelTime);
                            } else {
                                Preconditions.checkArgument(stop.route.getFirst()
                                        .equals(tracker.getDiversionPoint().link.getId().toString()));
                                Preconditions.checkArgument(stop.route.getLast().equals(stop.link));
                                path = createPath(stop.route, tracker.getDiversionPoint().time);
                            }

                            tracker.divertPath(path);
                        }

                        // trackTask(vehicle.getId(), stop.id, currentTask);
                    } else if (currentTask instanceof StayTask stayTask && stayTask.getLink() != stopLink) {
                        // we need to add a new drive
                        final VrpPathWithTravelData path;
                        if (stop.route == null) {
                            path = VrpPaths.calcAndCreatePath(stayTask.getLink(), stopLink, currentTask.getEndTime(),
                                    router, travelTime);
                        } else {
                            Preconditions
                                    .checkArgument(stop.route.getFirst().equals(stayTask.getLink().getId().toString()));
                            Preconditions.checkArgument(stop.route.getLast().equals(stop.link));
                            path = createPath(stop.route, stayTask.getEndTime());
                        }

                        DriveTask driveTask = taskFactory.createDriveTask(vehicle, path, DrtDriveTask.TYPE);
                        schedule.addTask(driveTask);

                        currentTask = driveTask;
                        // trackTask(vehicle.getId(), stop.id, currentTask);
                    }

                    // insert a potential wait before the next stop
                    if (currentTask.getEndTime() < stop.earliestStartTime) {
                        double beginTime = currentTask.getEndTime();
                        double endTime = Math.max(beginTime + 1, stop.earliestStartTime);

                        DrtStayTask stayTask = taskFactory.createStayTask(vehicle, beginTime, endTime, stopLink);
                        schedule.addTask(stayTask);
                        currentTask = stayTask;

                        // trackTask(vehicle.getId(), stop.id, currentTask);
                    }

                    // insert the next stop
                    if (stop.pickup.size() > 0 || stop.dropoff.size() > 0) {
                        double beginTime = currentTask.getEndTime();

                        double stopDuration = 1.0; // at least one second, technically at least one time step

                        if (stop.pickup.size() > 0) {
                            stopDuration = Math.max(stopDuration, defaultPickupDuration);
                        }

                        if (stop.dropoff.size() > 0) {
                            stopDuration = Math.max(stopDuration, defaultDropoffDuration);
                        }

                        stopDuration = Math.max(stopDuration, stop.stopDuration);

                        double endTime = beginTime + stopDuration;

                        DrtStopTask stopTask = taskFactory.createStopTask(vehicle, beginTime, endTime, stopLink);

                        for (String pickupId : stop.pickup) {
                            AcceptedDrtRequest request = requests.get(Id.create(pickupId, Request.class));

                            Preconditions.checkNotNull(request,
                                    "Request " + pickupId + " is assigned for pickup to vehicle " + vehicle.getId()
                                            + " but does not exist anymore (rejeced? / done?)");

                            stopTask.addPickupRequest(request);

                            Preconditions.checkState(requestEntries.get(request.getId()).pickupVehicleId == null,
                                    "Request " + request.getId() + " is assigned for pickup to vehicle "
                                            + vehicle.getId()
                                            + " but is already assigned to vehicle "
                                            + requestEntries.get(request.getId()).pickupVehicleId);

                            requestEntries.get(request.getId()).pickupVehicleId = vehicle.getId();
                        }

                        for (String dropoffId : stop.dropoff) {
                            AcceptedDrtRequest request = requests.get(Id.create(dropoffId, Request.class));

                            Preconditions.checkNotNull(request,
                                    "Request " + dropoffId + " is assigned for pickup to vehicle " + vehicle.getId()
                                            + " but does not exist anymore (dropped off?)");

                            stopTask.addDropoffRequest(request);

                            Preconditions.checkState(requestEntries.get(request.getId()).dropoffVehicleId == null,
                                    "Request " + request.getId() + " is assigned for dropoff to vehicle "
                                            + vehicle.getId()
                                            + " but is already assigned to vehicle "
                                            + requestEntries.get(request.getId()).dropoffVehicleId);

                            requestEntries.get(request.getId()).dropoffVehicleId = vehicle.getId();
                        }

                        schedule.addTask(stopTask);
                        currentTask = stopTask;

                        trackTask(vehicle.getId(), stop.id, currentTask);
                    } else {
                        StayTask stayTask = taskFactory.createStayTask(vehicle, currentTask.getEndTime(),
                                currentTask.getEndTime() + 1.0, stopLink);

                        schedule.addTask(stayTask);

                        currentTask = stayTask;
                        trackTask(vehicle.getId(), stop.id, currentTask);
                    }
                }
            } else {
                // stop driving
                if (currentTask instanceof DriveTask driveTask) {
                    OnlineDriveTaskTracker tracker = (OnlineDriveTaskTracker) driveTask.getTaskTracker();

                    final VrpPathWithTravelData path = VrpPaths.calcAndCreatePathForDiversion(
                            tracker.getDiversionPoint(), tracker.getDiversionPoint().link, router,
                            travelTime);
                    tracker.divertPath(path);
                }
            }

            // make sure the schedule ends with an idle task
            if (currentTask.getEndTime() < vehicle.getServiceEndTime()) {
                if (currentTask instanceof DrtStayTask stayTask) {
                    stayTask.setEndTime(vehicle.getServiceEndTime());
                } else {
                    final Link currentLink;
                    if (currentTask instanceof StayTask stayTask) {
                        currentLink = stayTask.getLink();
                    } else {
                        currentLink = ((DriveTask) currentTask).getPath().getToLink();
                    }

                    StayTask stayTask = taskFactory.createStayTask(vehicle, currentTask.getEndTime(),
                            vehicle.getServiceEndTime(), currentLink);
                    schedule.addTask(stayTask);
                }
            }
        }

        // validation
        for (var item : requestEntries.entrySet()) {
            var entry = item.getValue();

            if (entry.pickupVehicleId != null && entry.dropoffVehicleId == null) {
                throw new IllegalStateException("Request " + item.getKey() + " is assigned for pickup to vehicle "
                        + entry.pickupVehicleId + " but has not dropoff assigned");
            }

            if (entry.pickupVehicleId == null && entry.dropoffVehicleId != null) {
                throw new IllegalStateException("Request " + item.getKey() + " is assigned for dropoff to vehicle "
                        + entry.dropoffVehicleId + " but has not pickup assigned");
            }

            if (entry.pickupVehicleId != entry.dropoffVehicleId) {
                throw new IllegalStateException("Request " + item.getKey() + " is assigned for pickup to vehicle "
                        + entry.pickupVehicleId + " and for dropoff to vehicle " + entry.dropoffVehicleId);
            }

            if (entry.pickupVehicleId != null && !entry.scheduled) {
                entry.scheduled = true;

                AcceptedDrtRequest request = requests.get(item.getKey());

                double pickupTime = 0.0;
                double dropoffTime = 0.0;

                DvrpVehicle vehicle = fleet.getVehicles().get(entry.pickupVehicleId);
                Schedule schedule = vehicle.getSchedule();

                for (Task task : schedule.getTasks().subList(schedule.getCurrentTask().getTaskIdx(),
                        schedule.getTaskCount())) {
                    if (task instanceof DrtStopTask stopTask) {
                        if (stopTask.getPickupRequests().containsKey(request.getId())) {
                            pickupTime = stopTask.getEndTime();
                        }

                        if (stopTask.getDropoffRequests().containsKey(request.getId())) {
                            dropoffTime = stopTask.getBeginTime();
                        }
                    }
                }

                eventsManager.processEvent(new PassengerRequestScheduledEvent(now, mode, request.getId(),
                        request.getPassengerIds(), entry.pickupVehicleId, pickupTime, dropoffTime));
            }
        }
    }

    private VrpPathWithTravelData createPath(List<String> rawRoute, double departureTime) {
        double routeTravelTime = 0.0;
        double enterTime = departureTime;

        Link[] links = new Link[rawRoute.size()];
        double[] travelTimes = new double[rawRoute.size()];

        for (int k = 0; k < rawRoute.size(); k++) {
            Link link = network.getLinks().get(Id.createLinkId(rawRoute.get(k)));
            links[k] = link;
            travelTimes[k] = travelTime.getLinkTravelTime(link, enterTime, null, null);
            routeTravelTime += travelTimes[k];
            enterTime += travelTimes[k];
        }

        return new VrpPathWithTravelDataImpl(departureTime, routeTravelTime, links,
                travelTimes);
    }
}
