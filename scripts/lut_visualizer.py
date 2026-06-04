import sys
import numpy as np
import fastplotlib as fpl
from PySide6 import QtWidgets, QtCore
from rendercanvas.auto import RenderCanvas

class LUTVisualizer(QtWidgets.QWidget):
    def __init__(self, lut):
        super().__init__()
        self.lut = lut
        self.setWindowTitle("LUT Visualizer")
        self.resize(800, 800)

        self.global_min = 0.0
        self.global_max = 1.0
        self.titles = ['Phthalo Blue', 'Quinacridone Magenta', 'Hansa Yellow']
        self.cmaps = ['Blues', 'Purples', 'YlOrBr']

        layout = QtWidgets.QVBoxLayout(self)

        self.canvas = RenderCanvas(parent=self)
        self.canvas.setMinimumSize(400, 400)
        layout.addWidget(self.canvas, stretch=1)
        
        self.fig = fpl.Figure(canvas=self.canvas, cameras="3d", controller_types="orbit")

        slider_layout = QtWidgets.QGridLayout()
        
        self.z_label = QtWidgets.QLabel("B Slice: 0")
        self.z_slider = QtWidgets.QSlider(QtCore.Qt.Orientation.Horizontal)
        self.z_slider.setMinimum(0)
        self.z_slider.setMaximum(self.lut.shape[2] - 1)
        self.z_slider.valueChanged.connect(self.update_plot)

        self.p_label = QtWidgets.QLabel(f"Pigment")
        self.p_slider = QtWidgets.QSlider(QtCore.Qt.Orientation.Horizontal)
        self.p_slider.setMinimum(0)
        self.p_slider.setMaximum(2)
        self.p_slider.setTickPosition(QtWidgets.QSlider.TickPosition.TicksBelow)
        self.p_slider.setTickInterval(1)
        self.p_slider.valueChanged.connect(self.update_plot)

        slider_layout.addWidget(self.z_label, 0, 0)
        slider_layout.addWidget(self.z_slider, 0, 1)
        slider_layout.addWidget(self.p_label, 1, 0)
        slider_layout.addWidget(self.p_slider, 1, 1)
        layout.addLayout(slider_layout)

        initial_z_data = self.lut[:, :, 0, 0]
        
        self.surface = self.fig[0, 0].add_surface(
            data=initial_z_data,
            cmap=self.cmaps[0]
        )
        self.surface.clim = (self.global_min, self.global_max)
        self.fig[0, 0].title = self.titles[0]

        self.surface.world_object.local.scale = (1, 1, 255)

        max_z_world = self.global_max * 255
        self.fig[0, 0].add_scatter(
            data=np.array([
                [0, 0, 0], 
                [255, 255, max_z_world]
            ]),
            alpha=0.0,
            sizes=0
        )

        self.fig[0, 0].controller.target = (127.5, 127.5, 127.5)
        self.fig[0, 0].camera.world.reference_up = (0, 0, 1)
        self.fig[0, 0].camera.local.position = (255.0, 255.0, 200.0)
        self.fig[0, 0].camera.look_at((127.5, 127.5, 127.5))

        self.setFocusPolicy(QtCore.Qt.FocusPolicy.StrongFocus)
        self.z_slider.setFocus()

    def keyPressEvent(self, event):
        if event.key() == QtCore.Qt.Key.Key_Escape:
            self.close()

    def update_plot(self):
        z_idx = self.z_slider.value()
        p_idx = self.p_slider.value()

        self.z_label.setText(f"B Slice: {z_idx}")
        self.p_label.setText(f"Pigment")
        self.fig[0, 0].title = self.titles[p_idx]

        self.surface.data = self.lut[:, :, z_idx, p_idx]
        self.surface.cmap = self.cmaps[p_idx]

def main():
    file_path = 'data/pigments_lut_256_fp16.npy'
    print(f"Loading LUT from {file_path}...")
    
    try:
        lut = np.load(file_path).astype(np.float32)
    except FileNotFoundError:
        print(f"File not found: {file_path}. Exiting.")
        sys.exit(1)

    print(f"LUT Shape: {lut.shape}")

    if len(lut.shape) != 4 or lut.shape[3] != 3:
        raise ValueError("Expected a 4D array with shape (..., ..., ..., 3).")

    app = QtWidgets.QApplication(sys.argv)
    window = LUTVisualizer(lut)
    window.show()
    window.raise_()
    window.activateWindow()
    window.fig.show()
    sys.exit(app.exec())

if __name__ == "__main__":
    main()
