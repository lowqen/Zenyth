package dev.gomoku.zenyth.feature.library

import dev.gomoku.zenyth.core.i18n.tr
import dev.gomoku.zenyth.core.model.Move
import dev.gomoku.zenyth.core.model.Opening26
import dev.gomoku.zenyth.data.library.AppDirSource
import dev.gomoku.zenyth.data.library.LibraryItem
import dev.gomoku.zenyth.data.library.LibrarySourceView
import dev.gomoku.zenyth.data.library.LibraryState
import dev.gomoku.zenyth.data.library.ScanNote
import dev.gomoku.zenyth.data.library.SourceAccess
import dev.gomoku.zenyth.data.library.SourceKind
import dev.gomoku.zenyth.domain.library.CoverKey
import dev.gomoku.zenyth.domain.library.Covers
import dev.gomoku.zenyth.domain.library.LibraryCover
import dev.gomoku.zenyth.domain.library.LibraryPositions

enum class LibrarySort { RECENT, NAME, SIZE }

/** A source chip — [lost]: the folder's grant is gone (the chip still shows, the line under it offers it back). */
data class SourceChip(val id: String, val label: String, val folder: Boolean, val lost: Boolean)

/** One cover in the grid (or one row in the list). */
data class LibraryCard(
    val id: String,
    val title: String,
    val cover: CoverKey,
    val seed: Int,
    val subtitle: String,
    val attached: Boolean = false,
    /** A copy · move is working on it. */
    val working: Boolean = false,
)

data class TrashRow(val id: String, val title: String, val date: String)

/** The library's first page (design §6.2). */
data class LibraryUiState(
    val loaded: Boolean = false,
    val sources: List<SourceChip> = emptyList(),
    val sourceId: String = AppDirSource.ID,
    /** The chosen chip is a folder: its line (hint · «다시 허용» · «빼기»). */
    val folder: SourceChip? = null,
    /** «지금 붙은 DB» — the engine's entry, whichever source it is in. */
    val now: LibraryCard? = null,
    val cards: List<LibraryCard> = emptyList(),
    val sort: LibrarySort = LibrarySort.RECENT,
    val list: Boolean = false,
    val trash: List<TrashRow> = emptyList(),
    /** A copy · import running in the job process: «복사하는 중 42%». */
    val job: JobLine? = null,
    /** The detail open beside the grid (wide screens) — the chosen card. */
    val selectedId: String? = null,
) {
    /** The chosen chip cannot be written now (its folder grant is lost). */
    val blocked: Boolean get() = folder?.lost == true
}

data class JobLine(val label: String, val fraction: Float, val cancelling: Boolean)

/** What [LibraryDetailUi.scanLine] says in place of the stats it does not have yet. */
enum class ScanLine { NONE, COUNTING, EMPTY, WAITING, ERROR, STOPPED, MEMORY, UNORDERED }

data class OpeningRow(val index: Int, val count: Long) {
    val abbr: String get() = Opening26.abbr[index]
    val name: String get() = Opening26.name(index)
}

/** A root position: [moves] null when it does not read on this board (another size, a pass). */
data class RootRow(val key: String, val moves: List<Move>?, val subtree: Long)

data class RecordsUi(val w: Long, val l: Long, val d: Long, val comments: Long, val rules: String?)

/** A library entry's page (design §6.3). */
data class LibraryDetailUi(
    val id: String,
    val title: String,
    val cover: LibraryCover,
    val coverKey: CoverKey,
    /** The most frequent opening — what «자동» draws (null: an eclipse). */
    val autoOpening: Int? = null,
    val chips: List<String>,
    val attached: Boolean,
    /** A folder entry — the rename sheet offers the file name too. */
    val inFolder: Boolean,
    val file: String,
    val size: Long,
    val scanLine: ScanLine,
    val scanFraction: Float = 0f,
    val openings: List<OpeningRow> = emptyList(),
    val classified: Long = 0,
    val unclassified: Long = 0,
    /** Unclassified outweighs classified: the roots lead and the distribution folds. */
    val rootsFirst: Boolean = true,
    val roots: List<RootRow> = emptyList(),
    val records: RecordsUi? = null,
    /** Where a copy · move can go — the other reachable sources. */
    val targets: List<SourceChip> = emptyList(),
    /** Rename and delete wait for a copy · move on it. */
    val working: Boolean = false,
    /** The names the folder's other files take — the rename sheet's preview. */
    val takenNames: List<String> = emptyList(),
)

/** Builds the screen states from the repository's (pure — the screenshot tests use it too). */
object LibraryUiMapper {

    fun chips(sources: List<LibrarySourceView>): List<SourceChip> = sources.map {
        SourceChip(
            id = it.id,
            label = if (it.kind == SourceKind.APP) tr("이 기기", "This device", "本机") else tr("폴더: ${it.label}", "Folder: ${it.label}", "文件夹: ${it.label}"),
            folder = it.kind == SourceKind.SAF,
            lost = it.access == SourceAccess.LOST,
        )
    }

    fun card(item: LibraryItem, s: LibraryState): LibraryCard {
        val e = item.entry
        val stats = e.stats
        val scan = s.scan?.takeIf { it.entryId == item.id }
        val sub = when {
            scan != null && stats == null -> scan.early?.let { LibraryFormat.subtitle(it, item.size) }
                ?: tr("세는 중 ${(scan.fraction * 100).toInt()}%", "Counting ${(scan.fraction * 100).toInt()}%", "统计中 ${(scan.fraction * 100).toInt()}%")
            else -> LibraryFormat.subtitle(stats, item.size)
        }
        return LibraryCard(
            id = item.id,
            title = e.title,
            cover = Covers.keyOf(e.cover, stats),
            seed = e.cover.seed,
            subtitle = sub,
            attached = item.id == s.attachedId,
            working = item.id in s.working,
        )
    }

    fun home(s: LibraryState, sourceId: String, sort: LibrarySort, list: Boolean, selectedId: String?, job: JobLine?): LibraryUiState {
        val chips = chips(s.sources)
        val chosen = chips.firstOrNull { it.id == sourceId } ?: chips.firstOrNull()
        val id = chosen?.id ?: AppDirSource.ID
        val rows = s.items.filter { it.sourceId == id }
        val sorted = when (sort) {
            LibrarySort.RECENT -> rows.sortedByDescending { maxOf(it.entry.lastOpenedAt ?: 0L, it.entry.updatedAt, it.entry.createdAt) }
            LibrarySort.NAME -> rows.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.entry.title })
            LibrarySort.SIZE -> rows.sortedByDescending { it.size }
        }
        return LibraryUiState(
            loaded = s.loaded,
            sources = chips,
            sourceId = id,
            folder = chosen?.takeIf { it.folder },
            now = s.items.firstOrNull { it.id == s.attachedId }?.let { card(it, s) },
            cards = sorted.map { card(it, s) },
            sort = sort,
            list = list,
            trash = s.trashed.filter { it.sourceId == id }
                .sortedByDescending { it.entry.trashedAt ?: 0L }
                .map { TrashRow(it.id, it.entry.title, LibraryFormat.date(it.entry.trashedAt ?: 0L)) },
            job = job,
            selectedId = selectedId,
        )
    }

    fun detail(item: LibraryItem, s: LibraryState, boardSize: Int, otherFiles: List<String> = emptyList()): LibraryDetailUi {
        val e = item.entry
        val stats = e.stats
        val scan = s.scan?.takeIf { it.entryId == item.id }
        val note = s.notes[item.id]
        val openings = stats?.openings
        val classified = openings?.take(Opening26.COUNT)?.sum() ?: 0L
        val unclassified = openings?.getOrNull(Opening26.COUNT) ?: 0L
        val classifiable = stats != null && listOf("renju-15", "standard-15").any { (stats.rules[it] ?: 0L) > 0L }
        val line = when {
            stats == null -> when {
                scan != null -> ScanLine.COUNTING
                item.size == 0L -> ScanLine.EMPTY
                note == ScanNote.ERROR -> ScanLine.ERROR
                note == ScanNote.STOPPED -> ScanLine.STOPPED
                else -> ScanLine.WAITING
            }
            stats.roots == null -> ScanLine.MEMORY
            classifiable && openings == null -> ScanLine.UNORDERED
            else -> ScanLine.NONE
        }
        val inFolder = item.sourceId != AppDirSource.ID
        val chips = listOfNotNull(
            LibraryFormat.mainRule(stats?.rules),
            stats?.let { LibraryFormat.positions(it.records) },
            item.size.takeIf { it > 0 }?.let { LibraryFormat.bytes(it) },
            tr("마지막 저장 ", "Saved ", "最后保存 ") + LibraryFormat.date(e.updatedAt),
            e.file.takeIf { inFolder },
        )
        return LibraryDetailUi(
            id = item.id,
            title = e.title,
            cover = e.cover,
            coverKey = Covers.keyOf(e.cover, stats),
            autoOpening = Covers.dominantOpening(stats),
            chips = chips,
            attached = item.id == s.attachedId,
            inFolder = inFolder,
            file = e.file,
            size = item.size,
            scanLine = line,
            scanFraction = (scan?.fraction ?: 0.0).toFloat(),
            openings = openings?.take(Opening26.COUNT)
                ?.mapIndexed { i, n -> OpeningRow(i, n) }
                ?.filter { it.count > 0 }
                ?.sortedByDescending { it.count }
                .orEmpty(),
            classified = classified,
            unclassified = unclassified,
            rootsFirst = openings == null || unclassified > classified,
            roots = stats?.roots.orEmpty().map { RootRow(it.key, LibraryPositions.movesOfKey(it.key, boardSize), it.subtree) },
            records = stats?.let {
                RecordsUi(
                    w = it.labels.W, l = it.labels.L, d = it.labels.D, comments = it.commented,
                    rules = if (it.rules.size > 1) it.rules.entries.joinToString(" · ") { (k, n) -> "${LibraryFormat.rule(k)} ${LibraryFormat.count(n)}" } else null,
                )
            },
            targets = chips(s.sources).filter { it.id != item.sourceId && !it.lost },
            working = item.id in s.working,
            takenNames = otherFiles,
        )
    }
}
