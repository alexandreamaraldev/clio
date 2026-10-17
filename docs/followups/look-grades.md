# Look (filtros/ajustes): limitações e follow-ups

## Escopo do grading
- Só os clipes da trilha principal (e o fundo desfocado, que é derivado deles) recebem filtro/ajuste.
  Camadas/PIP e texto **não** são graduados; `set_clip_grade` rejeita clipes Overlay/Texto.
- Follow-up: graduar camadas exigiria um LUT por camada no compositor (ver `media3-compositor-layers.md`).

## HDR (HLG/PQ)
- O `ColorLutShaderProgram` do Media3 1.4.1 faz `checkArgument(!useHdr)`: um LUT num pipeline HDR derruba a exportação.
- Exportação: um clipe HDR deixa o pipeline inteiro da sequência em HDR. Por isso, se houver **qualquer** clipe HDR
  e algum grade (mesmo num clipe SDR), a composição usa `HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL` (API 29+) e o
  vídeo inteiro sai em SDR. Abaixo da API 29 todos os grades são ignorados nessa exportação e o app mostra um aviso.
- Fallback por aparelho: o tone-map por GPU exige `GL_EXT_YUV_target` (falta no emulador do CI). Ordem tentada:
  MediaCodec (API 31+) → OpenGL (API 29+) → `HDR_MODE_EXPERIMENTAL_FORCE_INTERPRET_HDR_AS_SDR` (mantém o grade,
  cores do trecho HDR lavadas, com aviso) → sem grades (com aviso). Cada falha (`ExportException`) tenta a próxima;
  se todas falharem, erro em pt-BR ("Não foi possível exportar este vídeo HDR neste aparelho…"), sem crash.
- Projetos HDR **sem** grade não são convertidos: exportam em HDR, numa única tentativa.
- Prévia: uma fonte ainda não sondada conta como HDR (o LUT não roda nela até o `HdrProbe` responder).
- Follow-up: **verificar a exportação HDR→SDR num aparelho físico** (o emulador do CI não decodifica HLG 10-bit nem tem
  `GL_EXT_YUV_target`; lá só o caminho de erro é exercitado).
- Detecção: `HdrProbe` (MediaExtractor, `KEY_COLOR_TRANSFER`) roda em background e guarda o resultado por URI.
- Prévia: com algum clipe HDR na playlist o efeito de LUT não é instalado; aparece o aviso
  "Vídeo HDR: filtros e ajustes não aparecem na prévia…".
- Follow-up: LUT em espaço linear/PQ (shader próprio) para manter HDR com grade.
- Cobertura de teste: `ExportGradeE2ETest#hdrSourcesExportWithGrades` (HLG com grade; SDR com grade + HLG) só cobre o erro `export_error` no CI; ver o follow-up acima.

## Banding em 8 bits
- O LUT é um cubo 33³ RGBA8 (os .cube de origem são 17³, reamostrados) com interpolação trilinear, aplicado a frames de 8 bits. Gradientes suaves (céu, parede)
  com ajustes fortes (contraste/saturação altos, "Desbotado") podem mostrar banding.
- Follow-up: textura RGBA16F/33³ quando suportado, ou dithering no shader.

## Prévia
- O grade de cada frame é escolhido pelo `presentationTimeUs` (GradeSchedule), não pelo tick da UI;
  o LUT é assado em `Dispatchers.Default` (requisições conflacionadas) e o redesenho pausado é limitado a ~80 ms.

## Cor de imagens (cinza 128 → 115) — causa raiz
- Não era o LUT nem faixa limitada/cheia: o `DefaultVideoFrameProcessor` do Media3 1.4, com o espaço de trabalho SDR
  padrão (`WORKING_COLOR_SPACE_DEFAULT`), converte entradas sRGB (imagens) com `smpte170mOetf(srgbEotf(x))`
  (`fragment_shader_transformation_sdr_internal_es2.glsl`). 128/255 → linear 0,216 → OETF 170M 0,452 → **115**;
  bate também com as outras amostras (80 → 65, 60 → 41). Vídeos SDR passam sem conversão.
- Correção: o Exporter usa `setSdrWorkingColorSpace(WORKING_COLOR_SPACE_ORIGINAL)`, que mantém os valores da entrada
  (imagens saem como na prévia/galeria). HDR continua no caminho de tone-map.
- Resíduo (cinza 128 → 132,130,132 no teste): **não é da exportação**. O arquivo exportado tem exatamente
  Y126 U128 V128 (BT.709 limitado ideal) no cinza, e vermelho/pele/azul batem com ±1 ao decodificar com a matriz BT.709.
  Um stream de cinza perfeito codificado só com MediaCodec (sem GL) também é lido como 132,130,132 pelo
  `MediaMetadataRetriever` do emulador: o desvio é a conversão YUV→RGB do retriever. Testes de cor devem usar
  `Bt709Frame` (androidTest), que decodifica o Y'CbCr com a matriz BT.709 limitada.

## Prévia de imagens
- No Media3 1.4 o `PlayerView` mostra imagens por um `ImageView` (`ImageOutput`), fora do `VideoFrameProcessor`:
  a prévia de stills já mostra os valores originais (cinza 128), então `WORKING_COLOR_SPACE_ORIGINAL` não se aplica
  ao player (para vídeo SDR, DEFAULT e ORIGINAL são idênticos).
- Consequência/follow-up: filtros em **imagens** não aparecem na prévia (aparecem na exportação). Corrigir exigiria
  renderizar stills pelo pipeline de efeitos (Media3 1.5+/CompositionPlayer) ou graduar o bitmap no `ImageOutput`.
