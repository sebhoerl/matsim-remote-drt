# %%

import pandas as pd
import plotly.express as px

# %%

for field in ("rejection_rate", "mean_wait_time"):
    df = pd.read_parquet("analysis.parquet")

    df = df.groupby([
        "requests", "fleet_size", 
        "dispatcher", "iteration"
    ])[[field]].mean().reset_index()

    df = df[
        (df["iteration"].eq(0) & df["dispatcher"].ne("05_qlearning")) |
        (df["iteration"].eq(24) & df["dispatcher"].eq("05_qlearning"))
    ]

    figure = px.line(df, 
        x = "requests", y = field, 
        color = "dispatcher", facet_col = "fleet_size")

    figure.write_image("{}.pdf".format(field),
        width = 700, height = 300, scale = 2.0)

# %%

df = pd.read_parquet("analysis.parquet")

df = df.groupby([
    "requests", "fleet_size", 
    "dispatcher", "iteration"
])[["rejection_rate"]].mean().reset_index()

df = df[df["dispatcher"] == "05_qlearning"]

figure = px.line(df, 
    x = "iteration", y = "rejection_rate", 
    color = "requests", facet_col = "fleet_size")

figure.write_image("qlearning.pdf",
    width = 700, height = 300, scale = 2.0)
