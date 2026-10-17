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
Curved clip lengths are snapped by the engine to whole 30 fps frames (also whole frames at 60 fps),
so timeline, music/voice-over and export agree and per-clip rounding does not add up. At 24/25 fps
exports a curved clip can still be off by under one frame of that rate. A split keeps the user's
cut point (not snapped).
