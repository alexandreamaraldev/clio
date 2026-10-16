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

    val player: ExoPlayer = ExoPlayer.Builder(app).build().apply {
        addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                ui = ui.copy(playing = isPlaying)
                if (isPlaying) { syncMusic(); musicPlayer.play() } else musicPlayer.pause()
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { applyItemParams() }
            override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_ENDED) { pause(); seekTimeline(0f) } }
        })
    }
    /** Music preview runs on its own player, slaved to the timeline. */
    val musicPlayer: ExoPlayer = ExoPlayer.Builder(app).build()
    private var musicClip: uniffi.clio_ffi.Clip? = null
    private var clipStarts: LongArray = LongArray(0)
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

    private fun apply(s: Snapshot, rebuildPlayer: Boolean) {
        val uiTracks = s.tracks.filter { it.clips.isNotEmpty() || it.kind == TrackKind.VIDEO }.map { t ->
            UiTrack(t.name, when (t.kind) { TrackKind.VIDEO -> "video"; TrackKind.AUDIO -> "audio"; TrackKind.TEXT -> "text"; TrackKind.EFFECT -> "fx" },
                t.clips.map { c -> UiClip(c.name, c.startMs.toFloat() / 1000f, c.durationMs.toFloat() / 1000f, c.id.toLong(), c.source, c.inMs.toFloat() / 1000f,
                    c.sourceMs.toFloat() / 1000f, c.speed, c.volume, c.fadeInMs.toFloat() / 1000f, c.fadeOutMs.toFloat() / 1000f, c.muted) })
        }
        val caps = s.captions.map { Engine.Cap(it.startMs.toLong(), it.endMs.toLong(), it.text) }
        val withCaps = if (caps.isEmpty()) uiTracks else uiTracks + UiTrack("Legendas", "text",
            caps.mapIndexed { i, c -> UiClip("Aa  " + c.text, c.startMs / 1000f, (c.endMs - c.startMs).coerceAtLeast(1) / 1000f, -1L - i) })
        ui = ui.copy(title = s.summary.name, tracks = withCaps, durationS = s.summary.durationMs.toFloat() / 1000f, captions = caps,
            hasMedia = s.tracks.any { it.kind == TrackKind.VIDEO && it.clips.isNotEmpty() }, canUndo = s.canUndo, canRedo = s.canRedo,
            loading = false, fill = s.fill, blurBg = s.blurBg, canvasW = s.summary.width.toInt(), canvasH = s.summary.height.toInt(),
            texts = s.tracks.filter { it.kind == TrackKind.TEXT }.flatMap { it.clips }.map { TextItem(it.id.toLong(), it.name, it.startMs.toLong(), (it.startMs + it.durationMs).toLong()) },
            music = s.tracks.firstOrNull { it.kind == TrackKind.AUDIO }?.clips?.firstOrNull()?.name)
        lastSnap = s
        if (rebuildPlayer) rebuildPlaylist(s) else refreshClipCache(s)
    }

    private fun rebuildPlaylist(s: Snapshot) {
        val clips = s.tracks.firstOrNull { it.id == videoTrack }?.clips.orEmpty()
        clipStarts = LongArray(clips.size) { clips[it].startMs.toLong() }
        videoClips = clips
        val app = getApplication<Application>()
        player.setMediaItems(clips.map { c ->
            MediaItem.Builder().setUri(c.source).setMediaId(c.id.toString()).apply {
                if (MediaKind.isImage(app, c.source)) setImageDurationMs(c.durationMs.toLong())
                else setClippingConfiguration(MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(c.inMs.toLong()).setEndPositionMs(c.inMs.toLong() + Mix.spanMs(c.durationMs.toLong(), c.speed)).build())
            }.build()
        })
        player.prepare()
        musicClip = s.tracks.firstOrNull { it.kind == TrackKind.AUDIO }?.clips?.firstOrNull()
        musicClip?.let { m ->
            musicPlayer.setMediaItem(MediaItem.fromUri(m.source)); musicPlayer.prepare()
            musicPlayer.playbackParameters = PlaybackParameters(m.speed)
        } ?: musicPlayer.clearMediaItems()
        applyItemParams()
        if (clips.isNotEmpty()) seekTimeline(playheadState.floatValue.coerceAtMost(ui.durationS))
    }

    /** Called by the UI once per frame while playing (withFrameNanos). */
    fun syncPlayhead() {
        val idx = player.currentMediaItemIndex
        val c = videoClips.getOrNull(idx)
        val local = (player.currentPosition / (c?.speed ?: 1f)).toLong()
        playheadState.floatValue = (clipStarts.getOrElse(idx) { 0L } + local) / 1000f
        if (c != null) player.volume = Mix.gain(local, c.durationMs.toLong(), c.volume, c.fadeInMs.toLong(), c.fadeOutMs.toLong(), c.muted).coerceAtMost(1f)
        musicClip?.let { m ->
            val t = (playheadState.floatValue * 1000).toLong() - m.startMs.toLong()
            musicPlayer.volume = Mix.gain(t, m.durationMs.toLong(), m.volume, m.fadeInMs.toLong(), m.fadeOutMs.toLong(), m.muted).coerceAtMost(1f)
        }
    }

    /** Per-clip speed/volume for whichever item ExoPlayer is on (it has one global setting). */
    private fun applyItemParams() {
        val c = videoClips.getOrNull(player.currentMediaItemIndex) ?: return
        if (player.playbackParameters.speed != c.speed) player.playbackParameters = PlaybackParameters(c.speed)
        player.volume = if (c.muted) 0f else c.volume.coerceAtMost(1f)
    }

    private fun syncMusic() {
        val m = musicClip ?: return
        val t = (playheadState.floatValue * 1000).toLong() - m.startMs.toLong()
        if (t in 0 until m.durationMs.toLong()) musicPlayer.seekTo(m.inMs.toLong() + Mix.spanMs(t, m.speed)) else musicPlayer.pause()
    }

    private fun seekTimeline(s: Float) {
        val ms = (s * 1000).toLong()
        val idx = clipStarts.indexOfLast { it <= ms }.coerceAtLeast(0)
        if (clipStarts.isNotEmpty()) player.seekTo(idx, Mix.spanMs(ms - clipStarts[idx], videoClips.getOrNull(idx)?.speed ?: 1f))
        applyItemParams()
        playheadState.floatValue = s
        syncMusic()
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
                ExportState.Done(exporter.export(snap, spec, style) { p -> exportState = ExportState.Running(p, spec.label) })
            } catch (e: CancellationException) { throw e } catch (e: Throwable) {
                android.util.Log.e("ClioExport", "export failed", e)
                ExportState.Failed(generateSequence(e) { it.cause }.take(4).joinToString(" ← ") { it.message ?: it.javaClass.simpleName })
            }
        }
    }

    override fun setCaptionStyle(style: CaptionStyle) { ui = ui.copy(captionStyle = style) }

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
    override fun setSpeed(clipId: Long?, speed: Float) = launchEdit(rebuildPlayer = true) { target(clipId)?.let { runCatching { setClipSpeed(it, speed) } } }.let { }
    override fun setAudio(clipId: Long?, volume: Float, fadeInS: Float, fadeOutS: Float, muted: Boolean) = launchEdit(rebuildPlayer = false) {
        target(clipId)?.let { runCatching { setClipAudio(it, volume, (fadeInS * 1000).toLong().toULong(), (fadeOutS * 1000).toLong().toULong(), muted) } }
    }.let { }

    private fun refreshClipCache(s: Snapshot) {
        videoClips = s.tracks.firstOrNull { it.id == videoTrack }?.clips.orEmpty()
        musicClip = s.tracks.firstOrNull { it.kind == TrackKind.AUDIO }?.clips?.firstOrNull()
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

    // --- Music ---
    override fun pickMusic() { _events.tryEmit(EditorEvent.PickMusic) }
    fun addMusic(uri: Uri) = viewModelScope.launch {
        val app = getApplication<Application>()
        runCatching { app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val d = media.audioDurationMs(uri).takeIf { it > 0 } ?: return@launch
        val name = media.displayName(uri)
        val tid = withContext(engine) {
            val existing = project.tracks().firstOrNull { it.kind == TrackKind.AUDIO }
            existing?.clips?.forEach { project.removeClip(it.id) }
            existing?.id ?: project.addTrack(TrackKind.AUDIO, "Música")
        }
        apply(edit { insertClip(tid, name, uri.toString(), 0u, d.toULong()) }, rebuildPlayer = true)
    }
    override fun removeMusic() = launchEdit(rebuildPlayer = true) {
        tracks().filter { it.kind == TrackKind.AUDIO }.flatMap { it.clips }.forEach { removeClip(it.id) }
    }.let { }
    override fun playPause() { if (player.isPlaying) player.pause() else if (ui.hasMedia) player.play() }
    override fun scrub(s: Float) { if (player.isPlaying) player.pause(); seekTimeline(s.coerceIn(0f, ui.durationS)) }
    override fun addClip() { _events.tryEmit(EditorEvent.PickMedia) }
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
        launchEdit { setCaptions(caps.map { Caption(it.startMs.toULong(), it.endMs.toULong(), it.text) }) }
    }

    override fun generateCaptions() {
        if (captionJob?.isActive == true) return
        captionJob = viewModelScope.launch {
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
                            uniffi.clio_ffi.transcribeCaptions(models.modelsDir.path, modelId, pcm.path, null, object : uniffi.clio_ffi.TranscribeProgress {
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
                        it.copy(startMs = it.startMs + c.startMs, endMs = end + c.startMs)
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
        flushSave()
        player.release()
        musicPlayer.release()
        // `project` is freed by its Cleaner once the flush above no longer references it.
    }
}

/** Latest editor state for instrumented end-to-end tests (read-only observation, no behaviour). */
object QaHooks {
    @Volatile var ui: EditorUi? = null
    @Volatile var export: ExportState? = null
}
