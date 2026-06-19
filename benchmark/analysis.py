# %%

import pandas as pd
from pathlib import Path
import plotly.express as px
import numpy as np
# %%

def parse_time(time):
    return np.dot(np.array(time.split(":")).astype(int), [3600, 60, 1])

df = []

for seed in (1000, 2000, 3000, 4000, 5000):
    for fleet_size in (10, 15, 20, 25, 50, 100):
        for requests in (500, 1000, 1500, 2000, 3000, 4000, 5000):
            for dispatcher in ("02_euclidean", "04_insertion", "05_qlearning"):
                path = Path("../benchmark/output/d{}_req{}_fs{}_seed{}".format(dispatcher, requests, fleet_size, seed))

                try:
                    df_partial = pd.read_csv(path / "drt_customer_stats_drt.csv", sep = ";")
                    df_legs = pd.read_csv(path / "ITERS/it.0/0.drt_legs_drt.csv", sep = ";")

                    delay_min = np.maximum(0.0, df_legs["arrivalTime"] - df_legs["latestArrivalTime"]) / 60.0
                    delay_min = delay_min.sum()

                    f_ok = df_legs["readyForPickupTime"] + df_legs["waitTime"] <= df_legs["latestDepartureTime"]
                    f_ok &= df_legs["arrivalTime"] <= df_legs["latestArrivalTime"]
                    ok = np.count_nonzero(f_ok)

                    rides = df_partial["rides"].values[0]
                    rejected = df_partial["rejections"].values[0]

                    with open(path / "stopwatch.csv") as f:
                        df_time = pd.read_csv(f, sep = ";")
                        start_time = parse_time(df_time["BEGIN iteration"].values[0])
                        end_time = parse_time(df_time["END iteration"].values[0])
                        runtime = end_time - start_time

                    df_partial = pd.read_csv(path / "drt_vehicle_stats_drt.csv", sep = ";")

                    total_distance = df_partial["totalDistance"].values[0]
                    passenger_distance = df_partial["totalPassengerDistanceTraveled"].values[0]

                    costs = 30.0 * fleet_size + 0.35 * rides + 0.1 * total_distance * 1e-3 + 0.5 * delay_min
                    revenue = passenger_distance * 1e-3 * 0.3 + 0.5 * rides
                    profit = revenue - costs

                    df.append({
                        "seed": seed,
                        "requests": requests,
                        "fleet_size": fleet_size,
                        "dispatcher": dispatcher,
                        "rides": rides,
                        "acceptance": ok / (rides + rejected),
                        "ok": ok,
                        "runtime": runtime,
                        "profit": profit
                    })

                except FileNotFoundError:
                    print("error", requests, fleet_size, dispatcher, seed)

df = pd.DataFrame.from_records(df)
df = df.sort_values(by = ["requests", "fleet_size"])
df
# %%

px.line(df, x = "requests", y = "acceptance", color = "dispatcher", facet_col = "fleet_size")
# %%

px.line(df, x = "requests", y = "runtime", color = "dispatcher", facet_col = "fleet_size")
# %%

px.line(df, x = "requests", y = "profit", color = "dispatcher", facet_col = "fleet_size")
