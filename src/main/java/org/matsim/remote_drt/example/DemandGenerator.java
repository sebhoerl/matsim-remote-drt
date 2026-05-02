package org.matsim.remote_drt.example;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.Population;
import org.matsim.api.core.v01.population.PopulationFactory;
import org.matsim.core.utils.geometry.CoordUtils;

public class DemandGenerator {
    public static final String GENERATOR_DISTANCE_ATTRIBUTE = "generator:minimum_distance";
    public static final String GENERATOR_REQUESTS_ATTRIBUTE = "generator:requests";

    private static final String ACTIVITY_TYPE = "generic";
    private static final String MODE = "drt";
    private static final String REQUEST_PREFIX = "request:";

    private final Network network;
    private final Random random;
    private final double minimumDistance;

    public DemandGenerator(Network network, Random random, double minimumDistance) {
        this.network = network;
        this.random = random;
        this.minimumDistance = minimumDistance;
    }

    public void run(Population population, int requests) {
        PopulationFactory factory = population.getFactory();

        List<Id<Link>> linkIds = new LinkedList<>(network.getLinks().keySet());
        Collections.sort(linkIds);

        Random networkRandom = new Random(0);

        List<Double> originCDF = new LinkedList<>();
        double originTotal = 0.0;

        List<Double> destinationCDF = new LinkedList<>();
        double destinationTotal = 0.0;

        for (int k = 0; k < linkIds.size(); k++) {
            double exponent = 2.0; // make it more spikey

            originTotal += Math.pow(networkRandom.nextDouble(), exponent);
            destinationTotal += Math.pow(networkRandom.nextDouble(), exponent);

            originCDF.add(originTotal);
            destinationCDF.add(destinationTotal);
        }

        for (int k = 0; k < linkIds.size(); k++) {
            originCDF.set(k, originCDF.get(k) / originTotal);
            destinationCDF.set(k, destinationCDF.get(k) / destinationTotal);
        }

        for (int i = 0; i < requests; i++) {
            Id<Link> originLinkId;
            Id<Link> destinationLinkId;
            double distance = 0.0;

            do {
                double originU = random.nextDouble();
                int originIndex = (int) originCDF.stream().filter(cdf -> originU > cdf).count();

                double destinationU = random.nextDouble();
                int destinationIndex = (int) originCDF.stream().filter(cdf -> destinationU > cdf).count();

                originLinkId = linkIds.get(originIndex);
                destinationLinkId = linkIds.get(destinationIndex);

                Link originLink = network.getLinks().get(originLinkId);
                Link destinationLink = network.getLinks().get(destinationLinkId);

                distance = CoordUtils.calcEuclideanDistance(originLink.getCoord(), destinationLink.getCoord());
            } while (distance < minimumDistance);

            double departureTime1 = random.nextGaussian(8.0 * 3600.0, 2.0 * 3600.0);
            double departureTime2 = random.nextGaussian(17.0 * 3600.0, 2.0 * 3600.0);
            double departureTime = random.nextBoolean() ? departureTime1 : departureTime2;

            Person person = factory.createPerson(Id.createPersonId(REQUEST_PREFIX + i));
            population.addPerson(person);

            Plan plan = factory.createPlan();
            person.addPlan(plan);

            Activity originActivity = factory.createActivityFromLinkId(ACTIVITY_TYPE, originLinkId);
            originActivity.setEndTime(departureTime);
            plan.addActivity(originActivity);

            Leg leg = factory.createLeg(MODE);
            plan.addLeg(leg);

            Activity destinationActivity = factory.createActivityFromLinkId(ACTIVITY_TYPE, destinationLinkId);
            plan.addActivity(destinationActivity);
        }

        population.getAttributes().putAttribute(GENERATOR_DISTANCE_ATTRIBUTE, minimumDistance);
        population.getAttributes().putAttribute(GENERATOR_REQUESTS_ATTRIBUTE, requests);
    }
}
