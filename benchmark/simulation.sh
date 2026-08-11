#/bin/bash
set -e

if [ ! -f README.md ]; then
    echo "Script should be called from the main directory."
    exit 1
fi

fleet_size="${1}"
requests="${2}"
dispatcher="${3}"
iterations="${4}"
seed="${5}"

name="fs${fleet_size}_req${requests}_disp${dispatcher}_it${iterations}_seed${seed}"
output_path="$(realpath benchmark)/output/${name}"

mkdir -p ${output_path}

# FLEET SECTION
mkdir -p "${output_path}/fleet"

for iteration in $(seq 0 ${iterations}); do
    fleet_path="${output_path}/fleet/fleet.${iteration}.xml"
                
    java -cp target/remote-drt-*-SNAPSHOT.jar \
        -DpreferLocalDtds=true \
        org.matsim.remote_drt.example.RunGenerateFleet \
        --network-path scenario/paris.xml.zst \
        --output-path ${fleet_path} \
        --fleet-size ${fleet_size} --seats 4 --seed $(( ${seed} + ${iteration} ))
done

# DEMAND SECTION
mkdir -p "${output_path}/demand"
cd examples

for iteration in $(seq 0 ${iterations}); do
    demand_path="${output_path}/demand/demand.${iteration}.xml"

    uv run demand/generate.py --attractors-path demand/attractors.gpkg \
        --output-path ${demand_path} \
        --requests ${requests} --seed $(( ${seed} + ${iteration} ))
done

cd .. # back to root

# SIMULATION SECTION
simulation_path="${output_path}/simulation"

if [ -d ${simulation_path} ]; then
    rm -rf ${simulation_path}
fi

java -cp target/remote-drt-*-SNAPSHOT.jar \
    -DpreferLocalDtds=true \
    org.matsim.remote_drt.example.RunSimulation \
    --network-path scenario/paris.xml.zst \
    --demand-path ${output_path}/demand/demand.__it__.xml \
    --fleet-path ${output_path}/fleet/fleet.__it__.xml \
    --output-path ${simulation_path} \
    --iterations ${iterations} \
    --use-automatic-rejection true &

while [ ! -f ${simulation_path}/tmp/remote_port_drt ]; do
    echo "waiting ..."
    sleep 1
done

cd examples
uv run ${dispatcher}_dispatcher.py $(cat ${simulation_path}/tmp/remote_port_drt)
