#/bin/bash
set -e

##
## Runs a benchmark between the example algorithms.
## - Should be called in the root directory, i.e. bash scripts/run_batch.sh
##
## - Results in batch/output
## - Cases that have been run already will not be repeated. Clean using rm -r batch/output
##
## - Analysis using analysis.py
##

if [ ! -f README.md ]; then
    echo "Script should be called from the main directory."
    exit 1
fi

seeds="1000 2000 3000 4000 5000"
fleet_sizes="10 15 20 25 50 100"
request_numbers="500 1000 1500 2000 3000 4000 5000"
algorithms="02_euclidean 04_insertion 05_qlearning"
learning_iterations=25

mkdir -p batch/output
mkdir -p batch/data

# Generate fleets
for seed in ${seeds}; do
    for fleet_size in ${fleet_sizes}; do
        for iteration in $(seq ${learning_iterations}); do
            java -cp target/remote-drt-*-SNAPSHOT.jar \
                org.matsim.remote_drt.example.RunGenerateFleet \
                --network-path scenario/paris.xml.zst \
                --output-path batch/data/fleet_fs${fleet_size}_seed${seed}.${iteration}.xml \
                --fleet-size ${fleet_size} --seats 4 --seed $(( ${seed} + ${iteration} ))
        done
    done
done

# Generate demand
cd examples

for seed in ${seeds}; do
    for requests in ${request_numbers}; do
        for iteration in $(seq ${learning_iterations}); do
            uv run demand/generate.py --attractors-path demand/attractors.gpkg \
                --output-path ../batch/data/demand_req${requests}_seed${seed}.${iteration}.xml \
                --requests ${requests} --seed $(( ${seed} + ${iteration} ))
        done
    done
done

cd ..

# Run the simulations
for seed in ${seeds}; do
    for requests in ${request_numbers}; do
        for fleet_size in ${fleet_sizes}; do
            for dispatcher in ${algorithms}; do
                output_path="batch/output/d${dispatcher}_req${requests}_fs${fleet_size}_seed${seed}"

                iterations=1
                if [[ ${dispatcher} == *"learn"* ]]; then
                    iterations=${learning_iterations}
                fi

                if [ ! -f "${output_path}/output_events.xml.gz" ]; then
                    rm -rf ${output_path}

                    java -cp target/remote-drt-*-SNAPSHOT.jar \
                        -DpreferLocalDtds=true \
                        org.matsim.remote_drt.example.RunSimulation \
                        --network-path scenario/paris.xml.zst \
                        --demand-path batch/data/demand_req${requests}_seed${seed}.__it__.xml \
                        --fleet-path batch/data/fleet_fs${fleet_size}_seed${seed}.__it__.xml \
                        --output-path ${output_path} \
                        --iterations ${iterations} \
                        --use-automatic-rejection true &

                    while [ ! -f ${output_path}/tmp/remote_port_drt ]; do
                        echo "waiting ..."
                        sleep 1
                    done

                    cd examples
                    uv run ${dispatcher}_dispatcher.py $(cat ../${output_path}/tmp/remote_port_drt)
                    cd ..
                fi
            done
        done
    done
done


for fleet_size in ${fleet_sizes}; do
    java -cp target/remote-drt-*-SNAPSHOT.jar \
        org.matsim.remote_drt.example.RunGenerateFleet \
        --network-path scenario/paris.xml.zst \
        --output-path batch_output/fleet_${fleet_size}.xml \
        --fleet-size ${fleet_size} --seats 4

    for requests in ${request_numbers}; do
        cd examples
        uv run demand/generate.py --attractors-path demand/attractors.gpkg --output-path ../batch_output/demand_${requests}.xml --requests ${requests}
        cd ..

        for dispatcher in ${algorithms}; do
            output_path="batch_output/output/${dispatcher}_${requests}_${fleet_size}"
            
            if [ ! -f "${output_path}/output_events.xml.gz" ]; then
                rm -rf ${output_path}
                
                java -cp target/remote-drt-*-SNAPSHOT.jar \
                    -DpreferLocalDtds=true \
                    org.matsim.remote_drt.example.RunSimulation \
                    --network-path scenario/paris.xml.zst \
                    --demand-path batch_output/demand_${requests}.xml \
                    --fleet-path batch_output/fleet_${fleet_size}.xml \
                    --output-path ${output_path} \
                    --use-automatic-rejection true &

                while [ ! -f ${output_path}/tmp/remote_port_drt ]; do
                    echo "waiting ..."
                    sleep 1
                done

                cd examples
                uv run ${dispatcher}_dispatcher.py $(cat ../${output_path}/tmp/remote_port_drt)
                cd ..
            fi
        done
    done
done
