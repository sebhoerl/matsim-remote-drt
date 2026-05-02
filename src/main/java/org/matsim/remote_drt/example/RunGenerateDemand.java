package org.matsim.remote_drt.example;

import java.io.IOException;
import java.util.Collections;
import java.util.Random;

import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Population;
import org.matsim.core.config.CommandLine;
import org.matsim.core.config.CommandLine.ConfigurationException;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.algorithms.TransportModeNetworkFilter;
import org.matsim.core.network.io.MatsimNetworkReader;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.population.io.PopulationWriter;

import com.fasterxml.jackson.core.JsonGenerationException;
import com.fasterxml.jackson.databind.JsonMappingException;

public class RunGenerateDemand {
	static public void main(String[] args) throws ConfigurationException, JsonGenerationException, JsonMappingException,
			IOException, InterruptedException {
		CommandLine cmd = new CommandLine.Builder(args) //
				.requireOptions("network-path", "output-path", "requests") //
				.allowOptions("seed", "minimum-distance") //
				.build();

		int seed = cmd.getOption("seed").map(Integer::parseInt).orElse(0);
		int requests = Integer.parseInt(cmd.getOptionStrict("requests"));
		double minimumDistance = cmd.getOption("minimum-distance").map(Double::parseDouble).orElse(1000.0);

		Network fullNetwork = NetworkUtils.createNetwork();
		new MatsimNetworkReader(fullNetwork).readFile(cmd.getOptionStrict("network-path"));

		Network network = NetworkUtils.createNetwork();
		new TransportModeNetworkFilter(fullNetwork).filter(network, Collections.singleton("car"));

		Config config = ConfigUtils.createConfig();
		Population population = PopulationUtils.createPopulation(config);

		Random random = new Random(seed);

		new DemandGenerator(network, random, minimumDistance).run(population, requests);
		new PopulationWriter(population).write(cmd.getOptionStrict("output-path"));
	}
}
