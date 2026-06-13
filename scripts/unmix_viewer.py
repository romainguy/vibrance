import fastplotlib as fpl
import numpy as np
import onnxruntime as ort
import pigments
import sys
from PySide6 import QtWidgets, QtCore
from rendercanvas.auto import RenderCanvas

class LUTVisualizer(QtWidgets.QWidget):
    def __init__(self, lut, data_config):
        super().__init__()
        self.lut = lut
        self.setWindowTitle("LUT Visualizer")
        self.resize(800, 800)

        self.global_min = 0.0
        self.global_max = 255.0
        self.titles = ['Phthalo Blue', 'Quinacridone Magenta', 'Hansa Yellow']
        self.cmaps = ['Blues', 'Purples', 'colorbrewer:YlOrBr']

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

        self.show_mlp_checkbox = QtWidgets.QCheckBox("Show MLP Output")
        self.show_mlp_checkbox.stateChanged.connect(self.update_visible_surfaces)
        self.show_mlp_error_checkbox = QtWidgets.QCheckBox("Show MLP Error")
        self.show_mlp_error_checkbox.stateChanged.connect(self.update_plot)

        slider_layout.addWidget(self.z_label, 0, 0)
        slider_layout.addWidget(self.z_slider, 0, 1)
        slider_layout.addWidget(self.p_label, 1, 0)
        slider_layout.addWidget(self.p_slider, 1, 1)
        slider_layout.addWidget(self.show_mlp_checkbox, 2, 0)
        slider_layout.addWidget(self.show_mlp_error_checkbox, 3, 0)
        layout.addLayout(slider_layout)

        initial_z_data = self.lut[:, :, 0, 0] * 255.0

        self.surface = self.fig[0, 0].add_surface(
            data=initial_z_data,
            cmap=self.cmaps[0]
        )
        self.surface.clim = (self.global_min, self.global_max)

        self.session = ort.InferenceSession(data_config.model_path_onnx)
        self.input_name = self.session.get_inputs()[0].name
        self.output_name = self.session.get_outputs()[0].name

        self.mlp_data = np.empty((256, 256), dtype=np.float32)
        self.updateMlpData(0, 0)
        self.mlp_surface = self.fig[0, 0].add_surface(
            data=self.mlp_data,
            cmap="bids:plasma"
        )
        self.mlp_surface.clim = (0, 255)
        self.mlp_surface.visible = False

        self.fig[0, 0].title = self.titles[0]

        self.fig[0, 0].add_scatter(
            data=np.array([
                [0, 0, 0], 
                [255, 255, 255]
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

    def updateMlpData(self, z_index, p_index):
        error_mode = self.show_mlp_error_checkbox.isChecked()
        for r in range(256):
            for g in range(256):
                input_data = np.array([[r / 255.0, g / 255.0, z_index / 255.0]], dtype=np.float32)
                results = self.session.run([self.output_name], {self.input_name: input_data})
                v = results[0][0][p_index] * 255.0
                if error_mode:
                    v = abs(v - self.lut[r, g, z_index, p_index] * 255.0)
                self.mlp_data[r][g] = v

    def update_visible_surfaces(self):
        show_mlp = self.show_mlp_checkbox.isChecked()
        if not self.surface.visible and not show_mlp:
            z_index = self.z_slider.value()
            p_index = self.p_slider.value()
            self.surface.data = self.lut[:, :, z_index, p_index] * 255.0
            self.surface.cmap = self.cmaps[p_index]
        if not self.mlp_surface.visible and show_mlp:
            self.updateMlpData(self.z_slider.value(), self.p_slider.value())
            self.mlp_surface.data = self.mlp_data
        self.surface.visible = not show_mlp
        self.mlp_surface.visible = show_mlp

    def update_plot(self):
        z_index = self.z_slider.value()
        p_index = self.p_slider.value()

        self.z_label.setText(f"B Slice: {z_index}")
        self.p_label.setText(f"Pigment")
        self.fig[0, 0].title = self.titles[p_index]

        if self.surface.visible:
            self.surface.data = self.lut[:, :, z_index, p_index] * 255.0
            self.surface.cmap = self.cmaps[p_index]

        if self.mlp_surface.visible:
            self.updateMlpData(z_index, p_index)
            self.mlp_surface.data = self.mlp_data

def main():
    data_config = pigments.load_data_config_from_json('data_config.json')
    print(f"Loading LUT from {data_config.lut_path_npy}...")
    
    try:
        lut = np.load(data_config.lut_path_npy).astype(np.float32)
    except FileNotFoundError:
        print(f"File not found: {data_config.lut_path_npy}. Exiting.")
        sys.exit(1)

    print(f"LUT Shape: {lut.shape}")

    if len(lut.shape) != 4 or lut.shape[3] != 3:
        raise ValueError("Expected a 4D array with shape (..., ..., ..., 3).")

    app = QtWidgets.QApplication(sys.argv)
    window = LUTVisualizer(lut, data_config)
    window.show()
    window.raise_()
    window.activateWindow()
    window.fig.show()
    sys.exit(app.exec())

if __name__ == "__main__":
    main()
