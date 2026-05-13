# %%

import pandas as pd
from pathlib import Path
import plotly.express as px
import numpy as np

# %%

def parse_time(time):
    return np.dot(np.array(time.split(":")).astype(int), [3600, 60, 1])

df = []

for fleet_size in (10, 25, 50, 100):
    for requests in (500, 1000, 2000, 3000, 4000, 5000):
        for dispatcher in ("02_euclidean", "04_insertion"):
            path = Path("../scenario/batch/output/{}_{}_{}".format(dispatcher, requests, fleet_size))

            try:
                df_partial = pd.read_csv(path / "drt_customer_stats_drt.csv", sep = ";")
                df_legs = pd.read_csv(path / "ITERS/it.0/0.drt_legs_drt.csv", sep = ";")

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

                df.append({
                    "requests": requests,
                    "fleet_size": fleet_size,
                    "dispatcher": dispatcher,
                    "rides": rides,
                    "acceptance": ok / (rides + rejected),
                    "ok": ok,
                    "runtime": runtime
                })

            except FileNotFoundError:
                print("error", requests, fleet_size, dispatcher)

df = pd.DataFrame.from_records(df)
df

# %%

px.line(df, x = "requests", y = "acceptance", color = "dispatcher", facet_col = "fleet_size")

# %%

px.line(df, x = "requests", y = "runtime", color = "dispatcher", facet_col = "fleet_size")

