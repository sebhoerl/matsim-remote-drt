#/bin/bash
set -e

##
## Runs a benchmark between the example algorithms.
## - Should be called in the root directory, i.e. bash scripts/run_batch.sh
##
## - Results in benchmark/output
## - Cases that have been run already will not be repeated. Clean using rm -r benchmark/output
##
## - Analysis using analysis.py
##

if [ ! -f README.md ]; then
    echo "Script should be called from the main directory."
    exit 1
fi

mvn -Pstandalone clean package

cd examples
uv sync
cd ..

learning_iterations=25

for seed in 1000 2000 3000 4000 5000; do
    for fleet_size in 10 15 20 25 50 100; do
        for requests in 500 100 1500 2000 3000 4000 5000; do
            for dispatcher in 02_euclidean 04_insertion 05_qlearning; do
                iterations=1

                if [[ ${dispatcher} == *"learn"* ]]; then
                    iterations=${learning_iterations}
                fi

                name="fs${fleet_size}_req${requests}_disp${dispatcher}_it${iterations}_seed${seed}"
                output_path="$(realpath benchmark)/output/${name}"

                if [ ! -f ${output_path}/simulation/output_events.xml.gz ]; then
                    bash benchmark/simulation.sh \
                        ${fleet_size} ${requests} ${dispatcher} ${iterations} ${seed}
                fi
            done
        done
    done
done
