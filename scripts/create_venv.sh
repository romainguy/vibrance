#!/bin/bash
python3 -m venv .venv
source .venv/bin/activate
pip3 install numpy scipy matplotlib torch torchvision onnxscript tqdm pyside6
# Need a fastplotlib version > 0.6.1
# TODO: Remove once the new version is released on PyPI
pip3 install git+https://github.com/fastplotlib/fastplotlib.git
