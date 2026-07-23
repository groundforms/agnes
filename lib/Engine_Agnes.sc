// Engine_Agnes.sc  (granular + spectral morph)
//
// Named after Agnes Martin, and The Islands I-XII (1979): twelve near-identical
// pale canvases, always hung as one work, in the same order. Variation without
// event; the whole thing experienced over time rather than at a glance.
//
// Norns granular/spectral freeze engine. Always listens; on capture, the most
// recent N seconds are copied into a freeze buffer. TWO resynthesis engines read
// that same buffer in parallel, crossfaded by a single `morph` control:
//
//   morph = 0  -> GRANULAR : Warp1 overlap-add grains. Alive, handles noisy and
//                 transient material gracefully. The general-purpose spine.
//   morph = 1  -> SPECTRAL : FFT magnitude hold. Glassier and smoother on tonal,
//                 harmonic sources; can turn noisy material to static hiss.
//
// Both are summed with an equal-power crossfade, so intermediate positions are a
// genuine blend rather than a hard switch.
//
// SPECTRAL / `refresh`:
//   A pure PV_MagFreeze holds a single instant, which makes capture size
//   meaningless and sounds static. Here a playhead loops through the captured
//   region and the spectrum re-grabs at `refresh` Hz. So:
//     refresh = 0    -> classic frozen-instant behaviour
//     refresh > 0    -> spectrum steps through the capture, evolving
//
// POLL: publishes wet amplitude as "agnesAmp" so the Lua UI can pulse with the
// sound. See the note in agnes.lua if the poll API differs on your version.
//
// ---- TODO (next iteration): hear norns TAPE playback ----------------------
// The recorder below taps SoundIn.ar(0), i.e. hardware input only. Tape
// playback is mixed in DOWNSTREAM of the engine in Crone's graph, so the
// history buffer currently cannot hear it. Options, best first:
//
//   1. Check whether Crone exposes a tape/playback bus an engine can read.
//      Cleanest if it exists. Verify against the Crone source on YOUR norns
//      version - the bus layout has changed over time, so don't assume.
//   2. Read the main output bus instead. Works, but this engine's own output
//      is on that bus, so it creates a feedback path. Would need a pre-engine
//      tap or gating, and gating fights the always-listening design.
//   3. Route tape out and back in physically. Inelegant, definitely works,
//      good for proving the idea is worth the effort before building it.
//
// Related tidy-up worth doing at the same time: switch SoundIn.ar(0) to
// In.ar(context.in_b, 1). Functionally the same today, but it's the idiomatic
// norns tap point and it's where the source change would land.
// --------------------------------------------------------------------------
//
// Core UGens only (FFT / PV_MagFreeze / PV_Diffuser / IFFT / Warp1) - no
// sc3-plugins dependency.
//
// Set norns PARAMETERS > LEVELS > monitor to 0; this engine mixes dry itself.

Engine_Agnes : CroneEngine {
    var <recSynth, <synth;
    var history, freeze, posBus, ampBus;
    var sr, histFrames;
    var duck = 0.03;   // fade-to-silence time used to bracket a capture
    // FFT size: time vs frequency resolution. 4096 = ~85ms analysis window,
    // smooth but smears transients together. 2048 = ~43ms, tighter in time,
    // coarser in pitch. 1024 = ~21ms, tight but poor low-end resolution, bad
    // for kicks. Changing this needs a script restart (SynthDef-time constant).
    var fftSize = 2048;

    *new { arg context, doneCallback;
        ^super.new(context, doneCallback);
    }

    alloc {
        sr = context.server.sampleRate;
        histFrames = (sr * 5).asInteger;              // 5 seconds of history

        history = Buffer.alloc(context.server, histFrames, 1);
        freeze  = Buffer.alloc(context.server, histFrames, 1);
        posBus  = Bus.control(context.server, 1);
        ampBus  = Bus.control(context.server, 1);

        // Always-listening recorder -> history, publishes write head (0..1).
        SynthDef(\agnesRec, { arg buf, posOut;
            var in     = SoundIn.ar(0);
            var frames = BufFrames.kr(buf);
            var phase  = Phasor.ar(0, BufRateScale.kr(buf), 0, frames);
            BufWr.ar(in, buf, phase);
            Out.kr(posOut, A2K.kr(phase / frames));
        }).add;

        // Granular + spectral in one voice, sharing dry/blend/fade.
        SynthDef(\agnesVoice, {
            arg out, buf, ampOut = 0,
                playFrac  = 1,     // fraction of buf holding valid capture
                pos       = 0.5,   // granular scan centre (0..1 of captured region)
                drift     = 0.1,   // granular wander speed (Hz)
                grainSize = 0.1,   // granular window (s)
                overlaps  = 3,
                scatter   = 0.1,   // granular position randomness
                pitch     = 1,     // 0.5 = -oct, 2 = +oct (both engines)
                morph     = 0,     // 0 = granular ... 1 = spectral
                refresh   = 0.5,   // spectral re-grab rate (Hz); 0 = fully frozen
                diffuse   = 0,     // spectral phase-diffusion rate (Hz); 0 = off
                specGain  = 1,     // spectral level trim
                specWin   = 0.3,   // spectral analysis window at `pos` (s)
                specMove  = 0.3,   // how far the analysis point wanders (0 = still)
                hold      = 0.8,   // freeze duty: 1 = always held, 0 = always live
                blend     = 0.5,   // dry/wet
                fade      = 0.05,  // fade-IN time after a capture (s)
                duck      = 0.03,  // fade-OUT time when a capture starts (s)
                gate      = 1,     // 1 = sounding, 0 = ducked to silence
                t_recapture = 0;   // trigger: new capture just landed

            var dry, ptr, gran;
            var specPtr, src, chain, frz, spec;
            var gAmp, sAmp, sig, amp, mix;

            dry = SoundIn.ar(0);

            // ---- GRANULAR ------------------------------------------------
            ptr = ((pos * playFrac) + (LFNoise2.kr(drift) * 0.02 * playFrac))
                    .clip(0, playFrac);
            gran = Warp1.ar(1, buf, ptr, pitch, grainSize, -1, overlaps, scatter, 1);

            // ---- SPECTRAL ------------------------------------------------
            // Source is a SECOND Warp1, not a looping playhead over raw audio.
            // A raw loop has a waveform discontinuity at the wrap point, and
            // PV_MagFreeze freezes MAGNITUDES ONLY - phase still comes from the
            // input - so that discontinuity came straight through the freeze as
            // a tick, once per loop period. Warp1 overlap-adds windowed grains,
            // so its output is continuous by construction and there is no wrap
            // point to click on.
            //
            // It reads from the same `ptr` as the granular side, so both engines
            // agree on position. specWin is its window size, so it still sets
            // how much material around `pos` the spectrum sees.
            // A STATIONARY analysis point makes every re-grab identical, which
            // is what made this feel dead. specMove wanders it slowly around
            // `pos` (within roughly specWin) so successive re-grabs catch
            // different material - the movement that made the first version
            // feel smeared and alive, but now confined near pos rather than
            // roaming the whole capture.
            specPtr = (ptr + (LFNoise2.kr(drift * 4)
                        * (specWin / BufDur.kr(buf)) * specMove))
                        .clip(0, playFrac);
            src   = Warp1.ar(1, buf, specPtr, pitch, specWin, -1, 4, 0.05, 1);

            chain = FFT(LocalBuf(fftSize), src, hop: 0.25, wintype: 1);   // Hann
            // refresh = 0 -> hold forever; refresh > 0 -> periodically re-grab
            // refresh = rate of re-grab; hold = how much of each cycle is
            // frozen. hold 1 = never reopens, hold 0 = never freezes (a live
            // spectral smear of the granular source, the most "alive" setting).
            frz   = Select.kr(refresh > 0,
                        [DC.kr(1), LFPulse.kr(refresh, 0, hold)]);
            // A capture must force an unfreeze, or with refresh = 0 the spectral
            // side would hold the PREVIOUS capture forever.
            frz   = frz * (1 - Trig.kr(t_recapture, 0.25));
            chain = PV_MagFreeze(chain, frz);
            // Phase diffusion: smears the metallic ring of a held spectrum.
            // Trigger-driven, so `diffuse` is a rate. 0 = bypassed.
            // (diffuse > 0) gates the trigger, so 0 is a real bypass rather
            // than "one randomisation at synth start, held forever"
            chain = PV_Diffuser(chain, Impulse.kr(diffuse) * (diffuse > 0));
            // A held spectrum can carry DC in bin 0; multiplied by the moving
            // gate envelope that becomes a thump on every capture.
            spec  = LeakDC.ar(IFFT(chain, wintype: 1)) * specGain;

            // ---- EQUAL-POWER MORPH ---------------------------------------
            gAmp = cos(morph * 0.5pi);
            sAmp = sin(morph * 0.5pi);
            sig  = (gran * gAmp) + (spec * sAmp);

            // Gated linear envelope. Unlike Lag this reaches true zero in a
            // known time, which is what lets the capture be bracketed cleanly.
            amp = EnvGen.kr(Env.asr(fade, 1, duck, \lin), gate);
            mix = (dry * (1 - blend)) + (sig * amp * blend);

            // wet-only amplitude for the UI (not the dry passthrough)
            Out.kr(ampOut, Amplitude.kr(sig * amp, 0.01, 0.3));
            Out.ar(out, mix ! 2);
        }).add;

        context.server.sync;

        recSynth = Synth.new(\agnesRec,
            [\buf, history, \posOut, posBus], context.xg);
        synth = Synth.after(recSynth, \agnesVoice,
            [\buf, freeze, \out, context.out_b, \ampOut, ampBus,
             \duck, duck], context.xg);

        // ---- Poll --------------------------------------------------------
        // Wet amplitude, for the grid UI. If your norns version disagrees with
        // this signature, the UI degrades gracefully (see agnes.lua).
        this.addPoll(\agnesAmp, { ampBus.getSynchronous });

        // ---- Commands ----------------------------------------------------

        // Capture is sequenced in time so the buffer swap happens in silence:
        //   duck -> wait -> copy -> wait for server -> repoint readers -> fade up
        // Doing it in one block (as a plain set) is what caused the click.
        this.addCommand("capture", "f", { arg msg;
            var sizeSec = msg[1].clip(0.05, 5.0);
            var len = (sizeSec * sr).asInteger;
            {
                var norm, writeFrame, start, first;

                // 1. fade the wet path to true silence
                synth.set(\gate, 0);
                (duck + 0.01).wait;

                // 2. swap buffer contents while nothing audible is reading them
                norm       = posBus.getSynchronous;
                writeFrame = (norm * histFrames).asInteger;
                start      = (writeFrame - len) % histFrames;
                if ((start + len) <= histFrames) {
                    history.copyData(freeze, 0, start, len);
                } {
                    first = histFrames - start;
                    history.copyData(freeze, 0, start, first);
                    history.copyData(freeze, first, 0, len - first);
                };
                0.05.wait;                       // let the copy land server-side

                // 3. repoint readers, force a spectral re-grab, fade back up
                synth.set(\playFrac, len / histFrames);
                synth.set(\t_recapture, 1);
                synth.set(\gate, 1);
            }.fork(SystemClock);                 // SystemClock: waits are seconds
        });

        // Clear: same duck-then-modify sequencing as capture, so wiping the
        // buffer under a sounding grain cloud doesn't click.
        this.addCommand("clear", "", {
            {
                synth.set(\gate, 0);
                (duck + 0.01).wait;
                freeze.zero;
                0.02.wait;
                synth.set(\playFrac, 1);
                // gate stays 0: nothing to sound until the next capture
            }.fork(SystemClock);
        });

        this.addCommand("morph",    "f", { arg m; synth.set(\morph,    m[1]); });
        this.addCommand("refresh",  "f", { arg m; synth.set(\refresh,  m[1]); });
        this.addCommand("diffuse",  "f", { arg m; synth.set(\diffuse,  m[1]); });
        this.addCommand("specGain", "f", { arg m; synth.set(\specGain, m[1]); });
        this.addCommand("specWin",  "f", { arg m; synth.set(\specWin,  m[1]); });
        this.addCommand("specMove", "f", { arg m; synth.set(\specMove, m[1]); });
        this.addCommand("hold",     "f", { arg m; synth.set(\hold,     m[1]); });
        this.addCommand("blend",    "f", { arg m; synth.set(\blend,    m[1]); });
        this.addCommand("fade",     "f", { arg m; synth.set(\fade,     m[1]); });
        this.addCommand("grainSize","f", { arg m; synth.set(\grainSize,m[1]); });
        this.addCommand("pitch",    "f", { arg m; synth.set(\pitch,    m[1]); });
        this.addCommand("pos",      "f", { arg m; synth.set(\pos,      m[1]); });
        this.addCommand("drift",    "f", { arg m; synth.set(\drift,    m[1]); });
        this.addCommand("overlaps", "f", { arg m; synth.set(\overlaps, m[1]); });
        this.addCommand("scatter",  "f", { arg m; synth.set(\scatter,  m[1]); });
    }

    free {
        synth.free;
        recSynth.free;
        history.free;
        freeze.free;
        posBus.free;
        ampBus.free;
    }
}
