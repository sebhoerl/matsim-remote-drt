package org.matsim.remote_drt.example.dynamic;

import java.net.URL;

import org.matsim.api.core.v01.IdSet;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigGroup;
import org.matsim.core.controler.PrepareForSim;
import org.matsim.core.population.io.PopulationReader;

public class DynamicDemand {
    private final Scenario scenario;
    private final String path;
    private final Config config;
    private final PrepareForSim prepare;

    public DynamicDemand(Config config, String path, Scenario scenario, PrepareForSim prepare) {
        this.config = config;
        this.path = path;
        this.scenario = scenario;
        this.prepare = prepare;
    }

    public void updateDemand(int iteration) {
        // clean population
        IdSet<Person> all = new IdSet<>(Person.class);
        all.addAll(scenario.getPopulation().getPersons().keySet());
        all.forEach(scenario.getPopulation()::removePerson);

        // load new population
        String iterationPath = path.replace("__it__", String.valueOf(iteration));
        URL iterationURL = ConfigGroup.getInputFileURL(config.getContext(), iterationPath);
        new PopulationReader(scenario).readURL(iterationURL);

        // prepare population
        prepare.run();
    }
}
