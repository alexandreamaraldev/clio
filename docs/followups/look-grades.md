# Look (filtros/ajustes): limitações e follow-ups

## Escopo do grading
- Só os clipes da trilha principal (e o fundo desfocado, que é derivado deles) recebem filtro/ajuste.
  Camadas/PIP e texto **não** são graduados; `set_clip_grade` rejeita clipes Overlay/Texto.
- Follow-up: graduar camadas exigiria um LUT por camada no compositor (ver `media3-compositor-layers.md`).

## HDR (HLG/PQ)
- O `ColorLutShaderProgram` do Media3 1.4.1 faz `checkArgument(!useHdr)`: um LUT num pipeline HDR derruba a exportação.
- Exportação: se algum clipe HDR tem grade, a composição usa
  `HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL` (API 29+), e o vídeo inteiro sai em SDR. Abaixo da API 29 o grade
  dos clipes HDR é ignorado e o app mostra um aviso em pt-BR.
- Prévia: com algum clipe HDR na playlist o efeito de LUT não é instalado; aparece o aviso
  "Vídeo HDR: filtros e ajustes não aparecem na prévia…".
- Follow-up: LUT em espaço linear/PQ (shader próprio) para manter HDR com grade.

## Banding em 8 bits
- O LUT é um cubo 17³ RGBA8 com interpolação trilinear, aplicado a frames de 8 bits. Gradientes suaves (céu, parede)
  com ajustes fortes (contraste/saturação altos, "Desbotado") podem mostrar banding.
- Follow-up: textura RGBA16F/33³ quando suportado, ou dithering no shader.

## Prévia
- O grade de cada frame é escolhido pelo `presentationTimeUs` (GradeSchedule), não pelo tick da UI;
  o LUT é assado em `Dispatchers.Default` (requisições conflacionadas) e o redesenho pausado é limitado a ~80 ms.
