#/bin/bash
set -e

if [ ! -f analysis.py ]; then
    echo "Script should be called from inside the analysis directory."
    exit 1
fi

sbatch --partition cpu --mem 12g --mincpus 1 --time 12:00:00 \
    --job-name remote-dispatch-analysis \
    --qos normal_medium --partition cpu-medium \
    --output slurm.output.log \
    --error slurm.error.log \
    mamba run --live-stream -n dispatch \
    uv run analysis.py
