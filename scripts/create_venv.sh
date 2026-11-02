#!/bin/bash
python3.11 -m venv .venv
source .venv/bin/activate
pip3.11 install numpy scipy matplotlib torch torchvision onnxscript onnxruntime tqdm pyside6
# Need a fastplotlib version > 0.6.1
pip3.11 install "fastplotlib>0.6.1"
