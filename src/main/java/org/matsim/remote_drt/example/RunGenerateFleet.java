package org.matsim.remote_drt.example;

import java.io.IOException;
import java.util.Collections;
import java.util.Random;

import org.matsim.api.core.v01.network.Network;
import org.matsim.contrib.dvrp.fleet.FleetSpecification;
import org.matsim.contrib.dvrp.fleet.FleetWriter;
import org.matsim.contrib.dvrp.load.IntegerLoadType;
import org.matsim.core.config.CommandLine;
import org.matsim.core.config.CommandLine.ConfigurationException;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.algorithms.TransportModeNetworkFilter;
import org.matsim.core.network.io.MatsimNetworkReader;

import com.fasterxml.jackson.core.JsonGenerationException;
import com.fasterxml.jackson.databind.JsonMappingException;

public class RunGenerateFleet {
	static public void main(String[] args) throws ConfigurationException, JsonGenerationException, JsonMappingException,
			IOException, InterruptedException {
		CommandLine cmd = new CommandLine.Builder(args) //
				.requireOptions("network-path", "output-path", "fleet-size", "seats") //
				.allowOptions("seed") //
				.build();

		int seed = cmd.getOption("seed").map(Integer::parseInt).orElse(0);
		int fleetSize = Integer.parseInt(cmd.getOptionStrict("fleet-size"));
		int seats = Integer.parseInt(cmd.getOptionStrict("seats"));

		Network fullNetwork = NetworkUtils.createNetwork();
		new MatsimNetworkReader(fullNetwork).readFile(cmd.getOptionStrict("network-path"));

		Network network = NetworkUtils.createNetwork();
		new TransportModeNetworkFilter(fullNetwork).filter(network, Collections.singleton("car"));

		Random random = new Random(seed);

		FleetSpecification fleet = new FleetGenerator(network, random).run(fleetSize, seats);

		new FleetWriter(fleet.getVehicleSpecifications().values().stream(), new IntegerLoadType("passengers"))
				.write(cmd.getOptionStrict("output-path"));
	}
}
