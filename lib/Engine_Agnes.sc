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
// Core UGens only (FFT / PV_MagFreeze / PV_Diffuser / IFFT / Warp1) - no
// sc3-plugins dependency.
//
// Set norns PARAMETERS > LEVELS > monitor to 0; this engine mixes dry itself.

Engine_Agnes : CroneEngine {
    var <recSynth, <synth;
    var history, freeze, posBus, ampBus;
    var sr, histFrames;

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
                blend     = 0.5,   // dry/wet
                fade      = 0.05,  // wet fade time (s)
                wet       = 1;

            var dry, ptr, gran;
            var frames, phase, src, chain, frz, spec;
            var gAmp, sAmp, sig, amp, mix;

            dry = SoundIn.ar(0);

            // ---- GRANULAR ------------------------------------------------
            ptr = ((pos * playFrac) + (LFNoise2.kr(drift) * 0.02 * playFrac))
                    .clip(0, playFrac);
            gran = Warp1.ar(1, buf, ptr, pitch, grainSize, -1, overlaps, scatter, 1);

            // ---- SPECTRAL ------------------------------------------------
            // Loop a playhead through the valid captured region only.
            frames = BufFrames.kr(buf) * playFrac;
            phase  = Phasor.ar(0, BufRateScale.kr(buf) * pitch, 0, frames);
            src    = BufRd.ar(1, buf, phase, loop: 1);

            chain = FFT(LocalBuf(4096), src, hop: 0.25, wintype: 1);   // Hann
            // refresh = 0 -> hold forever; refresh > 0 -> periodically re-grab
            frz   = Select.kr(refresh > 0,
                        [DC.kr(1), LFPulse.kr(refresh, 0, 0.9)]);
            chain = PV_MagFreeze(chain, frz);
            // Phase diffusion: smears the metallic ring of a held spectrum.
            // Trigger-driven, so `diffuse` is a rate. 0 = bypassed.
            chain = PV_Diffuser(chain, Impulse.kr(diffuse));
            spec  = IFFT(chain, wintype: 1) * specGain;

            // ---- EQUAL-POWER MORPH ---------------------------------------
            gAmp = cos(morph * 0.5pi);
            sAmp = sin(morph * 0.5pi);
            sig  = (gran * gAmp) + (spec * sAmp);

            amp = Lag.kr(wet, fade);
            mix = (dry * (1 - blend)) + (sig * amp * blend);

            // wet-only amplitude for the UI (not the dry passthrough)
            Out.kr(ampOut, Amplitude.kr(sig * amp, 0.01, 0.3));
            Out.ar(out, mix ! 2);
        }).add;

        context.server.sync;

        recSynth = Synth.new(\agnesRec,
            [\buf, history, \posOut, posBus], context.xg);
        synth = Synth.after(recSynth, \agnesVoice,
            [\buf, freeze, \out, context.out_b, \ampOut, ampBus], context.xg);

        // ---- Poll --------------------------------------------------------
        // Wet amplitude, for the grid UI. If your norns version disagrees with
        // this signature, the UI degrades gracefully (see agnes.lua).
        this.addPoll(\agnesAmp, { ampBus.getSynchronous });

        // ---- Commands ----------------------------------------------------

        this.addCommand("capture", "f", { arg msg;
            var sizeSec = msg[1].clip(0.05, 5.0);
            var len = (sizeSec * sr).asInteger;
            posBus.get({ arg norm;
                var writeFrame = (norm * histFrames).asInteger;
                var start = (writeFrame - len) % histFrames;
                if ((start + len) <= histFrames) {
                    history.copyData(freeze, 0, start, len);
                } {
                    var first = histFrames - start;
                    history.copyData(freeze, 0, start, first);
                    history.copyData(freeze, first, 0, len - first);
                };
                synth.set(\playFrac, len / histFrames, \wet, 0);
                synth.set(\wet, 1);
            });
        });

        this.addCommand("morph",    "f", { arg m; synth.set(\morph,    m[1]); });
        this.addCommand("refresh",  "f", { arg m; synth.set(\refresh,  m[1]); });
        this.addCommand("diffuse",  "f", { arg m; synth.set(\diffuse,  m[1]); });
        this.addCommand("specGain", "f", { arg m; synth.set(\specGain, m[1]); });
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
