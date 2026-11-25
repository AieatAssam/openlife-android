package org.openlife.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.openlife.app.R
import org.openlife.app.ui.theme.LocalOpenLifeBrandColors
import org.openlife.vault.model.Source
import org.openlife.vault.model.UserContentKind

/**
 * P2-07-R4: the communication assessment state, in plain words. Nothing in
 * this build assesses, and nothing here may read as "safe".
 */
@Composable
internal fun AssessmentNotice(source: Source) {
    // Review T-04: amber marks "not assessed" (VISUAL_IDENTITY §4) as a
    // 1.5 dp border (>= 3:1 on the card in both themes, PaletteContrastTest).
    // Light amber is under 4.5:1 for text, so the words keep the ink colour;
    // the colour only accompanies them, it never carries the meaning alone.
    Column(
        modifier = Modifier
            .padding(top = 8.dp)
            .fillMaxWidth()
            .border(BorderStroke(1.5.dp, LocalOpenLifeBrandColors.current.attention), MaterialTheme.shapes.small)
            .padding(12.dp),
    ) {
        Text(
            stringResource(ContentKindLabel.assessmentText(source.assessmentState)),
            modifier = Modifier.testTag("viewer_assessment_state"),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            stringResource(R.string.viewer_assessment_explanation),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * P2-07-R3: the optional user label. Yes / No / Not sure as one radio
 * group with 48 dp rows; the stored answer comes back from the database
 * through [source], and an answer is attributed to the user with its time.
 */
@Composable
internal fun ContentKindQuestion(source: Source, onSetUserContentKind: (UserContentKind?) -> Unit) {
    val selected = ContentKindLabel.selected(source)
    Text(
        stringResource(R.string.viewer_content_kind_question),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 8.dp).semantics { heading() },
    )
    Column(modifier = Modifier.selectableGroup()) {
        ContentKindChoice.entries.forEach { choice ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .selectable(
                        selected = choice == selected,
                        role = Role.RadioButton,
                        onClick = { ContentKindLabel.change(source, choice)?.let { onSetUserContentKind(it.stored) } },
                    )
                    .testTag("viewer_content_kind_${choice.name}"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = choice == selected, onClick = null)
                Text(stringResource(choice.label), modifier = Modifier.padding(start = 16.dp))
            }
        }
    }
    val answeredAt = source.userContentKindAt
    if (ContentKindLabel.recordedByAnotherVersion(source)) {
        Text(
            stringResource(R.string.viewer_content_kind_other_version),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("viewer_content_kind_other_version"),
        )
    } else if (answeredAt != null) {
        val time = dateTime(answeredAt)
        Text(
            withDataSpan(stringResource(R.string.viewer_content_kind_answered, time), time),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("viewer_content_kind_attribution"),
        )
    }
    Text(
        stringResource(R.string.viewer_content_kind_routing),
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = 4.dp),
    )
}
