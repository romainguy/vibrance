import numpy as np
import matplotlib.pyplot as plt
from matplotlib.widgets import Slider
import os

file_path = 'data/pigments_lut_256_fp16.npy'
print(f"Loading LUT from {file_path}...")
lut = np.load(file_path).astype(np.float32)
print(f"LUT Shape: {lut.shape}")

if len(lut.shape) != 4 or lut.shape[3] != 3:
    raise ValueError("Expected a 4D array with shape (..., ..., ..., 3).")

global_min = 0.0
global_max = 1.0

fig = plt.figure(figsize=(6, 6))
fig.canvas.manager.set_window_title('LUT Visualizer')
plt.subplots_adjust(bottom=0.25, wspace=0.0)

plot = fig.add_subplot(1, 1, 1, projection='3d')
titles = ['Phthalo Blue', 'Quinacridone Magenta', 'Hansa Yellow']
cmaps = ['Blues', 'Purples', 'YlOrBr']

X_grid, Y_grid = np.mgrid[0:256, 0:256]

def draw_surfaces(z, pigment):    
    Z_data = lut[:, :, z, pigment]

    plot.clear()
    plot.plot_surface(
        X_grid,
        Y_grid,
        Z_data,
        cmap=cmaps[pigment],
        linewidth=0,
        antialiased=True,
        shade=True
    )
    
    plot.set_zlim(global_min, global_max)
    plot.set_title(titles[pigment])
    plot.set_xlabel('R')
    plot.set_ylabel('G')
    plot.set_zlabel('Concentration')

draw_surfaces(0, 0)

z_slider = Slider(
    ax=plt.axes([0.30, 0.1, 0.45, 0.03]),
    label='B Slice ',
    valmin=0,
    valmax=lut.shape[2] - 1,
    valinit=0,
    valstep=1
)

pigment_slider = Slider(
    ax=plt.axes([0.30, 0.14, 0.45, 0.03]),
    label='Pigment ',
    valmin=0,
    valmax=2,
    valinit=0,
    valstep=1
)

def update_plot(update):
    draw_surfaces(int(z_slider.val), int(pigment_slider.val))
    fig.canvas.draw_idle()

z_slider.on_changed(update_plot)
pigment_slider.on_changed(update_plot)

def on_key(event):
    if event.key == 'escape':
        plt.close(event.canvas.figure)

fig.canvas.mpl_connect('key_press_event', on_key)

plt.show()