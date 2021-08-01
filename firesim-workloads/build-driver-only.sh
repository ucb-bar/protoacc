
set -ex

cd ../../../../../sim

make DESIGN=FireSim TARGET_CONFIG=DDR3FRFCFSLLCMaxSetb17MaxWayb1_FireSimProtoDeserMegaBoomConfig PLATFORM_CONFIG=BaseF1ConfigSingleMem_F7MHz f1

make DESIGN=FireSim TARGET_CONFIG=DDR3FRFCFSLLCMaxSetb17MaxWayb1_FireSimProtoSerMegaBoomConfig PLATFORM_CONFIG=BaseF1ConfigSingleMem_F7MHz f1

