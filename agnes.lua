-- agnes
-- granular / spectral freeze for norns
--
-- named after Agnes Martin, and The Islands I-XII (1979): twelve near-identical
-- pale canvases, always hung as one work, in the same order. variation without
-- event; the whole thing experienced over time rather than at a glance.
--
-- always listens to input; K2 captures the recent past and sustains it forever.
-- MORPH crossfades between two resynthesis engines reading the same capture:
--   morph 0 = granular (alive, good on anything)
--   morph 1 = spectral (glassy, best on tonal material)
--
-- UI: twelve panels of fine horizontal lines. a slow wave travels left to right,
-- so ideas develop and resolve across the panels. morph controls line
-- regularity - granular is irregular, spectral resolves into stillness.
-- brightness pulses with the wet signal.
--
-- encoders:  E1 morph (granular <> spectral)
--            E2 blend (dry/wet)
--            E3 scan position (where in the capture the grains read from)
-- keys:      K2 capture
--            K3 hold: E3 edits grain size instead of scan position
--            K1+K2 hold 2s: clear the buffer (panels wipe right to left)
--
-- moving an encoder briefly shows its value, then fades back to the bare grid.
--
-- controls map to the 16n Faderbank over MIDI (edit the CC numbers below).
--
-- engine: Agnes  (Engine_Agnes.sc in this script's lib/ folder)
--
-- NOTE: set PARAMETERS > LEVELS > monitor to 0 - the engine mixes dry itself.

engine.name = "Agnes"

-- 16n / 8mu CC map - change to match your controller config -------------------
local CC_SIZE    = 32   -- fader 1: capture size
local CC_BLEND   = 33   -- fader 2: dry/wet
local CC_MORPH   = 34   -- fader 3: granular <-> spectral
local CC_GRAIN   = 35   -- fader 4: grain size (granular texture)
local CC_POS     = 36   -- fader 5: scan position through the capture
local CC_REFRESH = 37   -- fader 6: spectral refresh rate
local CC_SPECWIN = 38   -- fader 7: spectral analysis window
local CC_DIFFUSE = 41   -- fader 10: spectral phase diffusion
local CC_FADE    = 39   -- fader 8: fade time
local CC_PITCH   = 40   -- fader 9: pitch
local CC_CAPTURE = 48   -- 8mu button (val > 0 triggers capture); optional

-- UI geometry ----------------------------------------------------------------
local PANELS   = 12     -- twelve canvases
local ROWS     = 10     -- horizontal lines per panel
local MARGIN   = 4
local PANEL_W  = 9
local PANEL_G  = 1
local TOP      = 12
local BOT      = 54
local FPS      = 15

local function show(label, value)
  hud.label, hud.value, hud.ttl = label, value, 1.5
end

local m
local ui_metro
local splash    = true
local k1        = false
local k2        = false
local k3        = false          -- held: E3 edits grain size
local clear_hold = 0             -- 0..1 progress of the K1+K2 clear gesture
local cleared    = false         -- latch, so clear fires once per hold
local CLEAR_SECS = 2.0
local hud       = { label = "", value = "", ttl = 0 }
local t         = 0     -- animation phase (seconds)
local amp       = 0     -- wet amplitude, from poll
local flash     = 0     -- brief brightening on capture

function init()
  params:add_control("capture_size", "capture size",
    controlspec.new(0.05, 5.0, "lin", 0, 2.0, "s"))

  params:add_control("blend", "blend",
    controlspec.new(0, 1, "lin", 0, 0.7, ""))
  params:set_action("blend", function(v) engine.blend(v) end)

  params:add_control("morph", "morph (gran>spec)",
    controlspec.new(0, 1, "lin", 0, 0, ""))
  params:set_action("morph", function(v) engine.morph(v) end)

  params:add_control("grain", "grain size",
    controlspec.new(0.02, 0.5, "exp", 0, 0.1, "s"))
  params:set_action("grain", function(v) engine.grainSize(v) end)

  params:add_control("scatter", "grain scatter",
    controlspec.new(0, 1, "lin", 0, 0.08, ""))
  params:set_action("scatter", function(v) engine.scatter(v) end)

  params:add_control("pos", "scan position",
    controlspec.new(0, 1, "lin", 0, 0.5, ""))
  params:set_action("pos", function(v) engine.pos(v) end)

  params:add_control("drift", "drift",
    controlspec.new(0.005, 1.0, "exp", 0, 0.04, "hz"))
  params:set_action("drift", function(v) engine.drift(v) end)

  params:add_control("refresh", "spec refresh",
    controlspec.new(0, 8, "lin", 0, 0, "hz"))
  params:set_action("refresh", function(v) engine.refresh(v) end)

  params:add_control("diffuse", "spec diffuse",
    controlspec.new(0, 20, "lin", 0, 0, "hz"))
  params:set_action("diffuse", function(v) engine.diffuse(v) end)

  params:add_control("spec_win", "spec window",
    controlspec.new(0.05, 2.0, "exp", 0, 0.3, "s"))
  params:set_action("spec_win", function(v) engine.specWin(v) end)

  params:add_control("spec_gain", "spec trim",
    controlspec.new(0, 4, "lin", 0, 1.0, "x"))
  params:set_action("spec_gain", function(v) engine.specGain(v) end)

  params:add_control("fade", "fade time",
    controlspec.new(0.005, 5.0, "exp", 0, 0.1, "s"))
  params:set_action("fade", function(v) engine.fade(v) end)

  params:add_control("pitch", "pitch",
    controlspec.new(0.0, 2.0, "lin", 0, 1.0, "x"))
  params:set_action("pitch", function(v) engine.pitch(v) end)

  params:bang()

  -- amplitude poll. wrapped in pcall so a poll-API mismatch degrades the UI
  -- to a still grid rather than breaking the script.
  local ok, p = pcall(function() return poll.set("agnesAmp") end)
  if ok and p then
    p.callback = function(v) amp = v end
    p.time = 1 / FPS
    p:start()
  end

  m = midi.connect()
  m.event = function(data)
    local d = midi.to_msg(data)
    if d.type == "cc" then
      local f = d.val / 127
      if     d.cc == CC_BLEND   then params:set_raw("blend", f)
      elseif d.cc == CC_MORPH   then params:set_raw("morph", f)
      elseif d.cc == CC_GRAIN   then params:set_raw("grain", f)
      elseif d.cc == CC_POS     then params:set_raw("pos", f)
      elseif d.cc == CC_REFRESH then params:set_raw("refresh", f)
      elseif d.cc == CC_SPECWIN then params:set_raw("spec_win", f)
      elseif d.cc == CC_DIFFUSE then params:set_raw("diffuse", f)
      elseif d.cc == CC_FADE    then params:set_raw("fade", f)
      elseif d.cc == CC_PITCH   then params:set_raw("pitch", f)
      elseif d.cc == CC_SIZE    then params:set_raw("capture_size", f)
      elseif d.cc == CC_CAPTURE and d.val > 0 then capture()
      end
    end
  end

  -- continuous redraw: the grid moves whether or not you touch anything
  ui_metro = metro.init(function() tick() end, 1 / FPS, -1)
  ui_metro:start()

  clock.run(function()
    clock.sleep(4)
    splash = false
  end)
end

function tick()
  t = t + (1 / FPS)
  if flash > 0 then flash = math.max(0, flash - 0.06) end
  if hud.ttl > 0 then hud.ttl = hud.ttl - (1 / FPS) end

  -- K1+K2 held: wipe progresses, fires once at the top
  if k1 and k2 then
    clear_hold = math.min(1, clear_hold + (1 / FPS) / CLEAR_SECS)
    if clear_hold >= 1 and not cleared then
      engine.clear()
      cleared = true
      show("cleared", "")
    end
  else
    clear_hold = 0
    cleared = false
  end
  redraw()
end

function capture()
  engine.capture(params:get("capture_size"))
  flash = 1.0
end

function key(n, z)
  if n == 1 then k1 = (z == 1) end
  if n == 2 then k2 = (z == 1) end
  if n == 3 then k3 = (z == 1) end
  if z == 1 then
    if splash then splash = false; return end
    -- K1 held turns K2 into the clear gesture, so don't also capture
    if n == 2 and not k1 then capture() end
  end
end

function enc(n, d)
  if splash then splash = false; return end
  if n == 1 then
    params:delta("morph", d)
    local mo = params:get("morph")
    show("morph", string.format("%s %.2f", mo < 0.5 and "gran" or "spec", mo))
  elseif n == 2 then
    params:delta("blend", d)
    show("blend", string.format("%.2f", params:get("blend")))
  elseif n == 3 then
    if k3 then
      params:delta("grain", d)
      show("grain size", string.format("%.0f ms", params:get("grain") * 1000))
    else
      params:delta("pos", d)
      show("scan", string.format("%.2f", params:get("pos")))
    end
  end
end

-- ---------------------------------------------------------------------------

function draw_splash()
  screen.level(15)
  screen.move(64, 22); screen.text_center("agnes")
  screen.level(5)
  screen.move(64, 38); screen.text_center("everything is")
  screen.move(64, 46); screen.text_center("relentlessly the same")
  screen.level(2)
  screen.move(64, 60); screen.text_center("after agnes martin")
end

function draw_grid()
  local mo   = params:get("morph")
  local pos  = params:get("pos")
  local a    = util.clamp(amp * 5, 0, 1)          -- normalised loudness
  local rowg = (BOT - TOP) / (ROWS - 1)
  local flow = t * 0.05                            -- slow left-to-right travel
  local lit  = math.floor(pos * (PANELS - 1) + 0.5)

  for p = 0, PANELS - 1 do
    local x = MARGIN + p * (PANEL_W + PANEL_G)

    -- each panel sits at its own phase in the wave, so ideas develop and
    -- resolve as the eye moves across the twelve
    local ph   = (p / PANELS) - flow
    local wave = 0.5 + 0.5 * math.cos(2 * math.pi * ph)

    for r = 0, ROWS - 1 do
      local y = TOP + r * rowg

      -- granular: per-line irregularity. spectral: uniform stillness.
      local jitter = (1 - mo) * 0.3
                   * math.sin(t * 0.7 + p * 1.7 + r * 2.3)

      local lv = wave * (0.28 + 0.72 * a) + jitter
      if p == lit then lv = lv + 0.18 end
      -- clear gesture: panels go blank from the right as you hold
      if clear_hold > 0 and (PANELS - 1 - p) < (clear_hold * PANELS) then
        lv = lv * 0.12
      end

      lv = util.clamp(lv + flash * 0.6, 0, 1)

      if lv > 0.04 then
        screen.level(math.floor(lv * 15))
        screen.move(x, y + 0.5)
        screen.line(x + PANEL_W, y + 0.5)
        screen.stroke()
      end
    end
  end

  -- readout appears only while you are touching something, then fades out
  if hud.ttl > 0 then
    local k = util.clamp(hud.ttl / 1.5, 0, 1)
    screen.level(math.floor(2 + k * 10))
    screen.move(0, 63);   screen.text(hud.label)
    screen.move(128, 63); screen.text_right(hud.value)
  end
end

function redraw()
  screen.clear()
  screen.line_width(1)
  if splash then draw_splash() else draw_grid() end
  screen.update()
end

function cleanup()
  if ui_metro then ui_metro:stop() end
end
