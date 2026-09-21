# Theatre — the films

## The idea

Theatre is the Drive screen with a film behind the numbers. You are **sitting in the car**, so
showing the car is pointless — a turntable render is a configurator, not a dash. What belongs
there is the part you cannot see: the engine working, and the sense of moving.

So every film is one of two things:

- **inside the machine** — cams, valves, pistons, the parts of a 2ZZ-GE doing their job, or
- **the feeling of speed** — light, embers, dust streaming past in the dark.

And the important part: **the dash plays these at the engine's pace.** The film's playback speed
is tied to the revs — a crawl at idle, close to double speed at the 8,200 stop, easing between.
Cams idle slowly and then hammer as you climb through the lift point; streaks stretch out as you
accelerate. Without a dongle the road speed stands in.

That is why every prompt asks for **one steady continuous motion with no cuts**: a cut looks
wrong when the film is being sped up and slowed down. A cam turning, a piston stroking, particles
flowing — those read at any speed.

## Using them

Each block between the `---` rules is **one complete prompt, self-contained**. Copy the whole
block into a fresh chat. They repeat the palette and the rules every time on purpose, because a
new chat knows none of it.

**Attach `reference-style.png` with each one** and say *"match the style and palette of the
attached image"* — it carries the dash's look across faster than a paragraph can.

When they come back: drop the MP4s in Downloads, run `python tools/faces/make_loops.py` to take
the watermark off and cut them into seamless loops, then `python tools/faces/pack_faces.py`, then
on the unit Settings ⚙ › Developer › **Fetch backdrops**, and pick one under Style › Backdrop.

A generator will not give you a seamless loop just because the prompt asked for one, so
`make_loops.py` measures every start/end frame pair and cuts at the closest match. Two of the four
so far landed on a join smaller than one frame's own motion and simply cut; the other two had no
natural match and dissolve their tail back into their head. Its `FILMS` table holds the cut points,
so a new film needs a row adding after a look at what the scan reports.

Start with **Cams** and **Rush**. One is the engine, one is the motion; between them they cover
what the face is for.

**Done so far:** Cams, Rush, Pistons and Embers. Ignition and Turbine are still to make.

---

## 1 · Cams — the signature

Create a 10 second seamlessly looping 16:9 video. Stylised 3D render, not photorealistic, not
live action.

Subject: the valvetrain of a Toyota 2ZZ-GE engine — a 1.8 litre four-cylinder twin-cam sixteen
valve — turning steadily, seen close and from the side. Two camshafts running across the frame,
the lobes rotating and pressing their followers down in firing order, valve springs compressing
and releasing. Just the valvetrain, isolated in space: no engine block around it, no bonnet, no
engine bay, no wiring, no hoses, no oil.

Style: a pure black void, #000000, with no floor, no room and no background of any kind. Matte
dark metal — no chrome, no polished reflections, no studio lighting in the surfaces. A single
hard red rim light (#E5372E) along one side picks out the edges of the lobes and the spring tops;
the far side falls to black. Black, white and that one red only — no orange, no blue, no teal, no
colour grading.

Motion: one steady continuous rotation at a constant rate, seen from a still camera. **No cuts,
no speed ramps, no camera shake** — the loop will be played back faster and slower by the dash,
so the motion must be even throughout. The last frame must match the first, with the cams back at
the same angle, so it loops seamlessly.

Composition: the camshafts across the upper two thirds of the frame. Keep the bottom third empty
and black — text is overlaid there.

Do not include: any text, part numbers, badges, logos, hands, people, cars, environment, sparks,
flames, smoke, lens flares or film grain.

---

## 2 · Rush — the motion

Create an 8 second seamlessly looping 16:9 video. Stylised 3D render, not photorealistic, not
live action, not filmed from a car.

Subject: fine streaks of white light rushing past the camera through a pure black void, flowing
from the centre of the frame outwards and past the edges, like standing still while the dark
moves. The streaks are thin, sharp and long, varying in length and brightness, with a handful of
red ones (#E5372E) among the white. Nothing else exists — no road, no tunnel, no stars, no
buildings, no vehicles.

Style: pure black background, #000000. Only black, white and that one red — no blue, no purple,
no orange, no nebula colours, no colour grading. The streaks are crisp lines, not glowing clouds
or volumetric fog. The centre of the frame stays darkest; the streaks brighten as they pass the
edges.

Motion: one steady continuous flow at a constant rate from a still camera. **No cuts, no speed
ramps, no shake** — the dash speeds this loop up and slows it down with the engine, so the flow
must be even throughout. The loop must be perfectly seamless with no visible join.

Composition: the flow fills the upper two thirds; keep the bottom third darker and quieter, as
text is overlaid there.

Do not include: any text, logos, people, cars, roads, tunnels, scenery, lens flares or film grain.

---

## 3 · Pistons

Create a 10 second seamlessly looping 16:9 video. Stylised 3D render, not photorealistic, not
live action.

Subject: a cutaway of a four-cylinder engine's bottom end working — four pistons rising and
falling on their connecting rods, the crankshaft turning beneath them, in firing order. Seen
square from the side so the stroke is obvious. Just the moving parts, isolated in space: no
engine block, no sump, no car, no engine bay, no oil, no smoke.

Style: a pure black void, #000000, no floor, no background. Matte dark metal — no chrome, no
polished reflections. A single hard red rim light (#E5372E) from one side catches the crowns of
the pistons and the edges of the rods; everything else falls to black. Black, white and that one
red only — no orange, no blue, no colour grading.

Motion: one steady continuous rotation of the crank at a constant rate, still camera. **No cuts,
no speed ramps, no shake** — the dash plays the loop faster and slower with the revs, so the
motion must be even. The last frame must match the first, with the crank back at the same angle,
for a seamless loop.

Composition: the engine across the upper two thirds; bottom third empty and black for overlaid
text.

Do not include: text, part numbers, logos, hands, people, cars, environment, flames, sparks or
grain.

---

## 4 · Embers

Create a 10 second seamlessly looping 16:9 video. Stylised 3D render, not photorealistic.

Subject: fine embers and dust drifting steadily through a pure black void, lit from one side,
travelling in one direction across the frame. Most are small and sharp white points; some are red
(#E5372E). Some pass close to the camera and are soft; most are far and crisp. Nothing else
exists in the frame — no fire, no smoke clouds, no ground, no objects.

Style: pure black background, #000000. Black, white and that one red only — no orange, no yellow,
no blue, no colour grading, no glow haze filling the frame.

Motion: one steady continuous drift at a constant rate, still camera. **No cuts, no speed ramps,
no shake** — the dash speeds the loop up and slows it down with the car, so the drift must be
even throughout. Perfectly seamless loop with no visible join.

Composition: the drift is busiest across the upper two thirds; keep the bottom third sparse and
dark, as text is overlaid there.

Do not include: text, logos, people, cars, scenery, fire, smoke, lens flares or film grain.

---

## 5 · Ignition

Create an 8 second seamlessly looping 16:9 video. Stylised 3D render, not photorealistic.

Subject: seen from directly above, four circular combustion chambers in a row, each pulsing with
a red glow in firing order — one flares as the next fades, over and over, travelling along the
row. The chambers are simple dark metal rings in a black void; the glow rises from inside them.
No engine around them, no car, no flames, no smoke.

Style: pure black background, #000000, no floor, no environment. Matte dark metal rings with hard
edges. The glow is red (#E5372E) and contained inside each chamber — it should not wash over the
whole frame. Black, white and that one red only, no orange or yellow fire colours.

Motion: one steady continuous firing cycle at a constant rate, still camera, the pulse travelling
evenly along the four. **No cuts, no speed ramps, no shake** — the dash plays it faster with the
revs, so the cycle must be even. Seamless loop.

Composition: the row of chambers across the upper two thirds; bottom third empty and black for
overlaid text.

Do not include: text, part numbers, logos, flames, smoke, sparks, people, cars, environment or
grain.

---

## 6 · Turbine

Create an 8 second seamlessly looping 16:9 video. Stylised 3D render, not photorealistic.

Subject: a finely bladed turbine or compressor wheel spinning steadily, seen close and almost
head-on, slightly off-axis so the blade depth reads. Dark machined metal, sharp edges, many thin
blades. Just the wheel, isolated in space — no housing, no engine, no car, no pipes.

Style: a pure black void, #000000, no floor, no background. Matte dark metal, no chrome, no
polished reflections. A single hard red rim light (#E5372E) rakes across the blade edges; the
rest falls to black. Black, white and that one red only — no blue, no orange, no colour grading.

Motion: one steady continuous rotation at a constant rate, still camera. **No cuts, no speed
ramps, no motion blur strobing, no shake** — the dash drives the loop faster and slower with the
revs, so the spin must be even. The last frame must match the first for a seamless loop.

Composition: the wheel centred in the upper two thirds; bottom third empty and black for overlaid
text.

Do not include: text, logos, badges, people, cars, environment, sparks, flames, lens flares or
film grain.

---

## Encoding

Anything the unit can play: H.264 in an MP4. The screen is 1024×600, so 1280 wide is plenty:

    ffmpeg -i in.mp4 -vf scale=1280:-2 -c:v libx264 -crf 26 -preset slow -an out.mp4
