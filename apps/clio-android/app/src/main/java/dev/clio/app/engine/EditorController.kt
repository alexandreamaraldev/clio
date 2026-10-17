package dev.clio.app.engine

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import dev.clio.app.ClioApp
import dev.clio.app.ui.screens.*
import kotlinx.coroutines.*
import kotlin.math.abs
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import uniffi.clio_ffi.Caption
import uniffi.clio_ffi.ClioProject
import uniffi.clio_ffi.Snapshot
import uniffi.clio_ffi.TrackKind
import java.io.File

sealed interface EditorEvent {
    data object Back : EditorEvent
    data object PickMedia : EditorEvent
    data object PickMusic : EditorEvent
    data object RequestMic : EditorEvent
}

sealed interface ExportState {
    data object Closed : ExportState
    data object Choosing : ExportState
    data class Running(val progress: Float, val label: String) : ExportState
    data class Done(val uri: Uri) : ExportState
    data class Failed(val message: String) : ExportState
}

/**
 * One open editing session (survives rotation). The engine project (Rust, via UniFFI) is the source
 * of truth for the timeline and undo history; all FFI calls run on a single background thread.
 * Media3 ExoPlayer plays the video track as an interim preview, synced to the timeline through
 * clipping configurations. Every edit autosaves the draft JSON.
 *
 * Args (SavedStateHandle): "draftId" (String), "isNew" (Boolean).
 */
class EditorController(app: Application, handle: SavedStateHandle) : AndroidViewModel(app), EditorActions {
    private val drafts = DraftStore(app)
    private val media = Media(app)
    private val models = ModelManager(app)
    private val appScope = (app as ClioApp).appScope
    private val exporter = Exporter(app)
    private val exportStateHolder = mutableStateOf<ExportState>(ExportState.Closed)
    var exportState: ExportState
        get() = exportStateHolder.value
        private set(v) { exportStateHolder.value = v; QaHooks.export = v }
    private var lastSnap: Snapshot? = null
    private var exportJob: Job? = null

    /** All engine access is serialised on this dispatcher (off Main). */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val engine = Dispatchers.Default.limitedParallelism(1)

    val draftId: String = handle["draftId"] ?: DraftStore.newId()
    private val isNew: Boolean = handle["isNew"] ?: false
    private val imports: List<String> = handle.get<ArrayList<String>>("imports").orEmpty()
    private lateinit var project: ClioProject
    private var videoTrack: ULong = 0u
    private val ready = CompletableDeferred<Unit>()

    private val uiHolder = mutableStateOf(EditorUi("", emptyList(), 0f, false, hasMedia = false, loading = true))
    var ui: EditorUi
        get() = uiHolder.value
        private set(v) { uiHolder.value = v; QaHooks.ui = v }
    /** Read only inside draw/layout lambdas so playback doesn't recompose the tree per frame. */
    private val playheadState = mutableFloatStateOf(0f)
    val playhead: () -> Float = { playheadState.floatValue }

    private val _events = MutableSharedFlow<EditorEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<EditorEvent> = _events

    /** Main-track transition look for the preview (see Layers.PreviewLook). */
    private val previewLook = Layers.PreviewLook()
    /** Filter/colour grade of the clip under the playhead: one baked LUT, same effect as export. */
    private val gradeLib by lazy { Grades.library(app) }
    /** Player position in playlist source µs (see GradeSchedule), updated on the UI tick and seeks. */
    @Volatile private var approxPlaylistUs = 0L
    private val gradeSchedule = GradeSchedule { approxPlaylistUs }
    private val previewGrade = GradeLut { pts -> gradeSchedule.planeAt(pts, gradeLib.identity) }
    private var grades: Map<Long, uniffi.clio_ffi.ColorGrade> = emptyMap()
    /** Live slider/strip value for one clip, before it is committed to the engine. */
    @Volatile private var gradeDraft: Pair<Long, uniffi.clio_ffi.ColorGrade>? = null
    /** Bake requests: conflated, baked on Dispatchers.Default, latest wins. */
    private val bakeRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)
    private var lastRedrawMs = 0L
    private var hdrInPlaylist = false
    private var lastPlanKey: Any? = null
    private fun vids(): List<String> { val app = getApplication<Application>(); return videoClips.map { it.source }.filter { !MediaKind.isImage(app, it) } }
    private val bakeJob = viewModelScope.launch {
        bakeRequests.collect {
            val clips = videoClips; val gs = grades; val draft = gradeDraft
            val app = getApplication<Application>()
            // Only re-bake/redraw when the per-item plan (source spans + resolved grades) changed;
            // planes themselves are cached per grade in the shared Grades.Library.
            val key = clips.map { c -> Triple(c.source, Mix.spanMs(c.durationMs.toLong(), c.speed) to c.durationMs, draft?.takeIf { it.first == c.id.toLong() }?.second ?: gs[c.id.toLong()]) }
            if (key == lastPlanKey) return@collect
            lastPlanKey = key
            val plan = withContext(Dispatchers.Default) {
                var at = 0L
                val starts = LongArray(clips.size); val planes = Array(clips.size) { i ->
                    val c = clips[i]
                    starts[i] = at
                    at += 1000 * (if (MediaKind.isImage(app, c.source)) c.durationMs.toLong() else Mix.spanMs(c.durationMs.toLong(), c.speed))
                    val g = draft?.takeIf { it.first == c.id.toLong() }?.second ?: gs[c.id.toLong()]
                    if (g == null) gradeLib.identity else gradeLib.plane(g)
                }
                starts to planes
            }
            gradeSchedule.publish(plan.first, plan.second)
            // Throttled redraw of the paused frame (at most every 80 ms; the last request always lands).
            if (!player.isPlaying && clips.isNotEmpty()) {
                val wait = 80 - (android.os.SystemClock.uptimeMillis() - lastRedrawMs)
                if (wait > 0) delay(wait)
                lastRedrawMs = android.os.SystemClock.uptimeMillis()
                seekTimeline(playheadState.floatValue)
            }
        }
    }
    /** Next picker result goes to an overlay layer instead of the main track. */
    private var pickForLayer = false
    val player: ExoPlayer = ExoPlayer.Builder(app).build().apply {
        addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                ui = ui.copy(playing = isPlaying)
                audio.tick((playheadState.floatValue * 1000).toLong(), isPlaying && !voice.isRecording)
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { applyItemParams() }
            override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_ENDED) { pause(); seekTimeline(0f) } }
        })
    }
    /** Audio lanes (music, detached audio, voiceover, SFX) preview on their own players, slaved to the timeline. */
    private val audio = AudioMix(app)
    private val voice = VoiceoverRecorder(app)
    private var voiceStartMs = 0L
    private var clipStarts: LongArray = LongArray(0)
    /** Speed curves of main clips by id: source <-> timeline mapping and per-segment preview speed. */
    private var speedMaps: Map<Long, SpeedMap> = emptyMap()
    /** Keyframes by clip id (engine evaluates them; preview and export share `clip_look_keyed`). */
    private var keyTracks: Map<ULong, uniffi.clio_ffi.KeyTracks> = emptyMap()
    private var allClips: Map<Long, uniffi.clio_ffi.Clip> = emptyMap()
    private fun localOf(c: uniffi.clio_ffi.Clip?, srcMs: Long): Long = c?.let { speedMaps[it.id.toLong()] }?.timelineAt(srcMs.toDouble())?.toLong() ?: (srcMs / (c?.speed ?: 1f)).toLong()
    private fun srcOf(c: uniffi.clio_ffi.Clip?, localMs: Long): Long = c?.let { speedMaps[it.id.toLong()] }?.sourceAt(localMs.toDouble())?.toLong() ?: Mix.spanMs(localMs, c?.speed ?: 1f)
    private var videoClips: List<uniffi.clio_ffi.Clip> = emptyList()
    private var saveJob: Job? = null
    private var captionJob: Job? = null
    private var lastJson: String? = null

    init {
        viewModelScope.launch {
            withContext(engine) {
                val json = if (isNew) null else drafts.load(draftId)
                project = json?.let { runCatching { ClioProject.fromDraftJson(it) }.getOrNull() }
                    ?: ClioProject(defaultName(), 1080u, 1920u)
                videoTrack = project.tracks().firstOrNull { it.kind == TrackKind.VIDEO }?.id ?: project.addTrack(TrackKind.VIDEO, "Vídeo")
            }
            ready.complete(Unit)
            apply(edit(save = false) { }, rebuildPlayer = true)
            if (isNew && imports.isNotEmpty()) addMedia(imports.map(Uri::parse))
            else if (isNew) _events.tryEmit(EditorEvent.PickMedia)
        }
    }

    private suspend fun defaultName(): String {
        val n = drafts.list().count { it.title.startsWith("Projeto ") } + 1
        return "Projeto $n"
    }

    /** Run an engine mutation off Main, then publish one snapshot. */
    private suspend fun edit(save: Boolean = true, block: ClioProject.() -> Unit): Snapshot {
        ready.await()
        val snap = withContext(engine) { project.block(); project.snapshot() }
        if (save) scheduleSave()
        return snap
    }

    private fun launchEdit(rebuildPlayer: Boolean = false, block: ClioProject.() -> Unit) =
        viewModelScope.launch { apply(edit(block = block), rebuildPlayer) }

    /** Points of a (possibly windowed, after split/trim) curve in the clip's own 0..1 range, for the editor. */
    private fun visiblePoints(c: uniffi.clio_ffi.SpeedCurve): List<Pair<Float, Float>> {
        if (c.from == 0f && c.to == 1f) return c.points.map { it.x to it.speed }
        val w = c.to - c.from
        val inner = c.points.filter { it.x > c.from + 0.01f * w && it.x < c.to - 0.01f * w }.map { (it.x - c.from) / w to it.speed }
        return (listOf(0f to uniffi.clio_ffi.curveSpeedAt(c, 0f)) + inner + (1f to uniffi.clio_ffi.curveSpeedAt(c, 1f))).take(12)
    }

    private fun apply(s: Snapshot, rebuildPlayer: Boolean) {
        val curves = s.speedCurves.associate { it.clipId to it.curve }
        keyTracks = s.keyframes.associate { it.clipId to it.keys }
        allClips = s.tracks.flatMap { it.clips }.associateBy { it.id.toLong() }
        // Paused edits: show the keyed main-clip transform at the playhead right away.
        Layers.mainClips(s).let { main ->
            val ms = (playheadState.floatValue * 1000).toLong()
            val c = main.firstOrNull { ms >= it.startMs.toLong() && ms < (it.startMs + it.durationMs).toLong() } ?: main.lastOrNull()
            val k = c?.let { keyTracks[it.id] }
            Layers.PreviewKeys.transform.value = if (c == null || k == null) Layers.IDENTITY.transform
                else Layers.mainTransform(c, k, ms - c.startMs.toLong(), Layers.Canvas(s.summary.width.toInt(), s.summary.height.toInt(), s.fill))
        }
        speedMaps = s.tracks.flatMap { it.clips }.mapNotNull { c -> curves[c.id]?.let { c.id.toLong() to SpeedMap.of(it, Mix.spanMs(c.durationMs.toLong(), c.speed), c.durationMs.toLong()) } }.toMap()
        val uiTracks = s.tracks.filter { it.clips.isNotEmpty() || it.kind == TrackKind.VIDEO }.map { t ->
            UiTrack(t.name, when (t.kind) { TrackKind.VIDEO -> "video"; TrackKind.AUDIO -> "audio"; TrackKind.TEXT -> "text"; TrackKind.EFFECT -> "fx"; TrackKind.OVERLAY -> "overlay" },
                t.clips.map { c -> UiClip(c.name, c.startMs.toFloat() / 1000f, c.durationMs.toFloat() / 1000f, c.id.toLong(), c.source, c.inMs.toFloat() / 1000f,
                    c.sourceMs.toFloat() / 1000f, c.speed, c.volume, c.fadeInMs.toFloat() / 1000f, c.fadeOutMs.toFloat() / 1000f, c.muted, detached = c.detachedTo != null,
                    speedPreset = curves[c.id]?.preset, speedPoints = curves[c.id]?.let(::visiblePoints).orEmpty(),
                    keys = LayersMapping.keys(keyTracks[c.id]), keyable = t.kind == TrackKind.VIDEO || t.kind == TrackKind.OVERLAY,
                    zoomPan = if (t.kind == TrackKind.VIDEO) LayersMapping.zoomPan(keyTracks[c.id], c.durationMs.toLong()) else null) })
        }
        val caps = s.captions.map { Engine.Cap(it.startMs.toLong(), it.endMs.toLong(), it.text, it.words) }
        val withCaps = if (caps.isEmpty()) uiTracks else uiTracks + UiTrack("Legendas", "text",
            caps.mapIndexed { i, c -> UiClip("Aa  " + c.text, c.startMs / 1000f, (c.endMs - c.startMs).coerceAtLeast(1) / 1000f, -1L - i) })
        ui = ui.copy(title = s.summary.name, tracks = withCaps, durationS = s.summary.durationMs.toFloat() / 1000f, captions = caps,
            hasMedia = s.tracks.any { it.kind == TrackKind.VIDEO && it.clips.isNotEmpty() }, canUndo = s.canUndo, canRedo = s.canRedo,
            loading = false, captionLanguage = s.speechLanguage, fill = s.fill, blurBg = s.blurBg, canvasW = s.summary.width.toInt(), canvasH = s.summary.height.toInt(),
            texts = s.tracks.filter { it.kind == TrackKind.TEXT }.flatMap { it.clips }.map { c -> TextItem(c.id.toLong(), c.name, c.startMs.toLong(), (c.startMs + c.durationMs).toLong(), s.textStyles.firstOrNull { it.clipId == c.id }?.style ?: StyledText.defaultStyle) },
            music = s.tracks.firstOrNull { it.kind == TrackKind.AUDIO && it.name == AudioLanes.MUSIC }?.clips?.firstOrNull()?.name,
            layers = LayersMapping.layers(getApplication(), s), cuts = LayersMapping.cuts(s))
        grades = Grades.byClip(s.grades)
        ui = ui.copy(grades = grades)
        Waveforms.request(getApplication(), audio.clipsOf(s))
        lastSnap = s
        if (rebuildPlayer) rebuildPlaylist(s) else refreshClipCache(s)
    }

    private fun rebuildPlaylist(s: Snapshot) {
        val clips = s.tracks.firstOrNull { it.id == videoTrack }?.clips.orEmpty()
        clipStarts = LongArray(clips.size) { clips[it].startMs.toLong() }
        videoClips = clips
        syncPreviewEffects()
        val app = getApplication<Application>()
        player.setMediaItems(clips.map { c ->
            MediaItem.Builder().setUri(c.source).setMediaId(c.id.toString()).apply {
                if (MediaKind.isImage(app, c.source)) setImageDurationMs(c.durationMs.toLong())
                else setClippingConfiguration(MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(c.inMs.toLong()).setEndPositionMs(c.inMs.toLong() + Mix.spanMs(c.durationMs.toLong(), c.speed)).build())
            }.build()
        })
        player.prepare()
        scheduleCurveMessages(clips)
        audio.setClips(audio.clipsOf(s))
        applyItemParams()
        if (clips.isNotEmpty()) seekTimeline(playheadState.floatValue.coerceAtMost(ui.durationS))
    }

    /** Called by the UI once per frame while playing (withFrameNanos). */
    fun syncPlayhead() {
        val idx = player.currentMediaItemIndex
        val c = videoClips.getOrNull(idx)
        val local = localOf(c, player.currentPosition)
        playheadState.floatValue = (clipStarts.getOrElse(idx) { 0L } + local) / 1000f
        approxPlaylistUs = playlistUs(idx, player.currentPosition)
        updateLook(idx, local)
        if (c != null) player.volume = AudioGain.preview(AudioGain.at(c, local))
        if (voice.isRecording) player.volume = 0f // no speaker bleed into the mic
        audio.tick((playheadState.floatValue * 1000).toLong(), player.isPlaying && !voice.isRecording)
    }

    /** Per-clip speed/volume for whichever item ExoPlayer is on (it has one global setting). */
    /**
     * Curves on preview: one PlayerMessage per speed change at its exact media position (playback
     * thread), so changes land by presentation time, not on the UI tick. Curved clips change pitch
     * with speed, exactly like the export (Media3's variable-speed audio is pitch-coupled).
     */
    private val curveMessages = mutableListOf<androidx.media3.exoplayer.PlayerMessage>()
    private fun setPlaybackSpeed(speed: Float, curved: Boolean) {
        val pitch = if (curved) speed else 1f
        val cur = player.playbackParameters
        if (abs(cur.speed - speed) > 0.002f * speed || abs(cur.pitch - pitch) > 0.002f) player.playbackParameters = PlaybackParameters(speed, pitch)
    }
    private fun scheduleCurveMessages(clips: List<uniffi.clio_ffi.Clip>) {
        curveMessages.forEach { it.cancel() }; curveMessages.clear()
        clips.forEachIndexed { idx, c ->
            val m = speedMaps[c.id.toLong()] ?: return@forEachIndexed
            for (srcMs in m.changesSrcMs) {
                val speed = m.speedAtSource(srcMs.toDouble())
                curveMessages += player.createMessage { _, _ -> setPlaybackSpeed(speed, curved = true) }
                    .setLooper(android.os.Looper.getMainLooper()).setPosition(idx, srcMs).setDeleteAfterDelivery(false).send()
            }
        }
    }

    private fun applyItemParams() {
        val c = videoClips.getOrNull(player.currentMediaItemIndex) ?: return
        // Curves: the current segment's speed (pitch is kept by ExoPlayer's Sonic, pitch = 1).
        val m = speedMaps[c.id.toLong()]
        setPlaybackSpeed(m?.speedAtSource(player.currentPosition.toDouble()) ?: c.speed, curved = m != null)
        player.volume = if (AudioGain.silent(c)) 0f else AudioGain.preview(c.volume)
    }

    private fun updateLook(idx: Int, localMs: Long) {
        val c = videoClips.getOrNull(idx) ?: return
        // No FFI call (and no GL effects installed) unless this clip has a transition.
        val k = keyTracks[c.id]
        Layers.PreviewKeys.transform.value = if (k == null) Layers.IDENTITY.transform
            else Layers.mainTransform(c, k, localMs, Layers.Canvas(ui.canvasW, ui.canvasH, ui.fill))
        if (!Layers.hasTransition(videoClips, idx)) { previewLook.reset(); return }
        previewLook.look = Layers.look(c, Layers.incoming(videoClips, idx), localMs)
    }

    /** Installs the transition GL effects only while the main track has any transition. */
    private var effectsOn = false to false
    private fun syncPreviewEffects() {
        val t = videoClips.indices.any { Layers.hasTransition(videoClips, it) }
        val app = getApplication<Application>()
        // Media3 1.4's LUT shader is SDR-only: with an HDR clip in the playlist the grade is not previewed.
        val vids = videoClips.map { it.source }.filter { !MediaKind.isImage(app, it) }
        // Not probed yet counts as HDR, so the SDR-only LUT never runs on an HDR source.
        hdrInPlaylist = vids.any { HdrProbe.cached(it) != false }
        // Unknown URIs are probed in the background, then the effects are re-synced.
        if (vids.any { HdrProbe.cached(it) == null }) viewModelScope.launch { if (HdrProbe.prefetch(app, vids)) redrawPaused() }
        val g = !hdrInPlaylist && (gradeDraft != null || videoClips.any { grades.containsKey(it.id.toLong()) })
        bakeRequests.tryEmit(Unit)
        if ((t to g) == effectsOn) return
        effectsOn = t to g
        previewLook.reset()
        // Colour first (the LUT), then the transition transform/fade, as in export.
        player.setVideoEffects((if (g) listOf<androidx.media3.common.Effect>(previewGrade) else emptyList()) + (if (t) previewLook.effects else emptyList()))
    }

    /** Re-bakes (off the main thread) and re-renders the paused frame so a new grade shows. */
    private fun redrawPaused() {
        syncPreviewEffects()
        if (vids().any { HdrProbe.cached(it) == true } && (gradeDraft != null || grades.isNotEmpty())) ui = ui.copy(notice = HdrProbe.PREVIEW_NOTICE)
    }

    /** Playlist source µs of item [idx] at source offset [srcMs] (what GradeSchedule counts in). */
    private fun playlistUs(idx: Int, srcMs: Long): Long {
        val app = getApplication<Application>()
        var at = 0L
        for (i in 0 until idx.coerceAtMost(videoClips.size)) { val c = videoClips[i]; at += if (MediaKind.isImage(app, c.source)) c.durationMs.toLong() else Mix.spanMs(c.durationMs.toLong(), c.speed) }
        return (at + srcMs) * 1000
    }

    /** The media picker was dismissed without a choice. */
    fun pickCancelled() { pickForLayer = false }

    private fun seekTimeline(s: Float) {
        val ms = (s * 1000).toLong()
        val idx = clipStarts.indexOfLast { it <= ms }.coerceAtLeast(0)
        if (clipStarts.isNotEmpty()) {
            val src = srcOf(videoClips.getOrNull(idx), ms - clipStarts[idx])
            approxPlaylistUs = playlistUs(idx, src); gradeSchedule.seeked(approxPlaylistUs)
            player.seekTo(idx, src)
        }
        applyItemParams()
        playheadState.floatValue = s
        if (clipStarts.isNotEmpty()) updateLook(idx, ms - clipStarts[idx])
        audio.seek(ms)
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        ui = ui.copy(savedLabel = SaveState.Saving)
        saveJob = viewModelScope.launch {
            delay(600)
            val json = withContext(engine) { if (project.summary().clipCount == 0u) null else project.toDraftJson() } ?: run {
                ui = ui.copy(savedLabel = SaveState.Draft); return@launch
            }
            lastJson = json
            drafts.save(draftId, json)
            ui = ui.copy(savedLabel = SaveState.Saved)
        }
    }

    fun addMedia(uris: List<Uri>) = viewModelScope.launch {
        val infos = uris.mapNotNull { u ->
            runCatching { getApplication<Application>().contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            media.probe(u)?.takeIf { it.durationMs > 0 }
        }
        if (infos.isEmpty()) return@launch
        val app = getApplication<Application>()
        if (pickForLayer) {
            pickForLayer = false
            val at = (playheadState.floatValue * 1000).toLong().toULong()
            apply(edit {
                infos.forEach {
                    val still = MediaKind.isImage(app, it.uri)
                    val len = if (still) 3000uL else it.durationMs.toULong()
                    val id = addOverlay(it.name, it.uri, at, len)
                    setClipSourceInfo(id, if (still) 0u else it.durationMs.toULong(), it.width.coerceAtLeast(0).toUInt(), it.height.coerceAtLeast(0).toUInt())
                }
            }, rebuildPlayer = false)
            return@launch
        }
        apply(edit {
            infos.forEach {
                val id = appendClip(videoTrack, it.name, it.uri, it.durationMs.toULong())
                val still = MediaKind.isImage(app, it.uri)
                setClipSourceInfo(id, if (still) 0u else it.durationMs.toULong(), it.width.coerceAtLeast(0).toUInt(), it.height.coerceAtLeast(0).toUInt())
            }
        }, rebuildPlayer = true)
    }

    // --- EditorActions ---
    /** Home lists drafts as soon as it appears, so leave only after the final save is on disk. */
    override fun back() {
        player.pause()
        val flush = flushSave()
        viewModelScope.launch { withTimeoutOrNull(2000) { flush?.join() }; _events.tryEmit(EditorEvent.Back) }
    }
    override fun export() { player.pause(); exportState = ExportState.Choosing }

    fun closeExport() { exportJob?.cancel(); exportState = ExportState.Closed }

    fun startExport(spec: ExportSpec, style: CaptionStyle = ui.captionStyle) {
        val snap = lastSnap ?: return
        exportJob = viewModelScope.launch {
            exportState = ExportState.Running(0f, spec.label)
            exportState = try {
                ExportState.Done(exporter.export(snap, spec, style) { p -> exportState = ExportState.Running(p, spec.label) }).also { exporter.lastNotice?.let { n -> ui = ui.copy(notice = n) } }
            } catch (e: CancellationException) { throw e } catch (e: Throwable) {
                android.util.Log.e("ClioExport", "export failed", e)
                // The screen shows one pt-BR sentence; the raw cause chain (GL, codec dumps) is only in the log above.
                ExportState.Failed(generateSequence<Throwable>(e) { it.cause }.filterIsInstance<UserFacingExportError>().firstOrNull()?.message
                    ?: (e as? IllegalArgumentException)?.message
                    ?: "Não foi possível exportar o vídeo. Tente novamente.")
            }
        }
    }

    override fun setCaptionStyle(style: CaptionStyle) { ui = ui.copy(captionStyle = style) }
    /** Saved in the draft (engine), so it survives reopening the project. */
    override fun setCaptionLanguage(language: String?) = launchEdit { setSpeechLanguage(language) }.let { }

    // --- Canvas: aspect ratio + framing ---
    override fun toggleFill() = launchEdit(rebuildPlayer = false) {
        val f = snapshot()
        when { f.fill -> setFraming(false, false); f.blurBg -> setFraming(true, false); else -> setFraming(false, true) }
    }.let { }
    override fun setFraming(fill: Boolean, blur: Boolean) = launchEdit { setFraming(fill, blur) }.let { }
    override fun setAspect(a: Aspect) { val (w, h) = a.canvas(); launchEdit { runCatching { setCanvas(w.toUInt(), h.toUInt()) } } }

    // --- Clip edits (all undoable in the engine) ---
    /** Explicit clip, or the main-track clip under the playhead. */
    private fun ClioProject.target(clipId: Long?): ULong? {
        val ms = (playheadState.floatValue * 1000).toULong()
        return clipId?.takeIf { it >= 0 }?.toULong()
            ?: tracks().firstOrNull { it.id == videoTrack }?.clips?.firstOrNull { ms >= it.startMs && ms < it.startMs + it.durationMs }?.id
    }
    override fun trim(clipId: Long, inS: Float, lenS: Float) = launchEdit(rebuildPlayer = true) {
        runCatching { trimClip(clipId.toULong(), (inS * 1000).toLong().coerceAtLeast(0).toULong(), (lenS * 1000).toLong().coerceAtLeast(1).toULong()) }
    }.let { }
    override fun move(clipId: Long, toIndex: Int) = launchEdit(rebuildPlayer = true) { runCatching { moveClip(clipId.toULong(), toIndex.coerceAtLeast(0).toUInt()) } }.let { }
    override fun duplicate(clipId: Long?) = launchEdit(rebuildPlayer = true) { target(clipId)?.let { runCatching { duplicateClip(it) } } }.let { }
    override fun setSpeedPreset(clipId: Long?, preset: String?) = launchEdit(rebuildPlayer = true) {
        target(clipId)?.let { id -> runCatching { if (preset == null) clearSpeedCurve(id) else setSpeedPreset(id, preset) } }
    }.also { if (preset != null) curveAudioNotice(clipId) }.let { }
    /** Clips already told about curve audio this session (undo/redo must not repeat it). */
    private val curveNoticeShown = mutableSetOf<Long>()
    /** Short pt-BR heads-up the first time a clip with sound gets a curve (once per clip per session). */
    private fun curveAudioNotice(clipId: Long?) {
        val c = videoClips.firstOrNull { it.id.toLong() == clipId } ?: videoClips.firstOrNull() ?: return
        if (curveNoticeShown.add(c.id.toLong()) && !c.muted && c.volume > 0f && !speedMaps.containsKey(c.id.toLong())) ui = ui.copy(notice = "Curva de velocidade: o som acompanha a velocidade (o tom muda junto).")
    }
    override fun setSpeedCurve(clipId: Long?, points: List<Pair<Float, Float>>) = launchEdit(rebuildPlayer = true) {
        target(clipId)?.let { id -> runCatching { setSpeedCurve(id, uniffi.clio_ffi.SpeedCurve("personalizado", points.map { uniffi.clio_ffi.SpeedPoint(it.first, it.second) })) } }
    }.also { curveAudioNotice(clipId) }.let { }
    override fun curveSpeedAt(points: List<Pair<Float, Float>>, x: Float): Float =
        runCatching { uniffi.clio_ffi.curveSpeedAt(uniffi.clio_ffi.SpeedCurve("personalizado", points.map { uniffi.clio_ffi.SpeedPoint(it.first, it.second) }), x) }.getOrDefault(1f)
    override fun setSpeed(clipId: Long?, speed: Float) = launchEdit(rebuildPlayer = true) { target(clipId)?.let { runCatching { setClipSpeed(it, speed) } } }.let { }
    override fun setAudio(clipId: Long?, volume: Float, fadeInS: Float, fadeOutS: Float, muted: Boolean) = launchEdit(rebuildPlayer = false) {
        target(clipId)?.let { runCatching { setClipAudio(it, volume, (fadeInS * 1000).toLong().toULong(), (fadeOutS * 1000).toLong().toULong(), muted) } }
    }.let { }

    private fun refreshClipCache(s: Snapshot) {
        videoClips = s.tracks.firstOrNull { it.id == videoTrack }?.clips.orEmpty()
        syncPreviewEffects()
        audio.setClips(audio.clipsOf(s))
        applyItemParams()
    }

    // --- Text overlays ---
    private suspend fun textTrack(): ULong = withContext(engine) {
        project.tracks().firstOrNull { it.kind == TrackKind.TEXT }?.id ?: project.addTrack(TrackKind.TEXT, "Texto")
    }
    override fun addText() {
        val at = (playheadState.floatValue * 1000).toLong().toULong()
        viewModelScope.launch {
            val t = textTrack()
            apply(edit { insertClip(t, "Seu texto", "", at, 3000u) }, rebuildPlayer = false)
        }
    }
    override fun editText(id: Long, text: String) = launchEdit { renameClip(id.toULong(), text) }.let { }
    override fun setTextStyle(id: Long, style: uniffi.clio_ffi.TextStyle) = launchEdit { runCatching { setTextStyle(id.toULong(), style) } }.let { }
    override fun retimeCaption(index: Int, startMs: Long, endMs: Long) = launchEdit {
        val (s0, e0) = TextRetime.window(startMs, endMs)
        Engine.retimeCaption(this, index, s0, e0).onFailure { android.util.Log.w("Clio", "retimeCaption($index, $startMs, $endMs) failed", it) }
    }.let { }
    override fun retimeText(id: Long, startMs: Long, endMs: Long) =
        launchEdit { val (s0, e0) = TextRetime.window(startMs, endMs); runCatching { retimeText(id.toULong(), s0.toULong(), (e0 - s0).toULong()) } }.let { }

    // --- Music ---
    override fun pickMusic() { _events.tryEmit(EditorEvent.PickMusic) }
    fun addMusic(uri: Uri) = viewModelScope.launch {
        val app = getApplication<Application>()
        runCatching { app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val d = media.audioDurationMs(uri).takeIf { it > 0 } ?: return@launch
        val name = media.displayName(uri)
        apply(edit { runCatching { setLaneClip(AudioLanes.MUSIC, name, uri.toString(), 0u, d.toULong()) } }, rebuildPlayer = true)
    }
    override fun removeMusic() = launchEdit(rebuildPlayer = true) { clearLane(AudioLanes.MUSIC) }.let { }

    // --- Audio lanes (AudioMix / VoiceoverRecorder / SfxLibrary) ---
    override fun detachAudio(clipId: Long?) = launchEdit(rebuildPlayer = true) { target(clipId)?.let { runCatching { detachAudio(it) } } }.let { }
    override fun addSfx(id: String) {
        val s = SfxLibrary.all.firstOrNull { it.id == id } ?: return
        val atMs = (playheadState.floatValue * 1000).toLong()
        val app = getApplication<Application>()
        viewModelScope.launch {
            val f = withContext(Dispatchers.IO) { SfxLibrary.file(app, s) }
            val uri = Uri.fromFile(f).toString()
            audio.audition(uri)
            apply(edit { runCatching { addAudioClip(AudioLanes.SFX, s.label, uri, atMs.toULong(), s.durationMs.toULong()) } }, rebuildPlayer = false)
            ui = ui.copy(notice = "Adicionado em ${"%d:%02d".format(atMs / 60_000, atMs / 1000 % 60)}")
            delay(1800)
            ui = ui.copy(notice = null)
        }
    }
    override fun toggleVoiceover() {
        if (voice.isRecording) { finishVoiceover(); return }
        if (!voice.hasPermission()) { _events.tryEmit(EditorEvent.RequestMic); return }
        voiceStartMs = (playheadState.floatValue * 1000).toLong()
        if (voice.start()) { ui = ui.copy(recording = true); if (ui.hasMedia) player.play() }
    }
    /** Stops recording and keeps the take (also on app stop, so nothing recorded is lost). */
    fun finishVoiceover() {
        if (!voice.isRecording) return
        player.pause()
        val take = voice.stop()
        ui = ui.copy(recording = false)
        if (take != null) launchEdit { runCatching { addVoiceover(take.uri, (voiceStartMs + take.offsetMs).toULong(), take.durationMs.toULong()) } }
    }
    override fun playPause() { if (player.isPlaying) player.pause() else if (ui.hasMedia) player.play() }
    override fun scrub(s: Float) { if (player.isPlaying) player.pause(); seekTimeline(s.coerceIn(0f, ui.durationS)) }
    override fun addClip() { pickForLayer = false; _events.tryEmit(EditorEvent.PickMedia) }

    // --- Layers (PIP) and transitions ---
    override fun addLayer() { player.pause(); pickForLayer = true; _events.tryEmit(EditorEvent.PickMedia) }
    override fun setLayerTransform(clipId: Long, t: UiTransform) {
        // A keyframed layer: the gesture sets (or updates) the key at the playhead instead.
        val c = allClips[clipId]
        if (c != null && keyTracks.containsKey(c.id)) {
            val local = localMsOf(c)
            launchEdit { runCatching { setKeyframeAt(c.id, local.toULong(), LayersMapping.transform(t), easeAt(c, local)) } }
            return
        }
        launchEdit { runCatching { setClipTransform(clipId.toULong(), LayersMapping.transform(t)) } }
    }

    // --- Keyframes ---
    private fun localMsOf(c: uniffi.clio_ffi.Clip): Long = ((playheadState.floatValue * 1000).toLong() - c.startMs.toLong()).coerceIn(0, c.durationMs.toLong())
    private fun easeAt(c: uniffi.clio_ffi.Clip, local: Long): uniffi.clio_ffi.Ease =
        keyTracks[c.id]?.let { k -> listOf(k.x, k.y, k.scale, k.rotation, k.opacity).flatten().lastOrNull { it.tMs.toLong() <= local + 20 }?.ease } ?: uniffi.clio_ffi.Ease.SMOOTH
    private fun keyEdit(clipId: Long, block: ClioProject.(uniffi.clio_ffi.Clip, Long) -> Unit) {
        val c = allClips[clipId] ?: return
        player.pause()
        val local = localMsOf(c)
        launchEdit { runCatching { block(c, local) } }
    }
    override fun toggleKeyframe(clipId: Long) = keyEdit(clipId) { c, l -> toggleKeyframe(c.id, l.toULong()) }
    override fun setKeyEase(clipId: Long, ease: KeyEase) = keyEdit(clipId) { c, l -> setKeyframeEase(c.id, l.toULong(), LayersMapping.ease(ease)) }
    override fun setZoomPan(clipId: Long, zp: UiZoomPan) = keyEdit(clipId) { c, _ ->
        setZoomPan(c.id, LayersMapping.transform(zp.start), LayersMapping.transform(zp.end), LayersMapping.ease(zp.ease))
    }
    override fun clearKeyframes(clipId: Long) = keyEdit(clipId) { c, _ -> clearKeyframes(c.id) }
    override fun layerTransformAt(clipId: Long, localMs: Long): UiTransform? {
        val c = allClips[clipId] ?: return null
        val k = keyTracks[c.id] ?: return null
        return LayersMapping.ui(Layers.transformAt(c, k, localMs))
    }
    override fun setTransition(clipId: Long, style: TransitionStyle, durS: Float) = launchEdit(rebuildPlayer = false) {
        runCatching { setTransition(clipId.toULong(), LayersMapping.kind(style), (durS * 1000).toLong().coerceAtLeast(0).toULong()) }
    }.let { }
    override fun previewGrade(clipId: Long, grade: uniffi.clio_ffi.ColorGrade?) {
        gradeDraft = grade?.let { clipId to it }
        redrawPaused()
    }
    override fun setGrade(clipId: Long, grade: uniffi.clio_ffi.ColorGrade) = viewModelScope.launch {
        val committing = gradeDraft
        apply(edit { runCatching { setClipGrade(clipId.toULong(), grade) } }, rebuildPlayer = false)
        // Only drop the draft we committed; a newer drag that started meanwhile stays live.
        if (gradeDraft === committing) gradeDraft = null
        redrawPaused()
    }.let { }
    override fun applyGradeToAll(clipId: Long) = viewModelScope.launch {
        apply(edit { runCatching { applyGradeToAll(clipId.toULong()) } }, rebuildPlayer = false)
        redrawPaused()
    }.let { }
    override fun undo() = launchEdit(rebuildPlayer = true) { undo() }.let { }
    override fun redo() = launchEdit(rebuildPlayer = true) { redo() }.let { }
    override fun split(clipId: Long?) {
        val ms = (playheadState.floatValue * 1000).toULong()
        launchEdit(rebuildPlayer = true) {
            val target = clipId?.takeIf { it >= 0 }?.toULong()
                ?: tracks().firstOrNull { it.id == videoTrack }?.clips?.firstOrNull { ms > it.startMs && ms < it.startMs + it.durationMs }?.id
            if (target != null) runCatching { splitClip(target, ms) }
        }
    }
    override fun delete(clipId: Long) {
        if (clipId < 0) {
            val i = (-1L - clipId).toInt()
            launchEdit { setCaptions(captions().filterIndexed { j, _ -> j != i }) }
        } else launchEdit(rebuildPlayer = true) { target(clipId)?.let { removeClip(it) } }
    }
    override fun editCaptions(caps: List<Engine.Cap>) {
        launchEdit { setCaptions(caps.map { it.toFfi() }) }
    }

    override fun generateCaptions() {
        if (captionJob?.isActive == true) return
        captionJob = viewModelScope.launch {
            val lang = ui.captionLanguage // null = Whisper auto-detect
            val app = getApplication<Application>()
            try {
                val modelId = uniffi.clio_ffi.defaultSpeechModel()
                if (!models.installed(modelId)) {
                    val mb = (uniffi.clio_ffi.speechModels().first { it.id == modelId }.sizeBytes.toLong() / 1_000_000).toInt()
                    ui = ui.copy(captionStatus = CaptionStatus.Downloading(0f, mb))
                    models.ensure(modelId) { p -> viewModelScope.launch(Dispatchers.Main) { ui = ui.copy(captionStatus = CaptionStatus.Downloading(p, mb)) } }
                }
                val clips = withContext(engine) { project.tracks().firstOrNull { it.id == videoTrack }?.clips.orEmpty() }
                val out = mutableListOf<Caption>()
                val n = clips.size.coerceAtLeast(1)
                for ((i, c) in clips.withIndex()) {
                    ui = ui.copy(captionStatus = CaptionStatus.Decoding(i.toFloat() / n))
                    val pcm = File(app.cacheDir, "pcm-${c.id}.f32")
                    val samples = withContext(Dispatchers.IO) {
                        AudioDecoder.decodeToFile(app, Uri.parse(c.source), pcm, c.inMs.toLong(), c.durationMs.toLong())
                    }
                    if (samples == 0L) { pcm.delete(); continue }
                    ui = ui.copy(captionStatus = CaptionStatus.Transcribing(i.toFloat() / n))
                    val job = coroutineContext.job
                    val caps = try {
                        withContext(Dispatchers.Default) {
                            uniffi.clio_ffi.transcribeCaptions(models.modelsDir.path, modelId, pcm.path, lang, object : uniffi.clio_ffi.TranscribeProgress {
                                override fun onProgress(fraction: Float, status: String): Boolean {
                                    // Marshal to Main; never touch UI state from the engine thread.
                                    viewModelScope.launch(Dispatchers.Main) { ui = ui.copy(captionStatus = CaptionStatus.Transcribing((i + fraction) / n)) }
                                    return job.isActive
                                }
                            })
                        }
                    } finally { pcm.delete() }
                    out += caps.map {
                        val end = minOf(it.endMs, c.durationMs)
                        it.copy(startMs = it.startMs + c.startMs, endMs = end + c.startMs,
                            words = it.words.map { w -> w.copy(startMs = minOf(w.startMs, end) + c.startMs, endMs = minOf(w.endMs, end) + c.startMs) })
                    }.filter { it.endMs > it.startMs }
                }
                ui = ui.copy(captionStatus = CaptionStatus.Idle)
                apply(edit { setCaptions(out) }, rebuildPlayer = false)
            } catch (e: CancellationException) { throw e } catch (e: Throwable) {
                ui = ui.copy(captionStatus = CaptionStatus.Failed(e.message ?: "erro"))
            }
        }
    }

    /** Final save on the app scope so leaving the editor never loses the last edit. */
    private fun flushSave(): Job? {
        saveJob?.cancel()
        if (!ready.isCompleted) return null
        val p = project
        return appScope.launch {
            val json = withContext(engine) { if (p.summary().clipCount == 0u) null else p.toDraftJson() } ?: return@launch
            if (json != lastJson) withContext(Dispatchers.IO) { drafts.saveBlocking(draftId, json) }
            lastJson = json
        }
    }

    override fun onCleared() {
        // A take still recording is kept: add it before the final save.
        voice.stop()?.let { t -> runBlocking(engine) { runCatching { project.addVoiceover(t.uri, (voiceStartMs + t.offsetMs).toULong(), t.durationMs.toULong()) } } }
        flushSave()
        player.release()
        audio.release()
        // `project` is freed by its Cleaner once the flush above no longer references it.
    }
}

/** Latest editor state for instrumented end-to-end tests (read-only observation, no behaviour). */
object QaHooks {
    @Volatile var ui: EditorUi? = null
    @Volatile var export: ExportState? = null
}


/**
 * Final guard before the unsigned engine calls: the duration is always computed from the clamped
 * start, so a window that slipped below 0 s is shifted (length kept), never collapsed to the minimum.
 */
internal object TextRetime {
    fun window(startMs: Long, endMs: Long): Pair<Long, Long> {
        val len = (endMs - startMs).coerceAtLeast(1)
        val s = startMs.coerceAtLeast(0)
        return s to s + len
    }
}
