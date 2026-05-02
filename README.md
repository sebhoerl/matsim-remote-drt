# MATSim Remote DRT

This repository contains the remote dispatching interface for MATSim's DRT contribution. It allows to perfom on-demand mobility simulation using MATSim while performing fleet dispatching from an external script or algorithm.

The interface is based on **ZeroMQ** for communication. All messages are exchanged in *json* format.

See the [getting started](docs/getting_started.md) guide on how to run a simple Python dispatcher (with nothing else than the present repository).

See [examples of increasing complexity](examples) implemented in Python:
- A simple random assignment dispatcher to introduce the framework
- A *Bipartite Matching* dispatcher based on Euclidean distances, including rejections
- A *Roaming dispatcher* showing how network data can be used with `networkx` and request pooling
- An *Insertion Heuristic* similar to the DRT algorithm combined with a Gaussian Mixture Model for relocation
- A *Q-learning* algorithm that iteratively learns optimal vehicle behavior

See the **[full documentation of the messaging interface](docs/schema.md)**. Beyond fleet dispatching it provides additional services such as routing through MATSim, generation of travel time matrices and obtaining the network topology.

## Using the interface in MATSim

An example implelmentation of how to integrate the interface can be found in `org.matsim.remote_drt.example.RunSimulation` in this repository. In essence, it boils down to adding these two lines to your run script:

```java
String mode = "drt"; // name of the mode (or from DrtConfigGroup)

// create modal parameters
RemoteDrtModeParameters parameters = new RemoteDrtModeParameters();
parameters.setMode(drtConfig.getMode());

// will choose a port randomly
// written to /output/tmp/remote_port_drt
parameters.setPort(0);

// set up global remote config group and add mode parameters
RemoteDrtConfigGroup remoteConfig = new RemoteDrtConfigGroup();
config.addModule(remoteConfig);
remoteConfig.addModeParameters(parameters);

// add functionality to controller
controller.addOverridingModule(new RemoteDrtModule());
```

Note that the used port is always written to the `tmp/remote_port_{mode}` file in the MATSim output directory. You can also entirely configure remote dispatching via `config.xml`:

```java
ConfigUtils.loadConfig("...", ..., new RemoteDrtConfigGroup());
/// ...
controller.addOverridingModule(new RemoteDrtModule());
```
