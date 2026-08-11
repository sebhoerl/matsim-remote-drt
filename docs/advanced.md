# Advanced topics

The following sections document some additional features that come with the repository.

## Advanced demand generation

The repository contains an advanced demand generation script, which can be called as follows:

```bash
cd examples
uv run demand/generate.py \
  --attractors-path demand/attractors.gpkg \
  --output-path /path/to/demand.xml \
  --seed 42
```

Run `uv run demand/generate.py --help` for additional options. The script will make use of a shape file with attractor points. It will then assign an morning attraction level to each point and then modulate the value throghout the day such that attraction is lower in the afternoon peak. An emission rate is modulated in the same way, but inversely. This way, we generate a commuting patterns between the attractor points that ressemble real-world demand.

## Iterative simulations and variability

You can pass the `--iterations 10` parameter to the `RunSimulation` script. This will make the simulation run the given number of iterations. This can be useful if a learning-based algorithm is used.

Furthermore, the simulator enters a special mode if the placeholder `__it__` in the demand path or the fleet path. The simulator will replace the placeholder by the current iteration number and load the respective file adaptively. This way, one can, for instance, provide 100 different demand files and 100 different fleet configurations for 100 iterations:

```bash
java -cp target/remote-drt-*-SNAPSHOT.jar \
    org.matsim.remote_drt.example.RunSimulation \
    --network-path scenario/paris.xml.zst \
    --demand-path scenario/demand.__it__.xml \
    --fleet-path scenario/fleet.__it__.xml \
    --output-path scenario/output
```

This is then useful to confront learning-based algorithms with noise and changing demand configurations.
