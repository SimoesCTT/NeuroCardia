#!/bin/bash
# Test all six features against the discriminative validity test.
# Runs at K=10, Arm A only. Fast enough to iterate.

cd ~/Desktop/neurocardia

for feature in RR AMP QRS_ENERGY BEAT_CORR QRS_WIDTH HILBERT_DP; do
    echo ""
    echo "############### Feature: $feature ###############"
    ./gradlew :validation:run \
        --args="discriminate /home/americosimoes/physionet/sinus-10min /home/americosimoes/physionet/af-10min A 10 $feature" \
        -q
done
