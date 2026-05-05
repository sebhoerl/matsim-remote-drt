# %%%

import numpy as np
import geopandas as gpd
import pandas as pd
import shapely.geometry as sgeo
from pathlib import Path
import typer

"""
This script is an example of how to generate MATSim demand based on some simple inputs. We 
use a file of attractors (geographic points) and then assign them some attraction value. Points
that have a high attraction will emit many trips in the morning and few in the evening. The
destinations will be distributed with the opposite weight. In terms of departure times, we
define two peak hours around which we sample from a normal distribution.
"""

# %%%

def main(attractors_path: Path = typer.Option(), output_path: Path = typer.Option(), requests: int = typer.Option(1000)):
    random_seed = 42
    spatial_sigma = 800 # m

    peaks = [
        { "mean": 8.0, "std": 2.0, "weight": 0.5 },
        { "mean": 17.0, "std": 2.5, "weight": 0.5 }
    ]

    spatial_interpolation = (8.0, 17.0)

    # %% Preparation

    # rng initialization
    random = np.random.default_rng(random_seed)

    # load attractors for structured demand
    df_attractors = gpd.read_file(attractors_path)

    # %% Departure times

    # sample a peak for each request
    peak_probability = np.array([peak["weight"] for peak in peaks])
    peak_probability /= np.sum(peak_probability)

    peak_selection = random.choice(len(peaks), requests, p = peak_probability)

    # sample a departure time for each request
    departure_times = np.zeros((requests,))

    for peak_index, peak in enumerate(peaks):
        mask = peak_selection == peak_index

        if np.any(mask):
            departure_times[mask] = random.normal(
                peak["mean"] * 3600.0, peak["std"] * 3600.0, np.count_nonzero(mask))

    # %% Sample origin and destination zones

    # sample an attraction value per zone (morning, in the evening attraction is inverse)
    attraction = random.random(size = len(df_attractors))

    # calculate an interpolation factor between morning and evening peak based on departure time
    interpolation = departure_times - spatial_interpolation[0] * 3600.0
    interpolation /= (spatial_interpolation[1] - spatial_interpolation[0]) * 3600.0
    interpolation = np.maximum(0.0, interpolation)
    interpolation = np.minimum(1.0, interpolation)

    # interpolate origin attraction depending on time of day
    origin_attraction = interpolation[:, np.newaxis] * attraction[np.newaxis, :] 
    origin_attraction += (1.0 - interpolation)[:, np.newaxis] * (1.0 - attraction[np.newaxis, :])

    # destination attraction is inverse
    destination_attraction = 1.0 - origin_attraction

    # norm to obtain a probability
    origin_attraction = origin_attraction / np.sum(origin_attraction, axis = 1)[:, np.newaxis]
    destination_attraction = destination_attraction / np.sum(destination_attraction, axis = 1)[:, np.newaxis]

    # sample origin and destination zones
    origin_zones = [random.choice(len(attraction), p = p) for p in origin_attraction]
    destination_zones = [random.choice(len(attraction), p = p) for p in destination_attraction]

    # %% Loation sampling

    # obtain centers for each attractor
    centers = np.vstack([
        df_attractors["geometry"].centroid.x,
        df_attractors["geometry"].centroid.y
    ]).T

    # initialize locations
    origin_locations = np.array([centers[k] for k in origin_zones])
    destination_locations = np.array([centers[k] for k in destination_zones])

    # sample a normal distribution around the centers
    origin_locations += random.standard_normal(origin_locations.shape) * spatial_sigma
    destination_locations += random.standard_normal(destination_locations.shape) * spatial_sigma

    # %% Transform to dataframe

    df_demand = gpd.GeoDataFrame(pd.DataFrame({
        "departure_time": departure_times,
        "origin_zone": origin_zones,
        "destination_zone": destination_zones,
        "geometry": [sgeo.LineString(od) for od in zip(
            gpd.points_from_xy(*origin_locations.T),
            gpd.points_from_xy(*destination_locations.T),
        )]
    }), crs = df_attractors.crs)

    df_demand["departure_time"] = df_demand["departure_time"].astype(int)

    # %% Write as XML

    demand = []
    demand.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
    demand.append("<!DOCTYPE population SYSTEM \"http://www.matsim.org/files/dtd/population_v6.dtd\">")
    demand.append("<population>")

    format_time = lambda t: "{:02d}:{:02d}:{:02d}:".format(
        t // 3600,
        (t % 3600) // 60,
        t % 60
    )

    for index, row in df_demand.iterrows():
        origin = row["geometry"].coords[0]
        destination = row["geometry"].coords[1]

        demand.append("  <person id=\"request:{}\">".format(index))
        demand.append("    <plan>")
        demand.append("      <activity type=\"generic\" x=\"{}\" y=\"{}\" end_time=\"{}\" />".format(origin[0], origin[1], format_time(row["departure_time"])))
        demand.append("      <leg mode=\"drt\" />")
        demand.append("      <activity type=\"generic\" x=\"{}\" y=\"{}\" />".format(destination[0], destination[1]))
        demand.append("    </plan>")
        demand.append("  </person>")

    demand.append("</population>")

    with open(output_path, "w+") as f:
        f.write("\n".join(demand))

if __name__ == "__main__":
    typer.run(main)
