# %%

import pandas as pd
from pathlib import Path
import numpy as np

from itertools import product
import tqdm

# %%

def parse_time(time):
    return np.dot(np.array(time.split(":")).astype(int), [3600, 60, 1])

df = []

tasks = product(
    (1000, 2000, 3000, 4000, 5000), # seed
    (10, 15, 20, 25, 50, 100), # fleet size
    (500, 1000, 1500, 2000, 3000, 4000, 5000), # requests
    ("02_euclidean", "04_insertion", "05_qlearning") # dispatcher
)

total = 5 * 6 * 7 * 3

for seed, fleet_size, requests, dispatcher in tqdm(tasks, total = total):
    iterations = 1

    if dispatcher == "05_qlearning":
        iterations = 25

    path = Path("../output/fs{}_req{}_disp{}_it{}_seed{}/simulation".format(fleet_size, requests, dispatcher, iterations, seed))

    for iteration in range(iterations):
        try:
            df_customer = pd.read_csv(path / "drt_customer_stats_drt.csv", sep = ";")
            df_vehicle = pd.read_csv(path / "drt_vehicle_stats_drt.csv", sep = ";")
            df_legs = pd.read_csv(path / "ITERS/it.{}/{}.drt_legs_drt.csv".format(iteration, iteration), sep = ";")

            wait_time = df_legs["waitTime"]
            departure_delay = np.maximum(0.0, df_legs["readyForPickupTime"] + df_legs["waitTime"] - df_legs["latestDepartureTime"])
            arrival_delay = np.maximum(0.0, df_legs["arrivalTime"] - df_legs["latestArrivalTime"])

            rides = df_customer["rides"].values[iteration]
            rejected = df_customer["rejections"].values[iteration]
            rejection_rate = rejected / (rides + rejected)

            vehicle_distance = df_vehicle["totalDistance"].values[iteration]
            empty_distance = df_vehicle["totalEmptyDistance"].values[iteration]
            passenger_distance = df_vehicle["totalPassengerDistanceTraveled"].values[iteration]
            empty_distance_share = empty_distance / vehicle_distance

            vehicle_costs = 30.0 * fleet_size
            rides_costs = 0.35 * rides
            distance_costs = 0.1 * vehicle_distance * 1e-3
            discount_costs = 0.5 * np.sum(arrival_delay) / 60.0
            total_costs = vehicle_costs + rides_costs + distance_costs + discount_costs

            distance_revenue = 0.3 * np.sum(passenger_distance) * 1e-3
            base_fare_revenue = 0.5 * rides
            total_revenue = distance_revenue + base_fare_revenue

            profit = total_revenue - total_costs

            df.append({
                "seed": seed, "fleet_size": fleet_size,
                "requests": requests, "dispatcher": dispatcher,
                "iteration": iteration,

                "mean_wait_time": np.mean(wait_time),
                "median_wait_time": np.median(wait_time),
                "q90_wait_time": np.percentile(wait_time, 90),

                "mean_departure_delay": np.mean(departure_delay),
                "median_departure_delay": np.median(departure_delay),
                "q90_departure_delay": np.percentile(departure_delay, 90),

                "mean_arrival_delay": np.mean(arrival_delay),
                "median_arrival_delay": np.median(arrival_delay),
                "q90_arrival_delay": np.percentile(arrival_delay, 90),

                "rejection_rate": rejection_rate,

                "vehicle_distance": vehicle_distance,
                "passenger_distance": passenger_distance,
                "empty_distance_share": empty_distance_share,

                "costs": total_costs,
                "revenue": total_revenue,
                "profit": profit
            })

        except FileNotFoundError:
            print("Missing", fleet_size, requests, dispatcher, iterations, iteration, seed)

df = pd.DataFrame.from_records(df)
df.to_parquet("analysis.parquet")
