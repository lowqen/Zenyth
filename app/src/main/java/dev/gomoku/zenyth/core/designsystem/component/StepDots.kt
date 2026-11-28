package dev.gomoku.zenyth.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.gomoku.zenyth.core.i18n.tr

/**
 * Where a sheet of steps is (L5-7 — the merge and split sheets): a dot per
 * step, the ones done and the current one filled, and the current step's name
 * after them. [current] counts from 1.
 *
 * Not [Stepper] — that one is a −/+ number. Read aloud as one phrase
 * («2/3단계: 겹칠 때»), the dots themselves are decoration.
 */
@Composable
fun StepDots(current: Int, labels: List<String>, modifier: Modifier = Modifier) {
    val label = labels.getOrNull(current - 1).orEmpty()
    val spoken = tr("${current}/${labels.size}단계: $label", "Step $current of ${labels.size}: $label", "第 $current/${labels.size} 步: $label")
    Row(
        modifier.clearAndSetSemantics { contentDescription = spoken },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val scheme = MaterialTheme.colorScheme
        labels.indices.forEach { i ->
            val on = i < current
            Box(
                Modifier
                    .size(if (i == current - 1) 10.dp else 8.dp)
                    .clip(CircleShape)
                    .background(if (on) scheme.primary else scheme.outlineVariant),
            )
        }
        Text(
            "$current/${labels.size} · $label",
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            color = scheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
