# Theatre environment

`app/app/src/main/res/drawable-nodpi/theatre_cinema_hall.jpg` is a perspective view rendered from the
**"Cinema Hall"** HDRI by Sergej Majboroda on Poly Haven (https://polyhaven.com/a/cinema_hall),
licensed **CC0** (public domain).

How it was made:
1. Tonemapped 8K equirectangular JPG from Poly Haven.
2. `ffmpeg -vf v360=input=e:output=flat:yaw=92:pitch=4:h_fov=110:v_fov=83.5:w=3200:h=2000:interp=lanczos`
   (seat view facing the screen).
3. Screen measured in the 3200x2000 view: centre (1597, 990), projected width 1040 px.
   The screen and its masking were painted black (the photo shows a film on it).
4. Side walls (doors, exit signs) and ceiling faded to black.
5. Resized to 4096x2048 (power of two, so the GPU can mipmap it), JPEG quality 86.

`VrRenderer` maps the image so its screen lands exactly on the video panel, so the distance,
position and eye-separation sliders move the whole hall with the screen. Each frame the video is
shrunk to 16x9 and used to light the room from the nearest part of the screen ("screen glow");
"house lights" sets the base room brightness.
