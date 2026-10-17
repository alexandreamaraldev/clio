# Curvas de velocidade: como funciona e limites

- **Motor (Rust, `speed.rs`)**: pontos de controle (x 0..1 da fonte, 0,1x–10x), interpolação cúbica monótona em
  log2(velocidade) (sem overshoot). Duração na timeline = integral da curva; `speed` do clipe = velocidade média.
  Presets: Montagem, Herói, Bala, Flash de entrada/saída; qualquer edição vira "Personalizado". Undo/rascunhos,
  corte (cada metade recebe sua parte da curva e o in-point certo), trim (a forma é reescalada) e transições seguem válidos.
- **Plano**: `curve_segments` divide a fonte em 48 trechos de velocidade constante, reescalados para preencher exatamente
  `duration_ms`. Prévia e exportação usam o mesmo plano (`SpeedMap`).
- **Prévia**: o ExoPlayer tem uma velocidade global; o controlador troca `PlaybackParameters` a cada trecho (no tick da
  UI), e o playhead/seek usa o mapeamento fonte↔timeline. Mudanças de trecho podem atrasar ~1 frame da UI. Tom de voz
  preservado (Sonic do ExoPlayer, pitch = 1).
- **Exportação**: vídeo reprogramado quadro a quadro (`TimestampAdjustment` com o mapeamento; a origem é o primeiro quadro
  do item). Áudio: `SpeedChangingAudioProcessor`, o único processador de velocidade variável do Media3 1.4 — ele
  **muda o tom junto com a velocidade** (efeito "fita"). Clipes com velocidade constante mantêm o tom.
  Follow-up: processador próprio com Sonic por trecho para manter o tom em curvas.
- **Câmera lenta suave**: sem interpolação/blend de quadros; trechos lentos repetem (seguram) cada quadro da fonte por
  mais tempo, então 0,15x em vídeo de 30 fps fica "travado" (~4,5 fps efetivos). Follow-up: blend entre quadros
  vizinhos ou interpolação óptica; gravar em 60/120 fps ajuda.
- Curvas só em vídeos da trilha principal, com áudio não destacado.

## Frame grid
Curved clips export through `CurveRetime` (replaces SpeedChangeEffect + FpsCap for them): every
output slot (1/fps, from the clip start to the engine's clip end) shows the nearest retimed input
frame, so fast parts drop frames, slow parts repeat them (no pts gap over one frame) and the last
frame is held to the end. It reports the engine's duration to Media3, so the next item starts where
the timeline says even when the video track is shorter than the probed span (e.g. 4.000 s of video
in a 4.023 s file, which made exports ~23 ms short). Engine durations are not snapped (timeline = ∫
within 5 ms); a 30 fps snap was tried in 8e2be69 and reverted.

## Last-frame hold

CurveRetime fills the clip's output on a fixed frame grid (`base + round(i·step)`) up to the
engine's curved duration. When the source's video track ends before that (e.g. a track ~23 ms
shorter than the probed span, or decoder rounding), `onEnd()` repeats the last decoded frame in
every remaining slot, so the clip never comes out short and the next clip still starts on time.
No slot is ever emitted at or past `base + durUs` (the next clip's start); the planner filters
those out (`CurveRetimePlannerTest.neverEmitsAtOrPastTheNextClipStart`). Audio is not padded: the
speed-changing processor follows the same provider and ends with the source.
