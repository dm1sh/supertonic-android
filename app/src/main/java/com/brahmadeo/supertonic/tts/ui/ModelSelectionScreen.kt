package com.brahmadeo.supertonic.tts.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.brahmadeo.supertonic.tts.R

@Composable
fun ModelSelectionScreen(
    englishSelected: Boolean,
    v2Selected: Boolean,
    v3Selected: Boolean,
    onEnglishSelectedChange: (Boolean) -> Unit,
    onV2SelectedChange: (Boolean) -> Unit,
    onV3SelectedChange: (Boolean) -> Unit,
    onContinue: () -> Unit
) {
    val hasSelection = englishSelected || v2Selected || v3Selected

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = stringResource(R.string.model_setup_title),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.model_setup_message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(24.dp))

            ModelOption(
                selected = englishSelected,
                onSelectedChange = onEnglishSelectedChange,
                title = stringResource(R.string.model_setup_english_label),
                description = stringResource(R.string.model_setup_english_description)
            )
            Spacer(modifier = Modifier.height(12.dp))
            ModelOption(
                selected = v2Selected,
                onSelectedChange = onV2SelectedChange,
                title = stringResource(R.string.model_setup_v2_label),
                description = stringResource(R.string.model_setup_v2_description)
            )
            Spacer(modifier = Modifier.height(12.dp))
            ModelOption(
                selected = v3Selected,
                onSelectedChange = onV3SelectedChange,
                title = stringResource(R.string.model_setup_v3_label),
                description = stringResource(R.string.model_setup_v3_description)
            )

            Spacer(modifier = Modifier.height(28.dp))
            Button(
                onClick = onContinue,
                enabled = hasSelection,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.model_setup_continue))
            }
        }
    }
}

@Composable
private fun ModelOption(
    selected: Boolean,
    onSelectedChange: (Boolean) -> Unit,
    title: String,
    description: String
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelectedChange(!selected) },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = selected,
                onCheckedChange = onSelectedChange
            )
            Column(
                modifier = Modifier.padding(start = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
