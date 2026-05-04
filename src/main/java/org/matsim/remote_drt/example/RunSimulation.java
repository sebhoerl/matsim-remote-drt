package org.matsim.remote_drt.example;

import java.io.IOException;

import org.matsim.api.core.v01.Scenario;
import org.matsim.contrib.common.zones.systems.grid.square.SquareGridZoneSystemParams;
import org.matsim.contrib.drt.optimizer.constraints.DrtOptimizationConstraintsSetImpl;
import org.matsim.contrib.drt.optimizer.insertion.DrtInsertionSearchParams;
import org.matsim.contrib.drt.optimizer.insertion.extensive.ExtensiveInsertionSearchParams;
import org.matsim.contrib.drt.routing.DrtRoute;
import org.matsim.contrib.drt.routing.DrtRouteFactory;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.contrib.drt.run.DrtConfigs;
import org.matsim.contrib.drt.run.MultiModeDrtConfigGroup;
import org.matsim.contrib.drt.run.MultiModeDrtModule;
import org.matsim.contrib.dvrp.run.AbstractDvrpModeModule;
import org.matsim.contrib.dvrp.run.DvrpConfigGroup;
import org.matsim.contrib.dvrp.run.DvrpModule;
import org.matsim.contrib.dvrp.run.DvrpQSimComponents;
import org.matsim.contrib.dvrp.trafficmonitoring.QSimFreeSpeedTravelTime;
import org.matsim.core.config.CommandLine;
import org.matsim.core.config.CommandLine.ConfigurationException;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.QSimConfigGroup.EndtimeInterpretation;
import org.matsim.core.config.groups.QSimConfigGroup.StarttimeInterpretation;
import org.matsim.core.config.groups.ReplanningConfigGroup.StrategySettings;
import org.matsim.core.config.groups.ScoringConfigGroup.ActivityParams;
import org.matsim.core.config.groups.ScoringConfigGroup.ModeParams;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.Controller;
import org.matsim.core.controler.OutputDirectoryHierarchy.OverwriteFileSetting;
import org.matsim.core.replanning.strategies.DefaultPlanStrategiesModule.DefaultSelector;
import org.matsim.core.router.util.TravelTime;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.remote_drt.RemoteDrtConfigGroup;
import org.matsim.remote_drt.RemoteDrtModeParameters;
import org.matsim.remote_drt.RemoteDrtModule;

import com.fasterxml.jackson.core.JsonGenerationException;
import com.fasterxml.jackson.databind.JsonMappingException;

public class RunSimulation {
	static public void main(String[] args) throws ConfigurationException, JsonGenerationException, JsonMappingException,
			IOException, InterruptedException {
		CommandLine cmd = new CommandLine.Builder(args) //
				.requireOptions("demand-path", "fleet-path", "network-path", "output-path") //
				.allowOptions("threads", "port", "update-demand", "iterations", "use-automatic-rejection") //
				.build();

		// prepare configuration
		Config config = ConfigUtils.createConfig(new MultiModeDrtConfigGroup(),
				new DvrpConfigGroup());

		// general configuration
		config.controller().setOverwriteFileSetting(OverwriteFileSetting.deleteDirectoryIfExists);

		int iterations = cmd.getOption("iterations").map(Integer::parseInt).orElse(1);
		config.controller().setLastIteration(iterations - 1);

		int threads = cmd.getOption("threads").map(Integer::parseInt)
				.orElse(Runtime.getRuntime().availableProcessors());
		config.global().setNumberOfThreads(threads);
		config.qsim().setNumberOfThreads(threads);

		// set up input paths
		config.plans().setInputFile(cmd.getOptionStrict("demand-path"));
		config.controller().setOutputDirectory(cmd.getOptionStrict("output-path"));
		config.network().setInputFile(cmd.getOptionStrict("network-path"));

		// configuration of traffic simulation
		config.qsim().setFlowCapFactor(1e9);
		config.qsim().setStorageCapFactor(1e9);
		config.qsim().setSimStarttimeInterpretation(StarttimeInterpretation.onlyUseStarttime);
		config.qsim().setEndTime(24.0 * 3600.0);
		config.qsim().setSimEndtimeInterpretation(EndtimeInterpretation.onlyUseEndtime);

		// configuration of replanning logic (always keep same plan)
		StrategySettings strategy = new StrategySettings();
		strategy.setStrategyName(DefaultSelector.KeepLastSelected);
		strategy.setWeight(1.0);
		config.replanning().addStrategySettings(strategy);

		// configuration of scoring (necessary so simulation works)
		ModeParams drtParams = new ModeParams("drt");
		config.scoring().addModeParams(drtParams);

		ActivityParams activityParams = new ActivityParams("generic");
		activityParams.setScoringThisActivityAtAll(false);
		config.scoring().addActivityParams(activityParams);

		// configuration of DVRP
		DvrpConfigGroup dvrpConfig = DvrpConfigGroup.get(config);

		SquareGridZoneSystemParams grid = new SquareGridZoneSystemParams();
		grid.setCellSize(400);

		dvrpConfig.getTravelTimeMatrixParams().addParameterSet(grid);
		dvrpConfig.getTravelTimeMatrixParams().setMaxNeighborDistance(0);

		// configuration of DRT
		DrtConfigGroup drtConfig = new DrtConfigGroup();
		MultiModeDrtConfigGroup.get(config).addDrtConfigGroup(drtConfig);

		drtConfig.setVehiclesFile(cmd.getOptionStrict("fleet-path"));
		drtConfig.setStopDuration(30.0);

		DrtInsertionSearchParams searchParams = new ExtensiveInsertionSearchParams();
		drtConfig.addParameterSet(searchParams);

		DrtOptimizationConstraintsSetImpl constraints = drtConfig
				.addOrGetDrtOptimizationConstraintsParams()
				.addOrGetDefaultDrtOptimizationConstraintsSet();

		// service characteristics during routing
		constraints.setMaxWaitTime(300.0);
		constraints.setMaxTravelTimeAlpha(1.5);
		constraints.setMaxTravelTimeBeta(300.0);

		DrtConfigs.adjustMultiModeDrtConfig(MultiModeDrtConfigGroup.get(config), config.scoring(), config.routing());

		// prepare the scenario
		Scenario scenario = ScenarioUtils.createScenario(config);

		scenario.getPopulation()
				.getFactory()
				.getRouteFactories()
				.setRouteFactory(DrtRoute.class, new DrtRouteFactory());

		// load scenario data
		ScenarioUtils.loadScenario(scenario);

		// setup controller
		Controler controller = new Controler(scenario);
		controller.addOverridingModule(new DvrpModule());
		controller.addOverridingModule(new MultiModeDrtModule());
		controller.configureQSimComponents(DvrpQSimComponents.activateAllModes(MultiModeDrtConfigGroup.get(config)));
		setupFreespeedTravelTime(controller);

		// optionally add scenario updater
		setupScenarioUpdater(cmd, controller, drtConfig);

		// setup remote dispatcher
		int remotePort = cmd.getOption("port").map(Integer::parseInt).orElse(0);
		boolean useAutomaticRejection = cmd.getOption("use-automatic-rejection").map(Boolean::parseBoolean).orElse(false);

		RemoteDrtModeParameters parameters = new RemoteDrtModeParameters();
		parameters.setMode(drtConfig.getMode());
		parameters.setPort(remotePort);
		parameters.setUseAutomaticRejection(useAutomaticRejection);

		RemoteDrtConfigGroup remoteConfig = new RemoteDrtConfigGroup();
		config.addModule(remoteConfig);
		remoteConfig.addModeParameters(parameters);

		controller.addOverridingModule(new RemoteDrtModule());

		controller.run();
	}

	static private void setupScenarioUpdater(CommandLine cmd, Controller controller, DrtConfigGroup drtConfig) {
		boolean updateDemand = cmd.getOption("update-demand").map(Boolean::parseBoolean).orElse(false);
		if (updateDemand) {
			controller.addOverridingModule(new ScenarioUpdaterModule(drtConfig));
		}
	}

	static private void setupFreespeedTravelTime(Controller controller) {
		controller.addOverridingModule(new AbstractDvrpModeModule("drt") {
			@Override
			public void install() {
				bindModal(TravelTime.class).toInstance(new QSimFreeSpeedTravelTime(controller.getConfig().qsim()));
			}
		});
	}
}
