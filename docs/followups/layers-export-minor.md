# Layers export: minor follow-ups (from the PR #2 review)

- **Edit lists:** `SequentialVideoDecoder` uses raw MediaExtractor sample times. MP4 `elst` offsets (trimmed/pre-roll footage) are ignored, so a layer can be early by the edit offset. Read the edit list (or compare against ExoPlayer's timeline) and shift the frame times.
- **YUV→RGB on the CPU:** it uses BT.601 coefficients and nearest sampling. Use BT.709 for HD sources (from `KEY_COLOR_STANDARD`) with bilinear chroma, or move the conversion to the GPU (decode to a SurfaceTexture and use an external-OES texture overlay).
- **Memory:** done in this PR. The budget counts the bitmap plus the three int arrays (`VIDEO_BPP` = 14 B/px), and a layer's decoder (codec, extractor, arrays) is closed as soon as export passes the layer's end.
- **EXIF on image layers:** `BitmapFactory` ignores the orientation tag, so rotated phone photos come out sideways. Apply `ExifInterface` orientation in `decodeStill`, and use it for the aspect in `plan`.
- **Bench:** add a VFR phone clip and a 60 fps clip to `ExportLayersBenchTest` (decodes per frame, wall time, output fps).
- **HDR / 10-bit layers:** `SequentialVideoDecoder` assumes 8-bit YUV 4:2:0 output (`COLOR_FormatYUV420Flexible`). 10-bit HLG/PQ phone footage (P010) is either rejected or converted with the wrong range and transfer, and the export is SDR. Detect `KEY_COLOR_TRANSFER`/P010 and tone-map to SDR (or decode on the GPU with Media3's HDR path), and add a 10-bit clip to the bench.
