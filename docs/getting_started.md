# Getting started

This repsository can run a remote dispatching simulation out-of-the-box. 

## Building the simulation

To run create a runnable *jar* that contains the simulation, build the present Maven package with the *standalone* profile:

```bash
# generates target/remote-drt-{version}-SNAPSHOT.jar
mvn clean package -Pstandalone
```

Note that while here we will run everything from the *jar*, you can also just import the repository into an IDE like VSCode and run the scripts manually.

## Generating the simulation data

We already provide a MATSim road network for Paris in `scenario/network.xml.gz`. To generate some **demand** (a population with single-trip persons), run the following command:

```bash
# generates 1000 requests in scenario/demand.xml
java -cp target/remote-drt-*-SNAPSHOT.jar \
    org.matsim.remote_drt.example.RunGenerateDemand \
    --network-path scenario/paris.xml.zst \
    --output-path scenario/demand.xml \
    --requests 1000
```

Note that this is just an example script. It will distribute departures around two normally distributed peak periods during the day. Second, we need to generate a **fleet**:

```bash
# generates 10 vehicles requests in scenario/fleet.xml
java -cp target/remote-drt-*-SNAPSHOT.jar \
    org.matsim.remote_drt.example.RunGenerateFleet \
    --network-path scenario/paris.xml.zst \
    --output-path scenario/fleet.xml \
    --fleet-size 10 --seats 4
```

The vehicles will be randomly distributed in the network.

## Starting the simulation

You can now start the simulation. For that, make sure to open this in one command windows and to later start the dispatcher in another one, so both proceses can run in parallel:

```bash
# generates 1000 requests in scenario/demand.xml
java -cp target/remote-drt-*-SNAPSHOT.jar \
    org.matsim.remote_drt.example.RunSimulation \
    --network-path scenario/paris.xml.zst \
    --demand-path scenario/demand.xml \
    --fleet-path scenario/fleet.xml \
    --output-path scenario/output
```

If you want to listen for the dispatching client on a specific port, you can pass `--port 4524`. Otherwise, a port will be chosen randomly and written out:

```bash
# show the randomly chosen port
cat scenario/output/drt_remote_port
```

## Running a remote dispatcher

A couple of remote dispatcher examples written in Python are located in the `examples` directory. The directory also contains a `uv` environment with all the (few) Python dependencies, mainly `zeromq`. You can directly call one of the dispatchers by passing the correct **port** to the script:

```bash
uv run 01_random_dispatcher.py 49759
```

This starts a random dispatcher which randomly assigns pending requests to idle vehicles. Have a look at the code for a guide-through of the implementation. Check out the other example dispatchers for more advanced examples.

On Linux, you can directly pass the port via the command line:

```bash
uv run 01_random_dispatcher.py $(cat ../scenario/output/temp/remote_port_drt)
```

## Analysis

Of course you can now have a look at the standard outputs of MATSim/DRT to perform post-processing and analyze the performance of the dispatcher.

## Going further

The built-in test simulation has a couple of additional features:
- By passing `--iterations 20` you can let MATSim run multiple iterations using the same demand (this way your algorithm can learn).
- By passing `--update-demand true` the demand and the fleet will be regenerated after every iteration. This way you can introduce variability over multiple iterations.
