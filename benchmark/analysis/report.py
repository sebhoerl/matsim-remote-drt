# %%

import pandas as pd
import plotly.express as px

# %%

df = pd.read_parquet("analysis.parquet")

df = df.groupby([
    "requests", "fleet_size", 
    "dispatcher", "iteration"
])[["mean_wait_time"]].mean().reset_index()

df = df[
    (df["iteration"].eq(0) & df["dispatcher"].ne("05_qlearning")) |
    (df["iteration"].eq(24) & df["dispatcher"].eq("05_qlearning"))
]

px.line(df, 
    x = "requests", y = "mean_wait_time", 
    color = "dispatcher", facet_col = "fleet_size")

# %%

df = pd.read_parquet("analysis.parquet")

df = df.groupby([
    "requests", "fleet_size", 
    "dispatcher", "iteration"
])[["mean_wait_time"]].mean().reset_index()

df = df[df["dispatcher"] == "05_qlearning"]

px.line(df, 
    x = "iteration", y = "mean_wait_time", 
    color = "requests", facet_col = "fleet_size")
