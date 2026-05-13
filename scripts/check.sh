#/bin/bash
set -e

##
## A simple script that checks the functioning of the algorithms in one go.
## - Should be called in the root directory, i.e. bash scripts/check.sh
## - Results in scenario/check
##

if [ ! -f README.md ]; then
    echo "Script should be called from the main directory."
    exit 1
fi

mvn clean package -Pstandalone
mkdir -p scenario/check

fleet_size=10
requests=1000
dispatchers="02_euclidean 04_insertion"

java -cp target/remote-drt-*-SNAPSHOT.jar \
    org.matsim.remote_drt.example.RunGenerateFleet \
    --network-path scenario/paris.xml.zst \
    --output-path scenario/check/fleet.xml \
    --fleet-size ${fleet_size} --seats 4

cd examples
uv run demand/generate.py --attractors-path demand/attractors.gpkg --output-path ../scenario/check/demand.xml --requests ${requests}
cd ..

for dispatcher in ${dispatchers}; do
    output_path="scenario/check/output/${dispatcher}"
    rm -rf ${output_path}
    
    java -cp target/remote-drt-*-SNAPSHOT.jar \
        -DpreferLocalDtds=true \
        org.matsim.remote_drt.example.RunSimulation \
        --network-path scenario/paris.xml.zst \
        --demand-path scenario/check/demand.xml \
        --fleet-path scenario/check/fleet.xml \
        --output-path ${output_path} \
        --use-automatic-rejection true &

    while [ ! -f ${output_path}/tmp/remote_port_drt ]; do
        echo "waiting ..."
        sleep 1
    done

    cd examples
    uv run ${dispatcher}_dispatcher.py $(cat ../${output_path}/tmp/remote_port_drt)
    cd ..
done
